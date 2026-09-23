package com.wordreview.controller;

import com.wordreview.dto.GroupNodeDTO;
import com.wordreview.entity.WordGroup;
import com.wordreview.service.WordGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class WordGroupController {

    private final WordGroupService groupService;

    /** 词组树 */
    @GetMapping("/tree")
    public ResponseEntity<List<GroupNodeDTO>> getTree() {
        return ResponseEntity.ok(groupService.getTree());
    }

    /** 默认词组ID（前端新增单词等场景用作默认值） */
    @GetMapping("/default-id")
    public ResponseEntity<Map<String, Object>> getDefaultGroupId() {
        Map<String, Object> result = new HashMap<>();
        result.put("id", groupService.getDefaultGroupId());
        return ResponseEntity.ok(result);
    }

    /** 新增词组 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String code = (String) body.get("code");
            String name = (String) body.get("name");
            Long parentId = toLong(body.get("parentId"));
            WordGroup group = groupService.create(code, name, parentId);

            result.put("success", true);
            result.put("message", "新增成功");
            result.put("id", group.getId());
            result.put("code", group.getCode());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 更新词组（编号、名称、上级词组） */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            String code = (String) body.get("code");
            String name = (String) body.get("name");
            Long parentId = toLong(body.get("parentId"));
            WordGroup group = groupService.update(id, code, name, parentId);

            result.put("success", true);
            result.put("message", "更新成功");
            result.put("code", group.getCode());
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    /** 删除词组（默认词组不可删除；有子词组时需先删除子词组） */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        try {
            groupService.delete(id);
            result.put("success", true);
            result.put("message", "删除成功");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        String s = value.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
