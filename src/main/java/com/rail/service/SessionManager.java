package com.rail.service;

import com.rail.model.Role;
import com.rail.model.User;

/**
 * Holds the identity of the person currently using the application.
 *
 * <p>A single desktop application has exactly one user at a time, so this is a
 * process-wide holder rather than an instance passed around. It is the reason
 * the dashboard is reachable only after a successful login: every screen asks
 * this object who the user is, and every service action is checked against it.
 *
 * <p>The held {@link User} is immutable and hash-free, so nothing sensitive is
 * parked here between screens.
 *
 * <p>All mutating methods are synchronised and Swing is single-threaded, so in
 * practice there is no contention; the synchronisation is there so the
 * invariant "at most one session" holds even if a background worker starts
 * calling in later.
 */
public final class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();

    private User currentUser;

    private SessionManager() {
    }

    public static SessionManager get() {
        return INSTANCE;
    }

    /** Called by {@code AuthService} only, after a password has been verified. */
    public synchronized void beginSession(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        this.currentUser = user;
    }

    /** Clears the session. The dashboard is unreachable again afterwards. */
    public synchronized void endSession() {
        this.currentUser = null;
    }

    /** The signed-in user, or {@code null} when nobody is signed in. */
    public synchronized User currentUser() {
        return currentUser;
    }

    public synchronized boolean isSignedIn() {
        return currentUser != null;
    }

    public synchronized boolean isAdmin() {
        return currentUser != null && currentUser.isAdmin();
    }

    /**
     * The signed-in user, or a failure if nobody is.
     *
     * <p>Every screen that shows personal data calls this rather than reading
     * {@link #currentUser()} and null-checking, so "no session" cannot be
     * rendered as an empty page.
     */
    public User requireUser() throws AccessDeniedException {
        User user = currentUser();
        if (user == null) {
            throw new AccessDeniedException("You need to sign in to do that.");
        }
        return user;
    }

    /** Enforces the administrator role in the service layer, not in the UI. */
    public User requireAdmin() throws AccessDeniedException {
        User user = requireUser();
        if (!user.isAdmin()) {
            throw new AccessDeniedException("That action is restricted to administrators.");
        }
        return user;
    }

    /**
     * Enforces that {@code claimedUserId} is the signed-in user, or an
     * administrator acting on someone's behalf.
     *
     * <p>This is the check that stops one traveller reading another's booking
     * even if a future caller passes the wrong id.
     */
    public void requireSelfOrAdmin(long claimedUserId) throws AccessDeniedException {
        User user = requireUser();
        if (user.userId() != claimedUserId && !user.isAdmin()) {
            throw new AccessDeniedException("You can only access your own records.");
        }
    }

    /** Convenience for building a role-aware label. */
    public static String describe(Role role) {
        return role.isAdmin() ? "Administrator" : "Passenger";
    }
}