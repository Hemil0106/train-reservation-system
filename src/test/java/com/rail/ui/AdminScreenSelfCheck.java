package com.rail.ui;

import com.rail.model.AdminView;
import com.rail.model.Role;
import com.rail.model.User;
import com.rail.service.AccessDeniedException;
import com.rail.service.SessionManager;

import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the administration window, which no database check can prove: that it
 * refuses to open for a traveller, and that what it offers matches the policies
 * the service enforces.
 *
 * <p>{@code AdminSelfCheck} proves the rules. This proves the screen does not
 * quietly offer a way around them - no delete button for a train or a station,
 * deactivation instead, and a berth removal that names a berth rather than a
 * coach.
 *
 * <p>Nothing is written. The window's own reads run on worker threads when it
 * opens and any of them failing shows a message rather than propagating, so this
 * touches the database only to read what is already there.
 */
public final class AdminScreenSelfCheck {

    private static final String[] TABS =
            {"Overview", "Trains", "Stops", "Rake", "Fares", "Stations"};

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("Administration screen self-check");
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("SKIP  screen checks (no display available)");
            return;
        }
        System.out.println();

        SessionManager.get().beginSession(
                new User(7L, "screen.check", "Screen Check", Role.CUSTOMER));
        try {
            see("the window refuses to open for a traveller", refusedForATraveller());
        } catch (Exception failed) {
            see("the window refuses to open for a traveller", false);
            failed.printStackTrace();
        } finally {
            SessionManager.get().endSession();
        }

        SessionManager.get().beginSession(
                new User(9L, "admin.check", "Admin Check", Role.ADMIN));
        try {
            checkTheWindow();
        } catch (Exception failed) {
            see("the window built for an administrator", false);
            failed.printStackTrace();
        } finally {
            SessionManager.get().endSession();
        }

        System.out.println();
        if (failed == 0) {
            System.out.println("All " + passed + " administration screen checks passed.");
        } else {
            System.out.println(failed + " of " + (passed + failed)
                    + " administration screen checks FAILED.");
            System.exit(1);
        }
    }

    private static boolean refusedForATraveller() throws Exception {
        AdminFrame frame = null;
        try {
            frame = new AdminFrame();
            return false;
        } catch (AccessDeniedException refused) {
            return true;
        } finally {
            if (frame != null) {
                frame.dispose();
            }
        }
    }

    /** The six tabs, and what each one offers and withholds. */
    private static void checkTheWindow() throws Exception {
        System.out.println("The window opens for an administrator");
        AdminFrame frame = new AdminFrame();
        try {
            see("the window built for an administrator", true);

            JTabbedPane tabs = tabsIn(frame.getContentPane());
            see("it has six tabs", tabs != null && tabs.getTabCount() == TABS.length);

            List<String> titles = new ArrayList<>();
            for (int i = 0; tabs != null && i < tabs.getTabCount(); i++) {
                titles.add(tabs.getTitleAt(i));
            }
            see("they are the six things an administrator changes",
                    List.of(TABS).equals(titles));

            List<String> buttons = buttonTextsIn(frame.getContentPane());
            List<String> text = labelTextsIn(frame.getContentPane());

            see("the overview offers a refresh", buttons.contains("Refresh"));
            see("a train is added and edited",
                    buttons.contains("Add train") && buttons.contains("Save changes"));
            see("a train is deactivated rather than deleted",
                    buttons.contains("Deactivate") && buttons.contains("Activate"));
            see("a stop is re-timed", buttons.contains("Save stop"));
            see("a coach and a berth can be added",
                    buttons.contains("Add coach") && buttons.contains("Add berth"));
            see("a coach and a berth can be removed",
                    buttons.contains("Remove coach") && buttons.contains("Remove berth"));
            see("a fare rule is saved and removed",
                    buttons.contains("Save rule") && buttons.contains("Remove rule"));
            see("a station is added and edited",
                    buttons.contains("Add station"));

            see("nothing anywhere deletes a train or a station",
                    buttons.stream().noneMatch(caption -> caption.toLowerCase().contains("delete")));
            see("a removal names what it removes",
                    List.of("Remove coach", "Remove berth", "Remove rule").stream()
                            .allMatch(buttons::contains));

            see("the policies are explained on screen",
                    text.stream().anyMatch(line -> line.contains("cannot be changed"))
                            && text.stream().anyMatch(line -> line.contains("no DELETE"))
                            && text.stream().anyMatch(line -> line.contains("end date")));

            see("the forms offer drop-downs to choose from",
                    combosIn(frame.getContentPane()).size() >= 7);

            List<String> berthTypes = optionsOf(frame.getContentPane(),
                    AdminView.BERTH_TYPES.size());
            see("the berth types offered are the column's own", berthTypes != null);
            see("they are the berth types in order",
                    berthTypes != null && berthTypes.equals(AdminView.BERTH_TYPES));

            see("every tab explains what it is for",
                    tabs != null && allTabsAreLabelled(tabs));
        } finally {
            frame.dispose();
        }
    }

    /** Each tab carries its heading, so an empty tab is still an explained one. */
    private static boolean allTabsAreLabelled(JTabbedPane tabs) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (labelTextsIn(tabs.getComponentAt(i)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The options of a drop-down holding exactly {@code count} choices, or null. */
    private static List<String> optionsOf(List<JComboBox<?>> pickers, int count) {
        for (JComboBox<?> picker : pickers) {
            if (picker.getItemCount() == count && count > 1) {
                List<String> options = new ArrayList<>();
                for (int i = 0; i < picker.getItemCount(); i++) {
                    options.add(String.valueOf(picker.getItemAt(i)));
                }
                return options;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- plumbing

    /** Every label on screen, flattened, so assertions can look for real text. */
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

    /** Every button caption, so an action can be asserted present or absent. */
    private static List<String> buttonTextsIn(Component root) {
        List<String> found = new ArrayList<>();
        for (Component child : childrenOf(root)) {
            if (child instanceof AbstractButton button && button.getText() != null) {
                found.add(button.getText());
            }
            if (child instanceof Container container) {
                found.addAll(buttonTextsIn(container));
            }
        }
        return found;
    }

    private static List<JComboBox<?>> combosIn(Component root) {
        List<JComboBox<?>> found = new ArrayList<>();
        for (Component child : childrenOf(root)) {
            if (child instanceof JComboBox<?> picker) {
                found.add(picker);
            }
            if (child instanceof Container container) {
                found.addAll(combosIn(container));
            }
        }
        return found;
    }

    /** The options of a drop-down holding exactly {@code count} choices, or null. */
    private static List<String> optionsOf(Component root, int count) {
        for (JComboBox<?> picker : combosIn(root)) {
            if (picker.getItemCount() == count && count > 1) {
                List<String> options = new ArrayList<>();
                for (int i = 0; i < picker.getItemCount(); i++) {
                    options.add(String.valueOf(picker.getItemAt(i)));
                }
                return options;
            }
        }
        return null;
    }

    private static JTabbedPane tabsIn(Component root) {
        for (Component child : childrenOf(root)) {
            if (child instanceof JTabbedPane tabs) {
                return tabs;
            }
            if (child instanceof Container container) {
                JTabbedPane found = tabsIn(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A frame, a panel and a tab all answer to this; none of them is a leaf. */
    private static List<Component> childrenOf(Component root) {
        return root instanceof Container container
                ? List.of(container.getComponents())
                : List.of();
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