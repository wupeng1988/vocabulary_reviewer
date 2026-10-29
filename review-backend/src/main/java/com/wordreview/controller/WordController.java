package com.wordreview.controller;

import com.wordreview.dto.AiWordDTO;
import com.wordreview.dto.GroupNodeDTO;
import com.wordreview.dto.WordPageResponse;
import com.wordreview.entity.Word;
import com.wordreview.repository.WordRepository;
import com.wordreview.service.AiParseService;
import com.wordreview.service.AudioGenerationTaskService;
import com.wordreview.service.DataExportImportService;
import com.wordreview.service.ExcelExportService;
import com.wordreview.service.ExcelImportService;
import com.wordreview.service.WordGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/words")
@RequiredArgsConstructor
public class WordController {

    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;
    private final DataExportImportService dataExportImportService;
    private final WordRepository wordRepository;
    private final WordGroupService wordGroupService;
    private final AudioGenerationTaskService audioGenerationTaskService;
    private final AiParseService aiParseService;

    /**
     * 允许排序的字段白名单：前端传来的 sortField -> 实体属性名。
     * 新增可排序列时记得同步补上，否则接口会返回 400「不支持的排序字段」。
     */
    private static final Map<String, String> SORT_FIELD_WHITELIST = new HashMap<>();

    static {
        SORT_FIELD_WHITELIST.put("id", "id");
        SORT_FIELD_WHITELIST.put("word", "wordLower");      // 生词：小写化后再排，避免大小写打乱字母序
        SORT_FIELD_WHITELIST.put("phonetic", "phonetic");
        SORT_FIELD_WHITELIST.put("meaning", "meaning");
        SORT_FIELD_WHITELIST.put("reviewTotal", "reviewTotal");
        // 最近 N 次拼写错误次数（N 见「易错词配置」），词库管理「近N次拼错」列
        SORT_FIELD_WHITELIST.put("recentSpellWrong", "recentSpellWrong");
        // 同义标签：按它排序可把近义词排到一起，空值由 withSynonymSort 统一排最后
        SORT_FIELD_WHITELIST.put("synonym", "synonym");
    }

    /**
     * 参数类错误统一转成 400。
     * 没有这个的话，排序白名单校验抛出的 IllegalArgumentException 会掉进 500，
     * 前端拿到的是一句没有上下文的「Internal Server Error」。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", e.getMessage());
        return ResponseEntity.badRequest().body(result);
    }

    /** 上传 Excel 并解析入库（groupId：导入到指定词组） */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "groupId", required = false) Long groupId) {
        try {
            ExcelImportService.ImportResult imported = excelImportService.importExcel(file, groupId);
            // 导入完成后，异步生成缺失音频
            audioGenerationTaskService.startBatchGeneration();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("count", imported.wordCount);
            result.put("groupsUsed", imported.groupsUsed);
            StringBuilder message = new StringBuilder("成功导入 " + imported.wordCount + " 个单词");
            if (!imported.groupsUsed.isEmpty()) {
                message.append("，分布在 ").append(imported.groupsUsed.size()).append(" 个词组：")
                        .append(String.join("、", imported.groupsUsed));
            }
            // 没匹配上词组的 Sheet 要说清楚，它们的词落在了「导入到词组」里。
            // 措辞把「工作表名」和「词组名」分开写，避免读起来像目标词组叫 Sheet1
            if (!imported.unmatchedSheets.isEmpty()) {
                message.append("。工作表 ").append(String.join("、", imported.unmatchedSheets))
                        .append(" 没找到同名词组，这些单词已导入到词组「")
                        .append(imported.targetGroupName).append("」");
            }
            result.put("message", message.toString());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "导入失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /** 分页查询词库（可选按词组筛选，含该词组的所有子孙词组） */
    @GetMapping("/page")
    public ResponseEntity<WordPageResponse> getPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "id") String sortField,
            @RequestParam(defaultValue = "asc") String sortOrder,
            @RequestParam(required = false) Long groupId,
            // 「只看已归组的词」：true 时只返回 synonym 非空的词
            @RequestParam(required = false) Boolean hasSynonym,
            // 精确按某个标签筛（可为空串 = 不限标签，但仍受 hasSynonym 约束）
            @RequestParam(required = false) String synonym) {

        // 前端页码从1开始，JPA从0开始。
        // sortField 来自请求参数，必须走白名单：既挡住非法属性名，也把 UI 字段名映射到实体字段
        //（生词列映射到 wordLower，走 LOWER(word) 做不区分大小写的字母序）。
        String entityField = SORT_FIELD_WHITELIST.get(sortField == null ? "" : sortField.trim());
        if (entityField == null) {
            throw new IllegalArgumentException("不支持的排序字段: " + sortField);
        }
        Sort.Direction direction = "asc".equalsIgnoreCase(sortOrder) ? Sort.Direction.ASC : Sort.Direction.DESC;
        boolean sortBySynonym = "synonym".equals(sortField);
        // 同义标签排序的空值必须排最后，而 Sort.Order.nullsLast() 在 Hibernate 里
        // 不会被翻译进 SQL，所以走仓储里写死 ORDER BY 的专用查询，只带分页信息
        Pageable pageRequest = sortBySynonym
                ? wordRepository.paginationOnly(PageRequest.of(page - 1, size))
                : PageRequest.of(page - 1, size, Sort.by(direction, entityField));

        String trimmedKeyword = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        List<Long> groupIds = groupId != null ? wordGroupService.getGroupAndDescendantIds(groupId) : null;

        // 同义标签筛选：空串视为「不限标签」，避免前端传空参数时误筛成空结果
        boolean filterByHasSynonym = Boolean.TRUE.equals(hasSynonym);
        String synonymFilter = (synonym != null && !synonym.trim().isEmpty()) ? synonym.trim() : null;
        if (synonymFilter != null) filterByHasSynonym = true;

        Page<Word> wordPage;
        if (groupIds != null && groupIds.isEmpty()) {
            // 筛选的词组无任何单词
            wordPage = Page.empty(pageRequest);
        } else if (synonymFilter != null && trimmedKeyword != null) {
            wordPage = wordRepository.findBySynonymAndKeyword(synonymFilter, trimmedKeyword, pageRequest);
        } else if (synonymFilter != null) {
            wordPage = wordRepository.findBySynonym(synonymFilter, pageRequest);
        } else if (filterByHasSynonym && trimmedKeyword != null) {
            wordPage = sortBySynonym
                    ? (direction == Sort.Direction.ASC
                        ? wordRepository.findSynonymNotBlankSortedAscAndKeyword(trimmedKeyword, pageRequest)
                        : wordRepository.findSynonymNotBlankSortedDescAndKeyword(trimmedKeyword, pageRequest))
                    : wordRepository.findBySynonymNotBlankAndKeyword(trimmedKeyword, pageRequest);
        } else if (filterByHasSynonym) {
            wordPage = sortBySynonym
                    ? (direction == Sort.Direction.ASC
                        ? wordRepository.findAllSortedBySynonymAsc(pageRequest)
                        : wordRepository.findAllSortedBySynonymDesc(pageRequest))
                    : wordRepository.findBySynonymNotBlank(pageRequest);
        } else if (sortBySynonym) {
            // 排序字段是同义标签但没开筛选：全库按标签排，空值（未归组）统一沉底
            wordPage = direction == Sort.Direction.ASC
                    ? wordRepository.findAllSortedBySynonymAsc(pageRequest)
                    : wordRepository.findAllSortedBySynonymDesc(pageRequest);
        } else if (groupIds != null && trimmedKeyword != null) {
            wordPage = wordRepository.findByGroupIdsAndKeyword(groupIds, trimmedKeyword, pageRequest);
        } else if (groupIds != null) {
            wordPage = wordRepository.findByGroupIds(groupIds, pageRequest);
        } else if (trimmedKeyword != null) {
            wordPage = wordRepository.findByWordContainingOrMeaningContaining(trimmedKeyword, trimmedKeyword, pageRequest);
        } else {
            wordPage = wordRepository.findAll(pageRequest);
        }

        List<WordPageResponse.WordItem> items = new ArrayList<>();
        for (Word w : wordPage.getContent()) {
            items.add(toWordItem(w));
        }

        // 一次性回填词组归属
        Map<Long, List<GroupNodeDTO>> groupMap =
                wordGroupService.getGroupsByWordIds(wordPage.getContent().stream().map(Word::getId).collect(Collectors.toList()));
        for (WordPageResponse.WordItem item : items) {
            item.setGroups(groupMap.getOrDefault(item.getId(), Collections.emptyList()));
        }

        WordPageResponse response = new WordPageResponse();
        response.setContent(items);
        response.setTotalElements(wordPage.getTotalElements());
        response.setTotalPages(wordPage.getTotalPages());
        response.setCurrentPage(page);
        response.setPageSize(size);
        return ResponseEntity.ok(response);
    }

    private WordPageResponse.WordItem toWordItem(Word w) {
        WordPageResponse.WordItem item = new WordPageResponse.WordItem();
        item.setId(w.getId());
        item.setWord(w.getWord());
        item.setPhonetic(w.getPhonetic());
        item.setMeaning(w.getMeaning());
        item.setExampleSentence(w.getExampleSentence());
        item.setExampleMeaning(w.getExampleMeaning());
        item.setReviewTotal(w.getReviewTotal());
        item.setRecogCorrect(w.getRecogCorrect());
        item.setRecogWrong(w.getRecogWrong());
        item.setSpellCorrect(w.getSpellCorrect());
        item.setSpellWrong(w.getSpellWrong());
        item.setIsDormant(w.getIsDormant());
        item.setRecogOnly(w.getRecogOnly());
        item.setRecentSpellWrong(w.getRecentSpellWrong() == null ? 0 : w.getRecentSpellWrong());
        item.setSynonym(w.getSynonym());
        return item;
    }

    /** 导出全部数据（含复习统计、配置、词组关系）为 JSON 文件 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportData() {
        try {
            byte[] data = dataExportImportService.exportAll();
            String filename = URLEncoder.encode("单词复习数据备份.json", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(null);
        }
    }

    /** 下载 Excel 导入模板：只有标题行的空表，用户照着列顺序填 */
    @GetMapping("/export-template")
    public ResponseEntity<byte[]> exportTemplate() {
        try {
            byte[] data = excelExportService.exportTemplate();
            String filename = URLEncoder.encode("词库导入模板.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(null);
        }
    }

    /** 导出单词为 Excel：每个词组一个 Sheet */
    @GetMapping("/export-excel")
    public ResponseEntity<byte[]> exportExcel() {
        try {
            byte[] data = excelExportService.exportGroupedExcel();
            String filename = URLEncoder.encode("词库.xlsx", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(null);
        }
    }

    /** 从 JSON 备份文件导入数据（含复习统计、配置、词组关系） */
    @PostMapping("/import-data")
    public ResponseEntity<Map<String, Object>> importData(@RequestParam("file") MultipartFile file) {
        try {
            int count = dataExportImportService.importData(file);
            // 导入完成后，异步生成缺失音频
            audioGenerationTaskService.startBatchGeneration();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("count", count);
            result.put("message", "成功导入 " + count + " 个单词及复习配置");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "导入失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /** 新增单词（groupId 可选，默认归属到默认词组） */
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addWord(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String wordText = (String) body.get("word");
            if (wordText == null || wordText.trim().isEmpty()) {
                result.put("success", false);
                result.put("message", "生词不能为空");
                return ResponseEntity.badRequest().body(result);
            }
            // 检查是否已存在
            if (wordRepository.findByWord(wordText.trim()).isPresent()) {
                result.put("success", false);
                result.put("message", "单词 '" + wordText.trim() + "' 已存在");
                return ResponseEntity.badRequest().body(result);
            }
            Word word = new Word();
            word.setWord(wordText.trim());
            if (body.containsKey("phonetic")) word.setPhonetic((String) body.get("phonetic"));
            if (body.containsKey("meaning")) word.setMeaning((String) body.get("meaning"));
            if (body.containsKey("exampleSentence")) word.setExampleSentence((String) body.get("exampleSentence"));
            if (body.containsKey("exampleMeaning")) word.setExampleMeaning((String) body.get("exampleMeaning"));
            word = wordRepository.save(word);

            // 归属词组：未指定则默认词组
            List<Long> groupIds = null;
            if (body.get("groupIds") instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> raw = (List<Object>) body.get("groupIds");
                groupIds = raw.stream().filter(Objects::nonNull).map(v -> ((Number) v).longValue()).collect(Collectors.toList());
            }
            wordGroupService.setWordGroups(word.getId(), groupIds);

            // 异步生成音频
            audioGenerationTaskService.triggerGeneration(wordText.trim());
            result.put("success", true);
            result.put("message", "添加成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "添加失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /**
     * 删除单词。
     * mode=word（默认）：彻底删除单词本身，同时清理所有词组关联
     * mode=group：只移出词组，保留单词；若该单词已不属于任何词组则落到默认词组
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteWord(
            @PathVariable Long id,
            @RequestParam(value = "mode", required = false, defaultValue = "word") String mode) {
        Map<String, Object> result = new HashMap<>();
        if (!wordRepository.existsById(id)) {
            result.put("success", false);
            result.put("message", "单词不存在");
            return ResponseEntity.notFound().build();
        }

        if ("group".equalsIgnoreCase(mode)) {
            List<Long> remaining = wordGroupService.removeWordFromGroup(id, null);
            result.put("success", true);
            result.put("message", "已从当前词组移除，单词已保留");
            result.put("remainingGroupIds", remaining);
            return ResponseEntity.ok(result);
        }

        wordGroupService.removeWordMemberships(id);
        wordRepository.deleteById(id);
        result.put("success", true);
        result.put("message", "删除成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 把单词移出指定词组（保留单词本身）。
     * 若该单词仅属于该词组，则自动加入默认词组。
     */
    @DeleteMapping("/{id}/group/{groupId}")
    public ResponseEntity<Map<String, Object>> removeFromGroup(
            @PathVariable Long id, @PathVariable Long groupId) {
        Map<String, Object> result = new HashMap<>();
        if (!wordRepository.existsById(id)) {
            result.put("success", false);
            result.put("message", "单词不存在");
            return ResponseEntity.notFound().build();
        }
        if (!wordGroupService.groupExists(groupId)) {
            result.put("success", false);
            result.put("message", "词组不存在");
            return ResponseEntity.badRequest().body(result);
        }

        List<Long> remaining = wordGroupService.removeWordFromGroup(id, groupId);
        Long defaultGroupId = wordGroupService.getDefaultGroupId();
        // 只有「唯一剩下的词组是默认词组」才算触发了回落
        boolean fellBack = remaining.size() == 1 && defaultGroupId.equals(remaining.get(0));
        result.put("success", true);
        result.put("message", fellBack
                ? "已从词组移除，该单词已移入默认词组"
                : "已从词组移除");
        result.put("remainingGroupIds", remaining);
        return ResponseEntity.ok(result);
    }

    /** 更新单词的仅认读标记 */
    @PutMapping("/{id}/recog-only")
    public ResponseEntity<Map<String, Object>> updateRecogOnly(
            @PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        Map<String, Object> result = new HashMap<>();
        Boolean recogOnly = body.get("recogOnly");
        if (recogOnly == null) {
            result.put("success", false);
            result.put("message", "缺少 recogOnly 参数");
            return ResponseEntity.badRequest().body(result);
        }
        return wordRepository.findById(id).map(word -> {
            word.setRecogOnly(recogOnly);
            wordRepository.save(word);
            result.put("success", true);
            result.put("recogOnly", recogOnly);
            return ResponseEntity.ok(result);
        }).orElseGet(() -> {
            result.put("success", false);
            result.put("message", "单词不存在");
            return ResponseEntity.notFound().build();
        });
    }

    /** 更新单词内容（生词、音标、含义、例句、例句释义） */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateWord(
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            return wordRepository.findById(id).map(word -> {
                String newWord = body.get("word");
                if (newWord != null && !newWord.trim().isEmpty()) {
                    // 检查新单词是否与其他单词冲突
                    wordRepository.findByWord(newWord.trim()).ifPresent(existing -> {
                        if (!existing.getId().equals(id)) {
                            throw new IllegalArgumentException("单词 '" + newWord.trim() + "' 已存在");
                        }
                    });
                    word.setWord(newWord.trim());
                }
                if (body.containsKey("phonetic")) word.setPhonetic(body.get("phonetic"));
                if (body.containsKey("meaning")) word.setMeaning(body.get("meaning"));
                if (body.containsKey("exampleSentence")) word.setExampleSentence(body.get("exampleSentence"));
                if (body.containsKey("exampleMeaning")) word.setExampleMeaning(body.get("exampleMeaning"));
                wordRepository.save(word);
                result.put("success", true);
                result.put("message", "更新成功");
                return ResponseEntity.ok(result);
            }).orElseGet(() -> {
                result.put("success", false);
                result.put("message", "单词不存在");
                return ResponseEntity.notFound().build();
            });
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 覆盖式设置某个单词所属词组（编辑单词时使用） */
    @PutMapping("/{id}/groups")
    public ResponseEntity<Map<String, Object>> setWordGroups(
            @PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            wordGroupService.setWordGroups(id, body.get("groupIds"));
            result.put("success", true);
            result.put("message", "词组已更新");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 批量删除选中的单词。
     * mode=word（默认）：彻底删除单词本体及所有词组关联
     * mode=group：只把单词移出指定词组（groupId，为空则取当前筛选词组），单词保留
     */
    @PostMapping("/batch-delete")
    public ResponseEntity<Map<String, Object>> batchDeleteWords(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Long> wordIds = new ArrayList<>();
            if (body.get("wordIds") instanceof List) {
                for (Object v : (List<?>) body.get("wordIds")) {
                    if (v != null) wordIds.add(((Number) v).longValue());
                }
            }
            if (wordIds.isEmpty()) {
                throw new IllegalArgumentException("请先选择单词");
            }
            String mode = (String) body.get("mode");
            Long groupId = body.get("groupId") instanceof Number ? ((Number) body.get("groupId")).longValue() : null;

            if ("group".equalsIgnoreCase(mode)) {
                int count = wordGroupService.batchRemoveWordsFromGroup(wordIds, groupId);
                result.put("success", true);
                result.put("count", count);
                result.put("message", "已从词组移除 " + count + " 个单词（单词已保留）");
                return ResponseEntity.ok(result);
            }

            int count = wordGroupService.purgeWords(wordIds);
            result.put("success", true);
            result.put("count", count);
            result.put("message", "已彻底删除 " + count + " 个单词");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 批量给选中的单词调整词组（mode: replace / add / remove） */
    // ==================== 同义标签 ====================

    /**
     * 批量给选中的单词打同义标签（或清除）。
     * 请求体：{wordIds:[...], synonym:"lawyer" }
     * synonym 传 null 或空串 = 清除标签（让词回到「未归组」）。
     *
     * 标签名会被 trim 并限长 100（与实体列长一致），超长直接 400 而不是截断——
     * 截断出来的 "lawye" 是个查不出来的标签，比报错更难排查。
     */
    @PostMapping("/batch-synonym")
    public ResponseEntity<Map<String, Object>> batchUpdateSynonym(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Long> wordIds = new ArrayList<>();
            if (body.get("wordIds") instanceof List) {
                for (Object v : (List<?>) body.get("wordIds")) {
                    if (v != null) wordIds.add(((Number) v).longValue());
                }
            }
            if (wordIds.isEmpty()) {
                result.put("success", false);
                result.put("message", "未选中任何单词");
                return ResponseEntity.badRequest().body(result);
            }

            String raw = (String) body.get("synonym");
            String synonym = (raw != null && !raw.trim().isEmpty()) ? raw.trim() : null;
            if (synonym != null && synonym.length() > 100) {
                result.put("success", false);
                result.put("message", "标签名过长（最多 100 字符）");
                return ResponseEntity.badRequest().body(result);
            }

            List<Word> words = wordRepository.findAllById(wordIds);
            for (Word w : words) {
                w.setSynonym(synonym);
            }
            wordRepository.saveAll(words);

            result.put("success", true);
            result.put("count", words.size());
            result.put("synonym", synonym);
            result.put("message", synonym == null
                    ? "已清除 " + words.size() + " 个单词的同义标签"
                    : "已把 " + words.size() + " 个单词归到「" + synonym + "」");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 已用过的全部同义标签（去重、字母序），供前端下拉框用 */
    @GetMapping("/synonyms")
    public ResponseEntity<Map<String, Object>> listSynonyms() {
        Map<String, Object> result = new HashMap<>();
        result.put("synonyms", wordRepository.findDistinctSynonyms());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/batch-groups")
    public ResponseEntity<Map<String, Object>> batchUpdateGroups(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Long> wordIds = new ArrayList<>();
            if (body.get("wordIds") instanceof List) {
                for (Object v : (List<?>) body.get("wordIds")) {
                    if (v != null) wordIds.add(((Number) v).longValue());
                }
            }
            List<Long> groupIds = new ArrayList<>();
            if (body.get("groupIds") instanceof List) {
                for (Object v : (List<?>) body.get("groupIds")) {
                    if (v != null) groupIds.add(((Number) v).longValue());
                }
            }
            String mode = (String) body.get("mode");
            int count = wordGroupService.batchUpdateGroups(wordIds, groupIds, mode);
            result.put("success", true);
            result.put("count", count);
            result.put("message", "已调整 " + count + " 个单词的词组");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /**
     * 批量AI解析添加。
     *
     * 入参 words 可能重复、可能带复数/过去式/-ing 等变形、甚至有拼写错误。
     * 处理顺序：
     *   1. 先在输入内部去重（保留首次出现的大小写）
     *   2. 排除词库中已存在的单词
     *   3. 把剩下的交给 AI，要求返回词典原形 lemma 及音标/全部释义/雅思例句/例句翻译
     *   4. 只用 lemma 入库；若 lemma 纠正后已存在（含本批次内重复），也计入「词库已包含」
     *
     * AI 解析全部完成后才开始入库，中途失败不会留下半截数据。
     */
    @PostMapping("/batch-ai")
    public ResponseEntity<Map<String, Object>> batchAiAdd(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (!(body.get("words") instanceof List)) {
                result.put("success", false);
                result.put("message", "请输入要解析的单词");
                return ResponseEntity.badRequest().body(result);
            }

            // 收集非空行
            List<String> raw = new ArrayList<>();
            for (Object v : (List<?>) body.get("words")) {
                if (v == null) continue;
                String w = v.toString().trim();
                if (!w.isEmpty()) raw.add(w);
            }
            if (raw.isEmpty()) {
                result.put("success", false);
                result.put("message", "请输入要解析的单词");
                return ResponseEntity.badRequest().body(result);
            }

            // 1. 输入内部去重（小写比对，保留首次出现的原样大小写）
            Map<String, String> unique = new LinkedHashMap<>();
            for (String w : raw) {
                unique.putIfAbsent(w.toLowerCase(Locale.ROOT), w);
            }
            int duplicateCount = raw.size() - unique.size();

            // 2. 排除词库已有单词
            Set<String> existing = new HashSet<>(wordRepository.findAllWords()); // 已是小写
            List<String> pending = new ArrayList<>();
            int existingCount = 0;
            for (String w : unique.values()) {
                if (existing.contains(w.toLowerCase(Locale.ROOT))) {
                    existingCount++;
                } else {
                    pending.add(w);
                }
            }

            int added = 0;
            int failed = 0;
            List<String> failedWords = new ArrayList<>();
            Set<String> addedKeys = new HashSet<>();

            if (!pending.isEmpty()) {
                Long groupId = body.get("groupId") == null ? null : ((Number) body.get("groupId")).longValue();
                List<Long> groupIds = groupId == null ? null : Collections.singletonList(groupId);

                List<AiWordDTO> parsed = aiParseService.parse(pending);

                Map<String, AiWordDTO> byInput = new HashMap<>();
                for (AiWordDTO d : parsed) {
                    if (d.getInput() != null && !d.getInput().trim().isEmpty()) {
                        byInput.putIfAbsent(d.getInput().trim().toLowerCase(Locale.ROOT), d);
                    }
                }

                for (String w : pending) {
                    AiWordDTO d = byInput.get(w.toLowerCase(Locale.ROOT));
                    if (d == null) {
                        failed++;
                        failedWords.add(w);
                        continue;
                    }

                    // 入库只用原型
                    String lemma = (d.getLemma() == null || d.getLemma().trim().isEmpty())
                            ? w : d.getLemma().trim();
                    String lemmaKey = lemma.toLowerCase(Locale.ROOT);

                    // 原型命中已有词，或本批次内前面已收录过同一个原型
                    if (existing.contains(lemmaKey) || addedKeys.contains(lemmaKey)) {
                        existingCount++;
                        continue;
                    }

                    Word word = new Word();
                    word.setWord(lemma);
                    word.setPhonetic(d.getPhonetic());
                    word.setMeaning(d.meaningText());
                    word.setExampleSentence(d.getExample());
                    word.setExampleMeaning(d.getExampleMeaning());
                    word = wordRepository.save(word);

                    wordGroupService.setWordGroups(word.getId(), groupIds);
                    audioGenerationTaskService.triggerGeneration(lemma);

                    addedKeys.add(lemmaKey);
                    existing.add(lemmaKey);
                    added++;
                }
            }

            StringBuilder msg = new StringBuilder();
            msg.append("本次添加了 ").append(added).append(" 个，词库已包含 ").append(existingCount).append(" 个");
            if (duplicateCount > 0) {
                msg.append("（输入已去重 ").append(duplicateCount).append(" 条）");
            }
            if (failed > 0) {
                msg.append("，解析失败 ").append(failed).append(" 个");
            }

            result.put("success", true);
            result.put("added", added);
            result.put("existing", existingCount);
            result.put("duplicated", duplicateCount);
            result.put("failed", failed);
            result.put("failedWords", failedWords);
            result.put("message", msg.toString());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "批量添加失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
