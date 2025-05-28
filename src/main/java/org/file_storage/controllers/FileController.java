package org.file_storage.controllers;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/files")
public class FileController {
    private final Map<String, FileEntry> fileStorage = new ConcurrentHashMap<>();
    private final Path uploadDir = Paths.get("tmp");

    public FileController() throws IOException {
        Files.createDirectories(uploadDir);
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws IOException {
        String id = UUID.randomUUID().toString();
        Path target = uploadDir.resolve(id + "_" + file.getOriginalFilename());
        file.transferTo(target);

        fileStorage.put(id, new FileEntry(target, Instant.now().plus(Duration.ofMinutes(10))));
        return "/files/download/" + id;
    }

    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> download(@PathVariable String id) throws IOException {
        FileEntry entry = fileStorage.get(id);
        if (entry == null || Instant.now().isAfter(entry.expiry())) {
            return ResponseEntity.notFound().build();
        }

        Path path = entry.path();
        Resource resource = new UrlResource(path.toUri());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + path.getFileName().toString().split("_", 2)[1])
                .body(resource);
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanup() {
        Instant now = Instant.now();
        fileStorage.entrySet().removeIf(entry -> {
            if (now.isAfter(entry.getValue().expiry())) {
                try {
                    Files.deleteIfExists(entry.getValue().path());
                } catch (IOException ignored) {}
                return true;
            }
            return false;
        });
    }

    private record FileEntry(Path path, Instant expiry) {}
}
