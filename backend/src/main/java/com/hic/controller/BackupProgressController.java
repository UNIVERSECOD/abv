package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.service.BackupOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/backup-progress")
@RequiredArgsConstructor
public class BackupProgressController {
    private final BackupOperationService operations;

    @GetMapping("/{id}")
    public ResponseEntity<?> progress(@PathVariable String id, @RequestHeader("X-Backup-Progress") String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(operations.progress(id, token)));
    }
}
