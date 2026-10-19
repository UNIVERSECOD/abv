package com.hic.controller;

import com.hic.dto.ApiResponse;
import com.hic.service.BackupOperationService;
import com.hic.service.AuditLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/backup/operations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('HEAD_OFFICE_HR')")
public class BackupOperationController {
    private final BackupOperationService operations;
    private final AuditLogService audit;

    @PostMapping
    public ResponseEntity<?> create() {
        var result = operations.create(null);
        audit.log("CREATE", "Backup", result.id(), "Tam sistem backupu istənildi");
        return ResponseEntity.accepted().body(ApiResponse.success(result));
    }

    @PostMapping(value = "/inspect", consumes = "application/zip")
    public ResponseEntity<?> upload(HttpServletRequest request) throws Exception {
        var result = operations.create(request.getInputStream());
        audit.log("CREATE", "BackupRestore", result.id(), "Bərpa faylı yoxlamaya göndərildi");
        return ResponseEntity.accepted().body(ApiResponse.success(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> status(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(operations.read(id)));
    }

    public record Confirmation(boolean confirmed) {}
    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable String id, @RequestBody Confirmation confirmation) {
        var result = operations.restore(id, confirmation.confirmed());
        audit.log("RESTORE", "Backup", id, "Avtomatik cari backup ilə bərpa təsdiqləndi");
        return ResponseEntity.accepted().body(ApiResponse.success(result));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable String id, @RequestParam(defaultValue = "false") boolean safety) {
        var file = operations.download(id, safety);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=attendra-" + (safety ? "before-restore-" : "backup-") + id + ".zip")
                .contentType(MediaType.parseMediaType("application/zip")).body(new FileSystemResource(file));
    }
}
