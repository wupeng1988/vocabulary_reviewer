package com.wordreview.entity;

import lombok.Data;
import javax.persistence.*;

/**
 * 单词与词组的关联关系（多对多）
 */
@Data
@Entity
@Table(name = "word_group_member",
        uniqueConstraints = @UniqueConstraint(columnNames = {"word_id", "group_id"}))
public class WordGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "word_id", nullable = false)
    private Long wordId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;
}
