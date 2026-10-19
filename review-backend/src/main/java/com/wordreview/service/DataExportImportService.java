package com.wordreview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.wordreview.entity.ReviewConfig;
import com.wordreview.entity.Word;
import com.wordreview.entity.WordGroup;
import com.wordreview.entity.WordGroupMember;
import com.wordreview.repository.ReviewConfigRepository;
import com.wordreview.repository.WordGroupMemberRepository;
import com.wordreview.repository.WordGroupRepository;
import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataExportImportService {

    private final WordRepository wordRepository;
    private final ReviewConfigRepository configRepository;
    private final WordGroupRepository groupRepository;
    private final WordGroupMemberRepository memberRepository;
    private final WordGroupService wordGroupService;

    @PersistenceContext
    private EntityManager entityManager;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * 导出所有数据为 JSON 字节流。
     * version 2：追加词组树和单词的词组归属；兼容老版本导入。
     */
    public byte[] exportAll() throws IOException {
        Map<String, Object> exportData = new LinkedHashMap<>();
        exportData.put("version", 2);
        exportData.put("exportTime", LocalDateTime.now());

        // 导出复习配置
        configRepository.findAll().stream().findFirst().ifPresent(config -> {
            Map<String, Object> configMap = new LinkedHashMap<>();
            configMap.put("batchSize", config.getBatchSize());
            configMap.put("maxDormantDays", config.getMaxDormantDays());
            configMap.put("emergencyWeight", config.getEmergencyWeight());
            exportData.put("reviewConfig", configMap);
        });

        // 导出词组树（父在前子在后，parentCode 引用父编号）
        List<WordGroup> groups = groupRepository.findAll();
        Map<Long, String> codeById = new HashMap<>();
        for (WordGroup g : groups) codeById.put(g.getId(), g.getCode());

        List<Map<String, Object>> groupList = new ArrayList<>();
        for (Long groupId : topoOrder(groups)) {
            WordGroup g = groups.stream().filter(x -> x.getId().equals(groupId)).findFirst().orElse(null);
            if (g == null) continue;
            Map<String, Object> groupMap = new LinkedHashMap<>();
            groupMap.put("code", g.getCode());
            groupMap.put("name", g.getName());
            groupMap.put("sortOrder", g.getSortOrder());
            groupMap.put("isDefault", g.getIsDefault());
            groupMap.put("isDifficult", g.getIsDifficult());
            groupMap.put("parentCode", g.getParentId() == null ? null : codeById.get(g.getParentId()));
            groupList.add(groupMap);
        }
        exportData.put("groups", groupList);

        // 导出所有单词（不含 id，导入时按 word 做 upsert），带词组编号列表
        List<Word> allWords = wordRepository.findAll();
        Map<Long, List<String>> codesByWord = new HashMap<>();
        for (WordGroupMember m : memberRepository.findAll()) {
            String code = codeById.get(m.getGroupId());
            if (code != null) {
                codesByWord.computeIfAbsent(m.getWordId(), k -> new ArrayList<>()).add(code);
            }
        }

        List<Map<String, Object>> wordList = new ArrayList<>();
        for (Word w : allWords) {
            Map<String, Object> wordMap = new LinkedHashMap<>();
            wordMap.put("word", w.getWord());
            wordMap.put("phonetic", w.getPhonetic());
            wordMap.put("meaning", w.getMeaning());
            wordMap.put("exampleSentence", w.getExampleSentence());
            wordMap.put("exampleMeaning", w.getExampleMeaning());
            wordMap.put("lastReviewDate", w.getLastReviewDate());
            wordMap.put("reviewTotal", w.getReviewTotal());
            wordMap.put("recogCorrect", w.getRecogCorrect());
            wordMap.put("recogWrong", w.getRecogWrong());
            wordMap.put("spellCorrect", w.getSpellCorrect());
            wordMap.put("spellWrong", w.getSpellWrong());
            wordMap.put("consecutiveCorrect", w.getConsecutiveCorrect());
            wordMap.put("isDormant", w.getIsDormant());
            wordMap.put("recogOnly", w.getRecogOnly());
            wordMap.put("spellRecentHistory", w.getSpellRecentHistory());
            wordMap.put("recentSpellWrong", w.getRecentSpellWrong());
            // 同义标签：纯分组信息、无隐私，参与备份（换机器后分组不丢）
            wordMap.put("synonym", w.getSynonym());
            wordMap.put("consecutiveSpellWrong", w.getConsecutiveSpellWrong());
            List<String> codes = codesByWord.get(w.getId());
            wordMap.put("groups", codes != null ? codes : Collections.emptyList());
            wordList.add(wordMap);
        }
        exportData.put("words", wordList);

        log.info("数据导出完成，共 {} 个单词，{} 个词组", allWords.size(), groups.size());
        return objectMapper.writeValueAsBytes(exportData);
    }

    /** 词组按父前子后的拓扑顺序排序（BFS） */
    private List<Long> topoOrder(List<WordGroup> groups) {
        Map<Long, List<Long>> children = new HashMap<>();
        for (WordGroup g : groups) {
            if (g.getParentId() != null) {
                children.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(g.getId());
            }
        }
        List<Long> result = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        for (WordGroup g : groups) {
            if (g.getParentId() == null) queue.add(g.getId());
        }
        Set<Long> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (!visited.add(id)) continue;
            result.add(id);
            queue.addAll(children.getOrDefault(id, Collections.emptyList()));
        }
        // 兜底：孤儿节点（parent 缺失）也补进来
        for (WordGroup g : groups) {
            if (!visited.contains(g.getId())) result.add(g.getId());
        }
        return result;
    }

    /**
     * 从 JSON 文件导入数据（支持 version 1 老版本与 version 2）。
     * - 有词组关系：先建词组树，再按文件中的归属导入
     * - 无词组关系（老版本）：所有单词加入默认词组
     */
    @Transactional
    public int importData(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.endsWith(".json")) {
            throw new IllegalArgumentException("仅支持 .json 格式的数据文件");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> importData = objectMapper.readValue(file.getInputStream(), Map.class);

        // 版本校验：接受 1（老版本）和 2
        Object versionObj = importData.get("version");
        int version = versionObj == null ? 1 : ((Number) versionObj).intValue();
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("不支持的数据文件版本");
        }

        // ===== 解析词组 =====
        Map<String, Long> codeToId = new HashMap<>();
        boolean hasGroupRelations = false;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groupList = (List<Map<String, Object>>) importData.get("groups");
        if (groupList != null && !groupList.isEmpty()) {
            hasGroupRelations = true;
            // 第一遍：创建/更新所有词组（不含父级关系）
            Set<String> seenCodes = new HashSet<>();
            for (Map<String, Object> groupMap : groupList) {
                String code = groupMap.get("code") == null ? null : groupMap.get("code").toString().trim();
                String name = groupMap.get("name") == null ? null : groupMap.get("name").toString().trim();
                if (code == null || code.isEmpty() || !seenCodes.add(code)) continue;

                WordGroup group = groupRepository.findByCode(code).orElseGet(WordGroup::new);
                group.setCode(code);
                group.setName(name);
                if (Boolean.TRUE.equals(groupMap.get("isDefault"))) {
                    group.setIsDefault(true);
                }
                if (Boolean.TRUE.equals(groupMap.get("isDifficult"))) {
                    group.setIsDifficult(true);
                }
                // 排序字段（老备份无此字段时保持原值，新词组落为 0）
                Integer sortOrder = toInteger(groupMap.get("sortOrder"));
                if (sortOrder != null) {
                    group.setSortOrder(sortOrder);
                } else if (group.getId() == null) {
                    group.setSortOrder(0);
                }
                codeToId.put(code, group.getId());
                WordGroup saved = groupRepository.save(group);
                codeToId.put(code, saved.getId());
            }
            // 第二遍：建立父子关系
            for (Map<String, Object> groupMap : groupList) {
                String code = groupMap.get("code") == null ? null : groupMap.get("code").toString().trim();
                Long groupId = codeToId.get(code);
                if (groupId == null) continue;
                Object parentCodeObj = groupMap.get("parentCode");
                Long parentId = null;
                if (parentCodeObj != null && !parentCodeObj.toString().trim().isEmpty()) {
                    parentId = codeToId.get(parentCodeObj.toString().trim());
                }
                WordGroup group = groupRepository.findById(groupId).orElse(null);
                if (group != null && !Objects.equals(group.getParentId(), parentId) && !Objects.equals(parentId, groupId)) {
                    group.setParentId(parentId);
                    groupRepository.save(group);
                }
            }
        }
        wordGroupService.ensureDefaultGroup();
        wordGroupService.ensureDifficultGroup();
        Long defaultGroupId = wordGroupService.getDefaultGroupId();

        int wordCount = 0;

        // ===== 导入复习配置 =====
        @SuppressWarnings("unchecked")
        Map<String, Object> configMap = (Map<String, Object>) importData.get("reviewConfig");
        if (configMap != null) {
            ReviewConfig config = configRepository.findAll().stream().findFirst().orElseGet(() -> configRepository.save(new ReviewConfig()));
            if (configMap.get("batchSize") != null) config.setBatchSize(((Number) configMap.get("batchSize")).intValue());
            if (configMap.get("maxDormantDays") != null) config.setMaxDormantDays(((Number) configMap.get("maxDormantDays")).intValue());
            if (configMap.get("emergencyWeight") != null) config.setEmergencyWeight(((Number) configMap.get("emergencyWeight")).doubleValue());
            configRepository.save(config);
            log.info("复习配置已导入");
        }

        // ===== 导入单词（最后统一处理词组归属） =====
        Map<Long, List<Long>> pendingReplaceMemberships = new LinkedHashMap<>();
        List<Long> pendingDefaultAdd = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> wordList = (List<Map<String, Object>>) importData.get("words");
        if (wordList != null) {
            for (Map<String, Object> wordMap : wordList) {
                String wordText = wordMap.get("word") == null ? null : wordMap.get("word").toString().trim();
                if (wordText == null || wordText.isEmpty()) continue;

                Optional<Word> existing = wordRepository.findByWord(wordText);
                Word word;
                if (existing.isPresent()) {
                    word = existing.get();
                } else {
                    word = new Word();
                    word.setWord(wordText);
                }

                word.setPhonetic(wordMap.get("phonetic") == null ? null : wordMap.get("phonetic").toString());
                word.setMeaning(wordMap.get("meaning") == null ? null : wordMap.get("meaning").toString());
                // 老备份没有 synonym 字段，wordMap.get 返回 null，正好表示「未归组」
                word.setSynonym(wordMap.get("synonym") == null ? null : wordMap.get("synonym").toString());
                word.setExampleSentence(wordMap.get("exampleSentence") == null ? null : wordMap.get("exampleSentence").toString());
                word.setExampleMeaning(wordMap.get("exampleMeaning") == null ? null : wordMap.get("exampleMeaning").toString());

                if (wordMap.get("reviewTotal") != null) word.setReviewTotal(((Number) wordMap.get("reviewTotal")).intValue());
                if (wordMap.get("recogCorrect") != null) word.setRecogCorrect(((Number) wordMap.get("recogCorrect")).intValue());
                if (wordMap.get("recogWrong") != null) word.setRecogWrong(((Number) wordMap.get("recogWrong")).intValue());
                if (wordMap.get("spellCorrect") != null) word.setSpellCorrect(((Number) wordMap.get("spellCorrect")).intValue());
                if (wordMap.get("spellWrong") != null) word.setSpellWrong(((Number) wordMap.get("spellWrong")).intValue());
                if (wordMap.get("consecutiveCorrect") != null) word.setConsecutiveCorrect(((Number) wordMap.get("consecutiveCorrect")).intValue());
                if (wordMap.get("isDormant") != null) word.setIsDormant((Boolean) wordMap.get("isDormant"));
                if (wordMap.get("recogOnly") != null) word.setRecogOnly((Boolean) wordMap.get("recogOnly"));

                // 拼写错误追踪（最近 N 次历史 + 错误次数 + 连续错误次数）
                // 老备份没有这些字段时保持新建行的默认值 0 / 空串
                Object historyObj = wordMap.get("spellRecentHistory");
                if (historyObj != null) word.setSpellRecentHistory(historyObj.toString());
                Integer recentWrong = toInteger(wordMap.get("recentSpellWrong"));
                if (recentWrong != null) word.setRecentSpellWrong(recentWrong);
                Integer consecutiveWrong = toInteger(wordMap.get("consecutiveSpellWrong"));
                if (consecutiveWrong != null) word.setConsecutiveSpellWrong(consecutiveWrong);

                Object lastReviewDateObj = wordMap.get("lastReviewDate");
                if (lastReviewDateObj instanceof String) {
                    word.setLastReviewDate(LocalDateTime.parse((String) lastReviewDateObj));
                } else if (lastReviewDateObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Number> dateArr = (List<Number>) lastReviewDateObj;
                    word.setLastReviewDate(LocalDateTime.of(
                            dateArr.get(0).intValue(), dateArr.get(1).intValue(),
                            dateArr.get(2).intValue(), dateArr.get(3).intValue(),
                            dateArr.get(4).intValue(), dateArr.get(5).intValue()));
                }

                Word managed = (Word) entityManager.merge(word);
                entityManager.flush();
                Long wordId = managed.getId();

                // 解析该单词的词组归属
                Object groupsObj = wordMap.get("groups");
                if (hasGroupRelations && groupsObj instanceof List && !((List<?>) groupsObj).isEmpty()) {
                    // 有词组关系：按文件覆盖写入
                    List<Long> memberships = new ArrayList<>();
                    for (Object codeObj : (List<?>) groupsObj) {
                        Long gid = codeToId.get(codeObj.toString().trim());
                        if (gid != null) memberships.add(gid);
                    }
                    if (memberships.isEmpty()) {
                        memberships.add(defaultGroupId);
                    }
                    pendingReplaceMemberships.put(wordId, memberships);
                } else {
                    // 老版本/无词组关系：仅补充默认词组归属，不破坏现有词组（“将单词加到默认词组”）
                    pendingDefaultAdd.add(wordId);
                }

                entityManager.clear();
                wordCount++;
            }
        }

        // 统一写入词组归属
        for (Map.Entry<Long, List<Long>> entry : pendingReplaceMemberships.entrySet()) {
            applyMemberships(entry.getKey(), entry.getValue());
        }
        for (Long wordId : pendingDefaultAdd) {
            if (!memberRepository.existsByWordIdAndGroupId(wordId, defaultGroupId)) {
                WordGroupMember member = new WordGroupMember();
                member.setWordId(wordId);
                member.setGroupId(defaultGroupId);
                memberRepository.save(member);
            }
        }

        log.info("数据导入完成，共处理 {} 个单词", wordCount);
        return wordCount;
    }

    /** 安全转 Integer，null/空/非法一律返回 null */
    private Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 覆盖式写入某单词的词组归属 */
    private void applyMemberships(Long wordId, List<Long> groupIds) {
        memberRepository.deleteByWordId(wordId);
        memberRepository.flush();
        Set<Long> unique = new LinkedHashSet<>(groupIds);
        for (Long groupId : unique) {
            if (!memberRepository.existsByWordIdAndGroupId(wordId, groupId)) {
                WordGroupMember member = new WordGroupMember();
                member.setWordId(wordId);
                member.setGroupId(groupId);
                memberRepository.save(member);
            }
        }
    }
}
