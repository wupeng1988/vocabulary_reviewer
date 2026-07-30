package com.wordreview.controller;

import com.wordreview.dto.ReviewConfigDTO;
import com.wordreview.dto.ReviewRecordRequest;
import com.wordreview.entity.ReviewConfig;
import com.wordreview.entity.Word;
import com.wordreview.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    /** 获取当前复习设置 */
    @GetMapping("/config")
    public ResponseEntity<ReviewConfigDTO> getConfig() {
        ReviewConfig config = reviewService.getConfig();
        ReviewConfigDTO dto = new ReviewConfigDTO();
        dto.setBatchSize(config.getBatchSize());
        dto.setMaxDormantDays(config.getMaxDormantDays());
        dto.setEmergencyWeight(config.getEmergencyWeight());
        return ResponseEntity.ok(dto);
    }

    /** 更新复习设置 */
    @PutMapping("/config")
    public ResponseEntity<ReviewConfigDTO> updateConfig(@RequestBody ReviewConfigDTO dto) {
        ReviewConfig newConfig = new ReviewConfig();
        newConfig.setBatchSize(dto.getBatchSize());
        newConfig.setMaxDormantDays(dto.getMaxDormantDays());
        newConfig.setEmergencyWeight(dto.getEmergencyWeight());
        ReviewConfig saved = reviewService.updateConfig(newConfig);

        ReviewConfigDTO result = new ReviewConfigDTO();
        result.setBatchSize(saved.getBatchSize());
        result.setMaxDormantDays(saved.getMaxDormantDays());
        result.setEmergencyWeight(saved.getEmergencyWeight());
        return ResponseEntity.ok(result);
    }

    /** 核心：生成本次复习列表（仅返回展示字段，不包含统计元数据） */
    @GetMapping("/generate")
    public ResponseEntity<List<Map<String, Object>>> generateReviewList() {
        List<Word> words = reviewService.generateReviewList();
        return ResponseEntity.ok(toDisplayList(words));
    }

    /** 根据指定单词ID生成复习列表 */
    @GetMapping("/generate-by-ids")
    public ResponseEntity<List<Map<String, Object>>> generateReviewListByIds(@RequestParam("ids") List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        List<Word> words = reviewService.generateReviewListByIds(ids);
        return ResponseEntity.ok(toDisplayList(words));
    }

    /** 将 Word 列表转为展示字段 Map 列表 */
    private List<Map<String, Object>> toDisplayList(List<Word> words) {
        return words.stream().map(w -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", w.getId());
            item.put("word", w.getWord());
            item.put("phonetic", w.getPhonetic());
            item.put("meaning", w.getMeaning());
            item.put("exampleSentence", w.getExampleSentence());
            item.put("exampleMeaning", w.getExampleMeaning());
            item.put("recogOnly", w.getRecogOnly());
            return item;
        }).collect(Collectors.toList());
    }

    /** 上报单次复习结果 */
    @PostMapping("/record")
    public ResponseEntity<Map<String, Object>> recordResult(@RequestBody ReviewRecordRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            reviewService.recordResult(request.getWordId(), request.getMode(), request.getResult());
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
