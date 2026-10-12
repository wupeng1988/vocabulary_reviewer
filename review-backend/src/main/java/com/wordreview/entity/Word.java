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
