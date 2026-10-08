package com.rail.ui;

import javax.swing.JLabel;
import javax.swing.Timer;
import java.awt.Font;

/**
 * A word that grows an ellipsis while the application is waiting for something.
 *
 * <h2>Why this and not a spinner</h2>
 *
 * <p>A database read on a worker thread leaves the user looking at a label that
 * has stopped changing. A row of characters turning says "still working" without
 * inventing a component, and it stops on its own - the timer is bounded rather
 * than left running until somebody remembers to stop it.
 *
 * <h2>The rules it keeps</h2>
 *
 * <ul>
 *   <li>{@link Timer}, so no second thread and no animation library.</li>
 *   <li>Every {@code setText} happens on the event-dispatch thread, because that
 *       is the only place a {@link JLabel} may be touched.</li>
 *   <li>Bounded: it settles on a final label and stops. A forgotten instance
 *       cannot keep a timer alive.</li>
 *   <li>Nothing here decides anything. It paints a word; whether a booking
 *       succeeds is decided by the service and the database.</li>
 * </ul>
 *
 * <p>ponytail: one label, one bounded timer, no registry and no lifecycle to
 * manage. Add a real spinner only if a wait ever lasts long enough for three
 * dots to look like a bug.
 */
final class Working {

    /** Slow enough to read as an ellipsis, quick enough never to be waited on. */
    private static final int TICK_MILLIS = 320;

    /** Eight ticks is about two and a half seconds, then it settles. */
    private static final int TICKS = 8;

    private Working() {
    }

    /**
     * A label that reads {@code verb}, then {@code verb.} and so on, and settles.
     *
     * <p>Must be called on the event-dispatch thread, like every other Swing call.
     * The returned label is an ordinary {@link JLabel}: a caller that replaces
     * it, or never shows it, simply never sees the animation.
     */
    static JLabel indicator(String verb) {
        JLabel label = new JLabel(verb);
        label.setFont(com.busticket.ui.Style.font(Font.PLAIN, 12));
        label.setForeground(com.busticket.ui.Style.MUTED);

        Timer timer = new Timer(TICK_MILLIS, null);
        int[] tick = {0};
        timer.addActionListener(event -> {
            tick[0]++;
            label.setText(verb + ".".repeat(Math.min(tick[0], 3)));
            if (tick[0] >= TICKS) {
                timer.stop();
            }
        });
        timer.start();
        return label;
    }
}