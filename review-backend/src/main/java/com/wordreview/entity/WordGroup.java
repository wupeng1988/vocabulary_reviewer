package com.wordreview.entity;

import lombok.Data;
import javax.persistence.*;

/**
 * 词组（支持树形结构）
 */
@Data
@Entity
@Table(name = "word_group")
public class WordGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;                    // 编号

    @Column(nullable = false, length = 200)
    private String name;                    // 名称

    @Column
    private Long parentId;                  // 父词组ID，null 表示根词组

    @Column(nullable = false)
    private Boolean isDefault = false;      // 是否内置默认词组
}
