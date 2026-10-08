package com.rail;

import com.rail.db.ConnectionSource;
import com.rail.db.DatabaseConnection;
import com.rail.db.UserDao;
import com.rail.model.BookingSummary;
import com.rail.model.Role;
import com.rail.model.StoredUser;
import com.rail.model.User;
import com.rail.security.PasswordHasher;
import com.rail.service.AccessDeniedException;
import com.rail.service.AuthException;
import com.rail.service.AuthService;
import com.rail.service.BookingQueryService;
import com.rail.service.SessionManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Milestone 3 checks: registration, login, logout and access control.
 *
 * <p>Runs the whole scenario inside one transaction on a single connection and
 * then rolls it back, so it creates no accounts and needs no DELETE privilege.
 * Confirmed afterwards by re-reading the row counts.
 *
 * <p>Skips cleanly when no database is configured, so an unconfigured machine
 * still builds.
 */
public final class AuthSelfCheck {

    private static int checksRun;
    private static int failures;

    private AuthSelfCheck() {
    }

    public static void main(String[] args) {
        if (!DatabaseConnection.isConfigured()) {
            System.out.println("SKIP  no database configured, nothing was connected to.");
            System.out.println("See README.md section 'Database setup' for the full procedure.");
            return;
        }

        try (Connection real = DatabaseConnection.getConnection()) {
            long usersBefore = countUsers(real);

            // Every service call gets this one connection, so the whole
            // scenario is a single transaction. The proxy swallows close()
            // because each service closes what it borrows, and closing the
            // shared connection mid-scenario would break every later call.
            Connection shared = nonClosing(real);
            ConnectionSource source = () -> shared;

            real.setAutoCommit(false);
            try {
                runScenario(shared, source);
            } finally {
                real.rollback();
            }

            System.out.println();
            System.out.println("Cleanup");
            long usersAfter = countUsers(real);
            check("no test account survived the rollback (" + usersBefore + " before, "
                    + usersAfter + " after)", usersAfter == usersBefore);
        } catch (SQLException failure) {
            System.err.println();
            System.err.println("DATABASE CHECK FAILED: " + failure.getMessage());
            System.exit(1);
        }

        System.out.println();
        if (failures == 0) {
            System.out.println("All " + checksRun + " authentication checks passed.");
        } else {
            System.out.println(failures + " of " + checksRun + " authentication checks FAILED.");
            System.exit(1);
        }
    }

    /**
     * Wraps a connection so {@code close()} does nothing.
     *
     * <p>The services use try-with-resources, which is correct in production
     * because {@code DatabaseConnection} hands out a fresh connection each
     * time. Here they all share one, so a real close would pull the rug out
     * from under the next call. The genuine connection is closed by the
     * caller's own try-with-resources once the scenario has rolled back.
     */
    private static Connection nonClosing(Connection real) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(
                AuthSelfCheck.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException wrapper) {
                        throw wrapper.getCause();
                    }
                });
    }

    private static void runScenario(Connection connection, ConnectionSource source) throws SQLException {
        AuthService auth = new AuthService(source);
        BookingQueryService bookings = new BookingQueryService(source);
        SessionManager session = SessionManager.get();

        section("Registration");

        User alice = register(auth, "Asha Rane", "asha_check",
                "asha.check@example.invalid", "9876543210", "correct-horse-1");
        check("a new account is returned", alice != null && alice.userId() > 0);
        check("the new account has the CUSTOMER role", alice.role() == Role.CUSTOMER);
        check("registration never grants ADMIN",
                register(auth, "Bob Admin", "bob_check", "bob@example.invalid",
                        "9876543211", "correct-horse-2").role() != Role.ADMIN);
        check("registration does not start a session", !session.isSignedIn());

        StoredUser storedAlice = new UserDao(connection)
                .findForLogin("asha_check").orElseThrow();
        check("the row was written", storedAlice.userId() == alice.userId());
        check("the full name is stored", "Asha Rane".equals(storedAlice.fullName()));
        check("the mobile is normalised to 10 digits", "9876543210".equals(storedAlice.mobile()));

        section("Password storage");

        check("the stored password is not the plaintext",
                !"correct-horse-1".equals(storedAlice.passwordHash()));
        check("the stored password is a BCrypt hash",
                storedAlice.passwordHash().startsWith("$2a$"));
        check("the hash verifies the right password",
                PasswordHasher.matches("correct-horse-1", storedAlice.passwordHash()));
        check("the hash rejects a wrong password",
                !PasswordHasher.matches("wrong-horse-1", storedAlice.passwordHash()));
        check("two hashes of one password differ (salted)",
                !PasswordHasher.hash("correct-horse-1")
                        .equals(PasswordHasher.hash("correct-horse-1")));
        check("reading a profile never selects the hash",
                new UserDao(connection).findProfile(alice.userId())
                        .map(user -> !user.hasPassword()).orElse(false));

        section("Duplicate registration");

        AuthException duplicateName = registerExpectingFailure(auth,
                "Asha Rane", "asha_check", "other@example.invalid", "9876543212", "another-pass-1");
        check("a duplicate username is refused", duplicateName != null);
        check("the duplicate username names the username field",
                duplicateName != null && AuthService.FIELD_USERNAME.equals(duplicateName.field()));

        AuthException duplicateEmail = registerExpectingFailure(auth,
                "Someone Else", "someone_check", "asha.check@example.invalid", "9876543213", "another-pass-2");
        check("a duplicate email is refused", duplicateEmail != null);
        check("the duplicate email names the email field",
                duplicateEmail != null && AuthService.FIELD_EMAIL.equals(duplicateEmail.field()));

        section("Validation");

        check("a blank full name is refused", refused(auth, "", "u1", "", "9876543214", "abcdefgh"));
        check("a short username is refused", refused(auth, "Real Name", "ab", "", "9876543214", "abcdefgh"));
        check("a username with a space is refused",
                refused(auth, "Real Name", "bad name", "", "9876543214", "abcdefgh"));
        check("a malformed email is refused",
                refused(auth, "Real Name", "u2", "not-an-email", "9876543214", "abcdefgh"));
        check("a short mobile is refused", refused(auth, "Real Name", "u3", "", "12345", "abcdefgh"));
        check("a non-numeric mobile is refused", refused(auth, "Real Name", "u4", "", "abcdefghij", "abcdefgh"));
        check("a short password is refused", refused(auth, "Real Name", "u5", "", "9876543214", "short"));
        check("an empty password is refused", refused(auth, "Real Name", "u6", "", "9876543214", ""));
        check("a mobile with a +91 prefix is accepted",
                register(auth, "Prefix User", "prefix_check", "", "+91 98765 43210", "abcdefgh") != null);
        check("validation failures name a field",
                refusedWithField(auth, "", "u7", "", "9876543214", "abcdefgh"));

        section("Login");

        User signedIn = loginExpectingSuccess(auth, "asha_check", "correct-horse-1");
        check("the correct password signs in", signedIn != null);
        check("the session records the user", session.isSignedIn()
                && session.currentUser().userId() == alice.userId());
        check("the session user carries no hash", !String.valueOf(session.currentUser())
                .contains("$2a$"));
        check("an ordinary user is not an admin", !session.isAdmin());

        section("Login failures are indistinguishable");

        AuthException wrongPassword = loginExpectingFailure(auth, "asha_check", "not-the-password");
        AuthException noSuchUser = loginExpectingFailure(auth, "nobody_here", "not-the-password");
        AuthException emptyPassword = loginExpectingFailure(auth, "asha_check", "");

        check("a wrong password is refused", wrongPassword != null);
        check("an unknown username is refused", noSuchUser != null);
        check("an empty password is refused", emptyPassword != null);
        check("wrong password and unknown user give the identical message",
                wrongPassword != null && noSuchUser != null
                        && wrongPassword.getMessage().equals(noSuchUser.getMessage()));
        check("the message is the generic one",
                wrongPassword != null
                        && AuthException.GENERIC_LOGIN_FAILURE.equals(wrongPassword.getMessage()));
        check("an empty password gives the same generic message",
                emptyPassword != null
                        && AuthException.GENERIC_LOGIN_FAILURE.equals(emptyPassword.getMessage()));

        // The seeded demo accounts carry a NULL hash: no password set.
        Optional<StoredUser> seeded = new UserDao(connection).findForLogin("demo_admin");
        if (seeded.isPresent()) {
            check("the seeded admin really has no password set",
                    !seeded.get().hasPassword());
            check("an account with no password cannot be signed into",
                    loginExpectingFailure(auth, "demo_admin", "") != null
                            && loginExpectingFailure(auth, "demo_admin", "anything") != null);
            check("a NULL hash is never treated as a match",
                    !PasswordHasher.matches("anything", null));
        } else {
            check("the seeded demo admin is present", false);
        }

        section("Permissions are enforced in the service layer");

        // The login section above left a session open. Drop it first, otherwise
        // the signed-out checks would pass for the wrong reason.
        auth.logout();

        check("a signed-out caller cannot read bookings", denied(() -> bookings.myBookingCount()));
        check("a signed-out caller cannot open a dashboard", denied(session::requireUser));
        check("a signed-out caller is not an administrator", !session.isAdmin());

        loginExpectingSuccess(auth, "asha_check", "correct-horse-1");

        check("a signed-in user can read their own bookings", allowed(() -> bookings.myBookingCount()));
        check("an ordinary user is refused the admin count",
                denied(() -> auth.adminAllBookingsCount()));
        check("an ordinary user is refused requireAdmin", denied(session::requireAdmin));
        check("an ordinary user cannot read another account's bookings",
                denied(() -> bookings.bookingsFor(alice.userId() + 999, 10)));
        check("an ordinary user may read their own by id",
                allowed(() -> bookings.bookingsFor(alice.userId(), 10)));

        section("Own-data isolation");

        check("a new account has no bookings", safeCount(bookings) == 0);
        check("a new account has no active bookings", safeActiveCount(bookings) == 0);
        try {
            List<BookingSummary> history = bookings.myBookings();
            check("a new account's booking list is empty, not invented", history.isEmpty());
            check("an unknown PNR is not found", bookings.myBookingByPnr(999999999L).isEmpty());
        } catch (AccessDeniedException denied) {
            check("a signed-in user can read their own bookings", false);
        }

        section("Administrator role");

        long adminId = insertAdmin(connection, "admin_check");
        check("the fixture administrator was created", adminId > 0);

        session.beginSession(new User(adminId, "admin_check", "Site Admin", Role.ADMIN));
        check("the session reports administrator", session.isAdmin());
        check("an administrator may call the admin count",
                allowed(() -> auth.adminAllBookingsCount()));
        check("an administrator may read another account's bookings",
                allowed(() -> bookings.bookingsFor(alice.userId(), 10)));

        section("Logout");

        auth.logout();
        check("logout clears the session", !session.isSignedIn());
        check("logout clears the current user", session.currentUser() == null);
        check("an administrator is no longer an administrator", !session.isAdmin());
        check("bookings are refused again after logout", denied(() -> bookings.myBookingCount()));
        check("the admin count is refused again after logout",
                denied(() -> auth.adminAllBookingsCount()));
        check("the dashboard is unreachable again after logout", denied(session::requireUser));
    }

    // ----- helpers -------------------------------------------------------

    private static long insertAdmin(Connection connection, String username) throws SQLException {
        return new UserDao(connection).insert(username, "Site Admin", "admin@example.invalid",
                "9000000000", PasswordHasher.hash("admin-pass-1"), Role.ADMIN);
    }

    private static User register(AuthService auth, String name, String username,
                                 String email, String mobile, String password) throws SQLException {
        try {
            return auth.register(name, username, email, mobile, password);
        } catch (AuthException failure) {
            throw new SQLException("registration of " + username + " failed: " + failure.getMessage());
        }
    }

    private static AuthException registerExpectingFailure(AuthService auth, String name, String username,
                                                          String email, String mobile, String password) {
        try {
            auth.register(name, username, email, mobile, password);
            return null;
        } catch (AuthException failure) {
            return failure;
        }
    }

    private static boolean refused(AuthService auth, String name, String username,
                                   String email, String mobile, String password) {
        return registerExpectingFailure(auth, name, username, email, mobile, password) != null;
    }

    private static boolean refusedWithField(AuthService auth, String name, String username,
                                            String email, String mobile, String password) {
        AuthException failure = registerExpectingFailure(auth, name, username, email, mobile, password);
        return failure != null && failure.field() != null;
    }

    private static User loginExpectingSuccess(AuthService auth, String username, String password) {
        try {
            return auth.login(username, password);
        } catch (AuthException failure) {
            return null;
        }
    }

    private static AuthException loginExpectingFailure(AuthService auth, String username, String password) {
        try {
            auth.login(username, password);
            return null;
        } catch (AuthException failure) {
            return failure;
        }
    }

    private static int safeCount(BookingQueryService bookings) {
        try {
            return bookings.myBookingCount();
        } catch (Exception failure) {
            return -1;
        }
    }

    private static int safeActiveCount(BookingQueryService bookings) {
        try {
            return bookings.myActiveBookingCount();
        } catch (Exception failure) {
            return -1;
        }
    }

    private static boolean denied(ThrowingRunnable action) {
        try {
            action.run();
            return false;
        } catch (AccessDeniedException expected) {
            return true;
        } catch (SQLException unexpected) {
            return false;
        }
    }

    private static boolean allowed(ThrowingRunnable action) {
        try {
            action.run();
            return true;
        } catch (Exception failure) {
            return false;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws AccessDeniedException, SQLException;
    }

    private static long countUsers(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM users");
             ResultSet results = statement.executeQuery()) {
            return results.next() ? results.getLong(1) : 0L;
        }
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(title);
    }

    private static void check(String description, boolean condition) {
        checksRun++;
        if (condition) {
            System.out.println("PASS  " + description);
        } else {
            failures++;
            System.out.println("FAIL  " + description);
        }
    }
}