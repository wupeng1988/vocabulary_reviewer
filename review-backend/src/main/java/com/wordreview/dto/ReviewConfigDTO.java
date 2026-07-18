package com.wordreview.dto;

import lombok.Data;

@Data
public class ReviewConfigDTO {
    private Integer batchSize;
    private Integer maxDormantDays;
    private Double emergencyWeight;
}
