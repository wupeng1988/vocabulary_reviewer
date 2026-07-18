package com.wordreview.dto;

import lombok.Data;

@Data
public class ReviewRecordRequest {
    private Long wordId;
    private String mode;    // "RECOG" 或 "SPELL"
    private Boolean result; // true=正确, false=错误
}
