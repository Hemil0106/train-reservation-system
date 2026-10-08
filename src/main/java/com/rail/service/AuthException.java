package com.rail.service;

/**
 * A failure the person in front of the screen can understand and act on.
 *
 * <p>Two kinds, told apart by {@link #field()}:
 *
 * <ul>
 *   <li>{@code field != null} - a registration problem. The message names the
 *       offending field so the form can show it in context.</li>
 *   <li>{@code field == null} - an authentication failure. The message is the
 *       same generic sentence for a wrong password, an unknown account, a
 *       disabled account and an account with no password set, so the login
 *       screen cannot be used to discover which accounts exist.</li>
 * </ul>
 *
 * <p>Checked, so a caller cannot forget to handle it.
 */
public class AuthException extends Exception {

    /** The one sentence shown for every failed login attempt. Never vary it per cause. */
    public static final String GENERIC_LOGIN_FAILURE = "Incorrect username or password.";

    private static final long serialVersionUID = 1L;

    private final String field;

    public AuthException(String message) {
        this(null, message);
    }

    public AuthException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The offending field name, or {@code null} for a generic authentication failure. */
    public String field() {
        return field;
    }

    public boolean isValidationFailure() {
        return field != null;
    }

    /** The single generic authentication failure, with no cause-specific wording. */
    public static AuthException loginFailed() {
        return new AuthException(GENERIC_LOGIN_FAILURE);
    }
}