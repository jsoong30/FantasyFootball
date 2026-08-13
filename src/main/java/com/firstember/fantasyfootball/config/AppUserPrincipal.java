package com.firstember.fantasyfootball.config;

import com.firstember.fantasyfootball.domain.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Wraps our {@link User} entity as Spring Security's principal, so controllers can pull the
 * whole domain object (sleeperUserId, etc.) straight off {@code @AuthenticationPrincipal}
 * instead of re-querying by username on every request.
 */
public class AppUserPrincipal implements UserDetails {

    private final User user;

    public AppUserPrincipal(User user) { this.user = user; }

    public User getUser() { return user; }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        // Every user gets ROLE_USER; ROLE_ADMIN is additive for admins, not a replacement --
        // so an admin still passes any check that just requires being a regular user.
        return user.getRole() == com.firstember.fantasyfootball.domain.Role.ADMIN
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override public String getPassword() { return user.getPasswordHash(); }
    @Override public String getUsername() { return user.getEmail(); }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return user.isEnabled(); }
}
