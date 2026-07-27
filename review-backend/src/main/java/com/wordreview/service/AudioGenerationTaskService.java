package com.wordreview.service;

import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 音频生成任务服务：
 * - 管理音频目录
 * - 单词音频的异步生成（避免重复）
 * - 批量扫描缺失音频并依次生成（间隔 1s + 随机 1-2s）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioGenerationTaskService {

    private final EdgeTtsService edgeTtsService;
    private final WordRepository wordRepository;

    @Value("${wordreview.audio-dir:audio}")
    private String audioDir;

    /** 正在生成中的单词集合，用于避免重复提交 */
    private final ConcurrentHashMap<String, CompletableFuture<Void>> generatingMap = new ConcurrentHashMap<>();

    /** 批量生成任务专用的单线程执行器（保证依次执行） */
    private final ExecutorService batchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-batch-gen");
        t.setDaemon(true);
        return t;
    });

    private final Random random = new Random();

    /** 标记是否已有批量任务在运行，避免重复启动 */
    private volatile boolean batchRunning = false;

    /**
     * 判断批量生成任务是否正在运行
     */
    public boolean isBatchRunning() {
        return batchRunning;
    }

    @PostConstruct
    public void init() {
        // 确保音频目录存在
        try {
            Files.createDirectories(Paths.get(audioDir));
            log.info("音频目录已就绪: {}", Paths.get(audioDir).toAbsolutePath());
        } catch (Exception e) {
            log.error("创建音频目录失败", e);
        }

        // 应用启动后，异步扫描并批量生成缺失的音频
        startBatchGeneration();
    }

    /**
     * 获取音频文件路径
     */
    public Path getAudioPath(String word) {
        return Paths.get(audioDir, word.trim().toLowerCase() + ".mp3");
    }

    /**
     * 判断音频文件是否已存在
     */
    public boolean audioExists(String word) {
        return Files.exists(getAudioPath(word));
    }

    /**
     * 判断单词是否正在生成中
     */
    public boolean isGenerating(String word) {
        return generatingMap.containsKey(word.trim().toLowerCase());
    }

    /**
     * 触发单个单词的异步生成（如已在生成中则跳过）
     */
    public void triggerGeneration(String word) {
        String normalizedWord = word.trim().toLowerCase();
        Path audioPath = getAudioPath(normalizedWord);

        if (Files.exists(audioPath)) {
            return;
        }

        generatingMap.computeIfAbsent(normalizedWord, w -> {
            log.info("开始异步生成音频: {}", w);
            CompletableFuture<Void> future = edgeTtsService.generateAudioAsync(w, audioPath);
            future.whenComplete((v, ex) -> {
                generatingMap.remove(w);
                if (ex != null) {
                    log.error("音频生成失败: {}", w, ex);
                }
            });
            return future;
        });
    }

    /**
     * 启动批量音频生成任务（异步，不阻塞调用方）。
     * 扫描数据库中所有生词，对缺少音频的依次生成，间隔 1s + 随机(1-2)s。
     * 如果已有批量任务在运行，则跳过。
     */
    public void startBatchGeneration() {
        if (batchRunning) {
            log.info("批量音频生成任务已在运行，跳过");
            return;
        }

        CompletableFuture.runAsync(() -> {
            batchRunning = true;
            try {
                List<String> allWords = wordRepository.findAllWords();
                long missingCount = allWords.stream()
                        .filter(w -> !Files.exists(getAudioPath(w)))
                        .count();

                if (missingCount == 0) {
                    log.info("所有单词音频已存在，无需批量生成");
                    return;
                }

                log.info("开始批量音频生成，共 {} 个单词缺少音频", missingCount);
                long generated = 0;
                long skipped = 0;

                for (String word : allWords) {
                    String normalizedWord = word.trim().toLowerCase();
                    Path audioPath = getAudioPath(normalizedWord);

                    if (Files.exists(audioPath)) {
                        skipped++;
                        continue;
                    }

                    try {
                        // 依次生成（同步，在批量线程中执行）
                        edgeTtsService.generateAudio(normalizedWord, audioPath);
                        generated++;
                    } catch (Exception e) {
                        log.error("批量生成音频失败: {}", normalizedWord, e);
                    }

                    // 间隔 1s + 随机 1-2s
                    int delayMs = 1000 + 1000 + random.nextInt(1001);
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("批量音频生成被中断");
                        break;
                    }
                }

                log.info("批量音频生成完成，生成 {} 个，跳过 {} 个（已存在）", generated, skipped);
            } catch (Exception e) {
                log.error("批量音频生成任务异常", e);
            } finally {
                batchRunning = false;
            }
        }, batchExecutor);
    }
}
