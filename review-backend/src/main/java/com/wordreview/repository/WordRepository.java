package com.wordreview.repository;

import com.wordreview.entity.Word;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    /** 启动时补齐新加列的 NULL 值（历史数据升级） */
    List<Word> findBySpellRecentHistoryIsNull();

    /** 启动时补齐新加列的 NULL 值（历史数据升级） */
    List<Word> findByRecentSpellWrongIsNull();

    /** 启动时补齐新加列的 NULL 值（历史数据升级） */
    List<Word> findByConsecutiveSpellWrongIsNull();

    // ==================== 按词组筛选 ====================

    /** 按词组（含子孙词组）分页查询全部单词 */
    @Query("SELECT w FROM Word w WHERE w.id IN (SELECT m.wordId FROM WordGroupMember m WHERE m.groupId IN :groupIds)")
    Page<Word> findByGroupIds(@Param("groupIds") List<Long> groupIds, Pageable pageable);

    /** 按词组（含子孙词组）+ 关键词分页查询 */
    @Query("SELECT w FROM Word w WHERE w.id IN (SELECT m.wordId FROM WordGroupMember m WHERE m.groupId IN :groupIds)"
            + " AND (w.word LIKE %:kw% OR w.meaning LIKE %:kw%)")
    Page<Word> findByGroupIdsAndKeyword(@Param("groupIds") List<Long> groupIds, @Param("kw") String keyword, Pageable pageable);

    // ==================== 同义标签 ====================

    /**
     * 分页查询：只看「已归组」的词（synonym 非空）。
     *
     * H2/SQL 里空串 '' 和 NULL 都算「没归组」，所以两个都要排除——
     * 只判 IS NOT NULL 会把空串的词也捞出来（历史上可能存进过 ''）。
     */
    @Query("SELECT w FROM Word w WHERE w.synonym IS NOT NULL AND TRIM(w.synonym) <> ''")
    Page<Word> findBySynonymNotBlank(Pageable pageable);

    /** 分页查询：只看某个具体标签的词 */
    @Query("SELECT w FROM Word w WHERE w.synonym = :synonym")
    Page<Word> findBySynonym(@Param("synonym") String synonym, Pageable pageable);

    /** 分页查询：按关键词（生词或含义）搜，且只限已归组的词 */
    @Query("SELECT w FROM Word w WHERE w.synonym IS NOT NULL AND TRIM(w.synonym) <> ''" +
           " AND (w.word LIKE %:kw% OR w.meaning LIKE %:kw%)")
    Page<Word> findBySynonymNotBlankAndKeyword(@Param("kw") String keyword, Pageable pageable);

    /** 分页查询：按关键词搜某个具体标签内的词 */
    @Query("SELECT w FROM Word w WHERE w.synonym = :synonym" +
           " AND (w.word LIKE %:kw% OR w.meaning LIKE %:kw%)")
    Page<Word> findBySynonymAndKeyword(@Param("synonym") String synonym,
                                       @Param("kw") String keyword, Pageable pageable);

    /** 已用过的全部标签（去重、非空、字母序），供前端下拉框用 */
    @Query("SELECT DISTINCT w.synonym FROM Word w " +
           "WHERE w.synonym IS NOT NULL AND TRIM(w.synonym) <> '' ORDER BY w.synonym ASC")
    List<String> findDistinctSynonyms();

    /**
     * 按同义标签分页（升序），空值排最后。
     *
     * 为什么空值要排最后：H2 默认 ORDER BY 把 NULL 当最小值排最前，
     * 而「没归组」的词有 4000 多个占绝大多数——排最前的话真正归了组的
     * 会被顶到第一页之后，等于白排。
     *
     * 为什么不用 Pageable/Sort 拼：实测 `new Sort.Order(...).nullsLast()` **不会**被
     * 翻译进生成的 SQL（`order by synonym asc`，NULL 照样排最前），
     * 所以直接在 JPQL 里写 NULLS LAST。同标签内按 id 升序，
     * 组间顺序由标签字符串决定，想控制先后就在标签前加数字前缀。
     */
    @Query(value = "SELECT w FROM Word w ORDER BY w.synonym ASC NULLS LAST, w.id ASC",
           countQuery = "SELECT COUNT(w) FROM Word w")
    Page<Word> findAllSortedBySynonymAsc(Pageable pageable);

    /** 同上但降序（空值同样排最后） */
    @Query(value = "SELECT w FROM Word w ORDER BY w.synonym DESC NULLS LAST, w.id ASC",
           countQuery = "SELECT COUNT(w) FROM Word w")
    Page<Word> findAllSortedBySynonymDesc(Pageable pageable);

    /** 已归组的词 + 按同义标签升序 + 关键词搜索 */
    @Query(value = "SELECT w FROM Word w WHERE w.synonym IS NOT NULL AND TRIM(w.synonym) <> '' "
                   + "AND (w.word LIKE %:kw% OR w.meaning LIKE %:kw%) "
                   + "ORDER BY w.synonym ASC NULLS LAST, w.id ASC",
           countQuery = "SELECT COUNT(w) FROM Word w WHERE w.synonym IS NOT NULL AND TRIM(w.synonym) <> '' "
                        + "AND (w.word LIKE %:kw% OR w.meaning LIKE %:kw%)")
    Page<Word> findSynonymNotBlankSortedAscAndKeyword(@Param("kw") String keyword, Pageable pageable);

    /** 已归组的词 + 按同义标签降序 + 关键词搜索 */
    @Query(value = "SELECT w FROM Word w WHERE w.synonym IS NOT NULL AND TRIM(w.synonym) <> '' "
                   + "AND (w.word LIKE %:kw% OR w.meaning LIKE %:kw%) "
                   + "ORDER BY w.synonym DESC NULLS LAST, w.id ASC",
           countQuery = "SELECT COUNT(w) FROM Word w WHERE w.synonym IS NOT NULL AND TRIM(w.synonym) <> '' "
                        + "AND (w.word LIKE %:kw% OR w.meaning LIKE %:kw%)")
    Page<Word> findSynonymNotBlankSortedDescAndKeyword(@Param("kw") String keyword, Pageable pageable);

    /**
     * 造一个「不带排序」的 Pageable，供上面几个 @Query 方法用。
     * JPQL 里已经写了 ORDER BY，Pageable 再带 Sort 只会多拼一段冗余的 order by，
     * 所以这里只保留分页信息。
     */
    default Pageable paginationOnly(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }
}
