package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.sleeper.SleeperService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin")
public class AdminController {

    private final SleeperService sleeperService;

    public AdminController(SleeperService sleeperService) {
        this.sleeperService = sleeperService;
    }

    /** Show the admin sync page. */
    @GetMapping("/sync")
    public String syncPage(Model model) {
        model.addAttribute("message", null);
        return "admin/sync";
    }

    /**
     * Trigger a Sleeper data sync for the given season year.
     * This can take 1–3 minutes (18 API calls + DB upserts).
     */
    @PostMapping("/sync")
    public String triggerSync(@RequestParam(defaultValue = "2024") int year, Model model) {
        try {
            String result = sleeperService.syncSeason(year);
            model.addAttribute("message", result);
            model.addAttribute("success", true);
        } catch (Exception e) {
            model.addAttribute("message", "Sync failed: " + e.getMessage());
            model.addAttribute("success", false);
        }
        return "admin/sync";
    }
}
