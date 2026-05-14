package com.cicd.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class ScriptService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".sh", ".bat");

    @Value("${cicd.scripts-dir:./workspace/scripts}")
    private String scriptsDir;

    /**
     * 프로젝트의 스크립트 파일을 저장합니다.
     * 기존 스크립트가 있으면 교체합니다.
     *
     * @return 저장된 파일명
     */
    public String save(Long projectId, MultipartFile file) throws IOException {
        String originalName = file.getOriginalFilename();
        validateExtension(originalName);

        Path dir = Paths.get(scriptsDir, projectId.toString());
        Files.createDirectories(dir);

        // 기존 스크립트 삭제
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }

        Path target = dir.resolve(originalName);
        file.transferTo(target);
        log.info("Script saved: {}", target);
        return originalName;
    }

    /**
     * 프로젝트의 스크립트 파일 경로를 반환합니다.
     */
    public Optional<Path> getScriptPath(Long projectId, String fileName) {
        if (fileName == null || fileName.isBlank()) return Optional.empty();
        Path path = Paths.get(scriptsDir, projectId.toString(), fileName);
        return Files.exists(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * 프로젝트의 스크립트 파일을 삭제합니다.
     */
    public void delete(Long projectId) {
        Path dir = Paths.get(scriptsDir, projectId.toString());
        try (var stream = Files.list(dir)) {
            stream.forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    private void validateExtension(String fileName) {
        if (fileName == null) throw new IllegalArgumentException("파일명이 없습니다.");
        String lower = fileName.toLowerCase();
        boolean valid = ALLOWED_EXTENSIONS.stream().anyMatch(lower::endsWith);
        if (!valid) {
            throw new IllegalArgumentException(".sh 또는 .bat 파일만 업로드 가능합니다: " + fileName);
        }
    }
}
