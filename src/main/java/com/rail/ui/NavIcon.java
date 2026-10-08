package com.rail.ui;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Small line icons for the sidebar, drawn with Java2D.
 *
 * <p>One switch, a few shapes each. An icon font or a folder of PNGs would cost
 * more than it returns for five monochrome glyphs, and these stay sharp at any
 * scale.
 */
public enum NavIcon {

    HOME, SEARCH, BOOKINGS, TAG, LOGOUT;

    /** The icon as a component, sized for a sidebar row. */
    public JComponent component(int size, Color colour) {
        JComponent panel = new JComponent() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setColor(colour);
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(g, size);
                g.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(size, size));
        panel.setMinimumSize(new Dimension(size, size));
        panel.setMaximumSize(new Dimension(size, size));
        return panel;
    }

    private void draw(Graphics2D g, int s) {
        switch (this) {
            case HOME -> {
                g.drawPolygon(new int[]{2, s / 2, s - 2}, new int[]{s / 2, 2, s / 2}, 3);
                g.drawRect(3, s / 2, s - 6, s / 2 - 2);
            }
            case SEARCH -> {
                int d = (int) (s * 0.62);
                g.drawOval(2, 2, d, d);
                g.drawLine(2 + d - 2, 2 + d - 2, s - 2, s - 2);
            }
            case BOOKINGS -> {
                // A ticket: rounded body with a perforation and two lines.
                g.drawRoundRect(2, 3, s - 4, s - 6, 4, 4);
                g.drawLine(2, 7, s - 2, 7);
                g.drawLine(6, 12, s - 8, 12);
            }
            case TAG -> {
                g.drawRoundRect(2, 3, s - 4, s - 6, 3, 3);
                g.drawOval(5, 6, 3, 3);
                g.drawLine(11, 10, s - 6, 10);
            }
            case LOGOUT -> {
                g.drawRoundRect(2, 3, (int) (s * 0.45), s - 6, 3, 3);
                g.drawLine((int) (s * 0.45), s / 2, s - 2, s / 2);
                g.drawLine(s - 6, s / 2 - 3, s - 2, s / 2);
                g.drawLine(s - 6, s / 2 + 3, s - 2, s / 2);
            }
        }
    }
}