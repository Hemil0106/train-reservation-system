package com.rail.ui;

import com.busticket.ui.Style;

import javax.swing.JButton;
import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * One sidebar row: a drawn icon, a label, and a chosen state.
 *
 * <p>Painted by hand rather than assembled from a border plus a background
 * colour, so the chosen state can carry a left accent bar and a tinted fill at
 * the same time.
 *
 * <p>The state is called "chosen" rather than "selected" because
 * {@link java.awt.Component} already owns a public {@code selected}, and
 * shadowing it with weaker access would not compile.
 */
final class NavButton extends JButton {

    private static final long serialVersionUID = 1L;

    private static final int ICON_SIZE = 18;
    private static final int ACCENT_WIDTH = 3;
    private static final int TEXT_GAP = 12;
    private static final int LEFT_INSET = 14;

    private static final Color RESTING_TEXT = new Color(0xB9C9DF);
    private static final Color CHOSEN_FILL = new Color(0x1E3352);
    private static final Color HOVER_FILL = new Color(0x1D3557);

    private final NavIcon icon;
    private boolean chosen;

    NavButton(NavIcon icon, String text) {
        this.icon = icon;
        setText(text);
        setFont(Style.font(Font.BOLD, 13));
        setContentAreaFilled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setOpaque(false);
        setHorizontalAlignment(LEFT);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(210, 40));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
    }

    void setChosen(boolean value) {
        this.chosen = value;
        repaint();
    }

    boolean isChosen() {
        return chosen;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();

        if (chosen || getModel().isPressed() || getModel().isRollover()) {
            g.setColor(chosen ? CHOSEN_FILL : HOVER_FILL);
            g.fillRoundRect(0, 0, w, h, 8, 8);
        }
        if (chosen) {
            g.setColor(Style.PRIMARY);
            g.fillRoundRect(0, (h - 22) / 2, ACCENT_WIDTH, 22, ACCENT_WIDTH, ACCENT_WIDTH);
        }

        Color foreground = chosen || getModel().isRollover()
                ? Color.WHITE : RESTING_TEXT;

        JComponent glyph = icon.component(ICON_SIZE, foreground);
        glyph.setBounds(LEFT_INSET, (h - ICON_SIZE) / 2, ICON_SIZE, ICON_SIZE);
        glyph.paint(g);

        g.setFont(getFont());
        g.setColor(foreground);
        int baseline = h / 2 + g.getFontMetrics(getFont()).getAscent() / 2 - 1;
        g.drawString(getText(), LEFT_INSET + ICON_SIZE + TEXT_GAP, baseline);
        g.dispose();
    }
}