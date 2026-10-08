package com.rail;

import com.rail.db.DatabaseConnection;
import com.rail.db.DbConfig;
import com.rail.ui.LoginFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Entry point of the RailReserve railway reservation application.
 *
 * <p>The dashboard is only ever constructed after a successful login, so
 * starting the application lands on the login screen and there is no path that
 * shows a signed-in shell to somebody who is not signed in.
 *
 * <p>Starting up also proves the database can be reached, and says which of
 * four different problems it is when it cannot: no configuration, no server,
 * a refused account, or a missing schema. One message for all four would send
 * an operator to copy a template that already exists.
 *
 * <p>{@link com.busticket.Main} is untouched and still opens the original bus
 * demo, which is why both live side by side.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("RailReserve is a desktop application and needs a display. "
                    + "Run the self-checks instead: com.rail.AuthSelfCheck");
            return;
        }

        // Every UI call has to happen on the event-dispatch thread.
        SwingUtilities.invokeLater(() -> {
            String problem = startupProblem();
            if (problem != null) {
                JOptionPane.showMessageDialog(null, problem,
                        "RailReserve cannot start", JOptionPane.WARNING_MESSAGE);
                System.exit(1);
                return;
            }
            new LoginFrame().setVisible(true);
        });
    }

    /**
     * Checks that the application can actually run, before any window opens.
     *
     * @return null when it can start, otherwise the HTML message explaining why
     *         it cannot
     */
    static String startupProblem() {
        DbConfig config = DatabaseConnection.config();

        if (!config.isConfigured()) {
            return unconfigured(config);
        }
        try (Connection connection = DatabaseConnection.getConnection()) {
            return null;
        } catch (SQLException problem) {
            // The detail goes to the console for whoever is diagnosing it. A
            // Connector/J message names the account and the host and never the
            // password, so this is the safe place for it.
            System.err.println("RailReserve: could not connect at startup: " + problem);
            return refused(config, problem);
        }
    }

    /** Nothing to connect with: name the file and the exact path it must have. */
    private static String unconfigured(DbConfig config) {
        return "<html><body style='width:460px'>"
                + "<b>No database is configured.</b><br><br>"
                + "RailReserve keeps its settings in a gitignored file so that no "
                + "password is ever committed. Copy the template and fill in your own "
                + "account:<br><br>"
                + "<code>" + escape(config.getConfigFile().toString()) + "</code><br><br>"
                + "Copy <code>database" + File.separator + "local.properties.example</code> "
                + "to that path first. The application looks for the file beside the "
                + "jar as well as beside whatever directory it was started from, so "
                + "either location works.<br><br>"
                + "See README section 2 for the full setup."
                + "</body></html>";
    }

    /**
     * The server said no. Which kind of no decides what the operator does next,
     * so the four cases get four sentences.
     */
    private static String refused(DbConfig config, SQLException problem) {
        String state = problem.getSQLState() == null ? "" : problem.getSQLState();
        String where = escape(config.getConfigFile().toString());
        String headline;
        String advice;

        if (state.startsWith("08") || mentions(problem, "Communications link failure")) {
            headline = "<b>MySQL could not be reached.</b>";
            advice = "Nothing is listening at " + escape(serverOf(config.getUrl()))
                    + ". Start the MySQL service, or correct db.host and db.port in "
                    + "<code>" + where + "</code>.";
        } else if (state.startsWith("28")) {
            headline = "<b>MySQL refused the account.</b>";
            advice = "The server rejected db.user or db.password. Check both in <code>"
                    + where + "</code>. The account needs the grants in "
                    + "database/create_app_user.sql; this application never changes "
                    + "permissions itself.";
        } else if (mentions(problem, "Unknown database")) {
            headline = "<b>The database does not exist on that server.</b>";
            advice = "Load database/schema.sql into schema "
                    + escape(databaseOf(config.getUrl()))
                    + " - see README section 2.";
        } else {
            headline = "<b>The database refused the connection.</b>";
            advice = "SQLState " + escape(state) + ", code " + problem.getErrorCode()
                    + ". The driver detail is on the console. Check <code>" + where
                    + "</code>.";
        }

        return "<html><body style='width:460px'>" + headline + "<br><br>"
                + advice + "</body></html>";
    }

    private static boolean mentions(SQLException problem, String text) {
        return problem.getMessage() != null
                && problem.getMessage().toLowerCase().contains(text.toLowerCase());
    }

    /** {@code host:port} from a JDBC URL, for the "cannot be reached" sentence. */
    private static String serverOf(String url) {
        String hostAndPort = url == null ? "" : url;
        int scheme = hostAndPort.indexOf("//");
        if (scheme >= 0) {
            hostAndPort = hostAndPort.substring(scheme + 2);
        }
        int slash = hostAndPort.indexOf('/');
        return slash >= 0 ? hostAndPort.substring(0, slash) : hostAndPort;
    }

    /** The schema name from a JDBC URL, for the "unknown database" sentence. */
    private static String databaseOf(String url) {
        if (url == null) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    /**
     * Makes text safe to put inside the HTML dialog. {@code JOptionPane}
     * renders its message as HTML, and a path containing {@code &} or
     * {@code <} would otherwise break the layout.
     */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}