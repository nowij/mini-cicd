package com.cicd.service;

import com.cicd.model.Project;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GitHubService {

    private final RestClient restClient = RestClient.create();

    /**
     * GitHub API로 저장소의 브랜치 목록을 반환합니다.
     * 실패 시 프로젝트 기본 브랜치 하나만 담은 리스트를 반환합니다.
     */
    public List<String> getBranches(Project project) {
        try {
            String[] ownerRepo = parseOwnerRepo(project.getRepoUrl());
            String owner = ownerRepo[0];
            String repo  = ownerRepo[1];

            // GitHub API는 기본 30개, per_page=100으로 최대 100개 조회
            String url = "https://api.github.com/repos/" + owner + "/" + repo + "/branches?per_page=100";

            List<Map<String, Object>> response = restClient.get()
                    .uri(url)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github.v3+json")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + project.getGithubToken())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});

            if (response == null || response.isEmpty()) {
                return fallback(project);
            }

            List<String> branches = response.stream()
                    .map(b -> (String) b.get("name"))
                    .toList();

            // 기본 브랜치를 맨 앞으로 정렬
            String defaultBranch = project.getBranch() != null ? project.getBranch() : "main";
            List<String> sorted = new ArrayList<>();
            if (branches.contains(defaultBranch)) {
                sorted.add(defaultBranch);
                branches.stream().filter(b -> !b.equals(defaultBranch)).forEach(sorted::add);
            } else {
                sorted.addAll(branches);
            }
            return sorted;

        } catch (Exception e) {
            log.warn("GitHub 브랜치 목록 조회 실패 (project={}): {}", project.getId(), e.getMessage());
            return fallback(project);
        }
    }

    /**
     * repoUrl에서 owner, repo 이름 추출
     * https://github.com/owner/repo.git  →  ["owner", "repo"]
     */
    private String[] parseOwnerRepo(String repoUrl) {
        String url = repoUrl.replaceAll("\\.git$", "");
        int idx = url.indexOf("github.com/");
        if (idx == -1) {
            throw new IllegalArgumentException("GitHub URL이 아닙니다: " + repoUrl);
        }
        String[] parts = url.substring(idx + "github.com/".length()).split("/");
        if (parts.length < 2) {
            throw new IllegalArgumentException("URL에서 owner/repo를 파싱할 수 없습니다: " + repoUrl);
        }
        return new String[]{parts[0], parts[1]};
    }

    private List<String> fallback(Project project) {
        String branch = project.getBranch() != null ? project.getBranch() : "main";
        return List.of(branch);
    }
}
