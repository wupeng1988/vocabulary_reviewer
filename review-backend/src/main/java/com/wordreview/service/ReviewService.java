package com.wordreview.service;

import com.wordreview.entity.DifficultConfig;
import com.wordreview.entity.ReviewConfig;
import com.wordreview.entity.Word;
import com.wordreview.repository.ReviewConfigRepository;
import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final WordRepository wordRepository;
    private final ReviewConfigRepository configRepository;
    private final DifficultConfigService difficultConfigService;
    private final DifficultWordService difficultWordService;

    /** 初始化默认配置 */
    @PostConstruct
    public void initDefaultConfig() {
        if (configRepository.count() == 0) {
            ReviewConfig config = new ReviewConfig();
            config.setBatchSize(50);
            config.setMaxDormantDays(15);
            config.setEmergencyWeight(0.7);
            configRepository.save(config);
            log.info("初始化默认复习配置: batchSize=50, maxDormantDays=15, emergencyWeight=0.7");
        }
    }

    /** 获取当前复习配置 */
    public ReviewConfig getConfig() {
        return configRepository.findAll().stream().findFirst().orElseGet(() -> {
            initDefaultConfig();
            return configRepository.findAll().get(0);
        });
    }

    /** 更新复习配置 */
    @Transactional
    public ReviewConfig updateConfig(ReviewConfig newConfig) {
        ReviewConfig config = getConfig();
        if (newConfig.getBatchSize() != null) config.setBatchSize(newConfig.getBatchSize());
        if (newConfig.getMaxDormantDays() != null) config.setMaxDormantDays(newConfig.getMaxDormantDays());
        if (newConfig.getEmergencyWeight() != null) config.setEmergencyWeight(newConfig.getEmergencyWeight());
        return configRepository.save(config);
    }

    /**
     * 根据指定单词ID生成复习列表
     */
    public List<Word> generateReviewListByIds(List<Long> ids) {
        List<Word> words = wordRepository.findByIdIn(ids);
        // 保持传入的ID顺序
        Map<Long, Word> wordMap = words.stream().collect(Collectors.toMap(Word::getId, w -> w));
        return ids.stream().map(wordMap::get).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * 核心：生成本次复习列表
     * 严格执行三级筛选 + 随机扰动机制
     */
    public List<Word> generateReviewList() {
        ReviewConfig config = getConfig();
        int batchSize = config.getBatchSize();
        int maxDormantDays = config.getMaxDormantDays();
        double emergencyWeight = config.getEmergencyWeight();
        double hungerWeight = 1.0 - emergencyWeight; // 0.3

        LocalDateTime cutoff = LocalDateTime.now().minusDays(maxDormantDays);

        // ===== Step 1: 强制召回（破饥饿硬约束） =====
        List<Word> forced = wordRepository.findOverdueOrNew(cutoff);
        List<Word> result = new ArrayList<>(forced);
        log.info("强制召回词数: {}", forced.size());

        if (result.size() >= batchSize) {
            // 强制召回已足够，截取并返回
            Collections.shuffle(result); // 加入随机性
            return result.subList(0, batchSize);
        }

        // ===== Step 2: 综合评分排序（填充剩余名额） =====
        int remaining = batchSize - result.size();
        List<Long> excludeIds = forced.stream().map(Word::getId).collect(Collectors.toList());

        // 如果没有需要排除的 ID，给一个不可能的 ID 避免空 IN 子句报错
        if (excludeIds.isEmpty()) {
            excludeIds = Collections.singletonList(-1L);
        }

        List<Word> candidates = wordRepository.findEligibleCandidates(cutoff, excludeIds);

        if (!candidates.isEmpty()) {
            // 计算 Score 并排序
            // 先计算所有候选词的 H 值，用于 Min-Max 归一化
            List<Double> hValues = new ArrayList<>();
            for (Word w : candidates) {
                double H = computeHungerFactor(w);
                hValues.add(H);
            }
            double hMin = hValues.stream().min(Double::compare).orElse(0.0);
            double hMax = hValues.stream().max(Double::compare).orElse(0.0);
            double hRange = hMax - hMin;
            if (hRange == 0) hRange = 1.0; // 防止除零

            // 为每个候选词计算最终得分
            for (int i = 0; i < candidates.size(); i++) {
                Word w = candidates.get(i);
                double E = computeEmergencyFactor(w);
                double H = hValues.get(i);
                double normalizedH = (H - hMin) / hRange;
                double score = E * emergencyWeight + normalizedH * hungerWeight;
                w.setPriorityScore(score);
            }

            // 按 Score 降序排序
            candidates.sort(Comparator.comparing(Word::getPriorityScore).reversed());

            // ===== Step 3: 候选池随机抽样（防死锁） =====
            int poolSize = Math.min(candidates.size(), remaining * 2);
            List<Word> topCandidates = new ArrayList<>(candidates.subList(0, poolSize));
            Collections.shuffle(topCandidates);

            int selectCount = Math.min(topCandidates.size(), remaining);
            result.addAll(topCandidates.subList(0, selectCount));
            log.info("评分候选填充词数: {}", selectCount);
        }

        log.info("本批复习列表总词数: {}", result.size());
        return result;
    }

    /**
     * 紧急因子 E = (recog_wrong + spell_wrong + 1) / (review_total + 2)
     */
    private double computeEmergencyFactor(Word w) {
        return (w.getRecogWrong() + w.getSpellWrong() + 1.0) / (w.getReviewTotal() + 2.0);
    }

    /**
     * 饥饿因子 H = (当前时间 - last_review_date) 的天数 * 0.5 - (review_total * 0.1)
     */
    private double computeHungerFactor(Word w) {
        long days = ChronoUnit.DAYS.between(w.getLastReviewDate(), LocalDateTime.now());
        return days * 0.5 - w.getReviewTotal() * 0.1;
    }

    /**
     * 记录单次复习结果。
     *
     * 模式口径：
     *   RECOG    认读 —— 只更新认读统计与复习次数
     *   PRACTICE 拼写练习 —— 只累计拼写错误追踪 + 判易错词组，**不动**复习次数/连续正确数
     *                        （拼写练习是看着释义写，比默写简单，混进调度统计会打乱原有排序）
     *   SPELL    默写 / 听音默写 —— 沿用原有默写统计口径，再累计拼写错误追踪 + 判易错词组
     *
     * 拼写错误追踪（PRACTICE / SPELL 都算一次拼写）：
     *   spell_recent_history 追加 '1'/'0' 并按窗口截断，recent_spell_wrong 同步重算，
     *   consecutive_spell_wrong 拼对清零、拼错累加。
     *
     * @return 上报结果：是否自动加入了易错词组、触发原因、当前最近 N 次拼错次数
     */
    @Transactional
    public Map<String, Object> recordResult(Long wordId, String mode, boolean result) {
        Word word = wordRepository.findById(wordId)
                .orElseThrow(() -> new IllegalArgumentException("单词不存在，ID: " + wordId));

        String m = mode == null ? "" : mode.trim().toUpperCase(Locale.ROOT);
        boolean isSpelling = "PRACTICE".equals(m) || "SPELL".equals(m);
        if (!"RECOG".equals(m) && !isSpelling) {
            throw new IllegalArgumentException("无效的复习模式: " + mode + "，仅支持 RECOG / PRACTICE / SPELL");
        }

        Map<String, Object> info = new HashMap<>();

        DifficultConfig spellingConfig = isSpelling ? difficultConfigService.get() : null;

        if (isSpelling) {
            // 窗口长度走 windowSize()：配置行缺值时兜底 30，避免 unboxing NPE
            int windowSize = difficultConfigService.windowSize();
            difficultWordService.trackSpelling(word, result, windowSize);
            info.put("windowSize", windowSize);

            if ("SPELL".equals(m)) {
                if (result) {
                    word.setSpellCorrect(word.getSpellCorrect() + 1);
                } else {
                    word.setSpellWrong(word.getSpellWrong() + 1);
                }
                applyReviewProgress(word, result);
            }
        } else {
            if (result) {
                word.setRecogCorrect(word.getRecogCorrect() + 1);
            } else {
                word.setRecogWrong(word.getRecogWrong() + 1);
            }
            applyReviewProgress(word, result);
        }

        wordRepository.save(word);
        info.put("recentSpellWrong", word.getRecentSpellWrong() == null ? 0 : word.getRecentSpellWrong());

        if (isSpelling) {
            String reason = difficultWordService.evaluateAutoAddReason(word, spellingConfig);
            if (reason != null && difficultWordService.addToDifficultGroup(word, reason)) {
                info.put("addedToDifficult", true);
                info.put("difficultGroupName", WordGroupService.DIFFICULT_GROUP_NAME);
                info.put("difficultReason", reason);
            }
        }
        return info;
    }

    /** 复习次数、最近复习时间、连续正确数、休眠标记 —— 认读与默写共用 */
    private void applyReviewProgress(Word word, boolean result) {
        word.setReviewTotal(word.getReviewTotal() + 1);
        word.setLastReviewDate(LocalDateTime.now());

        if (result) {
            word.setConsecutiveCorrect(word.getConsecutiveCorrect() + 1);
        } else {
            word.setConsecutiveCorrect(0);
        }

        // 连续正确 >= 5 则标记休眠
        word.setIsDormant(word.getConsecutiveCorrect() >= 5);
    }
}
