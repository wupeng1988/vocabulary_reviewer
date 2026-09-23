package com.wordreview.repository;

import com.wordreview.entity.Word;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WordRepository extends JpaRepository<Word, Long> {

    Optional<Word> findByWord(String word);

    /** 查询所有单词的文本（小写），用于批量音频生成扫描 */
    @Query("SELECT LOWER(w.word) FROM Word w")
    List<String> findAllWords();

    /** 分页搜索：按生词或含义模糊匹配 */
    Page<Word> findByWordContainingOrMeaningContaining(String word, String meaning, Pageable pageable);

    /** 强制召回：从未复习 或 超过 N 天未复习 */
    @Query("SELECT w FROM Word w WHERE w.lastReviewDate IS NULL OR w.lastReviewDate < :cutoff")
    List<Word> findOverdueOrNew(@Param("cutoff") LocalDateTime cutoff);

    /** 查询所有非强制召回的候选词（用于评分排序） */
    @Query("SELECT w FROM Word w WHERE w.lastReviewDate IS NOT NULL AND w.lastReviewDate >= :cutoff AND w.id NOT IN :excludeIds")
    List<Word> findEligibleCandidates(@Param("cutoff") LocalDateTime cutoff, @Param("excludeIds") List<Long> excludeIds);

    /** 统计：超过 N 天未复习的词数 */
    @Query("SELECT COUNT(w) FROM Word w WHERE w.lastReviewDate IS NULL OR w.lastReviewDate < :cutoff")
    long countOverdueOrNew(@Param("cutoff") LocalDateTime cutoff);

    /** 根据ID列表查询单词 */
    List<Word> findByIdIn(List<Long> ids);

    /** 易错词 Top N：按错误率降序 */
    @Query("SELECT w FROM Word w WHERE (w.recogWrong + w.spellWrong) > 0 ORDER BY (w.recogWrong + w.spellWrong) * 1.0 / (w.reviewTotal + 1) DESC")
    List<Word> findTopErrorProne(Pageable pageable);

    // ==================== 按词组筛选 ====================

    /** 按词组（含子孙词组）分页查询全部单词 */
    @Query("SELECT w FROM Word w WHERE w.id IN (SELECT m.wordId FROM WordGroupMember m WHERE m.groupId IN :groupIds)")
    Page<Word> findByGroupIds(@Param("groupIds") List<Long> groupIds, Pageable pageable);

    /** 按词组（含子孙词组）+ 关键词分页查询 */
    @Query("SELECT w FROM Word w WHERE w.id IN (SELECT m.wordId FROM WordGroupMember m WHERE m.groupId IN :groupIds)"
            + " AND (w.word LIKE %:kw% OR w.meaning LIKE %:kw%)")
    Page<Word> findByGroupIdsAndKeyword(@Param("groupIds") List<Long> groupIds, @Param("kw") String keyword, Pageable pageable);
}
