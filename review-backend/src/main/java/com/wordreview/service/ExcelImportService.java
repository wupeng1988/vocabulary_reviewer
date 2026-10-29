package com.wordreview.service;

import com.wordreview.entity.Word;
import com.wordreview.entity.WordGroup;
import com.wordreview.entity.WordGroupMember;
import com.wordreview.repository.WordGroupMemberRepository;
import com.wordreview.repository.WordGroupRepository;
import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final WordRepository wordRepository;
    private final WordGroupMemberRepository memberRepository;
    private final WordGroupRepository groupRepository;
    private final WordGroupService wordGroupService;

    @PersistenceContext
    private EntityManager entityManager;

    /** 导入结果：单词总数 + 实际落地的词组名 + 没有匹配到词组的 Sheet 名 */
    public static class ImportResult {
        /** 导入/更新的单词总数（跨所有 Sheet 去重后） */
        public int wordCount;
        /** 实际用到的词组名（按落地顺序去重） */
        public List<String> groupsUsed = new ArrayList<>();
        /** 没能匹配到词组、只能落到目标词组的 Sheet 名 */
        public List<String> unmatchedSheets = new ArrayList<>();
        /** 目标词组名（「导入到词组」，未匹配 Sheet 的单词落在它下面） */
        public String targetGroupName;
    }

    /**
     * 导入 Excel 文件，解析 A-E 列入库。
     *
     * <p><b>多 Sheet</b>：按词组分 Sheet 导出的文件（{@code /api/words/export-excel}）
     * 每个词组一个 Sheet，这里会<b>全部读取</b>，而不是只读第一个 Sheet。
     * 早期版本只取 {@code getSheetAt(0)}，而 Sheet 顺序里第一个恰好是默认词组，
     * 导致其余词组的几千个单词被静默丢弃、却还报「导入成功」。
     *
     * <p><b>Sheet 名 → 词组</b>：导出格式是 {@code 编号-名称}（如 {@code G002P1-Chapter1 Geographic}），
     * 取第一个 {@code -} 前的前缀当编号去匹配已有词组；匹配不上就退一步按名称精确匹配；
     * 再匹配不上则落到调用方指定的「导入到词组」，<b>不会自动创建词组</b>。
     *
     * <p><b>Upsert</b>：word 已存在则更新内容字段，保留复习统计数据。
     */
    @Transactional
    public ImportResult importExcel(MultipartFile file, Long groupId) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".xls") && !filename.endsWith(".xlsx"))) {
            throw new IllegalArgumentException("仅支持 .xls 和 .xlsx 格式的 Excel 文件");
        }

        Long targetGroupId = groupId != null ? groupId : wordGroupService.getDefaultGroupId();
        ImportResult result = new ImportResult();
        result.targetGroupName = groupRepository.findById(targetGroupId)
                .map(WordGroup::getName).orElse("默认词组");
        // wordId -> 该词归属的词组（一个词可以同时属于多个词组）
        Map<Long, Set<Long>> memberships = new LinkedHashMap<>();
        // 记录每个单词被处理过，用来去重计数（同一个词可能出现在多个 Sheet 里）
        Set<Long> touchedWordIds = new LinkedHashSet<>();

        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                // 这个 Sheet 的单词落到哪个词组
                Long sheetGroupId = resolveGroupForSheet(sheetName, targetGroupId, result);

                for (int i = 1; i <= sheet.getLastRowNum(); i++) { // 跳过第一行标题
                    Row row = sheet.getRow(i);
                    if (row == null) continue;

                    // 读取 A 列（生词），跳过空行
                    String wordText = getCellStringValue(row.getCell(0));
                    if (wordText == null || wordText.trim().isEmpty()) continue;

                    wordText = wordText.trim();
                    String phonetic = getCellStringValue(row.getCell(1));
                    String meaning = getCellStringValue(row.getCell(2));
                    String exampleSentence = getCellStringValue(row.getCell(3));
                    String exampleMeaning = getCellStringValue(row.getCell(4));

                    // Upsert 逻辑：逐条处理并立即 flush，避免同一 Excel 中重复词导致唯一键冲突
                    Optional<Word> existing = wordRepository.findByWord(wordText);
                    Word word;
                    if (existing.isPresent()) {
                        // 已存在：更新内容字段，保留复习统计
                        word = existing.get();
                        word.setPhonetic(phonetic);
                        word.setMeaning(meaning);
                        word.setExampleSentence(exampleSentence);
                        word.setExampleMeaning(exampleMeaning);
                        log.debug("更新已有单词: {}", wordText);
                    } else {
                        // 新词
                        word = new Word();
                        word.setWord(wordText);
                        word.setPhonetic(phonetic);
                        word.setMeaning(meaning);
                        word.setExampleSentence(exampleSentence);
                        word.setExampleMeaning(exampleMeaning);
                        log.debug("新增单词: {}", wordText);
                    }

                    Word managed = (Word) entityManager.merge(word);
                    entityManager.flush();
                    Long wordId = managed.getId();

                    // 归属关系：同一个词出现在多个 Sheet 时要累加，不能覆盖。
                    // 398 个词本来就属于多个词组，覆盖会让它们只剩最后一个组。
                    memberships.computeIfAbsent(wordId, k -> new LinkedHashSet<>()).add(sheetGroupId);
                    touchedWordIds.add(wordId);

                    entityManager.clear(); // 清空持久化上下文，防止批量积累
                }
            }
        }

        // 统一写入词组归属（按 (wordId, groupId) 去重，唯一约束不允许重复写）
        int memberCount = 0;
        for (Map.Entry<Long, Set<Long>> entry : memberships.entrySet()) {
            Long wordId = entry.getKey();
            for (Long g : entry.getValue()) {
                if (!memberRepository.existsByWordIdAndGroupId(wordId, g)) {
                    WordGroupMember member = new WordGroupMember();
                    member.setWordId(wordId);
                    member.setGroupId(g);
                    memberRepository.save(member);
                    memberCount++;
                }
            }
        }

        result.wordCount = touchedWordIds.size();
        log.info("Excel 导入完成，共 {} 个单词，新增 {} 条词组关联", result.wordCount, memberCount);
        return result;
    }

    /**
     * 决定某个 Sheet 的单词该落到哪个词组。
     *
     * <ol>
     *   <li>Sheet 名形如 {@code 编号-名称} 时，用编号去查已有词组</li>
     *   <li>查不到就按名称精确匹配（仅当恰好一个同名词组时才算，避免重名误配）</li>
     *   <li>都不行 → 落到「导入到词组」，<b>不自动创建词组</b></li>
     * </ol>
     */
    private Long resolveGroupForSheet(String sheetName, Long fallbackGroupId, ImportResult result) {
        // 从 Sheet 名里切出编号：第一个 '-' 之前的一段。编号本身不含 '-'，
        // 导出时的非法字符剔除 / 重名加 (2) / 31 字符截断都只影响名称段。
        int dash = sheetName.indexOf('-');
        String code = dash > 0 ? sheetName.substring(0, dash).trim() : null;
        String name = dash > 0 ? sheetName.substring(dash + 1).trim() : sheetName.trim();

        if (code != null && !code.isEmpty()) {
            Optional<WordGroup> byCode = groupRepository.findByCode(code);
            if (byCode.isPresent()) {
                return recordGroupUsed(result, byCode.get().getId(), byCode.get().getName());
            }
        }

        // 编号没查到，退一步按名称找（同名只认唯一一个）
        if (!name.isEmpty()) {
            List<WordGroup> byName = groupRepository.findAll().stream()
                    .filter(g -> name.equals(g.getName()))
                    .collect(java.util.stream.Collectors.toList());
            if (byName.size() == 1) {
                return recordGroupUsed(result, byName.get(0).getId(), byName.get(0).getName());
            }
        }

        // 匹配不上：落到「导入到词组」，并记下来让用户知道哪些 Sheet 没还原成功
        if (!result.unmatchedSheets.contains(sheetName)) {
            result.unmatchedSheets.add(sheetName);
        }
        String fallbackName = groupRepository.findById(fallbackGroupId)
                .map(WordGroup::getName).orElse("默认词组");
        return recordGroupUsed(result, fallbackGroupId, fallbackName);
    }

    /** 记录用到的词组名（去重、保序） */
    private Long recordGroupUsed(ImportResult result, Long groupId, String groupName) {
        if (!result.groupsUsed.contains(groupName)) {
            result.groupsUsed.add(groupName);
        }
        return groupId;
    }

    /**
     * 安全读取单元格字符串值
     */
    private String getCellStringValue(Cell cell) {
        if (cell == null) return null;

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getDateCellValue().toString();
                }
                double numVal = cell.getNumericCellValue();
                if (numVal == Math.floor(numVal) && !Double.isInfinite(numVal)) {
                    return String.valueOf((long) numVal);
                }
                return String.valueOf(numVal);
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default:
                return null;
        }
    }
}
