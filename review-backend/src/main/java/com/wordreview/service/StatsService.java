package com.wordreview.service;

import com.wordreview.entity.ReviewConfig;
import com.wordreview.entity.Word;
import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final WordRepository wordRepository;
    private final ReviewService reviewService;

    /**
     * 获取统计看板数据
     */
    public Map<String, Object> getDashboard() {
        ReviewConfig config = reviewService.getConfig();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(config.getMaxDormantDays());

        Map<String, Object> dashboard = new HashMap<>();

        // 总词数
        long totalWords = wordRepository.count();
        dashboard.put("totalWords", totalWords);

        // 今日待复习数（超过 maxDormantDays 天未复习 或 从未复习）
        long todayReviewCount = wordRepository.countOverdueOrNew(cutoff);
        dashboard.put("todayReviewCount", todayReviewCount);

        // 整体正确率
        List<Word> allWords = wordRepository.findAll();
        int totalCorrect = 0;
        int totalAttempts = 0;
        for (Word w : allWords) {
            totalCorrect += w.getRecogCorrect() + w.getSpellCorrect();
            totalAttempts += w.getReviewTotal();
        }
        double overallAccuracy = totalAttempts > 0 ? (double) totalCorrect / totalAttempts * 100 : 0;
        dashboard.put("overallAccuracy", Math.round(overallAccuracy * 100) / 100.0);

        // 认读正确率
        int recogCorrect = allWords.stream().mapToInt(Word::getRecogCorrect).sum();
        int recogTotal = allWords.stream().mapToInt(w -> w.getRecogCorrect() + w.getRecogWrong()).sum();
        double recogAccuracy = recogTotal > 0 ? (double) recogCorrect / recogTotal * 100 : 0;
        dashboard.put("recogAccuracy", Math.round(recogAccuracy * 100) / 100.0);

        // 默写正确率
        int spellCorrect = allWords.stream().mapToInt(Word::getSpellCorrect).sum();
        int spellTotal = allWords.stream().mapToInt(w -> w.getSpellCorrect() + w.getSpellWrong()).sum();
        double spellAccuracy = spellTotal > 0 ? (double) spellCorrect / spellTotal * 100 : 0;
        dashboard.put("spellAccuracy", Math.round(spellAccuracy * 100) / 100.0);

        // 易错词 Top 5
        List<Word> topErrors = wordRepository.findTopErrorProne(PageRequest.of(0, 5));
        List<Map<String, Object>> errorList = new java.util.ArrayList<>();
        for (Word w : topErrors) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", w.getId());
            item.put("word", w.getWord());
            item.put("meaning", w.getMeaning());
            int wrong = w.getRecogWrong() + w.getSpellWrong();
            int total = w.getReviewTotal();
            double errorRate = total > 0 ? (double) wrong / total * 100 : 0;
            item.put("errorRate", Math.round(errorRate * 100) / 100.0);
            item.put("wrongCount", wrong);
            errorList.add(item);
        }
        dashboard.put("topErrorWords", errorList);

        // 长期未复习词数量（>30天）
        LocalDateTime deepCutoff = LocalDateTime.now().minusDays(30);
        long longUnreviewed = wordRepository.countOverdueOrNew(deepCutoff);
        dashboard.put("longUnreviewedCount", longUnreviewed);

        return dashboard;
    }
}
