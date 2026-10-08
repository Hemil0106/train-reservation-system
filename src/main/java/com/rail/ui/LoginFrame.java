package com.rail.ui;

import com.busticket.ui.Style;
import com.rail.service.AccessDeniedException;
import com.rail.service.AuthException;
import com.rail.service.AuthService;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The login screen, and the only way into the application.
 *
 * <p>A failed attempt shows {@link AuthException#GENERIC_LOGIN_FAILURE} and
 * nothing else - no hint about whether the username exists, no field-level
 * detail - so the form cannot be used to enumerate accounts.
 */
public final class LoginFrame extends AuthFrame {

    private static final long serialVersionUID = 1L;

    private final AuthService auth = new AuthService();

    private final JTextField usernameField = textField("Your username");
    private final JPasswordField passwordField = passwordField("Your password");
    private final JButton signInButton = Style.primary("Sign in");

    public LoginFrame() {
        super("Sign in · RailReserve");

        heading("Welcome back",
                "Sign in with your local RailReserve account.");

        addRow("Username", usernameField);
        addRow("Password", passwordField);

        addGap(16);
        addFull(signInButton);

        addGap(12);
        addFull(buildRegisterPrompt());

        finishForm();

        signInButton.addActionListener(event -> attemptLogin());
        setDefaultButton(signInButton);
    }

    /** "No account yet? Create one" as a clickable link. */
    private JComponent buildRegisterPrompt() {
        JLabel prompt = new JLabel("No account yet?");
        prompt.setFont(Style.font(Font.PLAIN, 13));
        prompt.setForeground(Style.MUTED);

        JLabel link = new JLabel("Create one");
        link.setFont(Style.font(Font.BOLD, 13));
        link.setForeground(Style.PRIMARY);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                goTo(new RegisterFrame());
            }
        });

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(prompt, BorderLayout.WEST);
        row.add(link, BorderLayout.EAST);
        return row;
    }

    private void attemptLogin() {
        banner().clear();

        String username = usernameField.getText();
        String password = new String(passwordField.getPassword());

        if (username.trim().isEmpty() || password.isEmpty()) {
            banner().showError("Enter your username and password.");
            return;
        }

        signInButton.setEnabled(false);
        signInButton.setText("Signing in…");

        // BCrypt is deliberately slow, so this must not run on the EDT.
        offEdt(() -> {
            try {
                auth.login(username, password);
                SwingUtilities.invokeLater(() -> {
                    try {
                        goTo(new DashboardFrame());
                    } catch (AccessDeniedException denied) {
                        // Should be unreachable straight after a successful
                        // login, but if the dashboard ever refuses, drop the
                        // session again rather than leaving a half-open state.
                        auth.logout();
                        banner().showError(denied.getMessage());
                        signInButton.setEnabled(true);
                        signInButton.setText("Sign in");
                    }
                });
            } catch (AuthException failure) {
                // One message for every failure reason. Nothing is logged: the
                // inputs include a password, and a hash must never be printed.
                SwingUtilities.invokeLater(() -> {
                    banner().showError(failure.getMessage());
                    passwordField.setText("");
                    signInButton.setEnabled(true);
                    signInButton.setText("Sign in");
                });
            }
        });
    }
}