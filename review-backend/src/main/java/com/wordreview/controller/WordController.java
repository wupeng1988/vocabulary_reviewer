package com.wordreview.controller;

import com.wordreview.dto.WordPageResponse;
import com.wordreview.entity.Word;
import com.wordreview.repository.WordRepository;
import com.wordreview.service.DataExportImportService;
import com.wordreview.service.ExcelImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/words")
@RequiredArgsConstructor
public class WordController {

    private final ExcelImportService excelImportService;
    private final DataExportImportService dataExportImportService;
    private final WordRepository wordRepository;

    /** 上传 Excel 并解析入库 */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) {
        try {
            int count = excelImportService.importExcel(file);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("count", count);
            result.put("message", "成功导入 " + count + " 个单词");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "导入失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /** 分页查询词库 */
    @GetMapping("/page")
    public ResponseEntity<WordPageResponse> getPage(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {

        // 前端页码从1开始，JPA从0开始
        PageRequest pageRequest = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<Word> wordPage;

        if (keyword != null && !keyword.trim().isEmpty()) {
            wordPage = wordRepository.findByWordContainingOrMeaningContaining(
                    keyword.trim(), keyword.trim(), pageRequest);
        } else {
            wordPage = wordRepository.findAll(pageRequest);
        }

        WordPageResponse response = new WordPageResponse();
        response.setContent(wordPage.getContent().stream().map(w -> {
            WordPageResponse.WordItem item = new WordPageResponse.WordItem();
            item.setId(w.getId());
            item.setWord(w.getWord());
            item.setPhonetic(w.getPhonetic());
            item.setMeaning(w.getMeaning());
            item.setExampleSentence(w.getExampleSentence());
            item.setExampleMeaning(w.getExampleMeaning());
            item.setReviewTotal(w.getReviewTotal());
            item.setRecogCorrect(w.getRecogCorrect());
            item.setRecogWrong(w.getRecogWrong());
            item.setSpellCorrect(w.getSpellCorrect());
            item.setSpellWrong(w.getSpellWrong());
            item.setIsDormant(w.getIsDormant());
            return item;
        }).collect(Collectors.toList()));
        response.setTotalElements(wordPage.getTotalElements());
        response.setTotalPages(wordPage.getTotalPages());
        response.setCurrentPage(page);
        response.setPageSize(size);

        return ResponseEntity.ok(response);
    }

    /** 导出全部数据（含复习统计和配置）为 JSON 文件 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportData() {
        try {
            byte[] data = dataExportImportService.exportAll();
            String filename = URLEncoder.encode("单词复习数据备份.json", StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(data);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "导出失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(null);
        }
    }

    /** 从 JSON 备份文件导入数据（含复习统计和配置） */
    @PostMapping("/import-data")
    public ResponseEntity<Map<String, Object>> importData(@RequestParam("file") MultipartFile file) {
        try {
            int count = dataExportImportService.importData(file);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("count", count);
            result.put("message", "成功导入 " + count + " 个单词及复习配置");
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "导入失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    /** 删除单个单词 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteWord(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        if (wordRepository.existsById(id)) {
            wordRepository.deleteById(id);
            result.put("success", true);
            result.put("message", "删除成功");
            return ResponseEntity.ok(result);
        } else {
            result.put("success", false);
            result.put("message", "单词不存在");
            return ResponseEntity.notFound().build();
        }
    }
}
