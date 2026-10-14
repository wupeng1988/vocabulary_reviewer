package com.wordreview.service;

import com.wordreview.entity.DifficultConfig;
import com.wordreview.entity.Word;
import com.wordreview.entity.WordGroup;
import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 易错词维护：
 *   1. 记录最近 N 次拼写（拼写练习 / 默写 / 听音默写 合并）的历史与错误次数
 *   2. 按阈值把单词自动加进内置「易错词组」
 *
 * 拼写历史存在 word 表的 spell_recent_history 列上（'1'=拼错，'0'=拼对，末尾最新），
 * 并冗余一列 recent_spell_wrong 记录其中的错误个数，供词库管理排序和阈值判断直接用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DifficultWordService {

    public static final char WRONG_MARK = '1';
    public static final char RIGHT_MARK = '0';

    private final WordRepository wordRepository;
    private final WordGroupService wordGroupService;

    /**
     * 启动时把老数据里新加的三列补成 0 / 空串。
     * 加列时这些字段在实体上是 Integer/String，H2 增量 DDL 之后旧行是 NULL，
     * 不补的话排序会出现 NULL 排最前、计数 NPE。
     */
    @PostConstruct
    public void backfillLegacyWords() {
        try {
            // 三个条件可能命中同一批老行，按 id 去重后统一补
            Map<Long, Word> pending = new LinkedHashMap<>();
            for (Word w : wordRepository.findBySpellRecentHistoryIsNull()) pending.put(w.getId(), w);
            for (Word w : wordRepository.findByRecentSpellWrongIsNull()) pending.put(w.getId(), w);
            for (Word w : wordRepository.findByConsecutiveSpellWrongIsNull()) pending.put(w.getId(), w);
            for (Word w : pending.values()) {
                if (w.getSpellRecentHistory() == null) w.setSpellRecentHistory("");
                if (w.getRecentSpellWrong() == null) w.setRecentSpellWrong(0);
                if (w.getConsecutiveSpellWrong() == null) w.setConsecutiveSpellWrong(0);
            }
            if (!pending.isEmpty()) {
                wordRepository.saveAll(pending.values());
                log.info("已为 {} 个单词补齐拼写错误追踪字段", pending.size());
            }
        } catch (Exception e) {
            log.error("补齐拼写错误追踪字段失败", e);
        }
    }

    /**
     * 记一次拼写结果，更新该单词的「最近 N 次」历史。
     * 只改内存里的 Word，是否落库由调用方决定（ReviewService 会连同复习统计一起 save）。
     *
     * @param windowSize 统计窗口长度，超过长度就丢掉最旧的记录
     */
    public void trackSpelling(Word word, boolean correct, int windowSize) {
        String history = word.getSpellRecentHistory() == null ? "" : word.getSpellRecentHistory();
        history = history + (correct ? RIGHT_MARK : WRONG_MARK);
        int max = Math.max(1, windowSize);
        if (history.length() > max) {
            history = history.substring(history.length() - max);
        }
        word.setSpellRecentHistory(history);
        word.setRecentSpellWrong(countWrong(history));

        int consecutive = nullSafe(word.getConsecutiveSpellWrong());
        word.setConsecutiveSpellWrong(correct ? 0 : consecutive + 1);
    }

    /** 历史串里拼错的次数 */
    public static int countWrong(String history) {
        if (history == null || history.isEmpty()) return 0;
        int n = 0;
        for (int i = 0; i < history.length(); i++) {
            if (history.charAt(i) == WRONG_MARK) n++;
        }
        return n;
    }

    /**
     * 判断是否触发自动加入易错词组。
     *
     * @return 触发原因说明；未触发返回 null
     */
    public String evaluateAutoAddReason(Word word, DifficultConfig config) {
        // 阈值一律走 null 兜底：复习上报是高频路径，配置行被人手工改脏不该把整个复习流程炸掉
        int window = config.getWindowSize() == null ? 30 : config.getWindowSize();
        int consecutiveLimit = config.getConsecutiveWrongLimit() == null ? 3 : config.getConsecutiveWrongLimit();
        int recentLimit = config.getRecentWrongLimit() == null ? 5 : config.getRecentWrongLimit();

        int consecutive = nullSafe(word.getConsecutiveSpellWrong());
        if (consecutive >= consecutiveLimit) {
            return "连续拼写错误 " + consecutive + " 次（阈值 " + consecutiveLimit + "）";
        }
        int recent = nullSafe(word.getRecentSpellWrong());
        if (recent >= recentLimit) {
            return "最近 " + window + " 次拼写累计拼错 " + recent + " 次（阈值 " + recentLimit + "）";
        }
        return null;
    }

    /**
     * 把单词加进内置易错词组（已在里面则什么都不做）。
     *
     * @return true 表示这次是新加进去的，false 表示本来就在里面
     */
    @Transactional
    public boolean addToDifficultGroup(Word word, String reason) {
        Long groupId = wordGroupService.getDifficultGroupId();
        int added = wordGroupService.addWordsToGroup(Collections.singletonList(word.getId()), groupId);
        if (added > 0) {
            log.info("单词 [{}] 已加入易错词组，原因: {}", word.getWord(), reason);
            return true;
        }
        return false;
    }

    /** 一批单词里当前属于易错词组的那些 ID（一次查询） */
    public Set<Long> difficultWordIdsOf(List<Long> wordIds) {
        return wordGroupService.filterWordIdsInGroup(wordIds, wordGroupService.getDifficultGroupId());
    }

    /** 该单词当前是否在易错词组里 */
    public boolean isInDifficultGroup(Word word) {
        return wordGroupService.isWordInGroup(word.getId(), wordGroupService.getDifficultGroupId());
    }

    /** 内置易错词组（可能还没创建时由 WordGroupService 启动逻辑保证存在） */
    public WordGroup difficultGroup() {
        return wordGroupService.getDifficultGroup();
    }

    private static int nullSafe(Integer v) {
        return v == null ? 0 : v;
    }
}
