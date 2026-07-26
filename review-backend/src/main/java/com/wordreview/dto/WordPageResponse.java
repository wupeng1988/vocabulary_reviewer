package com.wordreview.dto;

import lombok.Data;
import java.util.List;

@Data
public class WordPageResponse {
    private List<WordItem> content;
    private long totalElements;
    private int totalPages;
    private int currentPage;
    private int pageSize;

    @Data
    public static class WordItem {
        private Long id;
        private String word;
        private String phonetic;
        private String meaning;
        private String exampleSentence;
        private String exampleMeaning;
        private Integer reviewTotal;
        private Integer recogCorrect;
        private Integer recogWrong;
        private Integer spellCorrect;
        private Integer spellWrong;
        private Boolean isDormant;
        private Boolean recogOnly;
    }
}
