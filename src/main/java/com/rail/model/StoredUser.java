package com.rail.model;

/**
 * A row of the {@code users} table, including the password hash.
 *
 * <p>Exists so that credential material has exactly one path: out of
 * {@code UserDao}, into {@code AuthService}, and nowhere else. The service
 * converts this into a hash-free {@link User} for the session and lets this
 * object go out of scope, which is why no UI component is ever handed one.
 *
 * <p>The hash is {@code null} for the demo accounts seeded by
 * {@code sample_data.sql}: those ship with no password set at all, and
 * {@code AuthService} refuses them at login rather than treating a null hash
 * as "any password is fine".
 */
public record StoredUser(
        long userId,
        String username,
        String fullName,
        String email,
        String mobile,
        String passwordHash,
        Role role,
        boolean active) {

    public boolean hasPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }
}