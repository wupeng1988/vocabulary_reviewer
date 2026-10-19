package com.wordreview.entity;

import lombok.Data;
import org.hibernate.annotations.Formula;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "word")
public class Word {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String word;                    // 生词 (A列)

    @Column
    private String phonetic;                // 音标 (B列)

    @Column(length = 1000)
    private String meaning;                 // 中文含义 (C列)

    @Column(length = 2000)
    private String exampleSentence;         // 例句 (D列)

    @Column(length = 1000)
    private String exampleMeaning;          // 例句释义 (E列)

    // ========== 复习元数据 (核心调度依据) ==========

    @Column
    private LocalDateTime lastReviewDate;   // 最近一次复习时间

    @Column(nullable = false)
    private Integer reviewTotal = 0;        // 总复习次数

    // 认识模式 (英->中)
    @Column(nullable = false)
    private Integer recogCorrect = 0;

    @Column(nullable = false)
    private Integer recogWrong = 0;

    // 默写模式 (中->英)
    @Column(nullable = false)
    private Integer spellCorrect = 0;

    @Column(nullable = false)
    private Integer spellWrong = 0;

    // 防饥饿 & 熟练度标记
    @Column(nullable = false)
    private Integer consecutiveCorrect = 0; // 连续正确次数

    @Column(nullable = false)
    private Boolean isDormant = false;      // 是否休眠

    // 仅认读标记
    @Column(nullable = false)
    private Boolean recogOnly = false;      // 是否仅认读（不参与默写）

    // ========== 拼写错误追踪（拼写练习 / 默写 / 听音默写 合并统计） ==========

    /**
     * 最近若干次拼写的滑动窗口历史。
     * 字符串下标 0 为最早一次、末尾为最新一次：'1'=拼错，'0'=拼对。
     * 每次拼写提交后按当前配置的窗口长度截断头部，因此长度恒 ≤ 窗口大小。
     * 历史数据升级后该列为 NULL，启动时由 DifficultWordService 统一补空串。
     */
    @Column(length = 200)
    private String spellRecentHistory = "";

    /**
     * 上面这段历史里拼错的次数，与 spellRecentHistory 始终保持同步。
     * 单独冗余一列是为了能直接按「最近 N 次拼写错误次数」排序和做阈值判断，
     * 否则要在 SQL 里统计字符串中 '1' 的个数，JPA 表达不了。
     * 历史数据升级后该列为 NULL，启动时统一补 0。
     */
    @Column
    private Integer recentSpellWrong = 0;

    /** 连续拼写错误次数，拼对一次即清零；用于「连续错误 N 次」自动进易错词组 */
    @Column
    private Integer consecutiveSpellWrong = 0;

    /**
     * 同义标签：把意思相近的词归到同一个标签下（如 attorney/barrister/solicitor 都标 "lawyer"），
     * 词库管理里按它排序就能把这些词排到一起，勾选复习时自然连着过。
     *
     * 约定：
     * - 为 null / 空串表示「没归组」，不是脏数据；所以不能用 nullable = false
     *   （Hibernate 增量 DDL 会生成 add column not null，H2 对已有数据直接失败，整条 DDL 回滚）
     * - 按它排序时空值统一排最后（见 WordRepository 的 sortSynonymNullsLast），
     *   所以想手动控制组间先后，在标签前加数字前缀即可，如 "1-lawyer"、"2-colour"
     */
    @Column(length = 100)
    private String synonym;

    /**
     * 仅供排序使用：生词的小写形式，由数据库在查询时计算，不落库、不参与持久化。
     * H2 默认 COLLATION=OFF，按 Java String.compareTo 排序区分大小写，
     * 会把 Apple 排在 apple 前面；词表按字母序看这种结果像 bug，
     * 所以「生词」列的排序走 LOWER(word)。
     */
    @Formula("LOWER(word)")
    private String wordLower;

    /** 评分用临时字段，不持久化 */
    @Transient
    private Double priorityScore;
}
