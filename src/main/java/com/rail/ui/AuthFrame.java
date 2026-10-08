package com.rail.ui;

import com.busticket.ui.Style;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JButton;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * The navy-and-white shell shared by the login and registration screens.
 *
 * <p>Both screens have the same shape - brand panel on the left, one rounded
 * card on the right - so that shape lives here once instead of being copied
 * and drifting. Subclasses only fill in the card.
 *
 * <p>Colours and painted controls come from {@link Style}, which the legacy bus
 * screens also use, so the two halves of the project look like one product.
 */
abstract class AuthFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final int BRAND_WIDTH = 372;
    private static final int ICON_SIZE = 26;

    /** Where the next {@link #addRow} / {@link #addFull} call will place itself. */
    private int row;

    private final JPanel form = new JPanel(new GridBagLayout());
    private final StatusBanner banner = new StatusBanner();

    AuthFrame(String title) {
        super(title);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(940, 604);
        setMinimumSize(new Dimension(900, 580));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Style.BACKGROUND);
        setContentPane(buildLayout());
    }

    private JPanel buildLayout() {
        JPanel root = new JPanel(new BorderLayout());
        root.add(buildBrandPanel(), BorderLayout.WEST);

        JPanel centre = new JPanel(new BorderLayout());
        centre.setBackground(Style.BACKGROUND);
        centre.setBorder(BorderFactory.createEmptyBorder(28, 28, 28, 28));

        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(30, 34, 30, 34));

        form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        content.add(form, BorderLayout.CENTER);
        content.add(banner, BorderLayout.SOUTH);
        card.add(content, BorderLayout.CENTER);

        centre.add(card, BorderLayout.CENTER);
        root.add(centre, BorderLayout.CENTER);
        return root;
    }

    /** The navy brand panel: mark, name, locomotive artwork and a tagline. */
    private JPanel buildBrandPanel() {
        JPanel brand = new JPanel();
        brand.setBackground(Style.SIDEBAR);
        brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
        brand.setBorder(BorderFactory.createEmptyBorder(34, 30, 30, 30));
        brand.setPreferredSize(new Dimension(BRAND_WIDTH, 0));
        brand.setMinimumSize(new Dimension(BRAND_WIDTH, 0));

        JPanel identity = new JPanel();
        identity.setOpaque(false);
        identity.setLayout(new BoxLayout(identity, BoxLayout.X_AXIS));
        identity.add(RailwayArt.mark(ICON_SIZE + 12));
        identity.add(Box.createHorizontalStrut(12));
        JLabel name = new JLabel("RailReserve");
        name.setFont(Style.font(Font.BOLD, 19));
        name.setForeground(Color.WHITE);
        name.setAlignmentY(Component.CENTER_ALIGNMENT);
        identity.add(name);
        identity.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.add(identity);

        brand.add(Box.createVerticalGlue());

        JPanel art = RailwayArt.locomotive();
        art.setAlignmentX(Component.LEFT_ALIGNMENT);
        art.setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));
        brand.add(art);

        brand.add(Box.createVerticalStrut(26));

        JLabel tagline = new JLabel("<html><body style='width:250px'>"
                + "Reserve a berth, keep your PNR, travel with confidence.</body></html>");
        tagline.setFont(Style.font(Font.PLAIN, 13));
        tagline.setForeground(new Color(0xAFC3DE));
        tagline.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.add(tagline);

        brand.add(Box.createVerticalGlue());

        JLabel note = new JLabel("Local demo · local MySQL");
        note.setFont(Style.font(Font.PLAIN, 11));
        note.setForeground(new Color(0x7D93B2));
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.add(note);

        return brand;
    }

    // ----- card building blocks, used by the subclasses -------------------

    protected StatusBanner banner() {
        return banner;
    }

    protected void heading(String title, String subtitle) {
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(Style.font(Font.BOLD, 24));
        titleLabel.setForeground(Style.TEXT);
        addFull(titleLabel);

        addGap(4);

        JLabel subtitleLabel = new JLabel(subtitle);
        subtitleLabel.setFont(Style.font(Font.PLAIN, 13));
        subtitleLabel.setForeground(Style.MUTED);
        addFull(subtitleLabel);

        addGap(18);
    }

    /** Adds a labelled field and returns the component, so callers can keep a reference. */
    protected JComponent addRow(String label, JComponent field) {
        addGap(10);
        JLabel caption = new JLabel(label);
        caption.setFont(Style.font(Font.PLAIN, 13));
        caption.setForeground(Style.MUTED);
        addFull(caption);

        addGap(3);
        addFull(field);
        return field;
    }

    protected void addGap(int height) {
        row++;
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, height, 0);
        form.add(Box.createVerticalStrut(height), c);
    }

    protected void addFull(JComponent component) {
        row++;
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.LINE_START;
        form.add(component, c);
    }

    /** Pushes everything to the top of the card. Call once, after the last row. */
    protected void finishForm() {
        row++;
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.weighty = 1;
        c.fill = GridBagConstraints.VERTICAL;
        c.insets = new Insets(0, 0, 0, 0);
        form.add(Box.createVerticalGlue(), c);
    }

    protected static JTextField textField(String tooltip) {
        JTextField field = new JTextField();
        style(field);
        field.setToolTipText(tooltip);
        return field;
    }

    protected static JPasswordField passwordField(String tooltip) {
        JPasswordField field = new JPasswordField();
        style(field);
        field.setToolTipText(tooltip);
        return field;
    }

    private static void style(JComponent field) {
        field.setFont(Style.font(Font.PLAIN, 14));
        field.setForeground(Style.TEXT);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Style.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
    }

    /** Registers the button that Enter should press. */
    protected void setDefaultButton(JButton button) {
        JRootPane rootPane = getRootPane();
        if (rootPane != null) {
            rootPane.setDefaultButton(button);
        }
    }

    /** Replaces this window with {@code next}, then disposes of this one. */
    protected void goTo(JFrame next) {
        next.setLocationRelativeTo(null);
        next.setVisible(true);
        dispose();
    }

    /**
     * Runs database work off the event-dispatch thread.
     *
     * <p>A BCrypt verification takes long enough to be noticed, and blocking the
     * EDT would freeze the window - including the repaint that shows the button
     * was pressed. Everything that touches MySQL goes through here.
     */
    protected static void offEdt(Runnable work) {
        Thread worker = new Thread(work, "rail-auth");
        worker.setDaemon(true);
        worker.start();
    }
}