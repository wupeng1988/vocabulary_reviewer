package com.wordreview.controller;

import com.wordreview.entity.DifficultConfig;
import com.wordreview.entity.Word;
import com.wordreview.entity.WordGroup;
import com.wordreview.repository.WordRepository;
import com.wordreview.service.DifficultConfigService;
import com.wordreview.service.DifficultWordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 易错词相关接口：阈值配置读写 + 手动把单词加进内置易错词组。
 *
 * 自动加入的逻辑在 ReviewService.recordResult 里（拼写类阶段上报结果时触发），
 * 这里只负责配置和手动操作。
 */
@RestController
@RequestMapping("/api/difficult")
@RequiredArgsConstructor
public class DifficultWordController {

    private final DifficultConfigService configService;
    private final DifficultWordService difficultWordService;
    private final WordRepository wordRepository;

    /** 读取易错词阈值配置 + 内置易错词组信息 */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        DifficultConfig config = configService.get();
        WordGroup group = difficultWordService.difficultGroup();

        Map<String, Object> result = new HashMap<>();
        result.put("windowSize", config.getWindowSize());
        result.put("consecutiveWrongLimit", config.getConsecutiveWrongLimit());
        result.put("recentWrongLimit", config.getRecentWrongLimit());
        result.put("groupName", group.getName());
        result.put("groupId", group.getId());
        result.put("groupCode", group.getCode());
        return ResponseEntity.ok(result);
    }

    /** 保存易错词阈值配置 */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody DifficultConfig body) {
        Map<String, Object> result = new HashMap<>();
        try {
            DifficultConfig saved = configService.save(body);
            result.put("success", true);
            result.put("message", "保存成功");
            result.put("windowSize", saved.getWindowSize());
            result.put("consecutiveWrongLimit", saved.getConsecutiveWrongLimit());
            result.put("recentWrongLimit", saved.getRecentWrongLimit());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "保存失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /** 手动把单词加入内置易错词组 */
    @PostMapping("/words/{wordId}/add")
    public ResponseEntity<Map<String, Object>> addWord(@PathVariable Long wordId) {
        Map<String, Object> result = new HashMap<>();
        try {
            Word word = wordRepository.findById(wordId)
                    .orElseThrow(() -> new IllegalArgumentException("单词不存在"));
            DifficultConfig config = configService.get();

            boolean added = difficultWordService.addToDifficultGroup(word, "用户手动加入");
            WordGroup group = difficultWordService.difficultGroup();
            result.put("success", true);
            result.put("added", added);
            result.put("groupId", group.getId());
            result.put("groupName", group.getName());
            result.put("recentSpellWrong", word.getRecentSpellWrong() == null ? 0 : word.getRecentSpellWrong());
            result.put("windowSize", config.getWindowSize());
            result.put("message", added
                    ? "「" + word.getWord() + "」已加入" + group.getName()
                    : "「" + word.getWord() + "」本来就在" + group.getName() + "里");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 单词在易错词组中的状态（含最近 N 次拼错次数），供复习卡片展示 */
    @GetMapping("/words/{wordId}")
    public ResponseEntity<Map<String, Object>> wordStatus(@PathVariable Long wordId) {
        Map<String, Object> result = new HashMap<>();
        Word word = wordRepository.findById(wordId).orElse(null);
        if (word == null) {
            result.put("success", false);
            result.put("message", "单词不存在");
            return ResponseEntity.notFound().build();
        }
        DifficultConfig config = configService.get();
        result.put("success", true);
        result.put("inDifficultGroup", difficultWordService.isInDifficultGroup(word));
        result.put("recentSpellWrong", word.getRecentSpellWrong() == null ? 0 : word.getRecentSpellWrong());
        result.put("windowSize", config.getWindowSize());
        return ResponseEntity.ok(result);
    }
}
