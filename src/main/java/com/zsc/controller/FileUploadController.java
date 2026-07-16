package com.zsc.controller;



import com.zsc.indexing.IndexingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/assistant")
@RequiredArgsConstructor
public class FileUploadController {

    private final IndexingService indexingService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadFiles(
            @RequestParam("projectId") String projectId,
            @RequestParam("files") List<MultipartFile> files) {

        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No files provided"));
        }

        indexingService.indexFiles(files, projectId);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Indexed " + files.size() + " files"));
    }

    /**
     * 查询已存在的文件（断点续传：前端上传前调用，跳过已入库的文件）
     * GET /assistant/upload/check-exist?projectId=xxx&fileNames=a,b,c
     */
    @GetMapping("/upload/check-exist")
    public ResponseEntity<?> checkExistingFiles(
            @RequestParam("projectId") String projectId,
            @RequestParam("fileNames") String fileNames) {
        if (fileNames == null || fileNames.isBlank()) {
            return ResponseEntity.ok(Map.of("existing", List.of()));
        }
        List<String> nameList = Arrays.asList(fileNames.split(","));
        List<String> existing = indexingService.findExistingFiles(projectId, nameList);
        return ResponseEntity.ok(Map.of("existing", existing));
    }


    @DeleteMapping("/deleteVectors")
    public ResponseEntity<?> deleteVectors(@RequestParam String projectId) {
        indexingService.deleteProjectVectors(projectId);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Deleted vectors for project " + projectId));
    }
}