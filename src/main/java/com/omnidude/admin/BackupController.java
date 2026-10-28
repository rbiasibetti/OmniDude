package com.omnidude.admin;

import com.omnidude.admin.BackupService.BackupInfo;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Backups do banco. Só ADMIN (regra em SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class BackupController {

    private final BackupService service;

    public BackupController(BackupService service) {
        this.service = service;
    }

    @PostMapping("/backup")
    @ResponseStatus(HttpStatus.CREATED)
    public BackupInfo create() {
        return service.create();
    }

    @GetMapping("/backups")
    public List<BackupInfo> list() {
        return service.list();
    }

    @GetMapping("/backups/{name}")
    public ResponseEntity<Resource> download(@PathVariable String name) {
        Path f = service.file(name);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.getFileName().toString()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new FileSystemResource(f));
    }

    @DeleteMapping("/backups/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        service.delete(name);
    }
}
