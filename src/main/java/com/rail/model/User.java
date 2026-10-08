package com.rail.model;

/**
 * The identity of the person currently using the application.
 *
 * <p>This type deliberately carries <strong>no password hash, email or mobile
 * number</strong>. It is what the session holds and what the UI is allowed to
 * see, so the credential material needed to authenticate simply has nowhere to
 * travel. {@link StoredUser} exists for the narrow path that must read a hash.
 *
 * <p>Immutable, so it can be passed freely between the service layer and the
 * Swing components without defensive copying.
 */
public record User(long userId, String username, String fullName, Role role) {

    public User {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName must not be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
    }

    public boolean isAdmin() {
        return role.isAdmin();
    }

    /** The given name, for a friendlier greeting than the full name. */
    public String firstName() {
        int space = fullName.indexOf(' ');
        return space > 0 ? fullName.substring(0, space) : fullName;
    }

    /** One or two initials, for the sidebar avatar. Derived, never stored. */
    public String initials() {
        StringBuilder letters = new StringBuilder();
        for (String part : fullName.trim().split("\\s+")) {
            if (!part.isEmpty() && letters.length() < 2) {
                letters.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return letters.length() == 0 ? "?" : letters.toString();
    }
}