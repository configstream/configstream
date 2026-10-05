package io.github.configstream.admin.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Reads the signed-in person's Spring Security authorities. Only loaded when Spring Security is present. */
final class SpringAuthorities {

    private SpringAuthorities() {
    }

    /** Whether the current person has the authority {@code group} or {@code ROLE_<group>}, ignoring case. */
    static boolean has(String group) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && (name.equalsIgnoreCase(group) || name.equalsIgnoreCase("ROLE_" + group))) {
                return true;
            }
        }
        return false;
    }
}
