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

    /**
     * 排序字段，数值越小越靠前。决定词组在词组管理列表、词组筛选下拉、
     * 数据管理导入词组选择器和按词组导出 Excel 中的顺序。
     * 相同取值时按编号 code 升序兜底。历史数据由 WordGroupService 启动时补 0。
     */
    @Column
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private Boolean isDefault = false;      // 是否内置默认词组
}
