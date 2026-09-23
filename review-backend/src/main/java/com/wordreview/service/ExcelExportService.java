package com.wordreview.service;

import com.wordreview.entity.Word;
import com.wordreview.entity.WordGroup;
import com.wordreview.entity.WordGroupMember;
import com.wordreview.repository.WordGroupMemberRepository;
import com.wordreview.repository.WordGroupRepository;
import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 按词组分 Sheet 导出 Excel
 */
@Service
@RequiredArgsConstructor
public class ExcelExportService {

    private static final String[] HEADERS = {"生词", "音标", "含义", "例句", "例句释义"};

    private final WordRepository wordRepository;
    private final WordGroupRepository groupRepository;
    private final WordGroupMemberRepository memberRepository;

    /**
     * 每个词组一个 Sheet；未归属任何词组的单词导出到「未分组」Sheet。
     * 空词组不生成 Sheet。
     */
    public byte[] exportGroupedExcel() throws IOException {
        List<Word> allWords = wordRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        Map<Long, Word> wordMap = allWords.stream().collect(Collectors.toMap(Word::getId, w -> w));

        Map<Long, List<Word>> wordsByGroup = new HashMap<>();
        Set<Long> groupedWordIds = new HashSet<>();
        for (WordGroupMember m : memberRepository.findAll()) {
            Word w = wordMap.get(m.getWordId());
            if (w == null) continue;
            wordsByGroup.computeIfAbsent(m.getGroupId(), k -> new ArrayList<>()).add(w);
            groupedWordIds.add(m.getWordId());
        }

        List<Word> ungrouped = allWords.stream()
                .filter(w -> !groupedWordIds.contains(w.getId()))
                .collect(Collectors.toList());

        List<WordGroup> groups = groupRepository.findAll().stream()
                .sorted(Comparator.comparing(WordGroup::getCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(WordGroup::getId))
                .collect(Collectors.toList());

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Set<String> usedSheetNames = new HashSet<>();
            for (WordGroup g : groups) {
                List<Word> words = wordsByGroup.getOrDefault(g.getId(), Collections.emptyList());
                if (words.isEmpty()) continue;
                String sheetName = uniqueSheetName(usedSheetNames, g.getCode() + "-" + g.getName());
                createSheet(workbook, sheetName, words);
            }

            if (!ungrouped.isEmpty()) {
                createSheet(workbook, uniqueSheetName(usedSheetNames, "未分组"), ungrouped);
            }

            if (workbook.getNumberOfSheets() == 0) {
                workbook.createSheet("词库");
            }

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void createSheet(Workbook workbook, String sheetName, List<Word> words) {
        Sheet sheet = workbook.createSheet(sheetName);

        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < HEADERS.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(HEADERS[i]);
            CellStyle style = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            style.setFont(font);
            cell.setCellStyle(style);
        }

        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(w.getWord() != null ? w.getWord() : "");
            row.createCell(1).setCellValue(w.getPhonetic() != null ? w.getPhonetic() : "");
            row.createCell(2).setCellValue(w.getMeaning() != null ? w.getMeaning() : "");
            row.createCell(3).setCellValue(w.getExampleSentence() != null ? w.getExampleSentence() : "");
            row.createCell(4).setCellValue(w.getExampleMeaning() != null ? w.getExampleMeaning() : "");
        }

        for (int i = 0; i < HEADERS.length; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    /** Sheet 名去非法字符（[]:*?/\ 不允许出现）+ 去重 + 截断 31 字符 */
    private String uniqueSheetName(Set<String> used, String base) {
        String clean = base.replaceAll("[\\\\/*?\\[\\]:]", "").trim();
        if (clean.isEmpty()) clean = "词组";
        if (clean.length() > 31) clean = clean.substring(0, 31);
        String candidate = clean;
        int n = 1;
        while (used.contains(candidate)) {
            String suffix = "(" + (++n) + ")";
            candidate = clean.length() + suffix.length() > 31
                    ? clean.substring(0, 31 - suffix.length()) + suffix
                    : clean + suffix;
        }
        used.add(candidate);
        return candidate;
    }
}
