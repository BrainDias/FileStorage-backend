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
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/files")
public class FileController {
    private final Map<String, FileEntry> fileStorage = new ConcurrentHashMap<>();
    //отображает id файла на id одноразовой ссылки на его скачивание
    private final Map<String, String> downloadLinks = new ConcurrentHashMap<>();
    private final Path uploadDir = Files.createTempDirectory("file-storage-");

    public FileController() throws IOException {
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws IOException {
        String fileId = UUID.randomUUID().toString();
        Path target = uploadDir.resolve(fileId + "_" + file.getOriginalFilename());
        file.transferTo(target);

        fileStorage.put(fileId, new FileEntry(
                target,
                //ссылка на скачивание будет действовать 10 минут
                Instant.now().plus(Duration.ofMinutes(10)),
                new AtomicReference<>(Instant.now()),
                new AtomicInteger(0)
        ));

        String downloadId = UUID.randomUUID().toString();
        downloadLinks.put(downloadId, fileId);

        return "/files/download/" + downloadId;
    }

    //возвращает новую одноразовую ссылку на скачивание уже загруженного файла с известным именем
    @GetMapping("/link/{filename}")
    public ResponseEntity<String> generateDownloadLink(@PathVariable String filename) {
        Optional<Map.Entry<String, FileEntry>> match = fileStorage.entrySet().stream()
                .filter(e -> e.getValue().path().getFileName().toString().endsWith("_" + filename))
                .findFirst();

        if (match.isEmpty() || Instant.now().isAfter(match.get().getValue().expiry())) {
            return ResponseEntity.notFound().build();
        }

        String fileId = match.get().getKey();
        String downloadId = UUID.randomUUID().toString();
        downloadLinks.put(downloadId, fileId);

        return ResponseEntity.ok("/files/download/" + downloadId);
    }



    @GetMapping("/download/{downloadId}")
    public ResponseEntity<Resource> download(@PathVariable String downloadId) throws IOException {
        //находим id файла по одноразовому id скачивания и удаляем этот id скачивания
        String fileId = downloadLinks.remove(downloadId);
        if (fileId == null) return ResponseEntity.notFound().build();


        FileEntry entry = fileStorage.get(fileId);
        if (entry == null || Instant.now().isAfter(entry.expiry())) {
            //время действия ссылки на файл прошло, удаляем ее
            fileStorage.remove(fileId);
            return ResponseEntity.notFound().build();
        }
        entry.lastAccess().set(Instant.now());
        entry.downloadCount().incrementAndGet();

        Path path = entry.path();
        Resource resource = new UrlResource(path.toUri());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + path.getFileName().toString().split("_", 2)[1])
                .body(resource);
    }

    @GetMapping("/stats")
    public List<Map<String, Object>> stats() {
        return fileStorage.entrySet().stream().map(entry -> {
            FileEntry fe = entry.getValue();
            Map<String, Object> map = new HashMap<>();
            map.put("id", entry.getKey());
            map.put("filename", fe.path().getFileName().toString().split("_", 2)[1]);
            map.put("expiresAt", fe.expiry().toString());
            map.put("lastDownload", fe.lastAccess().get().toString());
            map.put("downloads", fe.downloadCount().get());
            return map;
        }).toList();
    }

    @Scheduled(timeUnit = TimeUnit.DAYS, fixedDelay = 30)
    public void cleanupUnused() {
        Instant now = Instant.now();
        fileStorage.entrySet().removeIf(entry -> {
            FileEntry fe = entry.getValue();
            boolean stale = fe.lastAccess().get().isBefore(now.minus(Duration.ofDays(30)));

            if ( stale) {
                try {
                    Files.deleteIfExists(fe.path());
                } catch (IOException ignored) {}
                return true;
            }
            return false;
        });
    }

    private record FileEntry(
            Path path,
            Instant expiry,
            AtomicReference<Instant> lastAccess,
            AtomicInteger downloadCount
    ) {}
}
