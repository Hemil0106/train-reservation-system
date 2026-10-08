package com.rail.model;

import java.util.Locale;

/**
 * The account roles the application distinguishes.
 *
 * <p>The Milestone 3 brief calls the non-admin role "USER". The database
 * column is {@code ENUM('CUSTOMER','ADMIN')}, chosen in Milestone 2, and this
 * enum deliberately reuses that stored spelling rather than inventing a second
 * vocabulary and a translation layer between them. {@link #CUSTOMER} <em>is</em>
 * the ordinary user role.
 *
 * <p>Roles are read from and written to the database verbatim. An unknown
 * value cannot reach this enum: {@link #fromDatabase} fails loudly instead of
 * silently downgrading an unknown role to the least privileged one.
 */
public enum Role {

    /** An ordinary traveller. May only ever see and act on their own data. */
    CUSTOMER,

    /** An administrator. Administrative actions are still gated in the service layer. */
    ADMIN;

    /** The value stored in {@code users.role}. */
    public String databaseValue() {
        return name();
    }

    /**
     * Maps a stored value to a role.
     *
     * @throws IllegalArgumentException if the database holds something this
     *                                  build does not understand, rather than
     *                                  quietly treating it as a normal user
     */
    public static Role fromDatabase(String stored) {
        if (stored == null) {
            throw new IllegalArgumentException("users.role was NULL");
        }
        try {
            return valueOf(stored.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown role in database: " + stored, exception);
        }
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }
}