package com.wordreview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wordreview.entity.ReviewConfig;
import com.wordreview.entity.Word;
import com.wordreview.repository.ReviewConfigRepository;
import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportImportService {

    private final WordRepository wordRepository;
    private final ReviewConfigRepository configRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 导出所有数据为 JSON 字节流
     * 包含所有单词（含复习统计）和复习配置
     */
    public byte[] exportAll() throws IOException {
        Map<String, Object> exportData = new LinkedHashMap<>();
        exportData.put("version", 1);
        exportData.put("exportTime", LocalDateTime.now());

        // 导出复习配置
        Optional<ReviewConfig> configOpt = configRepository.findAll().stream().findFirst();
        if (configOpt.isPresent()) {
            ReviewConfig config = configOpt.get();
            Map<String, Object> configMap = new LinkedHashMap<>();
            configMap.put("batchSize", config.getBatchSize());
            configMap.put("maxDormantDays", config.getMaxDormantDays());
            configMap.put("emergencyWeight", config.getEmergencyWeight());
            exportData.put("reviewConfig", configMap);
        }

        // 导出所有单词（不含 id，导入时按 word 做 upsert）
        List<Word> allWords = wordRepository.findAll();
        List<Map<String, Object>> wordList = new ArrayList<>();
        for (Word w : allWords) {
            Map<String, Object> wordMap = new LinkedHashMap<>();
            wordMap.put("word", w.getWord());
            wordMap.put("phonetic", w.getPhonetic());
            wordMap.put("meaning", w.getMeaning());
            wordMap.put("exampleSentence", w.getExampleSentence());
            wordMap.put("exampleMeaning", w.getExampleMeaning());
            wordMap.put("lastReviewDate", w.getLastReviewDate());
            wordMap.put("reviewTotal", w.getReviewTotal());
            wordMap.put("recogCorrect", w.getRecogCorrect());
            wordMap.put("recogWrong", w.getRecogWrong());
            wordMap.put("spellCorrect", w.getSpellCorrect());
            wordMap.put("spellWrong", w.getSpellWrong());
            wordMap.put("consecutiveCorrect", w.getConsecutiveCorrect());
            wordMap.put("isDormant", w.getIsDormant());
            wordMap.put("recogOnly", w.getRecogOnly());
            wordList.add(wordMap);
        }
        exportData.put("words", wordList);

        log.info("数据导出完成，共 {} 个单词", allWords.size());
        return objectMapper.writeValueAsBytes(exportData);
    }

    /**
     * 从 JSON 文件导入数据
     * Upsert 逻辑：按 word 字段匹配，存在则更新所有字段（含复习统计），不存在则新建
     * ReviewConfig 直接替换现有配置
     */
    @Transactional
    public int importData(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.endsWith(".json")) {
            throw new IllegalArgumentException("仅支持 .json 格式的数据文件");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> importData = objectMapper.readValue(file.getInputStream(), Map.class);

        // 版本校验
        Object version = importData.get("version");
        if (version == null || ((Number) version).intValue() != 1) {
            throw new IllegalArgumentException("不支持的数据文件版本");
        }

        int wordCount = 0;

        // 导入复习配置
        @SuppressWarnings("unchecked")
        Map<String, Object> configMap = (Map<String, Object>) importData.get("reviewConfig");
        if (configMap != null) {
            ReviewConfig config = configRepository.findAll().stream().findFirst().orElseGet(() -> {
                ReviewConfig c = new ReviewConfig();
                return configRepository.save(c);
            });
            if (configMap.get("batchSize") != null) {
                config.setBatchSize(((Number) configMap.get("batchSize")).intValue());
            }
            if (configMap.get("maxDormantDays") != null) {
                config.setMaxDormantDays(((Number) configMap.get("maxDormantDays")).intValue());
            }
            if (configMap.get("emergencyWeight") != null) {
                config.setEmergencyWeight(((Number) configMap.get("emergencyWeight")).doubleValue());
            }
            configRepository.save(config);
            log.info("复习配置已导入");
        }

        // 导入单词
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> wordList = (List<Map<String, Object>>) importData.get("words");
        if (wordList != null) {
            for (Map<String, Object> wordMap : wordList) {
                String wordText = (String) wordMap.get("word");
                if (wordText == null || wordText.trim().isEmpty()) continue;

                wordText = wordText.trim();
                Optional<Word> existing = wordRepository.findByWord(wordText);
                Word word;
                if (existing.isPresent()) {
                    // 已存在：更新所有字段（含复习统计）
                    word = existing.get();
                } else {
                    // 新建
                    word = new Word();
                    word.setWord(wordText);
                }

                // 设置内容字段
                word.setPhonetic((String) wordMap.get("phonetic"));
                word.setMeaning((String) wordMap.get("meaning"));
                word.setExampleSentence((String) wordMap.get("exampleSentence"));
                word.setExampleMeaning((String) wordMap.get("exampleMeaning"));

                // 设置复习统计
                if (wordMap.get("reviewTotal") != null) {
                    word.setReviewTotal(((Number) wordMap.get("reviewTotal")).intValue());
                }
                if (wordMap.get("recogCorrect") != null) {
                    word.setRecogCorrect(((Number) wordMap.get("recogCorrect")).intValue());
                }
                if (wordMap.get("recogWrong") != null) {
                    word.setRecogWrong(((Number) wordMap.get("recogWrong")).intValue());
                }
                if (wordMap.get("spellCorrect") != null) {
                    word.setSpellCorrect(((Number) wordMap.get("spellCorrect")).intValue());
                }
                if (wordMap.get("spellWrong") != null) {
                    word.setSpellWrong(((Number) wordMap.get("spellWrong")).intValue());
                }
                if (wordMap.get("consecutiveCorrect") != null) {
                    word.setConsecutiveCorrect(((Number) wordMap.get("consecutiveCorrect")).intValue());
                }
                if (wordMap.get("isDormant") != null) {
                    word.setIsDormant((Boolean) wordMap.get("isDormant"));
                }
                if (wordMap.get("recogOnly") != null) {
                    word.setRecogOnly((Boolean) wordMap.get("recogOnly"));
                }

                // 解析 lastReviewDate
                Object lastReviewDateObj = wordMap.get("lastReviewDate");
                if (lastReviewDateObj instanceof String) {
                    word.setLastReviewDate(LocalDateTime.parse((String) lastReviewDateObj));
                } else if (lastReviewDateObj instanceof List) {
                    // Jackson 反序列化 LocalDateTime 为数组格式时的处理
                    @SuppressWarnings("unchecked")
                    List<Number> dateArr = (List<Number>) lastReviewDateObj;
                    word.setLastReviewDate(LocalDateTime.of(
                            dateArr.get(0).intValue(), dateArr.get(1).intValue(),
                            dateArr.get(2).intValue(), dateArr.get(3).intValue(),
                            dateArr.get(4).intValue(), dateArr.get(5).intValue()));
                }

                entityManager.persist(entityManager.merge(word));
                entityManager.flush();
                entityManager.clear();
                wordCount++;
            }
        }

        log.info("数据导入完成，共处理 {} 个单词", wordCount);
        return wordCount;
    }
}
