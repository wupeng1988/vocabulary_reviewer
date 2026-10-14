package com.wordreview.controller;

import com.wordreview.dto.ReviewConfigDTO;
import com.wordreview.dto.ReviewRecordRequest;
import com.wordreview.entity.ReviewConfig;
import com.wordreview.entity.Word;
import com.wordreview.service.DifficultWordService;
import com.wordreview.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;
    private final DifficultWordService difficultWordService;

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
        // 一次查出本批里已在易错词组的单词，前端据此把「加入易错词组」按钮换成已加入标签
        Set<Long> difficultIds = difficultWordService.difficultWordIdsOf(
                words.stream().map(Word::getId).collect(Collectors.toList()));

        return words.stream().map(w -> {
            Map<String, Object> item = new HashMap<>();
            item.put("id", w.getId());
            item.put("word", w.getWord());
            item.put("phonetic", w.getPhonetic());
            item.put("meaning", w.getMeaning());
            item.put("exampleSentence", w.getExampleSentence());
            item.put("exampleMeaning", w.getExampleMeaning());
            item.put("recogOnly", w.getRecogOnly());
            item.put("inDifficultGroup", difficultIds.contains(w.getId()));
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 上报单次复习结果。
     *
     * mode: RECOG / PRACTICE / SPELL；拼写类阶段（PRACTICE、SPELL）会顺带维护
     * 「最近 N 次拼写错误次数」，并在达到阈值时把单词自动加进内置易错词组。
     * 响应里的 addedToDifficult / difficultReason 供前端弹提示用。
     */
    @PostMapping("/record")
    public ResponseEntity<Map<String, Object>> recordResult(@RequestBody ReviewRecordRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (request.getWordId() == null || request.getResult() == null) {
                result.put("success", false);
                result.put("message", "缺少 wordId 或 result 参数");
                return ResponseEntity.badRequest().body(result);
            }
            result.putAll(reviewService.recordResult(request.getWordId(), request.getMode(), request.getResult()));
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
