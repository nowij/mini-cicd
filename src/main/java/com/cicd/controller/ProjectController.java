package com.cicd.controller;

import com.cicd.model.Project;
import com.cicd.model.enums.DeployOs;
import com.cicd.model.enums.ProjectType;
import com.cicd.repository.BuildRepository;
import com.cicd.repository.ProjectRepository;
import com.cicd.service.ScriptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final BuildRepository buildRepository;
    private final ScriptService scriptService;

    @GetMapping("/")
    public String dashboard(Model model) {
        List<Project> projects = projectRepository.findAll();
        var lastBuilds = new java.util.HashMap<Long, com.cicd.model.Build>();
        for (Project p : projects) {
            buildRepository.findTopByProjectOrderByStartedAtDesc(p)
                    .ifPresent(b -> lastBuilds.put(p.getId(), b));
        }
        model.addAttribute("projects", projects);
        model.addAttribute("lastBuilds", lastBuilds);
        return "index";
    }

    @GetMapping("/projects/new")
    public String newProjectForm(Model model) {
        model.addAttribute("project", new Project());
        model.addAttribute("projectTypes", ProjectType.values());
        model.addAttribute("deployOsTypes", DeployOs.values());
        model.addAttribute("isNew", true);
        return "project-form";
    }

    @PostMapping("/projects")
    public String createProject(@Valid @ModelAttribute Project project,
                                BindingResult result,
                                @RequestParam(required = false) MultipartFile scriptFile,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            model.addAttribute("projectTypes", ProjectType.values());
            model.addAttribute("deployOsTypes", DeployOs.values());
            model.addAttribute("isNew", true);
            return "project-form";
        }
        projectRepository.save(project);

        handleScriptUpload(project, scriptFile, redirectAttributes);

        redirectAttributes.addFlashAttribute("successMessage", "프로젝트가 생성되었습니다.");
        return "redirect:/projects/" + project.getId();
    }

    @GetMapping("/projects/{id}")
    public String projectDetail(@PathVariable Long id, Model model) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + id));
        List<com.cicd.model.Build> builds = buildRepository.findByProjectOrderByStartedAtDesc(project);
        model.addAttribute("project", project);
        model.addAttribute("builds", builds);
        return "project-detail";
    }

    @GetMapping("/projects/{id}/edit")
    public String editProjectForm(@PathVariable Long id, Model model) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + id));
        model.addAttribute("project", project);
        model.addAttribute("projectTypes", ProjectType.values());
        model.addAttribute("deployOsTypes", DeployOs.values());
        model.addAttribute("isNew", false);
        return "project-form";
    }

    @PostMapping("/projects/{id}/edit")
    public String updateProject(@PathVariable Long id,
                                @Valid @ModelAttribute Project updatedProject,
                                BindingResult result,
                                @RequestParam(required = false) MultipartFile scriptFile,
                                Model model,
                                RedirectAttributes redirectAttributes) {
        if (result.hasErrors()) {
            model.addAttribute("projectTypes", ProjectType.values());
            model.addAttribute("deployOsTypes", DeployOs.values());
            model.addAttribute("isNew", false);
            return "project-form";
        }
        Project existing = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + id));

        existing.setName(updatedProject.getName());
        existing.setDescription(updatedProject.getDescription());
        existing.setRepoUrl(updatedProject.getRepoUrl());
        existing.setBranch(updatedProject.getBranch());
        existing.setGithubToken(updatedProject.getGithubToken());
        existing.setProjectType(updatedProject.getProjectType());
        existing.setBuildCommand(updatedProject.getBuildCommand());
        existing.setBuildSubDir(updatedProject.getBuildSubDir());
        existing.setDeployHost(updatedProject.getDeployHost());
        existing.setDeployPort(updatedProject.getDeployPort());
        existing.setDeployUser(updatedProject.getDeployUser());
        existing.setDeployPassword(updatedProject.getDeployPassword());
        existing.setDeployKeyPath(updatedProject.getDeployKeyPath());
        existing.setDeployOs(updatedProject.getDeployOs());
        existing.setDeployTargetDir(updatedProject.getDeployTargetDir());
        existing.setAppName(updatedProject.getAppName());
        existing.setStartCommand(updatedProject.getStartCommand());
        existing.setStopCommand(updatedProject.getStopCommand());

        projectRepository.save(existing);

        handleScriptUpload(existing, scriptFile, redirectAttributes);

        redirectAttributes.addFlashAttribute("successMessage", "프로젝트가 수정되었습니다.");
        return "redirect:/projects/" + id;
    }

    @PostMapping("/projects/{id}/delete")
    public String deleteProject(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        scriptService.delete(id);
        projectRepository.deleteById(id);
        redirectAttributes.addFlashAttribute("successMessage", "프로젝트가 삭제되었습니다.");
        return "redirect:/";
    }

    @PostMapping("/projects/{id}/script/delete")
    public String deleteScript(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("프로젝트를 찾을 수 없습니다: " + id));
        scriptService.delete(id);
        project.setScriptFileName(null);
        projectRepository.save(project);
        redirectAttributes.addFlashAttribute("successMessage", "스크립트 파일이 삭제되었습니다.");
        return "redirect:/projects/" + id + "/edit";
    }

    private void handleScriptUpload(Project project, MultipartFile scriptFile,
                                    RedirectAttributes redirectAttributes) {
        if (scriptFile == null || scriptFile.isEmpty()) return;
        try {
            String fileName = scriptService.save(project.getId(), scriptFile);
            project.setScriptFileName(fileName);
            projectRepository.save(project);
        } catch (Exception e) {
            log.warn("Script upload failed for project {}: {}", project.getId(), e.getMessage());
            redirectAttributes.addFlashAttribute("warnMessage", "스크립트 업로드 실패: " + e.getMessage());
        }
    }
}
