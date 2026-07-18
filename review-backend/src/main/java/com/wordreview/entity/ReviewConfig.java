package com.wordreview.entity;

import lombok.Data;
import javax.persistence.*;

@Data
@Entity
@Table(name = "review_config")
public class ReviewConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer batchSize = 50;           // 每批复习词数

    @Column(nullable = false)
    private Integer maxDormantDays = 15;      // 最大沉睡天数（超过则强制召回）

    @Column(nullable = false)
    private Double emergencyWeight = 0.7;     // 紧急因子权重
}
