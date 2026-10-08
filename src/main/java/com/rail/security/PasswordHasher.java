package com.rail.security;

import org.mindrot.jbcrypt.BCrypt;

/**
 * Password hashing, in one place.
 *
 * <p>Plaintext passwords are never stored, logged, compared or kept in a field.
 * BCrypt is used because it is deliberately slow and salted per password, which
 * is the point: it makes offline cracking of a stolen {@code users} table
 * expensive. A fast hash would be the wrong tool here even though it would
 * "work".
 *
 * <p>jBCrypt is used rather than Spring Security, which would bring a web
 * framework, a DI container and many transitive jars to obtain one algorithm.
 *
 * <p>Note that BCrypt only considers the first 72 bytes of a password. That is a
 * property of the algorithm, not a bug here, and is harmless for this
 * application: {@code AuthService} caps passwords well below that.
 */
public final class PasswordHasher {

    /**
     * Cost factor: 2^12 = 4096 rounds. Deliberately not higher - this is a
     * single-user desktop application where a 4x slower login would be felt as
     * an unresponsive UI.
     */
    private static final int WORK_FACTOR = 12;

    /**
     * A hash of a value nobody knows, used to spend the same CPU time when an
     * account does not exist as when it does. See {@link #spendTimeForMissingUser}.
     */
    private static final String DUMMY_HASH = BCrypt.hashpw("no-such-account", BCrypt.gensalt(WORK_FACTOR));

    private PasswordHasher() {
    }

    /** Hashes a plaintext password for storage. Never log the result either. */
    public static String hash(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(WORK_FACTOR));
    }

    /**
     * Checks a plaintext password against a stored hash.
     *
     * <p>Returns {@code false} rather than throwing for a null, blank or
     * malformed hash, so a corrupt row denies access instead of crashing the
     * login screen.
     */
    public static boolean matches(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null || storedHash.isBlank()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPassword, storedHash);
        } catch (IllegalArgumentException malformed) {
            // Thrown when the stored value is not a valid BCrypt hash.
            return false;
        }
    }

    /**
     * Burns roughly the same amount of time as a real verification.
     *
     * <p>Without this, "no such user" would return noticeably faster than
     * "wrong password", which turns the login form into an account-existence
     * oracle even though both report the same message.
     */
    public static void spendTimeForMissingUser(String plainPassword) {
        matches(plainPassword == null ? "" : plainPassword, DUMMY_HASH);
    }
}