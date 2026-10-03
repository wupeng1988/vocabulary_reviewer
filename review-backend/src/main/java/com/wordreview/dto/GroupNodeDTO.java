package com.wordreview.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GroupNodeDTO {
    private Long id;
    private String code;
    private String name;
    private Long parentId;
    private Integer sortOrder;              // 排序字段，数值越小越靠前
    private Boolean isDefault;
    private long wordCount;                     // 直接归属该词组的单词数
    private List<GroupNodeDTO> children = new ArrayList<>();
}
