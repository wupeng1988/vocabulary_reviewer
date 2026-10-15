package com.wordreview.service;

import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
     * 重新生成单个单词的音频：删除旧文件后异步生成新文件。
     * @return true 如果成功触发重新生成
     */
    public boolean regenerateAudio(String word) {
        String normalizedWord = word.trim().toLowerCase();
        Path audioPath = getAudioPath(normalizedWord);

        // 删除旧音频
        try {
            Files.deleteIfExists(audioPath);
        } catch (Exception e) {
            log.error("删除旧音频失败: {}", normalizedWord, e);
            return false;
        }

        // 触发生成（不检查是否已存在，因为刚删了）
        generatingMap.computeIfAbsent(normalizedWord, w -> {
            log.info("重新生成音频: {}", w);
            CompletableFuture<Void> future = edgeTtsService.generateAudioAsync(w, audioPath);
            future.whenComplete((v, ex) -> {
                generatingMap.remove(w);
                if (ex != null) {
                    log.error("音频重新生成失败: {}", w, ex);
                }
            });
            return future;
        });
        return true;
    }

    /**
     * 清理无用音频：删掉音频目录里已经找不到对应单词的 mp3。
     *
     * 「用不到」的口径与 {@link #getAudioPath} 完全一致（word.trim().toLowerCase() + ".mp3"），
     * 也就是那些永远不会被 Files.exists 命中、程序再也不会去读的文件 —— 最常见的场景是
     * 单词从词库删掉了，音频还留在磁盘上。词库里还在的词一个都不会动，删掉的文件等
     * 下次复习时按需重新生成即可。
     *
     * 只扫音频目录第一层（getAudioPath 也是直接拼一层路径），且只删 .mp3，
     * .DS_Store 之类的杂项和子目录一律不动。
     *
     * @param dryRun true 只扫描统计、不真删，供前端弹确认框时先看一眼
     */
    public Map<String, Object> cleanupUnusedAudio(boolean dryRun) {
        Map<String, Object> result = new HashMap<>();
        result.put("dryRun", dryRun);

        Set<String> usedNames = new HashSet<>();
        for (String word : wordRepository.findAllWords()) {
            usedNames.add(word.trim().toLowerCase() + ".mp3");
        }

        int totalFiles = 0;
        int keptFiles = 0;
        int orphanFiles = 0;
        int deletedFiles = 0;
        int failedFiles = 0;
        long freedBytes = 0L;
        List<String> samples = new ArrayList<>();

        Path dir = Paths.get(audioDir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.mp3")) {
            for (Path file : stream) {
                totalFiles++;
                String name = file.getFileName().toString();
                if (usedNames.contains(name)) {
                    keptFiles++;
                    continue;
                }
                // 正在生成中的不碰，别跟写线程抢同一个文件句柄
                if (generatingMap.containsKey(name.substring(0, name.length() - ".mp3".length()))) {
                    keptFiles++;
                    continue;
                }

                orphanFiles++;
                if (samples.size() < 20) {
                    samples.add(name);
                }
                try {
                    freedBytes += Files.size(file);
                } catch (Exception ignore) {
                    // 拿不到大小就当 0，不影响删除
                }
                if (dryRun) {
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                    deletedFiles++;
                } catch (Exception e) {
                    failedFiles++;
                    log.warn("删除无用音频失败: {}", name, e);
                }
            }
        } catch (Exception e) {
            log.error("扫描音频目录失败: {}", audioDir, e);
            result.put("success", false);
            result.put("message", "扫描音频目录失败: " + e.getMessage());
            return result;
        }

        if (dryRun) {
            log.info("扫描无用音频：共 {} 个 mp3，词库用到 {} 个，无用 {} 个",
                    totalFiles, keptFiles, orphanFiles);
            result.put("message", orphanFiles > 0
                    ? "发现 " + orphanFiles + " 个无用音频"
                    : "没有无用音频");
        } else {
            log.info("清理无用音频完成：删掉 {} 个，失败 {} 个，释放 {}",
                    deletedFiles, failedFiles, freedBytes);
            result.put("message", failedFiles > 0
                    ? "已删除 " + deletedFiles + " 个无用音频，" + failedFiles + " 个删除失败"
                    : "已删除 " + deletedFiles + " 个无用音频");
        }

        result.put("success", true);
        result.put("totalFiles", totalFiles);
        result.put("keptFiles", keptFiles);
        result.put("orphanFiles", orphanFiles);
        result.put("deletedFiles", deletedFiles);
        result.put("failedFiles", failedFiles);
        result.put("freedBytes", freedBytes);
        result.put("samples", samples);
        return result;
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
