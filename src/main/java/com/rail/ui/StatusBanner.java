package com.rail.ui;

import com.busticket.ui.Style;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Font;

/**
 * An inline message strip used for validation errors and confirmations.
 *
 * <p>Stays hidden until there is something to say, so a form never carries an
 * empty grey bar. On an error it flashes its background twice.
 *
 * <p>That flash is the only animation in the authentication screens. It is a
 * {@link Timer} driving one repaint of one small component, so it cannot make
 * the UI feel slower - which is the test every animation here has to pass.
 */
final class StatusBanner extends JLabel {

    private static final long serialVersionUID = 1L;

    private static final int FLASHES = 2;
    private static final int FLASH_MILLIS = 110;

    private final Color errorBackground;
    private final Color successBackground;
    private final Color errorForeground;
    private final Color successForeground;

    StatusBanner() {
        setFont(Style.font(Font.BOLD, 12));
        setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
        setOpaque(true);

        errorBackground = tint(Style.ERROR);
        successBackground = tint(Style.SUCCESS);
        errorForeground = Style.ERROR;
        successForeground = new Color(0x0E6B42);

        setVisible(false);
    }

    void showError(String message) {
        setText(message);
        setForeground(errorForeground);
        setBackground(errorBackground);
        setVisible(true);
        flash();
    }

    void showSuccess(String message) {
        setText(message);
        setForeground(successForeground);
        setBackground(successBackground);
        setVisible(true);
    }

    /** Neutral, for work in progress: not a result, so neither success nor error. */
    void showInfo(String message) {
        setText(message);
        setForeground(Style.TEXT);
        setBackground(Style.BACKGROUND);
        setVisible(true);
    }

    void clear() {
        setText("");
        setVisible(false);
    }

    private void flash() {
        Color resting = errorBackground;
        Timer timer = new Timer(FLASH_MILLIS, null);
        int[] tick = {0};
        timer.addActionListener(event -> {
            tick[0]++;
            setBackground(tick[0] % 2 == 0 ? resting : tint(Style.ERROR).brighter());
            if (tick[0] >= FLASHES * 2) {
                ((Timer) event.getSource()).stop();
                setBackground(resting);
            }
        });
        timer.start();
    }

    /** The same hue at low opacity, so an error strip never shouts. */
    private static Color tint(Color base) {
        return new Color(
                (int) (base.getRed() * 0.10 + 255 * 0.90),
                (int) (base.getGreen() * 0.10 + 255 * 0.90),
                (int) (base.getBlue() * 0.10 + 255 * 0.90));
    }
}