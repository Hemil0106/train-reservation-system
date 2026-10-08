package com.busticket.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

/**
 * Shared colours, typography and lightweight painted controls.
 *
 * Promoted to public in Milestone 3 so the railway screens (com.rail.ui)
 * render through the same design system as the legacy bus screens, instead
 * of a second hand-maintained copy of these colours drifting out of sync.
 * It remains a small utility holder, not a framework: no configuration, no
 * theme registry, no plugin hook.
 */
public final class Style {

    public static final Color BACKGROUND = new Color(0xF4F7FB);
    public static final Color SIDEBAR = new Color(0x14243A);
    public static final Color PRIMARY = new Color(0x246BFD);
    public static final Color PRIMARY_HOVER = new Color(0x185BE5);
    public static final Color SECONDARY_BLUE = new Color(0x4D8DFF);
    public static final Color TEXT = new Color(0x1D2939);
    public static final Color MUTED = new Color(0x667085);
    public static final Color BORDER = new Color(0xE4EAF2);
    public static final Color SUCCESS = new Color(0x16A36A);
    public static final Color WARNING = new Color(0xE9A23B);
    public static final Color ERROR = new Color(0xD64545);
    public static final Color WHITE = Color.WHITE;
    public static final int GAP = 8;

    private Style() {
    }

    public static <T extends JPanel> T prepare(T panel) {
        panel.setBackground(BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        return panel;
    }

    public static JLabel pageHeading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(font(Font.BOLD, 27));
        label.setForeground(TEXT);
        return label;
    }

    public static JLabel screenHeading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(font(Font.BOLD, 20));
        label.setForeground(TEXT);
        return label;
    }

    public static JLabel sectionHeading(String text) {
        JLabel label = new JLabel(text);
        label.setFont(font(Font.BOLD, 16));
        label.setForeground(TEXT);
        return label;
    }

    public static JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(font(Font.PLAIN, 13));
        label.setForeground(MUTED);
        return label;
    }

    public static JLabel fieldValue(String text) {
        JLabel label = new JLabel(text);
        label.setFont(font(Font.BOLD, 14));
        label.setForeground(TEXT);
        return label;
    }

    public static JLabel asValue(JLabel label) {
        label.setFont(font(Font.BOLD, 14));
        label.setForeground(TEXT);
        return label;
    }

    public static JLabel muted(String text) {
        JLabel label = new JLabel(text);
        label.setFont(font(Font.PLAIN, 12));
        label.setForeground(MUTED);
        return label;
    }

    public static JButton primary(String text) {
        return new RoundedButton(text, PRIMARY, Color.WHITE, PRIMARY_HOVER, false);
    }

    public static JButton secondary(String text) {
        return new RoundedButton(text, WHITE, TEXT, new Color(0xEEF4FF), true);
    }

    public static JButton sidebarButton(String text) {
        return new RoundedButton(text, SIDEBAR, new Color(0xC5D4E8), new Color(0x203A5A), false);
    }

    public static RoundedPanel card() {
        return new RoundedPanel(10, WHITE, BORDER);
    }

    public static JScrollPane scroll(Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(null);
        pane.setViewportBorder(null);
        pane.getViewport().setBackground(BACKGROUND);
        pane.getVerticalScrollBar().setUnitIncrement(16);
        return pane;
    }

    public static GridBagConstraints cell(int row, int column) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = row;
        c.insets = new Insets(GAP, GAP, GAP, GAP);
        c.anchor = GridBagConstraints.LINE_START;
        return c;
    }

    public static Font font(int style, float size) {
        return new Font("Segoe UI", style, Math.round(size));
    }

    public static void warn(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Please check", JOptionPane.WARNING_MESSAGE);
    }

    public static final class RoundedPanel extends JPanel {
        private final int radius;
        private final Color fill;
        private final Color outline;

        RoundedPanel(int radius, Color fill, Color outline) {
            this.radius = radius;
            this.fill = fill;
            this.outline = outline;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g.setColor(outline);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g.dispose();
            super.paintComponent(graphics);
        }
    }

    private static final class RoundedButton extends JButton {
        private final Color normal;
        private final Color hover;
        private final boolean outlined;
        private boolean hovered;

        RoundedButton(String text, Color normal, Color foreground, Color hover, boolean outlined) {
            super(text);
            this.normal = normal;
            this.hover = hover;
            this.outlined = outlined;
            setFont(font(Font.BOLD, 13));
            setForeground(foreground);
            setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) { hovered = true; repaint(); }
                @Override public void mouseExited(MouseEvent event) { hovered = false; repaint(); }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = isEnabled() ? (hovered ? hover : normal) : new Color(0xD7DEEA);
            g.setColor(fill);
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            if (outlined) {
                g.setColor(BORDER);
                g.setStroke(new BasicStroke(1f));
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            }
            g.dispose();
            super.paintComponent(graphics);
        }
    }
}
