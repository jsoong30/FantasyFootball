package com.firstember.fantasyfootball.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("appName", "FantasyFootball");
        model.addAttribute("welcomeMsg", "Welcome to your Fantasy Football Tracker!");
        return "index";
    }
}
