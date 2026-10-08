package com.rail.ui;

import com.busticket.ui.Style;
import com.rail.model.BerthAllocation;
import com.rail.model.BookingDetail;
import com.rail.model.BookingDraft;
import com.rail.model.BookingSummary;
import com.rail.model.ConfirmedBooking;
import com.rail.model.FareEstimate;
import com.rail.model.TrainSelection;
import com.rail.model.User;
import com.rail.service.AccessDeniedException;
import com.rail.service.AuthService;
import com.rail.service.BookingQueryService;
import com.rail.service.BookingService;
import com.rail.service.CancellationService;
import com.rail.service.RefundPolicy;
import com.rail.service.SessionManager;
import com.rail.service.ValidationException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;


/**
 * The signed-in application shell: navy sidebar, light working area.
 *
 * <p>Reachable only with a session. The constructor refuses to build one
 * without it, so there is no code path that opens this window for a signed-out
 * visitor - the check is not merely "the login screen forgot to open it".
 *
 * <p>Every figure on this screen is read from the database. A traveller who has
 * never booked anything sees zeroes and an explicit empty state, never a
 * placeholder row or an invented statistic.
 */
public final class DashboardFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final int SIDEBAR_WIDTH = 244;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final transient AuthService auth = new AuthService();
    private final transient BookingQueryService bookings = new BookingQueryService();
    private final transient CancellationService cancellations = new CancellationService();

    // One search panel for the life of the window. Rebuilding it per visit would
    // throw away the route, date and party size the traveller already chose.
    private final TrainSearchPanel searchPanel = new TrainSearchPanel();

    /** What the traveller chose on the search screen, for the booking stage. */
    private transient TrainSelection selection;

    /**
     * The booking being prepared, kept across navigation.
     *
     * <p>Held here rather than inside {@link PassengerPanel} so that going back
     * to search and returning does not lose the passengers already entered.
     * Choosing a different train replaces it, because a passenger list belongs to
     * the train and class it was chosen for.
     */
    private transient BookingDraft draft;

    /** The passenger screen, rebuilt per selection but fed the surviving draft. */
    private transient PassengerPanel passengerPanel;

    /** True while a booking transaction is in flight, to keep Continue single-shot. */
    private transient boolean booking;

    private final transient User user = SessionManager.get().currentUser();
    private final JPanel content = new JPanel(new BorderLayout());
    private final StatusBanner banner = new StatusBanner();

    private final NavButton homeButton = new NavButton(NavIcon.HOME, "Home");
    private final NavButton searchButton = new NavButton(NavIcon.SEARCH, "Search Trains");
    private final NavButton myBookingsButton = new NavButton(NavIcon.BOOKINGS, "My Bookings");
    private final NavButton pnrButton = new NavButton(NavIcon.TAG, "PNR Status");
    private final NavButton logoutButton = new NavButton(NavIcon.LOGOUT, "Logout");

    public DashboardFrame() throws AccessDeniedException {
        super("RailReserve");
        // Refuse to build a signed-in shell for a signed-out visitor, rather
        // than rendering an empty dashboard.
        SessionManager.get().requireUser();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1120, 700);
        setMinimumSize(new Dimension(1000, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Style.BACKGROUND);
        setContentPane(buildLayout());

        homeButton.setChosen(true);
        searchPanel.onSelection(this::onTrainSelected);
        showHome();
    }

    /**
     * Records the chosen train and class, then opens passenger entry.
     *
     * <p>Choosing a train starts a booking draft. No booking row is created here
     * or anywhere downstream: this milestone ends with a validated draft, so the
     * screen says so rather than implying a ticket exists.
     */
    private void onTrainSelected(TrainSelection chosen) {
        this.selection = chosen;
        if (draft != null && draft.selection().equals(chosen)) {
            // The same train and class again, usually after a detour through
            // Home. Keep the passengers already entered instead of quietly
            // starting over.
            showPassengers();
            return;
        }
        // A different train, so a new draft: a passenger list belongs to the
        // train and class it was chosen for.
        this.draft = new BookingDraft(chosen);
        this.passengerPanel = null;
        banner.showSuccess("Selected " + chosen.summary() + ". Total "
                + money(chosen.totalFare()) + ".");
        showPassengers();
    }

    /**
     * The passenger screen for the current draft.
     *
     * <p>The panel is kept across navigation, so going Back to search and
     * returning does not lose the passengers already entered.
     */
    private void showPassengers() {
        if (draft == null) {
            showSearchTrains();
            return;
        }
        select(searchButton);
        if (passengerPanel == null) {
            passengerPanel = new PassengerPanel(draft);
            passengerPanel.onBack(this::showSearchTrains);
            passengerPanel.onContinue(this::onDraftReady);
        }

        JPanel rows = column();
        rows.add(passengerPanel);
        show(rows);
    }

    /**
     * The draft passed every check: book it.
     *
     * <p>The booking runs off the event thread and nothing is drawn until the
     * transaction has committed. {@link BookingService} only returns once the
     * database has agreed, so the confirmation below cannot show a PNR that does
     * not exist - and a refusal leaves the draft intact so the traveller can fix
     * one passenger and try again.
     */
    private void onDraftReady(BookingDraft ready) {
        // A second click while the first booking is still in flight would book
        // twice: two PNRs and two berths for one form. One flag is the whole fix.
        if (booking) {
            return;
        }
        booking = true;
        banner.showInfo("Booking " + ready.selection().trainNo()
                + ". Hold on, this takes a moment...");

        new SwingWorker<ConfirmedBooking, Exception>() {
            @Override
            protected ConfirmedBooking doInBackground() throws Exception {
                return new BookingService().confirm(ready);
            }

            @Override
            protected void done() {
                booking = false;
                try {
                    showConfirmation(get());
                } catch (ExecutionException refused) {
                    onBookingRefused(refused.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /**
     * Turns a refusal into something the traveller can act on.
     *
     * <p>The wording comes from the service, not from here, so the message on
     * screen is the same one the engine refused with and the two cannot drift.
     */
    private void onBookingRefused(Throwable cause) {
        if (cause instanceof ValidationException invalid) {
            banner.showError(invalid.getMessage());
            return;
        }
        if (cause instanceof AccessDeniedException denied) {
            banner.showError(denied.getMessage());
            return;
        }
        // A SQL error is never shown: the booking service already replaces it
        // with a message that does not leak the schema, and echoing the original
        // here would undo that.
        banner.showError("The booking could not be completed. Nothing was reserved.");
    }

    /**
     * The committed booking, rendered from what the database agreed to.
     *
     * <p>No field here is computed from the draft: the PNR, the status and every
     * berth come from {@link ConfirmedBooking}, which only exists after a commit.
     *
     * <p>Package-private so {@code BookingConfirmationSelfCheck} can render a
     * committed booking without a database write. It is the one piece of this
     * class that renders a real ticket, so it is the one piece worth checking.
     */
    void showConfirmation(ConfirmedBooking booked) {
        this.draft = null;
        this.passengerPanel = null;
        select(myBookingsButton);

        JPanel rows = column();
        rows.add(Style.screenHeading("Booking confirmed"));
        gap(rows, 4);
        rows.add(Style.muted("Committed. Your berths are reserved and your PNR is "
                + booked.pnrDisplay() + "."));
        gap(rows, 16);
        rows.add(ticketCard(booked));
        gap(rows, 16);
        rows.add(berthTable(booked));
        gap(rows, 16);
        rows.add(fareCard(booked));
        if (booked.preferencesNotHonoured() > 0) {
            gap(rows, 16);
            rows.add(Style.muted(booked.preferencesNotHonoured()
                    + " passenger(s) asked for a berth position that was free in a different "
                    + "one. The granted berth is shown above as it was allocated."));
        }
        gap(rows, 20);
        show(rows);

        banner.showSuccess("PNR " + booked.pnrDisplay() + " confirmed for "
                + booked.passengerCount()
                + (booked.passengerCount() == 1 ? " passenger." : " passengers."));
    }

    /** The header a ticket would carry: who, what, when, which train. */
    private JComponent ticketCard(ConfirmedBooking booked) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        String[][] facts = {
                {"PNR", booked.pnrDisplay()},
                {"Train", booked.trainNo() + "  " + booked.trainName()},
                {"Date", booked.journeyDate().format(DATE)},
                {"Route", booked.fromStationCode() + " → " + booked.toStationCode()},
                {"Class", booked.classCode() + "  " + booked.className()},
                {"Status", booked.status().name().replace('_', ' ')}};
        for (int index = 0; index < facts.length; index++) {
            GridBagConstraints left = gridCell(index % 2);
            left.gridy = index / 2;
            GridBagConstraints right = gridCell(index % 2 + 1);
            right.gridy = index / 2;
            card.add(Style.fieldLabel(facts[index][0]), left);
            card.add(Style.fieldValue(facts[index][1]), right);
        }
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40 + ((facts.length + 1) / 2) * 44));
        return card;
    }

    /** One row per passenger: who asked, what they asked for, what they got. */
    private JComponent berthTable(ConfirmedBooking booked) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout());

        JPanel table = new JPanel(new GridBagLayout());
        table.setOpaque(false);
        table.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        String[] headers = {"Passenger", "Age", "Requested", "Coach", "Berth", "Position"};
        for (int column = 0; column < headers.length; column++) {
            JLabel label = new JLabel(headers[column]);
            label.setFont(Style.font(Font.BOLD, 11));
            label.setForeground(Style.MUTED);
            table.add(label, tableCell(0, column));
        }

        int row = 1;
        for (BerthAllocation berth : booked.passengers()) {
            String[] values = {
                    berth.passengerName(),
                    String.valueOf(berth.age()),
                    berth.requestedPreference().name().replace('_', ' '),
                    berth.coachNumber(),
                    String.valueOf(berth.berthNumber()),
                    berth.grantedPosition().name().replace('_', ' ')
                            + (berth.preferenceHonoured() ? "" : "  (fallback)")};
            for (int column = 0; column < values.length; column++) {
                JLabel label = new JLabel(values[column]);
                label.setFont(Style.font(Font.PLAIN, 12));
                label.setForeground(Style.TEXT);
                table.add(label, tableCell(row, column));
            }
            row++;
        }

        card.add(table, BorderLayout.CENTER);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40 + booked.passengers().size() * 26));
        return card;
    }

    /** The fare as charged, broken down rather than just totalled. */
    private JComponent fareCard(ConfirmedBooking booked) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new GridBagLayout());
        card.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        FareEstimate fare = booked.fare();
        String[][] lines = {
                {"Base fare", String.format("%.2f", fare.baseFare())},
                {"Distance fare (" + fare.distanceKm() + " km)",
                        String.format("%.2f", fare.distanceFare())},
                {"Per passenger", String.format("%.2f", fare.perPassenger())},
                {"Passengers", String.valueOf(fare.passengers())}};
        for (int index = 0; index < lines.length; index++) {
            GridBagConstraints left = gridCell(0);
            left.gridy = index;
            GridBagConstraints right = gridCell(1);
            right.gridy = index;
            card.add(Style.muted(lines[index][0]), left);
            card.add(Style.fieldValue(lines[index][1]), right);
        }
        GridBagConstraints totalLabel = gridCell(0);
        totalLabel.gridy = lines.length;
        GridBagConstraints totalValue = gridCell(1);
        totalValue.gridy = lines.length;
        card.add(Style.fieldLabel("Total"), totalLabel);
        card.add(Style.fieldValue(String.format("%.2f", fare.total())), totalValue);

        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40 + (lines.length + 1) * 44));
        return card;
    }

    /** The current selection, or {@code null} if the traveller has not chosen yet. */
    public TrainSelection currentSelection() {
        return selection;
    }

    /** The booking being prepared, or {@code null} before a train is chosen. */
    public BookingDraft currentDraft() {
        return draft;
    }

    /** A headline figure, rounded to whole rupees for the dashboard's summary tiles. */
    private static String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
        format.setMaximumFractionDigits(0);
        return format.format(amount);
    }

    private JPanel buildLayout() {
        JPanel root = new JPanel(new BorderLayout());
        root.add(buildSidebar(), BorderLayout.WEST);

        content.setBackground(Style.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(26, 30, 10, 30));
        root.add(content, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(Style.BACKGROUND);
        south.setBorder(BorderFactory.createEmptyBorder(0, 30, 18, 30));
        south.add(banner, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        return root;
    }

    // ----- sidebar -------------------------------------------------------

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel();
        sidebar.setBackground(Style.SIDEBAR);
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.setBorder(BorderFactory.createEmptyBorder(26, 18, 22, 18));
        sidebar.setPreferredSize(new Dimension(SIDEBAR_WIDTH, 0));
        sidebar.setMinimumSize(new Dimension(SIDEBAR_WIDTH, 0));

        JPanel identity = new JPanel();
        identity.setOpaque(false);
        identity.setLayout(new BoxLayout(identity, BoxLayout.X_AXIS));
        identity.setAlignmentX(Component.LEFT_ALIGNMENT);
        identity.add(RailwayArt.mark(34));
        identity.add(Box.createHorizontalStrut(10));
        JLabel brand = new JLabel("RailReserve");
        brand.setFont(Style.font(Font.BOLD, 16));
        brand.setForeground(Color.WHITE);
        brand.setAlignmentY(Component.CENTER_ALIGNMENT);
        identity.add(brand);
        sidebar.add(identity);

        sidebar.add(Box.createVerticalStrut(28));
        sidebar.add(navGroup(homeButton, searchButton, myBookingsButton, pnrButton));

        sidebar.add(Box.createVerticalGlue());
        sidebar.add(logoutButton);
        sidebar.add(Box.createVerticalStrut(20));
        sidebar.add(buildUserCard());

        homeButton.addActionListener(event -> showHome());
        searchButton.addActionListener(event -> showSearchTrains());
        myBookingsButton.addActionListener(event -> showMyBookings());
        pnrButton.addActionListener(event -> showPnrStatus());
        logoutButton.addActionListener(event -> logout());

        return sidebar;
    }

    private static JComponent navGroup(NavButton... buttons) {
        JPanel group = new JPanel();
        group.setOpaque(false);
        group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
        group.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (NavButton button : buttons) {
            group.add(button);
            group.add(Box.createVerticalStrut(4));
        }
        return group;
    }

    /** The signed-in traveller's name, username and role. Real, never a placeholder. */
    private JComponent buildUserCard() {
        JPanel card = new JPanel();
        card.setBackground(new Color(0x1B2E49));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));

        JLabel name = new JLabel(user.fullName());
        name.setFont(Style.font(Font.BOLD, 13));
        name.setForeground(Color.WHITE);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(name);

        card.add(Box.createVerticalStrut(4));
        JLabel detail = new JLabel(user.initials() + "  ·  @" + user.username());
        detail.setFont(Style.font(Font.PLAIN, 11));
        detail.setForeground(new Color(0x9FB6D2));
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(detail);

        card.add(Box.createVerticalStrut(2));
        JLabel role = new JLabel(SessionManager.describe(user.role()));
        role.setFont(Style.font(Font.PLAIN, 11));
        role.setForeground(new Color(0x7D93B2));
        role.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(role);

        return card;
    }

    private void select(NavButton chosen) {
        NavButton[] items = {homeButton, searchButton, myBookingsButton, pnrButton};
        for (NavButton button : items) {
            button.setChosen(button == chosen);
        }
        banner.clear();
    }

    // ----- screens -------------------------------------------------------

    private void showHome() {
        select(homeButton);

        JPanel rows = column();
        rows.add(welcomeCard());

        gap(rows, 20);
        rows.add(sectionTitle("Your travel at a glance"));
        gap(rows, 12);
        rows.add(statCards());

        if (user.isAdmin()) {
            gap(rows, 20);
            rows.add(adminCard());
        }

        gap(rows, 20);
        rows.add(sectionTitle("Recent bookings"));
        gap(rows, 12);
        rows.add(recentBookingsCard());

        show(rows);
    }

    private void showMyBookings() {
        select(myBookingsButton);

        JPanel rows = column();
        rows.add(sectionTitle("My Bookings"));
        gap(rows, 12);
        JComponent holder = slot();
        rows.add(holder);
        show(rows);

        fillLater(holder, bookings::myBookings, history -> history.isEmpty()
                        ? emptyState("You have not booked anything yet",
                                "Bookings appear here as soon as you reserve a berth. "
                                        + "Nothing is pre-filled and no sample bookings are shown.")
                        : bookingTable(history),
                "Could not read your bookings.");
    }

    private void showSearchTrains() {
        select(searchButton);

        JPanel rows = column();
        rows.add(Style.screenHeading("Search Trains"));
        gap(rows, 12);
        rows.add(searchPanel);
        show(rows);
    }

    /** A real PNR lookup, scoped to the signed-in traveller in SQL. */
    private void showPnrStatus() {
        select(pnrButton);

        JPanel rows = column();
        rows.add(sectionTitle("PNR Status"));
        gap(rows, 6);
        rows.add(pnrLookupCard());

        gap(rows, 12);
        JComponent holder = slot();
        rows.add(holder);
        holder.add(emptyState("Looking for a booking?",
                "Enter your own 10-digit PNR above. Only bookings made with this account "
                        + "can be found here."), BorderLayout.NORTH);
        show(rows);
    }

    private JComponent pnrLookupCard() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(12, 0));
        card.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        JTextField input = new JTextField();
        input.setFont(Style.font(Font.PLAIN, 14));
        input.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Style.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        JButton lookup = Style.primary("View ticket");
        lookup.addActionListener(event -> {
            String typed = input.getText().trim();
            if (typed.isEmpty()) {
                banner.showError("Enter a PNR to check.");
                return;
            }
            long pnr;
            try {
                pnr = Long.parseLong(typed);
            } catch (NumberFormatException notANumber) {
                banner.showError("A PNR is a number, usually 10 digits.");
                return;
            }
            lookup.setEnabled(false);
            new SwingWorker<Optional<BookingDetail>, Exception>() {
                @Override
                protected Optional<BookingDetail> doInBackground() throws Exception {
                    return bookings.myTicketByPnr(pnr);
                }

                @Override
                protected void done() {
                    lookup.setEnabled(true);
                    try {
                        Optional<BookingDetail> found = get();
                        if (found.isPresent()) {
                            // Same ticket the My Bookings list opens, so the two
                            // screens cannot disagree about one booking.
                            showTicket(found.get());
                        } else {
                            banner.showError("No booking found for that PNR.");
                        }
                    } catch (ExecutionException denied) {
                        banner.showError(denied.getCause() instanceof AccessDeniedException
                                ? denied.getCause().getMessage()
                                : "Could not reach the database.");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }.execute();
        });

        JLabel caption = new JLabel("Enter your PNR:");
        caption.setFont(Style.font(Font.PLAIN, 13));
        caption.setForeground(Style.MUTED);

        card.add(caption, BorderLayout.WEST);
        card.add(input, BorderLayout.CENTER);
        card.add(lookup, BorderLayout.EAST);
        return card;
    }

    // ----- the ticket ----------------------------------------------------

    /**
     * One booking, in full, with the actions available on it.
     *
     * <p>Package-private so {@code BookingManagementSelfCheck} can render it
     * against the live frame, exactly as {@link #showConfirmation} is.
     */
    void showTicket(BookingDetail ticket) {
        JPanel rows = column();
        rows.add(sectionTitle("Ticket " + ticket.pnrDisplay()));

        gap(rows, 12);
        rows.add(ticketCard(ticket));

        show(rows);

        if (ticket.cancellation() != null) {
            banner.showSuccess("Booking " + ticket.pnrDisplay() + " was cancelled. Refund "
                    + amount(ticket.cancellation().refundAmount()) + ".");
        }
    }

    private JComponent ticketCard(BookingDetail ticket) {
        JPanel rows = column();

        rows.add(ticketHeader(ticket));
        gap(rows, 12);
        rows.add(ticketPassengers(ticket));
        gap(rows, 12);
        rows.add(ticketFare(ticket));

        if (ticket.cancellation() != null) {
            gap(rows, 12);
            rows.add(ticketCancellation(ticket.cancellation()));
        }

        gap(rows, 16);
        rows.add(ticketActions(ticket));
        return rows;
    }

    private JComponent ticketHeader(BookingDetail ticket) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

        JLabel pnr = new JLabel("PNR " + ticket.pnrDisplay());
        pnr.setFont(Style.font(Font.BOLD, 18));
        pnr.setForeground(Style.TEXT);
        top.add(pnr, BorderLayout.WEST);

        JLabel status = new JLabel(ticket.status().replace('_', ' '));
        status.setFont(Style.font(Font.BOLD, 12));
        status.setForeground(ticket.bookingStatus() == com.rail.model.BookingStatus.CONFIRMED
                ? Style.SUCCESS : Style.MUTED);
        top.add(status, BorderLayout.EAST);
        card.add(top);

        gap(card, 8);
        JLabel train = new JLabel(ticket.trainNo() + "  " + escape(ticket.trainName()));
        train.setFont(Style.font(Font.PLAIN, 14));
        train.setForeground(Style.TEXT);
        train.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(train);

        gap(card, 6);
        JLabel route = new JLabel("<html><body style='width:520px'>"
                + "<b>" + escape(ticket.routeLabel()) + "</b>  "
                + escape(ticket.fromStationName()) + " to " + escape(ticket.toStationName())
                + "<br>" + ticket.journeyDate().format(DATE)
                + " &nbsp;·&nbsp; " + escape(ticket.className()) + " (" + ticket.classCode() + ")"
                + " &nbsp;·&nbsp; " + ticket.passengerCount()
                + (ticket.passengerCount() == 1 ? " passenger" : " passengers")
                + "</body></html>");
        route.setFont(Style.font(Font.PLAIN, 13));
        route.setForeground(Style.MUTED);
        route.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(route);
        return card;
    }

    private JComponent ticketPassengers(BookingDetail ticket) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));

        JPanel table = new JPanel(new GridBagLayout());
        table.setOpaque(false);

        String[] headers = {"Passenger", "Age", "Gender", "Coach / berth", "Fare", "Status"};
        for (int column = 0; column < headers.length; column++) {
            JLabel label = new JLabel(headers[column]);
            label.setFont(Style.font(Font.BOLD, 11));
            label.setForeground(Style.MUTED);
            table.add(label, tableCell(0, column));
        }

        int row = 1;
        for (BookingDetail.Passenger passenger : ticket.passengers()) {
            String[] values = {
                    escape(passenger.fullName()) + (passenger.primary() ? "  (contact)" : ""),
                    String.valueOf(passenger.age()),
                    passenger.gender().toLowerCase(Locale.ROOT),
                    passenger.coachNumber() + " / " + passenger.berthNumber()
                            + "  " + passenger.position().toLowerCase(Locale.ROOT),
                    amount(passenger.amount()),
                    passenger.reservationStatus().replace('_', ' ')};
            for (int column = 0; column < values.length; column++) {
                JLabel label = new JLabel(values[column]);
                label.setFont(Style.font(Font.PLAIN, 12));
                label.setForeground(Style.TEXT);
                table.add(label, tableCell(row, column));
            }
            row++;
        }

        card.add(table, BorderLayout.CENTER);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                40 + ticket.passengers().size() * 26));
        return card;
    }

    private JComponent ticketFare(BookingDetail ticket) {
        return keyValueCard(List.of(
                new String[]{"Total fare paid", amount(ticket.totalFare())},
                new String[]{"Passengers", String.valueOf(ticket.passengerCount())}));
    }

    private JComponent ticketCancellation(BookingDetail.Cancellation cancellation) {
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[]{"Cancelled on",
                cancellation.cancelledAt() == null ? "-"
                        : cancellation.cancelledAt().format(DateTimeFormatter
                        .ofPattern("d MMM yyyy, HH:mm"))});
        if (cancellation.reason() != null && !cancellation.reason().isBlank()) {
            lines.add(new String[]{"Reason", escape(cancellation.reason())});
        }
        lines.add(new String[]{"Refund", amount(cancellation.refundAmount())});
        lines.add(new String[]{"Cancellation charges", amount(cancellation.cancellationCharges())});
        lines.add(new String[]{"Refund status",
                cancellation.refundStatus().replace('_', ' ')});
        if (!cancellation.refundable()) {
            lines.add(new String[]{"Note",
                    "No refund was due: this journey had already departed."});
        }
        return keyValueCard(lines);
    }

    private JComponent ticketActions(BookingDetail ticket) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton back = Style.secondary("Back to My Bookings");
        back.addActionListener(event -> showMyBookings());
        row.add(back);

        if (ticket.cancellable()) {
            row.add(Box.createHorizontalStrut(10));
            JButton cancel = dangerButton("Cancel this booking");
            cancel.addActionListener(event -> confirmCancellation(ticket));
            row.add(cancel);
        } else {
            row.add(Box.createHorizontalStrut(10));
            JLabel blocked = Style.muted(ticket.cancellationBlocker() == null
                    ? "This booking cannot be cancelled."
                    : ticket.cancellationBlocker());
            blocked.setFont(Style.font(Font.PLAIN, 12));
            row.add(blocked);
        }
        return row;
    }

    /**
     * Quote, confirm, cancel - in that order, with the passenger agreeing to the
     * numbers first.
     *
     * <p>The quote is read on a worker thread, then the dialog shows exactly what
     * the service will record. {@code CancellationService} recalculates inside its
     * own transaction regardless, so a fare that changed in between shows up in
     * the row rather than being quietly honoured.
     */
    private void confirmCancellation(BookingDetail ticket) {
        new SwingWorker<CancellationService.Quote, Exception>() {
            @Override
            protected CancellationService.Quote doInBackground() throws Exception {
                return cancellations.quote(ticket.pnr());
            }

            @Override
            protected void done() {
                try {
                    askToConfirm(get());
                } catch (ExecutionException refused) {
                    banner.showError(refused.getCause() instanceof CancellationService
                            .CancellationException
                            ? refused.getCause().getMessage()
                            : "Could not reach the database.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private void askToConfirm(CancellationService.Quote quote) {
        RefundPolicy.Outcome outcome = quote.outcome();

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JLabel heading = new JLabel("<html><body style='width:430px'><b>Cancel booking "
                + quote.ticket().pnrDisplay() + "?</b><br>"
                + escape(quote.policy()) + "</body></html>");
        heading.setFont(Style.font(Font.PLAIN, 13));
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(heading);

        body.add(Box.createVerticalStrut(12));
        body.add(keyValueCard(List.of(
                new String[]{"Fare paid", amount(quote.ticket().totalFare())},
                new String[]{"Refund", amount(outcome.refundAmount())},
                new String[]{"Charges kept", amount(outcome.charges())})));

        body.add(Box.createVerticalStrut(12));
        JLabel reasonCaption = Style.muted("Reason (optional):");
        reasonCaption.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(reasonCaption);
        body.add(Box.createVerticalStrut(4));
        JTextField reason = new JTextField();
        reason.setAlignmentX(Component.LEFT_ALIGNMENT);
        reason.setMaximumSize(new Dimension(440, 30));
        body.add(reason);

        int answer = JOptionPane.showConfirmDialog(this, body,
                "Cancel booking " + quote.ticket().pnrDisplay() + "?",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            banner.showInfo("Nothing was changed. Booking "
                    + quote.ticket().pnrDisplay() + " is still confirmed.");
            return;
        }
        runCancellation(quote.ticket().pnr(), reason.getText());
    }

    private void runCancellation(long pnr, String reason) {
        banner.showInfo("Cancelling booking " + String.format("%010d", pnr) + "...");
        new SwingWorker<BookingDetail, Exception>() {
            @Override
            protected BookingDetail doInBackground() throws Exception {
                return cancellations.cancel(pnr, reason);
            }

            @Override
            protected void done() {
                try {
                    BookingDetail cancelled = get();
                    banner.showSuccess("Booking " + cancelled.pnrDisplay() + " cancelled. Refund "
                            + amount(cancelled.cancellation().refundAmount())
                            + ", cancellation charges "
                            + amount(cancelled.cancellation().cancellationCharges())
                            + ". The berths are available again.");
                    showTicket(cancelled);
                } catch (ExecutionException refused) {
                    banner.showError(refused.getCause() instanceof CancellationService
                            .CancellationException
                            ? refused.getCause().getMessage()
                            : "Could not cancel that booking. Nothing was changed.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    /** Two-column key/value card. Rows are {@code {label, value}} pairs. */
    private static JComponent keyValueCard(List<String[]> rows) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(14, 20, 14, 20));
        for (String[] row : rows) {
            JPanel line = new JPanel(new BorderLayout(12, 0));
            line.setOpaque(false);
            line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
            JLabel label = new JLabel(row[0]);
            label.setFont(Style.font(Font.PLAIN, 12));
            label.setForeground(Style.MUTED);
            line.add(label, BorderLayout.WEST);
            JLabel value = new JLabel(row[1]);
            value.setFont(Style.font(Font.PLAIN, 13));
            value.setForeground(Style.TEXT);
            line.add(value, BorderLayout.EAST);
            card.add(line);
        }
        return card;
    }

    /**
     * Money at full precision, matching how {@link #fareCard} prints a fare.
     *
     * <p>Distinct from {@link #money}, which rounds for the dashboard's headline
     * figures. A refund is a ledger value, so it is shown to the paisa rather than
     * rounded into a number the database never stored.
     */
    private static String amount(BigDecimal figure) {
        return figure == null ? "-" : String.format("%.2f", figure);
    }

    /** A secondary button in the error colour: destructive, but not the primary action. */
    private static JButton dangerButton(String text) {
        JButton button = Style.secondary(text);
        button.setForeground(Style.ERROR);
        return button;
    }

    private void logout() {
        auth.logout();
        LoginFrame login = new LoginFrame();
        login.setVisible(true);
        dispose();
    }

    // ----- home sections -------------------------------------------------

    private JComponent welcomeCard() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 152));

        JPanel text = column();
        JLabel hello = new JLabel("Welcome back, " + escape(user.firstName()));
        hello.setFont(Style.font(Font.BOLD, 22));
        hello.setForeground(Style.TEXT);
        text.add(hello);
        gap(text, 6);
        JLabel sub = new JLabel("<html><body style='width:420px'>" + welcomeSentence() + "</body></html>");
        sub.setFont(Style.font(Font.PLAIN, 13));
        sub.setForeground(Style.MUTED);
        text.add(sub);

        card.add(text, BorderLayout.CENTER);
        card.add(RailwayArt.locomotive(), BorderLayout.EAST);
        return card;
    }

    /** Greeting text built from the session only - no invented activity. */
    private String welcomeSentence() {
        if (user.isAdmin()) {
            return "You are signed in as an administrator. The figures on this page "
                    + "cover every account, not just your own journeys.";
        }
        return "Signed in as <b>@" + escape(user.username()) + "</b>. Search a train, "
                + "reserve a berth, and keep your PNR to hand.";
    }

    /**
     * The headline figures, all counted from {@code bookings}.
     *
     * <p>A brand new account shows zeroes. That is the truth, not a failure.
     */
    private JComponent statCards() {
        JComponent holder = slot();
        fillLater(holder, () -> new int[]{bookings.myBookingCount(), bookings.myActiveBookingCount()},
                counts -> {
                    JPanel row = new JPanel(new GridBagLayout());
                    row.setOpaque(false);
                    row.setAlignmentX(Component.LEFT_ALIGNMENT);
                    row.add(statCard("Total bookings", String.valueOf(counts[0])), gridCell(0));
                    row.add(statCard("Active bookings", String.valueOf(counts[1])), gridCell(1));
                    row.add(statCard("Role", SessionManager.describe(user.role())), gridCell(2));
                    return row;
                },
                "Could not read your bookings.");
        return holder;
    }

    /** Administrator-only figures. The service re-checks the role on every call. */
    private JComponent adminCard() {
        JComponent holder = slot();
        fillLater(holder, () -> auth.adminAllBookingsCount(), count -> adminCard("Bookings across all accounts: " + count + "."),
                "Could not read administrative figures.");
        return holder;
    }

    private JComponent adminCard(String detail) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, Style.PRIMARY),
                BorderFactory.createEmptyBorder(16, 20, 16, 20)));

        JPanel text = column();
        JLabel title = new JLabel("Administration");
        title.setFont(Style.font(Font.BOLD, 14));
        title.setForeground(Style.TEXT);
        text.add(title);
        gap(text, 6);
        JLabel body = new JLabel(detail);
        body.setFont(Style.font(Font.PLAIN, 12));
        body.setForeground(Style.MUTED);
        text.add(body);

        card.add(text, BorderLayout.WEST);

        JButton open = Style.secondary("Open administration");
        open.addActionListener(event -> openAdministration());
        card.add(open, BorderLayout.EAST);
        return card;
    }

    /**
     * Opens the administration window.
     *
     * <p>Only reachable from the administrator-only card, and the window checks
     * the role again on construction, so the button is a convenience rather than
     * the thing that decides.
     */
    private void openAdministration() {
        try {
            new AdminFrame().setVisible(true);
        } catch (AccessDeniedException refused) {
            banner.showError(refused.getMessage());
        }
    }

    private JComponent recentBookingsCard() {
        JComponent holder = slot();
        fillLater(holder, bookings::myRecentBookings, recent -> recent.isEmpty()
                        ? emptyState("No bookings yet",
                                "Once you reserve a berth, your journeys will be listed here.")
                        : bookingTable(recent),
                "Could not read your bookings.");
        return holder;
    }

    // ----- building blocks ----------------------------------------------

    /**
     * Fills a component from the database without blocking the event thread.
     *
     * <p>The slot shows a loading caption immediately and is repopulated when the
     * query returns, so no JDBC ever runs inside a Swing event handler and the
     * window keeps repainting while the read is in flight.
     */
    private <T> void fillLater(JComponent slot, Callable<T> query,
                               Function<T, JComponent> render, String failureMessage) {
        slot.removeAll();
        JLabel loading = Style.muted("Loading...");
        slot.add(loading, BorderLayout.NORTH);
        new SwingWorker<T, Exception>() {
            @Override
            protected T doInBackground() throws Exception {
                return query.call();
            }

            @Override
            protected void done() {
                slot.removeAll();
                try {
                    slot.add(render.apply(get()), BorderLayout.NORTH);
                } catch (ExecutionException | InterruptedException failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    slot.add(emptyState("Nothing to show", failureMessage), BorderLayout.NORTH);
                } catch (RuntimeException failure) {
                    slot.add(emptyState("Nothing to show", failureMessage), BorderLayout.NORTH);
                }
                revalidate();
                repaint();
            }
        }.execute();
    }

    /** A transparent, full-width slot for {@link #fillLater}. */
    private static JComponent slot() {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setAlignmentX(Component.LEFT_ALIGNMENT);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return holder;
    }

    private void show(JPanel rows) {
        gap(rows, 24);
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(Style.BACKGROUND);
        page.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 10));
        page.add(rows, BorderLayout.NORTH);

        content.removeAll();
        content.add(Style.scroll(page), BorderLayout.CENTER);
        content.revalidate();
        content.repaint();
    }

    private static JPanel column() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static void gap(JPanel target, int height) {
        target.add(Box.createVerticalStrut(height));
    }

    private static GridBagConstraints gridCell(int column) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, column == 0 ? 0 : 8, 0, 8);
        return c;
    }

    private static GridBagConstraints tableCell(int row, int column) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(0, 0, row == 0 ? 10 : 6, 20);
        return c;
    }

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Style.font(Font.BOLD, 16));
        label.setForeground(Style.TEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private static JComponent statCard(String caption, String figure) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

        JLabel value = new JLabel(figure);
        value.setFont(Style.font(Font.BOLD, 26));
        value.setForeground(Style.TEXT);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(value);

        gap(card, 2);
        JLabel label = new JLabel(caption);
        label.setFont(Style.font(Font.PLAIN, 12));
        label.setForeground(Style.MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(label);
        return card;
    }

    /**
     * A rounded white panel of real rows, with a ticket button on each.
     *
     * <p>An instance method, unlike the rest of this class's builders, because each
     * row's button has to know how to open a ticket. The button loads by PNR on a
     * worker thread, so the list is useful without any pre-loading: no ticket is
     * read until somebody asks for it.
     */
    private JComponent bookingTable(List<BookingSummary> rows) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout());

        JPanel table = new JPanel(new GridBagLayout());
        table.setOpaque(false);
        table.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        String[] headers = {"PNR", "Train", "Journey", "Route", "Class", "Status", "Fare", ""};
        for (int column = 0; column < headers.length; column++) {
            JLabel label = new JLabel(headers[column]);
            label.setFont(Style.font(Font.BOLD, 11));
            label.setForeground(Style.MUTED);
            table.add(label, tableCell(0, column));
        }

        int row = 1;
        for (BookingSummary booking : rows) {
            String[] values = {
                    booking.pnrDisplay(),
                    booking.trainNo() + "  " + booking.trainName(),
                    booking.journeyDate() == null ? "-" : booking.journeyDate().format(DATE),
                    booking.fromStation() + " → " + booking.toStation(),
                    booking.travelClass(),
                    booking.status().replace('_', ' '),
                    String.format("%.2f", booking.totalFare())};
            for (int column = 0; column < values.length; column++) {
                JLabel label = new JLabel(values[column]);
                label.setFont(Style.font(Font.PLAIN, 12));
                label.setForeground(Style.TEXT);
                table.add(label, tableCell(row, column));
            }

            JButton open = Style.secondary("Ticket");
            open.addActionListener(event -> openTicket(booking.pnr()));
            table.add(open, tableCell(row, values.length));
            row++;
        }

        card.add(table, BorderLayout.CENTER);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40 + rows.size() * 26));
        return card;
    }

    /** Loads one ticket by PNR, then opens it. One place, so both callers behave the same. */
    private void openTicket(long pnr) {
        new SwingWorker<Optional<BookingDetail>, Exception>() {
            @Override
            protected Optional<BookingDetail> doInBackground() throws Exception {
                return bookings.myTicketByPnr(pnr);
            }

            @Override
            protected void done() {
                try {
                    Optional<BookingDetail> found = get();
                    if (found.isPresent()) {
                        showTicket(found.get());
                    } else {
                        banner.showError("No booking found for that PNR.");
                    }
                } catch (ExecutionException failure) {
                    banner.showError(failure.getCause() instanceof AccessDeniedException
                            ? failure.getCause().getMessage()
                            : "Could not read that booking.");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private static JComponent emptyState(String title, String detail) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));

        JPanel text = column();
        JLabel heading = new JLabel(title);
        heading.setFont(Style.font(Font.BOLD, 15));
        heading.setForeground(Style.TEXT);
        text.add(heading);

        gap(text, 6);
        JLabel body = new JLabel("<html><body style='width:470px'>" + detail + "</body></html>");
        body.setFont(Style.font(Font.PLAIN, 13));
        body.setForeground(Style.MUTED);
        text.add(body);

        card.add(text, BorderLayout.CENTER);
        return card;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
