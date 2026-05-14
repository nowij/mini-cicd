package com.cicd.controller;

import com.cicd.service.SettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsService settingsService;

    @GetMapping
    public String settings(Model model) {
        model.addAttribute("settings", settingsService.getSettings());
        return "settings";
    }

    @PostMapping
    public String save(
            @RequestParam int logRetentionDays,
            @RequestParam int cleanupHour,
            @RequestParam int cleanupMinute,
            RedirectAttributes redirectAttributes) {
        settingsService.save(logRetentionDays, cleanupHour, cleanupMinute);
        redirectAttributes.addFlashAttribute("successMessage", "설정이 저장되었습니다.");
        return "redirect:/settings";
    }
}
