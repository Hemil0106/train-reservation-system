package com.busticket.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JPanel;
import javax.swing.Timer;

/** A small Java2D bus scene used in the home hero; the timer only repaints this panel. */
public class BusIllustrationPanel extends JPanel {

    private final Timer roadTimer;
    private int dashOffset;

    public BusIllustrationPanel() {
        setOpaque(false);
        roadTimer = new Timer(80, event -> {
            dashOffset = (dashOffset + 4) % 32;
            repaint();
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        roadTimer.start();
    }

    @Override
    public void removeNotify() {
        roadTimer.stop();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int width = getWidth();
        int height = getHeight();

        g.setColor(new Color(255, 255, 255, 28));
        g.fillOval(width - 155, 8, 140, 140);
        g.fillOval(2, height - 75, 120, 100);

        int roadY = height - 32;
        g.setColor(new Color(17, 51, 111, 110));
        g.fillRoundRect(10, roadY, Math.max(1, width - 20), 14, 10, 10);
        g.setColor(new Color(255, 255, 255, 180));
        for (int x = -32 + dashOffset; x < width; x += 32) {
            g.fillRoundRect(x, roadY + 6, 15, 2, 2, 2);
        }

        int busWidth = Math.min(190, Math.max(120, width - 45));
        int busHeight = Math.min(88, Math.max(58, height - 55));
        int busX = Math.max(18, (width - busWidth) / 2);
        int busY = Math.max(24, roadY - busHeight - 2);
        g.setColor(new Color(255, 255, 255, 245));
        g.fillRoundRect(busX, busY, busWidth, busHeight, 18, 18);
        g.setColor(new Color(204, 224, 255));
        g.fillRoundRect(busX + 18, busY + 15, busWidth - 48, 28, 7, 7);
        g.setColor(new Color(36, 107, 253));
        g.fillRoundRect(busX + busWidth - 27, busY + 14, 13, 48, 4, 4);
        g.setColor(new Color(20, 36, 58));
        g.fillOval(busX + 27, busY + busHeight - 10, 20, 20);
        g.fillOval(busX + busWidth - 52, busY + busHeight - 10, 20, 20);
        g.setColor(new Color(255, 206, 86));
        g.fillRoundRect(busX + 5, busY + 52, 10, 9, 3, 3);
        g.dispose();
    }
}
