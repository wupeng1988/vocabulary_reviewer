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
        /** 最近 N 次拼写（拼写练习/默写/听音默写）里拼错的次数，N 见易错词配置 */
        private Integer recentSpellWrong;
        private List<GroupNodeDTO> groups;   // 所属词组
    }
}
