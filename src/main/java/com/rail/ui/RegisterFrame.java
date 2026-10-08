package com.rail.ui;

import com.busticket.ui.Style;
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
 * Account registration.
 *
 * <p>Collects full name, username, email, mobile and password, hands them to
 * {@link AuthService#register}, and shows whichever field the service objected
 * to. The service is the only thing that decides what is valid; this screen
 * does not duplicate those rules, so a client-side check can never be the only
 * check.
 *
 * <p>The frame is a little taller than the login screen because it has more
 * fields. Nothing else differs between the two.
 */
public final class RegisterFrame extends AuthFrame {

    private static final long serialVersionUID = 1L;

    private final AuthService auth = new AuthService();

    private final JTextField fullNameField = textField("As printed on your ID");
    private final JTextField usernameField = textField("Letters, digits, dot, underscore, hyphen");
    private final JTextField emailField = textField("Optional");
    private final JTextField mobileField = textField("10 digits");
    private final JPasswordField passwordField = passwordField("At least 8 characters");
    private final JButton createButton = Style.primary("Create account");

    public RegisterFrame() {
        super("Create account · RailReserve");
        setSize(940, 700);

        heading("Create your account",
                "One local account. Your password is stored only as a BCrypt hash.");

        addRow("Full name", fullNameField);
        addRow("Username", usernameField);
        addRow("Email", emailField);
        addRow("Mobile number", mobileField);
        addRow("Password", passwordField);

        addGap(16);
        addFull(createButton);

        addGap(12);
        addFull(buildSignInPrompt());

        finishForm();

        createButton.addActionListener(event -> attemptRegister());
        setDefaultButton(createButton);
    }

    private JComponent buildSignInPrompt() {
        JLabel prompt = new JLabel("Already registered?");
        prompt.setFont(Style.font(Font.PLAIN, 13));
        prompt.setForeground(Style.MUTED);

        JLabel link = new JLabel("Sign in");
        link.setFont(Style.font(Font.BOLD, 13));
        link.setForeground(Style.PRIMARY);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                goTo(new LoginFrame());
            }
        });

        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(prompt, BorderLayout.WEST);
        row.add(link, BorderLayout.EAST);
        return row;
    }

    private void attemptRegister() {
        banner().clear();

        String fullName = fullNameField.getText();
        String username = usernameField.getText();
        String email = emailField.getText();
        String mobile = mobileField.getText();
        String password = new String(passwordField.getPassword());

        createButton.setEnabled(false);
        createButton.setText("Creating…");

        offEdt(() -> {
            try {
                auth.register(fullName, username, email, mobile, password);
                SwingUtilities.invokeLater(() -> {
                    // Registration deliberately does not start a session: the
                    // new account signs in through the normal login path.
                    LoginFrame login = new LoginFrame();
                    login.setVisible(true);
                    dispose();
                });
            } catch (AuthException failure) {
                SwingUtilities.invokeLater(() -> {
                    banner().showError(failure.getMessage());
                    passwordField.setText("");
                    highlightField(failure.field());
                    createButton.setEnabled(true);
                    createButton.setText("Create account");
                });
            }
        });
    }

    /** Puts the caret on the field the service complained about. */
    private void highlightField(String field) {
        JTextField target = switch (field == null ? "" : field) {
            case AuthService.FIELD_FULL_NAME -> fullNameField;
            case AuthService.FIELD_USERNAME -> usernameField;
            case AuthService.FIELD_EMAIL -> emailField;
            case AuthService.FIELD_MOBILE -> mobileField;
            case AuthService.FIELD_PASSWORD -> passwordField;
            default -> null;
        };
        if (target != null) {
            target.requestFocusInWindow();
        }
    }
}