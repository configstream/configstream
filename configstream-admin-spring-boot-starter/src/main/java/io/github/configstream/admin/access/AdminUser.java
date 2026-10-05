package io.github.configstream.admin.access;

/** The person signed in to the admin server, as the host application's login identified them. */
public interface AdminUser {

    String name();

    /**
     * Whether the login places this person in {@code group}: a role {@code group} in the servlet sense
     * ({@code request.isUserInRole}), or, with Spring Security, an authority {@code group} or {@code ROLE_<group>}.
     * Map your identity provider's groups to such authorities in the host application.
     */
    boolean isInGroup(String group);
}
