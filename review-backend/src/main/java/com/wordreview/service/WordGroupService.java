package com.wordreview.service;

import com.wordreview.dto.GroupNodeDTO;
import com.wordreview.entity.Word;
import com.wordreview.entity.WordGroup;
import com.wordreview.entity.WordGroupMember;
import com.wordreview.repository.WordGroupMemberRepository;
import com.wordreview.repository.WordGroupRepository;
import com.wordreview.repository.WordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 词组管理：树形增删改查 + 单词与词组的关联维护
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WordGroupService {

    public static final String DEFAULT_GROUP_NAME = "默认词组";

    /** 排序字段 null 安全比较：数值小者靠前，相同时按编号 code 升序，再按 id */
    public static final Comparator<WordGroup> GROUP_ORDER =
            Comparator.comparing(WordGroup::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(WordGroup::getCode, Comparator.nullsLast(String::compareTo))
                    .thenComparing(WordGroup::getId);

    private final WordGroupRepository groupRepository;
    private final WordGroupMemberRepository memberRepository;
    private final WordRepository wordRepository;

    /**
     * 启动时确保默认词组存在，并把当前没有任何词组归属的单词补进默认词组
     * （覆盖老版本数据升级场景）
     */
    @PostConstruct
    public void init() {
        try {
            ensureDefaultGroup();
        } catch (Exception e) {
            log.error("初始化默认词组失败", e);
        }
    }

    /** 确保默认词组存在，并补齐历史数据的默认归属 */
    @Transactional
    public WordGroup ensureDefaultGroup() {
        WordGroup def = groupRepository.findAll().stream()
                .filter(g -> Boolean.TRUE.equals(g.getIsDefault()))
                .findFirst()
                .orElse(null);

        if (def == null) {
            def = new WordGroup();
            def.setCode(groupRepository.existsByCode("G001") ? generateCode() : "G001");
            def.setName(DEFAULT_GROUP_NAME);
            def.setParentId(null);
            def.setIsDefault(true);
            def = groupRepository.save(def);
            log.info("已创建内置默认词组: {} ({})", def.getName(), def.getCode());
        }

        backfillDefaultMembership(def.getId());
        backfillSortOrder();
        return def;
    }

    /** 为历史数据补齐排序字段（加列后旧行为 NULL，统一补 0） */
    private void backfillSortOrder() {
        List<WordGroup> pending = groupRepository.findAll().stream()
                .filter(g -> g.getSortOrder() == null)
                .collect(Collectors.toList());
        if (!pending.isEmpty()) {
            for (WordGroup g : pending) {
                g.setSortOrder(0);
            }
            groupRepository.saveAll(pending);
            log.info("已为 {} 个历史词组补齐排序字段", pending.size());
        }
    }

    /** 为没有任何词组归属的单词补上默认词组 */
    private void backfillDefaultMembership(Long defaultGroupId) {
        Set<Long> assigned = memberRepository.findAll().stream()
                .map(WordGroupMember::getWordId)
                .collect(Collectors.toSet());

        List<WordGroupMember> toSave = new ArrayList<>();
        for (Word w : wordRepository.findAll()) {
            if (!assigned.contains(w.getId())) {
                WordGroupMember m = new WordGroupMember();
                m.setWordId(w.getId());
                m.setGroupId(defaultGroupId);
                toSave.add(m);
            }
        }
        if (!toSave.isEmpty()) {
            memberRepository.saveAll(toSave);
            log.info("已为 {} 个无归属单词补齐默认词组", toSave.size());
        }
    }

    /** 默认词组ID */
    public Long getDefaultGroupId() {
        return ensureDefaultGroup().getId();
    }

    // ==================== 查询 ====================

    /** 词组树（wordCount 为直接归属该词组的单词数） */
    public List<GroupNodeDTO> getTree() {
        Map<Long, Long> counts = memberRepository.findAll().stream()
                .collect(Collectors.groupingBy(WordGroupMember::getGroupId, Collectors.counting()));

        Map<Long, GroupNodeDTO> nodeMap = new LinkedHashMap<>();
        for (WordGroup g : groupRepository.findAll()) {
            GroupNodeDTO node = new GroupNodeDTO();
            node.setId(g.getId());
            node.setCode(g.getCode());
            node.setName(g.getName());
            node.setParentId(g.getParentId());
            node.setSortOrder(g.getSortOrder());
            node.setIsDefault(g.getIsDefault());
            node.setWordCount(counts.getOrDefault(g.getId(), 0L));
            nodeMap.put(g.getId(), node);
        }

        List<GroupNodeDTO> roots = new ArrayList<>();
        for (GroupNodeDTO node : nodeMap.values()) {
            if (node.getParentId() == null || !nodeMap.containsKey(node.getParentId())) {
                roots.add(node);
            } else {
                nodeMap.get(node.getParentId()).getChildren().add(node);
            }
        }
        sortNodes(roots);
        return roots;
    }

    private void sortNodes(List<GroupNodeDTO> nodes) {
        nodes.sort(Comparator.comparing(GroupNodeDTO::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(GroupNodeDTO::getCode, Comparator.nullsLast(String::compareTo))
                .thenComparing(GroupNodeDTO::getId));
        for (GroupNodeDTO n : nodes) {
            sortNodes(n.getChildren());
        }
    }

    /** 单词ID → 所属词组列表（用于分页结果回填） */
    public Map<Long, List<GroupNodeDTO>> getGroupsByWordIds(List<Long> wordIds) {
        Map<Long, List<GroupNodeDTO>> result = new HashMap<>();
        if (wordIds == null || wordIds.isEmpty()) return result;

        List<WordGroupMember> members = memberRepository.findByWordIdIn(wordIds);
        if (members.isEmpty()) return result;

        Map<Long, WordGroup> groupMap = groupRepository.findAllById(
                        members.stream().map(WordGroupMember::getGroupId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(WordGroup::getId, g -> g));

        for (WordGroupMember m : members) {
            WordGroup g = groupMap.get(m.getGroupId());
            if (g == null) continue;
            GroupNodeDTO ref = new GroupNodeDTO();
            ref.setId(g.getId());
            ref.setCode(g.getCode());
            ref.setName(g.getName());
            ref.setParentId(g.getParentId());
            ref.setSortOrder(g.getSortOrder());
            ref.setIsDefault(g.getIsDefault());
            result.computeIfAbsent(m.getWordId(), k -> new ArrayList<>()).add(ref);
        }
        result.values().forEach(list -> list.sort(
                Comparator.comparing(GroupNodeDTO::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(GroupNodeDTO::getCode, Comparator.nullsLast(String::compareTo))
                        .thenComparing(GroupNodeDTO::getId)));
        return result;
    }

    /** 某单词所属的词组ID列表 */
    public List<Long> getGroupIdsOfWord(Long wordId) {
        return memberRepository.findByWordId(wordId).stream()
                .map(WordGroupMember::getGroupId)
                .collect(Collectors.toList());
    }

    /** 词组是否存在 */
    public boolean groupExists(Long groupId) {
        return groupId != null && groupRepository.existsById(groupId);
    }

    /** 词组及其所有子孙词组ID（用于按词组筛选单词） */
    public List<Long> getGroupAndDescendantIds(Long groupId) {
        if (groupId == null) return Collections.emptyList();

        Map<Long, List<Long>> childrenMap = new HashMap<>();
        for (WordGroup g : groupRepository.findAll()) {
            if (g.getParentId() != null) {
                childrenMap.computeIfAbsent(g.getParentId(), k -> new ArrayList<>()).add(g.getId());
            }
        }

        List<Long> result = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(groupId);
        Set<Long> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (!visited.add(current)) continue;   // 防御环
            result.add(current);
            queue.addAll(childrenMap.getOrDefault(current, Collections.emptyList()));
        }
        return result;
    }

    // ==================== 增删改 ====================

    @Transactional
    public WordGroup create(String code, String name, Long parentId, Integer sortOrder) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("词组名称不能为空");
        }
        String finalCode = (code == null || code.trim().isEmpty()) ? generateCode() : code.trim();
        if (groupRepository.existsByCode(finalCode)) {
            throw new IllegalArgumentException("词组编号 '" + finalCode + "' 已存在");
        }
        if (parentId != null && !groupRepository.existsById(parentId)) {
            throw new IllegalArgumentException("上级词组不存在");
        }

        WordGroup group = new WordGroup();
        group.setCode(finalCode);
        group.setName(name.trim());
        group.setParentId(parentId);
        group.setSortOrder(sortOrder != null ? sortOrder : 0);
        group.setIsDefault(false);
        return groupRepository.save(group);
    }

    @Transactional
    public WordGroup update(Long id, String code, String name, Long parentId, Integer sortOrder) {
        WordGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("词组不存在"));

        if (name != null && !name.trim().isEmpty()) {
            group.setName(name.trim());
        }
        if (code != null && !code.trim().isEmpty() && !code.trim().equals(group.getCode())) {
            String newCode = code.trim();
            if (groupRepository.existsByCode(newCode)) {
                throw new IllegalArgumentException("词组编号 '" + newCode + "' 已存在");
            }
            group.setCode(newCode);
        }

        if (parentId != null) {
            if (parentId.equals(id)) {
                throw new IllegalArgumentException("上级词组不能是自己");
            }
            if (!groupRepository.existsById(parentId)) {
                throw new IllegalArgumentException("上级词组不存在");
            }
            if (getGroupAndDescendantIds(id).contains(parentId)) {
                throw new IllegalArgumentException("上级词组不能是自身的子词组");
            }
        }
        group.setParentId(parentId);
        if (sortOrder != null) {
            group.setSortOrder(sortOrder);
        }
        return groupRepository.save(group);
    }

    @Transactional
    public void delete(Long id) {
        WordGroup group = groupRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("词组不存在"));
        if (Boolean.TRUE.equals(group.getIsDefault())) {
            throw new IllegalArgumentException("内置默认词组不可删除");
        }
        if (groupRepository.existsByParentId(id)) {
            throw new IllegalArgumentException("该词组下还有子词组，请先删除子词组");
        }
        memberRepository.deleteByGroupId(id);
        groupRepository.delete(group);
        log.info("已删除词组: {} ({})", group.getName(), group.getCode());
    }

    // ==================== 单词与词组的关联 ====================

    /** 覆盖式设置某单词所属词组（传空则回落到默认词组） */
    @Transactional
    public void setWordGroups(Long wordId, List<Long> groupIds) {
        if (!wordRepository.existsById(wordId)) {
            throw new IllegalArgumentException("单词不存在");
        }
        List<Long> ids = normalizeGroupIds(groupIds);
        if (ids.isEmpty()) {
            ids = Collections.singletonList(getDefaultGroupId());
        }
        for (Long gid : ids) {
            if (!groupRepository.existsById(gid)) {
                throw new IllegalArgumentException("词组不存在，ID: " + gid);
            }
        }
        memberRepository.deleteByWordId(wordId);
        memberRepository.flush();
        saveMemberships(Collections.singletonList(wordId), ids);
    }

    /**
     * 批量调整多个单词的词组归属
     * @param mode replace=覆盖 / add=追加 / remove=移除
     */
    @Transactional
    public int batchUpdateGroups(List<Long> wordIds, List<Long> groupIds, String mode) {
        if (wordIds == null || wordIds.isEmpty()) {
            throw new IllegalArgumentException("请先选择单词");
        }
        List<Long> ids = normalizeGroupIds(groupIds);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个词组");
        }
        for (Long gid : ids) {
            if (!groupRepository.existsById(gid)) {
                throw new IllegalArgumentException("词组不存在，ID: " + gid);
            }
        }

        String finalMode = (mode == null || mode.trim().isEmpty()) ? "replace" : mode.trim().toLowerCase();
        List<Long> validWordIds = wordRepository.findByIdIn(wordIds).stream()
                .map(Word::getId).collect(Collectors.toList());

        switch (finalMode) {
            case "add":
                saveMemberships(validWordIds, ids);
                break;
            case "remove":
                for (Long wordId : validWordIds) {
                    List<WordGroupMember> existing = memberRepository.findByWordId(wordId).stream()
                            .filter(m -> ids.contains(m.getGroupId()))
                            .collect(Collectors.toList());
                    if (!existing.isEmpty()) {
                        memberRepository.deleteAll(existing);
                    }
                }
                break;
            case "replace":
            default:
                for (Long wordId : validWordIds) {
                    memberRepository.deleteByWordId(wordId);
                }
                memberRepository.flush();
                saveMemberships(validWordIds, ids);
                break;
        }

        log.info("批量调整词组完成：{} 个单词，模式 {}，词组 {}", validWordIds.size(), finalMode, ids);
        return validWordIds.size();
    }

    /**
     * 批量把单词移出指定词组（保留单词本身）。
     * 移出后无任何归属的单词自动落到默认词组。
     *
     * @return 实际处理的单词数
     */
    @Transactional
    public int batchRemoveWordsFromGroup(List<Long> wordIds, Long groupId) {
        if (wordIds == null || wordIds.isEmpty()) {
            throw new IllegalArgumentException("请先选择单词");
        }
        int count = 0;
        for (Long wordId : wordRepository.findByIdIn(wordIds).stream()
                .map(Word::getId).collect(Collectors.toList())) {
            removeWordFromGroup(wordId, groupId);
            count++;
        }
        log.info("批量移出词组完成：{} 个单词，词组 {}", count, groupId);
        return count;
    }

    /** 删除单词时清理其词组关联 */
    @Transactional
    public void removeWordMemberships(Long wordId) {
        memberRepository.deleteByWordId(wordId);
    }

    /**
     * 彻底删除多个单词：清除单词本体及其所有词组关联
     *
     * @return 实际删除的单词数
     */
    @Transactional
    public int purgeWords(List<Long> wordIds) {
        if (wordIds == null || wordIds.isEmpty()) {
            throw new IllegalArgumentException("请先选择单词");
        }
        List<Long> validIds = wordRepository.findByIdIn(wordIds).stream()
                .map(Word::getId).collect(Collectors.toList());
        memberRepository.deleteByWordIdIn(validIds);
        wordRepository.deleteAllById(validIds);
        log.info("批量彻底删除单词：{} 个", validIds.size());
        return validIds.size();
    }

    /**
     * 把单词移出指定词组，但保留单词本身。
     * 若该单词已不属于任何词组，则落到默认词组，保证「至少归属一个词组」的约束。
     *
     * @return 剩余词组ID列表
     */
    @Transactional
    public List<Long> removeWordFromGroup(Long wordId, Long groupId) {
        List<WordGroupMember> current = memberRepository.findByWordId(wordId);
        List<WordGroupMember> toDelete = current.stream()
                .filter(m -> groupId != null && groupId.equals(m.getGroupId()))
                .collect(Collectors.toList());
        if (!toDelete.isEmpty()) {
            memberRepository.deleteAll(toDelete);
            memberRepository.flush();
        }

        // 移出后无任何归属时落到默认词组
        List<Long> remaining = memberRepository.findByWordId(wordId).stream()
                .map(WordGroupMember::getGroupId)
                .collect(Collectors.toList());
        if (remaining.isEmpty()) {
            Long defaultGroupId = getDefaultGroupId();
            // getDefaultGroupId 内部的补齐逻辑可能已建立该关联，需先查重
            if (!memberRepository.existsByWordIdAndGroupId(wordId, defaultGroupId)) {
                WordGroupMember m = new WordGroupMember();
                m.setWordId(wordId);
                m.setGroupId(defaultGroupId);
                memberRepository.save(m);
            }
            remaining.add(defaultGroupId);
        }
        return remaining;
    }

    /** 给一批单词补充关联（跳过已存在的关联），返回新增的关联数 */
    @Transactional
    public int addWordsToGroup(List<Long> wordIds, Long groupId) {
        if (wordIds == null || wordIds.isEmpty() || groupId == null) return 0;
        int added = 0;
        for (Long wordId : wordIds) {
            if (!memberRepository.existsByWordIdAndGroupId(wordId, groupId)) {
                WordGroupMember m = new WordGroupMember();
                m.setWordId(wordId);
                m.setGroupId(groupId);
                memberRepository.save(m);
                added++;
            }
        }
        return added;
    }

    private void saveMemberships(List<Long> wordIds, List<Long> groupIds) {
        if (wordIds.isEmpty() || groupIds.isEmpty()) return;
        List<WordGroupMember> toSave = new ArrayList<>();
        for (Long wordId : wordIds) {
            for (Long groupId : groupIds) {
                if (!memberRepository.existsByWordIdAndGroupId(wordId, groupId)) {
                    WordGroupMember m = new WordGroupMember();
                    m.setWordId(wordId);
                    m.setGroupId(groupId);
                    toSave.add(m);
                }
            }
        }
        if (!toSave.isEmpty()) {
            memberRepository.saveAll(toSave);
        }
    }

    private List<Long> normalizeGroupIds(List<Long> groupIds) {
        if (groupIds == null) return new ArrayList<>();
        return groupIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
    }

    /** 生成下一个可用编号，形如 G001 */
    private String generateCode() {
        int n = (int) groupRepository.count() + 1;
        String code;
        do {
            code = String.format("G%03d", n++);
        } while (groupRepository.existsByCode(code));
        return code;
    }
}
