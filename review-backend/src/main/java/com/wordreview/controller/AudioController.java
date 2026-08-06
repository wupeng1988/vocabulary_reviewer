package com.wordreview.controller;

import com.wordreview.service.AudioGenerationTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/audio")
@RequiredArgsConstructor
public class AudioController {

    private final AudioGenerationTaskService audioGenerationTaskService;

    /**
     * 获取单词音频文件。
     * - 已存在 → 返回 MP3
     * - 不存在 → 触发异步生成，返回 202
     */
    @GetMapping("/{word}")
    public ResponseEntity<?> getAudio(@PathVariable String word) {
        String normalizedWord = word.trim().toLowerCase();
        Path audioPath = audioGenerationTaskService.getAudioPath(normalizedWord);

        // 已存在：直接返回
        if (Files.exists(audioPath)) {
            return serveAudioFile(audioPath);
        }

        // 不存在：触发异步生成
        audioGenerationTaskService.triggerGeneration(normalizedWord);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "generating");
        result.put("word", normalizedWord);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    /**
     * 查询音频生成状态。
     * - 已存在 → ready
     * - 正在生成 → generating
     * - 不存在且未在生成 → 触发生成，返回 generating
     */
    @GetMapping("/{word}/status")
    public ResponseEntity<Map<String, Object>> getAudioStatus(@PathVariable String word) {
        String normalizedWord = word.trim().toLowerCase();
        Path audioPath = audioGenerationTaskService.getAudioPath(normalizedWord);

        Map<String, Object> result = new HashMap<>();
        result.put("word", normalizedWord);

        if (Files.exists(audioPath)) {
            result.put("status", "ready");
            return ResponseEntity.ok(result);
        }

        // 不存在：触发生成
        audioGenerationTaskService.triggerGeneration(normalizedWord);
        result.put("status", "generating");
        return ResponseEntity.ok(result);
    }

    /**
     * 触发批量音频生成。
     * - 已有任务在运行 → 返回 200 提示正在执行
     * - 无任务运行 → 启动批量生成，返回 202
     */
    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> startBatchGeneration() {
        Map<String, Object> result = new HashMap<>();
        if (audioGenerationTaskService.isBatchRunning()) {
            result.put("status", "running");
            result.put("message", "批量生成任务正在执行中");
            return ResponseEntity.ok(result);
        }
        audioGenerationTaskService.startBatchGeneration();
        result.put("status", "started");
        result.put("message", "批量生成任务已启动");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    /**
     * 查询批量音频生成状态。
     */
    @GetMapping("/batch/status")
    public ResponseEntity<Map<String, Object>> getBatchStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", audioGenerationTaskService.isBatchRunning() ? "running" : "idle");
        return ResponseEntity.ok(result);
    }

    /**
     * 重新生成指定单词的音频（删除旧文件后重新生成）。
     */
    @PostMapping("/regenerate")
    public ResponseEntity<Map<String, Object>> regenerateAudio(@RequestBody Map<String, List<String>> body) {
        List<String> words = body.get("words");
        if (words == null || words.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "未指定单词");
            return ResponseEntity.badRequest().body(result);
        }

        int count = 0;
        for (String word : words) {
            String normalizedWord = word.trim().toLowerCase();
            count += audioGenerationTaskService.regenerateAudio(normalizedWord) ? 1 : 0;
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", count);
        result.put("message", "已触发 " + count + " 个单词的音频重新生成");
        return ResponseEntity.ok(result);
    }

    private ResponseEntity<?> serveAudioFile(Path audioPath) {
        try {
            Resource resource = new FileSystemResource(audioPath);
            long contentLength = Files.size(audioPath);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(contentLength))
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(resource);
        } catch (Exception e) {
            log.error("返回音频文件失败", e);
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", "读取音频文件失败");
            return ResponseEntity.internalServerError().body(result);
        }
    }
}
