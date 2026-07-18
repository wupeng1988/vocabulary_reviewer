package com.wordreview.service;

import com.wordreview.entity.Word;
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
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final WordRepository wordRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 导入 Excel 文件，解析 A-E 列入库
     * Upsert 逻辑：word 已存在则更新内容字段，保留复习统计数据
     */
    @Transactional
    public int importExcel(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || (!filename.endsWith(".xls") && !filename.endsWith(".xlsx"))) {
            throw new IllegalArgumentException("仅支持 .xls 和 .xlsx 格式的 Excel 文件");
        }

        int count = 0;
        try (InputStream is = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(is)) {

            Sheet sheet = workbook.getSheetAt(0); // 取第一个 Sheet

            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
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

                entityManager.persist(entityManager.merge(word));
                entityManager.flush();
                entityManager.clear(); // 清空持久化上下文，防止批量积累
                count++;
            }

            log.info("Excel 导入完成，共处理 {} 个单词", count);
            return count;
        }
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
