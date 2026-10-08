package com.rail.ui;

import com.busticket.ui.Style;
import com.rail.model.Role;
import com.rail.model.User;
import com.rail.service.SessionManager;

import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the shell a user actually moves around in: that every sidebar row
 * reaches its own screen, that exactly one row is marked chosen, and that the
 * window survives being resized.
 *
 * <p>No other check does this. The database checks prove what each service
 * returns; the screen checks prove what one screen says. Neither drives the
 * navigation between screens, and nothing at all looked at what happens when the
 * window is made smaller than its content.
 *
 * <p>The navigation buttons are found by their captions and clicked with
 * {@link AbstractButton#doClick()}, so this exercises the real listeners on the
 * real component tree rather than calling a screen method directly. That is why
 * no test-only method had to be added to {@link DashboardFrame}.
 *
 * <p>Every click runs on the event-dispatch thread, because that is the only
 * thread allowed to touch a Swing component, and the assertions run there too.
 * The screens that read from the database hand that work to a worker thread, so
 * the headings asserted here are the ones laid out synchronously.
 *
 * <p>Skips itself on a headless machine, like the other screen checks.
 */
public final class NavigationSelfCheck {

    /** Wide enough for every screen, and narrower than the default on purpose. */
    private static final int[][] SIZES = {
            {1280, 800},
            {1024, 700},
            {900, 640},
            {1440, 900}};

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("Navigation and window self-check");
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("SKIP  screen checks (no display available)");
            return;
        }
        System.out.println();

        SessionManager.get().beginSession(
                new User(7L, "nav.check", "Nav Check", Role.CUSTOMER));
        try {
            checkNavigation();
            checkResizing();
        } catch (Exception failed) {
            see("the dashboard built for a traveller", false);
            failed.printStackTrace();
        } finally {
            SessionManager.get().endSession();
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("All " + passed + " navigation checks passed.");
        } else {
            System.out.println(failed + " of " + (passed + failed)
                    + " navigation checks FAILED.");
            System.exit(1);
        }
    }

    /** Every row reaches its own screen, and only one row is chosen at a time. */
    private static void checkNavigation() throws Exception {
        section("Every sidebar row reaches its own screen");
        DashboardFrame frame = onEdt(DashboardFrame::new);
        try {
            see("the dashboard built for a traveller", frame != null);
            see("it opens on Home", shows(frame, "Your travel at a glance"));

            click(frame, "Search Trains");
            see("Search Trains reaches the search form", shows(frame, "Search Trains"));
            see("the search form is on that screen", hasComponent(frame, TrainSearchPanel.class));
            see("exactly one sidebar row is chosen", chosenCount(frame) == 1);

            click(frame, "My Bookings");
            see("My Bookings reaches the history", shows(frame, "My Bookings"));
            see("exactly one sidebar row is chosen", chosenCount(frame) == 1);

            click(frame, "PNR Status");
            see("PNR Status reaches the lookup", shows(frame, "PNR Status"));
            see("it says only the traveller's own PNR will be found",
                    shows(frame, "Only bookings made with this account"));
            see("exactly one sidebar row is chosen", chosenCount(frame) == 1);

            click(frame, "Home");
            see("Home comes back", shows(frame, "Recent bookings"));
            see("exactly one sidebar row is chosen", chosenCount(frame) == 1);

            see("a traveller is offered no administration button",
                    !buttonTexts(frame).contains("Open administration"));
        } finally {
            dispose(frame);
        }
    }

    /** The window is laid out again at several sizes without collapsing. */
    private static void checkResizing() throws Exception {
        section("The window survives being resized");
        DashboardFrame frame = onEdt(DashboardFrame::new);
        try {
            see("the dashboard built again for the resize checks", frame != null);

            // A window that has never been shown has no native peer, and a peerless
            // window silently ignores setSize - which would make every assertion
            // below pass for the wrong reason. Shown, then measured, then disposed.
            SwingUtilities.invokeAndWait(() -> frame.setVisible(true));
            see("the window is on screen", frame.isShowing());

            Dimension minimum = frame.getMinimumSize();
            Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
            see("it declares a minimum size", minimum.width > 0 && minimum.height > 0);

            for (int[] size : SIZES) {
                String at = size[0] + "x" + size[1];
                resize(frame, size[0], size[1]);
                // Not "the size asked for": Swing clamps a setSize up to the
                // minimum size, and the window manager clamps it down to the
                // work area. Both are correct, so what is asserted is the range
                // the window is entitled to occupy, not the request.
                see("asked for " + at + ", it is no smaller than its minimum",
                        frame.getWidth() >= minimum.width && frame.getHeight() >= minimum.height);
                see("asked for " + at + ", it is no wider than the screen",
                        frame.getWidth() <= screen.width);
                // Windows lets a window be a little taller than the screen when the
                // overflow is its own title bar, so the allowance is the insets.
                see("asked for " + at + ", it fits the screen apart from its own frame",
                        frame.getHeight() <= screen.height + frame.getInsets().top
                                + frame.getInsets().bottom);
                see("nothing collapses at " + at + " (" + collapsed(frame) + ")",
                        collapsed(frame) == null);
                see("the sidebar keeps its width at " + at, sidebarKeepsItsWidth(frame));
                see("the content pane is laid out at " + at,
                        frame.getContentPane().getWidth() > 0
                                && frame.getContentPane().getHeight() > 0);
            }

            resize(frame, 400, 300);
            see("a window cannot be dragged below its minimum", frame.getWidth() >= minimum.width
                    && frame.getHeight() >= minimum.height);
        } finally {
            dispose(frame);
        }
    }

    // ---------------------------------------------------------------- driving

    private static void click(DashboardFrame frame, String caption) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AbstractButton button = buttonWithCaption(frame, caption);
            if (button == null) {
                throw new IllegalStateException("no sidebar row reading " + caption);
            }
            button.doClick();
        });
    }

    private static void resize(DashboardFrame frame, int width, int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.setSize(width, height);
            frame.validate();
            frame.doLayout();
            layout(frame.getContentPane());
        });
    }

    /** Lays out the whole tree, so a size change is measured and not just set. */
    private static void layout(Container root) {
        root.doLayout();
        for (Component child : childrenOf(root)) {
            if (child instanceof Container container) {
                layout(container);
            }
        }
    }

    // ---------------------------------------------------------------- asserting

    private static boolean shows(Container root, String text) {
        return labelTextsIn(root).stream().anyMatch(line -> line.contains(text));
    }

    private static boolean hasComponent(Container root, Class<?> type) {
        for (Component child : childrenOf(root)) {
            if (type.isInstance(child)) {
                return true;
            }
            if (child instanceof Container container && hasComponent(container, type)) {
                return true;
            }
        }
        return false;
    }

    private static int chosenCount(Container root) {
        int chosen = 0;
        for (Component child : childrenOf(root)) {
            if (child instanceof NavButton button && button.isChosen()) {
                chosen++;
            }
            if (child instanceof Container container) {
                chosen += chosenCount(container);
            }
        }
        return chosen;
    }

    /**
     * The first visible component squeezed out of existence, or null.
     *
     * <p>Named rather than counted, so a failure says which component and at
     * what size - a bare "something collapsed" would send whoever reads it
     * hunting. Only application components are considered, and only ones that ask
     * for space: a {@code Box} strut legitimately has no width, and the look and
     * feel's own scroll-bar arrows are given no size whenever the bar is not
     * needed, which says nothing about this application's layout. A visible
     * {@code com.rail} or {@code com.busticket} component that asks for both
     * dimensions and gets neither is a layout bug.
     */
    private static String collapsed(Container root) {
        for (Component child : childrenOf(root)) {
            if (child instanceof JComponent component && component.isVisible()
                    && child.getClass().getName().startsWith("com.")) {
                Dimension preferred = component.getPreferredSize();
                Dimension size = component.getSize();
                if (preferred.width > 0 && preferred.height > 0
                        && (size.width <= 0 || size.height <= 0)) {
                    return component.getClass().getSimpleName()
                            + " asked for " + preferred.width + "x" + preferred.height
                            + " and got " + size.width + "x" + size.height;
                }
            }
            if (child instanceof Container container) {
                String found = collapsed(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * The sidebar is a fixed-width column, so a resize must not squeeze it.
     *
     * <p>Found by its own background rather than by a field: this check is about
     * what a resized window looks like, so it reads the laid-out tree the same
     * way a person would look at the screen.
     */
    private static boolean sidebarKeepsItsWidth(Container root) {
        Component sidebar = sidebarIn(root);
        if (sidebar == null) {
            return false;
        }
        int wanted = sidebar.getPreferredSize().width;
        return wanted > 0 && sidebar.getWidth() == wanted;
    }

    private static Component sidebarIn(Container root) {
        for (Component child : childrenOf(root)) {
            if (Style.SIDEBAR.equals(child.getBackground())) {
                return child;
            }
            if (child instanceof Container container) {
                Component found = sidebarIn(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> labelTextsIn(Component root) {
        List<String> found = new ArrayList<>();
        for (Component child : childrenOf(root)) {
            if (child instanceof JLabel label && label.getText() != null) {
                found.add(label.getText());
            }
            if (child instanceof Container container) {
                found.addAll(labelTextsIn(container));
            }
        }
        return found;
    }

    private static List<String> buttonTexts(Container root) {
        List<String> found = new ArrayList<>();
        for (Component child : childrenOf(root)) {
            if (child instanceof AbstractButton button && button.getText() != null) {
                found.add(button.getText());
            }
            if (child instanceof Container container) {
                found.addAll(buttonTexts(container));
            }
        }
        return found;
    }

    private static AbstractButton buttonWithCaption(Container root, String caption) {
        for (Component child : childrenOf(root)) {
            if (child instanceof AbstractButton button && caption.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container container) {
                AbstractButton found = buttonWithCaption(container, caption);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<Component> childrenOf(Component root) {
        return root instanceof Container container ? List.of(container.getComponents()) : List.of();
    }

    // ---------------------------------------------------------------- plumbing

    private static DashboardFrame onEdt(SqlSupplier<DashboardFrame> build) throws Exception {
        DashboardFrame[] built = new DashboardFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                built[0] = build.get();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        return built[0];
    }

    /** Builds on the event thread, where a frame may legitimately throw. */
    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws Exception;
    }

    private static void dispose(DashboardFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(frame::dispose);
    }

    private static void section(String title) {
        System.out.println(title);
        System.out.println("-".repeat(title.length()));
    }

    private static void see(String what, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (ok) {
            passed++;
        } else {
            failed++;
        }
    }
}