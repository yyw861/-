package com.sportshop.backup;

import com.sportshop.backup.BackupModels.BackupView;
import com.sportshop.backup.BackupModels.RestoreCommand;
import com.sportshop.backup.BackupModels.RestorePreview;
import com.sportshop.backup.BackupModels.RestoreResult;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
    private final BackupService service;
    public BackupController(BackupService service) { this.service = service; }

    @GetMapping public List<BackupView> list() { return service.list(); }

    @PostMapping
    public ResponseEntity<BackupView> create() {
        BackupView created = service.createBackup();
        return ResponseEntity.created(URI.create("/api/backups/" + created.id())).body(created);
    }

    @PostMapping("/{id}/restore-preview")
    public RestorePreview preview(@PathVariable UUID id) { return service.inspect(id); }

    @PostMapping("/{id}/restore")
    public ResponseEntity<RestoreResult> restore(@PathVariable UUID id, @RequestBody RestoreCommand command) {
        return ResponseEntity.ok().location(URI.create("/api/backups/" + id)).body(service.restore(id, command.confirmationText()));
    }

    @ExceptionHandler(BackupService.BackupValidationException.class)
    ResponseEntity<ProblemDetail> validation(BackupService.BackupValidationException exception) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage()));
    }

    @ExceptionHandler(BackupService.BackupException.class)
    ResponseEntity<ProblemDetail> failure(BackupService.BackupException exception) {
        return ResponseEntity.internalServerError().body(ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage()));
    }
}
