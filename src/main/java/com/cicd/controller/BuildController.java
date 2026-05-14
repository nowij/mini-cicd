package com.cicd.controller;

import com.cicd.model.Build;
import com.cicd.repository.BuildRepository;
import com.cicd.service.BuildService;
import com.cicd.service.LogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class BuildController {

    private final BuildService buildService;
    private final BuildRepository buildRepository;
    private final LogService logService;

    /**
     * 빌드 트리거: 빌드 레코드 생성 후 로그 페이지로 리다이렉트
     */
    @PostMapping("/projects/{projectId}/deploy")
    public String triggerBuild(@PathVariable Long projectId,
                               @RequestParam(required = false) String branch,
                               RedirectAttributes redirectAttributes) {
        try {
            Build build = buildService.createBuild(projectId, branch);
            buildService.runBuild(build.getId());
            return "redirect:/builds/" + build.getId() + "/logs";
        } catch (IllegalStateException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/projects/" + projectId;
        }
    }

    /**
     * 실시간 빌드 로그 페이지
     */
    @GetMapping("/builds/{buildId}/logs")
    public String buildLogs(@PathVariable Long buildId, Model model) {
        Build build = buildRepository.findById(buildId)
                .orElseThrow(() -> new IllegalArgumentException("빌드를 찾을 수 없습니다: " + buildId));
        model.addAttribute("build", build);
        model.addAttribute("project", build.getProject());
        return "build-logs";
    }

    /**
     * SSE 로그 스트림 엔드포인트
     */
    @GetMapping(value = "/builds/{buildId}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter streamLogs(@PathVariable Long buildId) {
        return logService.subscribe(buildId);
    }

    /**
     * 완료된 빌드 로그 JSON 반환 (페이지 새로고침용)
     */
    @GetMapping(value = "/api/builds/{buildId}/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getBuildLogs(@PathVariable Long buildId) {
        Build build = buildRepository.findById(buildId)
                .orElseThrow(() -> new IllegalArgumentException("빌드를 찾을 수 없습니다: " + buildId));
        return ResponseEntity.ok(Map.of(
                "status", build.getStatus().name(),
                "lines", logService.readLogLines(buildId)
        ));
    }
}
