package com.cicd.controller;

import com.cicd.model.Project;
import com.cicd.repository.ProjectRepository;
import com.cicd.service.GitHubService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GitHubController {

    private final ProjectRepository projectRepository;
    private final GitHubService gitHubService;

    /**
     * 프로젝트의 GitHub 브랜치 목록 반환
     * GET /api/projects/{id}/branches
     */
    @GetMapping("/projects/{id}/branches")
    public ResponseEntity<List<String>> getBranches(@PathVariable Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + id));
        return ResponseEntity.ok(gitHubService.getBranches(project));
    }
}
