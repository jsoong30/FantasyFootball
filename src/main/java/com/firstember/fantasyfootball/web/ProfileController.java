package com.firstember.fantasyfootball.web;

import com.firstember.fantasyfootball.config.AppUserPrincipal;
import com.firstember.fantasyfootball.domain.User;
import com.firstember.fantasyfootball.repo.UserRepository;
import com.firstember.fantasyfootball.sleeper.FantasyLeagueService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ProfileController {

    private final UserRepository userRepository;
    private final FantasyLeagueService fantasyLeagueService;

    public ProfileController(UserRepository userRepository, FantasyLeagueService fantasyLeagueService) {
        this.userRepository = userRepository;
        this.fantasyLeagueService = fantasyLeagueService;
    }

    @GetMapping("/profile")
    public String profile(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("user", principal.getUser());
        model.addAttribute("message", null);
        return "auth/profile";
    }

    /**
     * Links (or changes) the Sleeper account tied to this app account. Resolves and caches the
     * Sleeper user id now rather than on every page view -- see User.sleeperUserId javadoc.
     */
    @PostMapping("/profile")
    public String updateSleeperUsername(@AuthenticationPrincipal AppUserPrincipal principal,
                                        @RequestParam String sleeperUsername,
                                        Model model) {
        User user = userRepository.findById(principal.getUser().getId()).orElseThrow();
        String trimmed = sleeperUsername.trim();

        if (trimmed.isEmpty()) {
            user.setSleeperUsername(null);
            user.setSleeperUserId(null);
            userRepository.save(user);
            refreshSessionPrincipal(user);
            model.addAttribute("user", user);
            model.addAttribute("message", "Sleeper account unlinked.");
            return "auth/profile";
        }

        String resolvedId = fantasyLeagueService.resolveSleeperUserId(trimmed);
        if (resolvedId == null) {
            model.addAttribute("user", user);
            model.addAttribute("message", "Couldn't find a Sleeper user named \"" + trimmed + "\" -- not saved.");
            return "auth/profile";
        }

        user.setSleeperUsername(trimmed);
        user.setSleeperUserId(resolvedId);
        userRepository.save(user);
        refreshSessionPrincipal(user);
        model.addAttribute("user", user);
        model.addAttribute("message", "Linked to Sleeper account \"" + trimmed + "\".");
        return "auth/profile";
    }

    /**
     * Spring Security caches the principal object in the session at login time -- without this,
     * a profile change (like linking a Sleeper account) wouldn't take effect anywhere else in the
     * app (e.g. the "YOU" tag on /league) until the next login. Rebuilds the session's
     * Authentication with the freshly saved User so the change is live immediately.
     */
    private void refreshSessionPrincipal(User updated) {
        var newAuth = new UsernamePasswordAuthenticationToken(
                new AppUserPrincipal(updated), null, new AppUserPrincipal(updated).getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(newAuth);
    }
}
