package com.wordreview.dto;

import lombok.Data;

@Data
public class ReviewRecordRequest {
    private Long wordId;
    private String mode;    // "RECOG" / "PRACTICE"(拼写练习) / "SPELL"(默写、听音默写)
    private Boolean result; // true=正确, false=错误
}
