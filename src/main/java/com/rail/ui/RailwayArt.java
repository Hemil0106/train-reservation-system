package com.rail.ui;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The application's railway artwork, drawn with plain Java2D.
 *
 * <p>No image files and no icon font: a handful of shapes keeps the jar
 * self-contained and stays crisp at any size, which matters for HiDPI displays
 * where a fixed PNG would blur.
 *
 * <p>Everything here is decoration. Nothing draws data.
 */
public final class RailwayArt {

    private static final Color INK = new Color(0x14243A);
    private static final Color STEEL = new Color(0x8FA8C8);
    private static final Color GLASS = new Color(0xBBD4F5);

    private RailwayArt() {
    }

    /**
     * A locomotive on a track, for the navy branding panel.
     *
     * <p>Paints at whatever size the layout gives it.
     */
    public static JPanel locomotive() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) {
                    return;
                }
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

                int trackY = (int) (h * 0.80);
                int bodyTop = (int) (h * 0.30);
                int bodyBottom = (int) (h * 0.68);
                int bodyHeight = bodyBottom - bodyTop;

                drawTrack(g, w, trackY);

                // One coach, then the locomotive: left to right along the rail.
                int coachLeft = (int) (w * 0.06);
                int coachRight = (int) (w * 0.44);
                roundRect(g, coachLeft, bodyTop + bodyHeight / 5, coachRight - coachLeft,
                        bodyHeight - bodyHeight / 5, 10, INK, STEEL);

                int engineLeft = (int) (w * 0.50);
                int engineRight = (int) (w * 0.95);
                roundRect(g, engineLeft, bodyTop, engineRight - engineLeft, bodyHeight, 12, INK, STEEL);

                // Windows.
                int windowTop = bodyTop + (int) (bodyHeight * 0.22);
                int windowHeight = (int) (bodyHeight * 0.40);
                int windowWidth = (int) (w * 0.055);
                for (int i = 0; i < 3; i++) {
                    int x = coachLeft + 14 + i * (windowWidth + 12);
                    roundRect(g, x, windowTop, windowWidth, windowHeight, 5, GLASS, null);
                }
                roundRect(g, engineLeft + 14, windowTop, (int) (w * 0.13), windowHeight, 5, GLASS, null);

                // Headlamp.
                g.setColor(new Color(0xFFD98A));
                g.fillOval(engineRight - (int) (w * 0.075), bodyTop + (int) (bodyHeight * 0.30),
                        (int) (w * 0.035), (int) (w * 0.035));

                drawWheels(g, coachLeft, coachRight, engineLeft, engineRight, trackY, w);
                g.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(300, 132));
        return panel;
    }

    /** The round rail mark shown beside the application name. */
    public static JComponent mark(int size) {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int inset = 2;
                int d = Math.min(getWidth(), getHeight()) - inset * 2;

                g.setColor(Color.WHITE);
                g.fillOval(inset, inset, d, d);
                g.setColor(new Color(0x246BFD));
                g.setStroke(new BasicStroke(Math.max(1.6f, d / 12f), BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                // Two converging rails: the simplest possible rail symbol.
                int top = (int) (d * 0.34);
                int bottom = (int) (d * 0.72);
                g.drawLine(inset + (int) (d * 0.34), top, inset + (int) (d * 0.20), bottom);
                g.drawLine(inset + (int) (d * 0.66), top, inset + (int) (d * 0.80), bottom);
                int ties = 3;
                for (int i = 0; i <= ties; i++) {
                    double t = i / (double) ties;
                    int y = (int) (top + (bottom - top) * t);
                    int halfWidth = (int) (d * (0.16 + 0.10 * t));
                    g.drawLine(inset + d / 2 - halfWidth, y, inset + d / 2 + halfWidth, y);
                }
                g.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(size, size));
        panel.setMinimumSize(new Dimension(size, size));
        return panel;
    }

    private static void drawTrack(Graphics2D g, int width, int trackY) {
        g.setColor(STEEL);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(0, trackY, width, trackY);
        g.setStroke(new BasicStroke(1.5f));
        int sleeperSpacing = 18;
        for (int x = 6; x < width; x += sleeperSpacing) {
            g.drawLine(x, trackY + 3, x, trackY + 9);
        }
    }

    private static void drawWheels(Graphics2D g,
                                   int coachLeft, int coachRight,
                                   int engineLeft, int engineRight,
                                   int trackY, int width) {
        int diameter = Math.max(6, (int) (width * 0.045));
        int centreY = trackY - diameter / 2;
        g.setColor(STEEL);
        for (int x = coachLeft + 18; x < coachRight - 10; x += diameter + 14) {
            g.fillOval(x, centreY, diameter, diameter);
        }
        for (int x = engineLeft + 16; x < engineRight - 14; x += diameter + 16) {
            g.fillOval(x, centreY, diameter, diameter);
        }
        // Connecting rod, the detail that makes it read as a locomotive.
        g.setStroke(new BasicStroke(2f));
        g.drawLine(coachLeft + 20, centreY + diameter / 2, engineRight - 18, centreY + diameter / 2);
    }

    private static void roundRect(Graphics2D g, int x, int y, int width, int height,
                                  int arc, Color fill, Color outline) {
        if (fill != null) {
            g.setColor(fill);
            g.fillRoundRect(x, y, width, height, arc, arc);
        }
        if (outline != null) {
            g.setColor(outline);
            g.drawRoundRect(x, y, width, height, arc, arc);
        }
    }
}