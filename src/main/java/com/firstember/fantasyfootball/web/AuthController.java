package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.domain.User;
import com.firstember.fantasyfootball.repo.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "auth/login";
    }

    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("error", null);
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@RequestParam String email,
                           @RequestParam String password,
                           @RequestParam String confirmPassword,
                           Model model) {
        String normalizedEmail = email.trim().toLowerCase();

        if (password.length() < 8) {
            model.addAttribute("error", "Password must be at least 8 characters.");
            return "auth/register";
        }
        if (!password.equals(confirmPassword)) {
            model.addAttribute("error", "Passwords don't match.");
            return "auth/register";
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            model.addAttribute("error", "An account with that email already exists.");
            return "auth/register";
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(password));
        userRepository.save(user);

        return "redirect:/login?registered";
    }
}
