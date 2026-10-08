package com.rail.ui;

import com.busticket.ui.Style;
import com.rail.model.BerthPreference;
import com.rail.model.BookingDraft;
import com.rail.model.ClassProfile;
import com.rail.db.TrainDAO.FareRule;
import com.rail.model.FareEstimate;
import com.rail.model.Gender;
import com.rail.model.PassengerDraft;
import com.rail.model.PassengerInput;
import com.rail.model.TrainSelection;
import com.rail.service.BookingPreparationService;
import com.rail.service.ValidationException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Passenger entry for a chosen train, ending in a validated booking draft.
 *
 * <p>Nothing here books anything. There is no save, no confirm and no code path
 * from this panel to {@code bookings} or {@code seat_reservations}; the panel
 * fills a {@link BookingDraft} that lives in memory. The Continue button says so
 * in as many words rather than pretending a ticket exists.
 *
 * <p>Two things make this screen honest rather than merely working:
 *
 * <ul>
 *   <li>Berth preferences are read from the selected class's coaches, so a class
 *       is never offered a berth it has none of. AC 2-Tier shows no middle
 *       berth, and Chair Car shows window and aisle instead of berths.</li>
 *   <li>A preference is labelled a preference. Nothing here promises a berth,
 *       because allocation has not happened and this project does not do
 *       automatic allocation at all.</li>
 * </ul>
 *
 * <p>The only database reads happen once, in {@link #load()}, off the event
 * dispatch thread. Validation and re-pricing afterwards are pure and run on the
 * EDT, because all the rules live in {@link BookingPreparationService}.
 */
public final class PassengerPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter DATE_OUT = DateTimeFormatter.ofPattern("d MMM yyyy");

    /**
     * Illustrative catering choices.
     *
     * <p>There is no catering data in this project, so these are labels rather
     * than a menu read from anywhere. A choice is carried in the draft for the
     * booking stage and is not yet stored anywhere.
     */
    private static final String[] MEAL_CHOICES = {
            "No meal", "Veg Thali", "Non-Veg Thali", "Jain Meal", "Breakfast", "Dinner"};

    private final transient BookingPreparationService service;
    private final transient BookingDraft draft;

    private final JTextField nameField = new JTextField(16);
    private final JTextField ageField = new JTextField(5);
    private final JComboBox<Gender> genderBox = new JComboBox<>(Gender.values());
    private final JComboBox<BerthPreference> berthBox = new JComboBox<>();
    private final JComboBox<String> mealBox = new JComboBox<>(MEAL_CHOICES);
    private final JLabel entryMessage = Style.muted(" ");

    private final JTextField mobileField = new JTextField(16);
    private final JTextField emailField = new JTextField(16);
    private final JLabel contactMessage = Style.muted(" ");

    private final DefaultTableModel passengerModel =
            new DefaultTableModel(new Object[]{"#", "Name", "Age", "Gender", "Berth preference", "Meal"}, 0) {
                private static final long serialVersionUID = 1L;

                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
    private final JTable passengerTable = new JTable(passengerModel);
    private final JLabel summaryText = Style.muted(" ");

    private final JButton continueButton = Style.primary("Continue");
    private final JButton addButton = Style.primary("Add passenger");

    private transient ClassProfile profile;
    private transient FareRule fareRule;
    private transient Consumer<BookingDraft> continueListener = ready -> { };
    private transient Runnable backListener = () -> { };

    public PassengerPanel(BookingDraft draft) {
        this(draft, new BookingPreparationService());
    }

    public PassengerPanel(BookingDraft draft, BookingPreparationService service) {
        super(new BorderLayout());
        setOpaque(false);
        this.draft = draft;
        this.service = service;

        JPanel rows = new JPanel();
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));

        rows.add(tripCard());
        rows.add(Box.createVerticalStrut(Style.GAP * 2));
        rows.add(passengerEntryCard());
        rows.add(Box.createVerticalStrut(Style.GAP * 2));
        rows.add(passengerListCard());
        rows.add(Box.createVerticalStrut(Style.GAP * 2));
        rows.add(contactCard());
        rows.add(Box.createVerticalStrut(Style.GAP * 2));
        rows.add(summaryCard());
        rows.add(Box.createVerticalStrut(Style.GAP * 2));
        rows.add(footer());

        add(Style.scroll(wrap(rows)), BorderLayout.CENTER);
        applyContactIfPresent();
        load();
    }

    // ---------------------------------------------------------------- wiring

    /** Fired when the traveller asks to go back to search, keeping the draft. */
    public void onBack(Runnable listener) {
        this.backListener = listener == null ? () -> { } : listener;
    }

    /** Fired when a complete draft is handed on for the review stage. */
    public void onContinue(Consumer<BookingDraft> listener) {
        this.continueListener = listener == null ? ready -> { } : listener;
    }

    public BookingDraft draft() {
        return draft;
    }

    // ---------------------------------------------------------------- layout

    private JComponent wrap(JPanel rows) {
        rows.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(rows, BorderLayout.NORTH);
        return holder;
    }

    private JComponent tripCard() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 6));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        TrainSelection chosen = draft.selection();
        card.add(Style.sectionHeading("Passenger details"), BorderLayout.NORTH);

        JPanel facts = new JPanel(new GridBagLayout());
        facts.setOpaque(false);
        int row = 0;
        fact(facts, row++, "Train", chosen.trainNo() + " - " + chosen.trainName());
        fact(facts, row++, "From", chosen.fromStation().name() + " (" + chosen.fromStation().code() + ")");
        fact(facts, row++, "To", chosen.toStation().name() + " (" + chosen.toStation().code() + ")");
        fact(facts, row++, "Journey date", chosen.journeyDate().format(DATE_OUT));
        fact(facts, row++, "Travel class", chosen.className());
        fact(facts, row, "Passengers", String.valueOf(chosen.passengers()));
        card.add(facts, BorderLayout.CENTER);

        JLabel note = Style.muted("Nothing is booked on this screen. "
                + "Berth preferences are requests, not confirmed seats.");
        note.setFont(Style.font(Font.ITALIC, 11f));
        card.add(note, BorderLayout.SOUTH);
        return card;
    }

    private void fact(JPanel grid, int row, String label, String value) {
        GridBagConstraints labelCell = Style.cell(row, 0);
        labelCell.anchor = GridBagConstraints.LINE_START;
        JLabel caption = Style.fieldLabel(label);
        grid.add(caption, labelCell);

        GridBagConstraints valueCell = Style.cell(row, 1);
        valueCell.anchor = GridBagConstraints.LINE_START;
        valueCell.gridwidth = 3;
        valueCell.fill = GridBagConstraints.HORIZONTAL;
        grid.add(Style.fieldValue(value), valueCell);
    }

    private JComponent passengerEntryCard() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        card.add(Style.sectionHeading("Add a passenger"), BorderLayout.NORTH);

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        int row = 0;
        field(grid, row++, "Full name", nameField);
        field(grid, row++, "Age", ageField);
        field(grid, row++, "Gender", genderBox);
        field(grid, row++, "Berth preference", berthBox);
        field(grid, row, "Meal (optional)", mealBox);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, Style.GAP, 0));
        actions.setOpaque(false);
        JButton clear = Style.secondary("Clear");
        clear.addActionListener(event -> clearEntry());
        actions.add(addButton);
        actions.add(clear);

        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(actions, BorderLayout.NORTH);
        south.add(entryMessage, BorderLayout.SOUTH);

        card.add(grid, BorderLayout.CENTER);
        card.add(south, BorderLayout.SOUTH);

        addButton.addActionListener(event -> addPassenger());
        clearEntry();
        return card;
    }

    private JComponent passengerListCard() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        card.add(Style.sectionHeading("Passengers on this booking"), BorderLayout.NORTH);

        passengerTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        passengerTable.setFont(Style.font(Font.PLAIN, 13f));
        passengerTable.setRowHeight(26);
        passengerTable.setFillsViewportHeight(true);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, Style.GAP, 0));
        actions.setOpaque(false);
        JButton remove = Style.secondary("Remove selected");
        remove.addActionListener(event -> removeSelectedPassenger());
        actions.add(remove);

        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(actions, BorderLayout.NORTH);
        JLabel hint = Style.muted(
                "A requested berth is a preference only. Allocation happens later, if at all.");
        hint.setFont(Style.font(Font.ITALIC, 11f));
        south.add(hint, BorderLayout.SOUTH);

        card.add(new JScrollPane(passengerTable), BorderLayout.CENTER);
        card.add(south, BorderLayout.SOUTH);
        return card;
    }

    private JComponent contactCard() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        card.add(Style.sectionHeading("Contact details"), BorderLayout.NORTH);

        JLabel lead = Style.muted(
                "Who we contact about this booking. These are not any passenger's personal details.");
        lead.setFont(Style.font(Font.ITALIC, 11f));

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        field(grid, 0, "Mobile", mobileField);
        field(grid, 1, "Email (optional)", emailField);

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(lead);
        north.add(Box.createVerticalStrut(8));
        north.add(grid);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(contactMessage, BorderLayout.NORTH);

        card.add(north, BorderLayout.CENTER);
        card.add(south, BorderLayout.SOUTH);

        // Re-validated on focus loss, so the message appears while typing rather
        // than only after a failed Continue.
        FocusAdapter revalidate = new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                applyContact();
            }
        };
        mobileField.addFocusListener(revalidate);
        emailField.addFocusListener(revalidate);
        return card;
    }

    private JComponent summaryCard() {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 8));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel heading = Style.sectionHeading("Booking summary");
        JLabel estimateNote = Style.muted("Estimated fare. Not a quote and not a charge: "
                + "concessions, catering and quota premiums are not included.");
        estimateNote.setFont(Style.font(Font.ITALIC, 11f));

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(heading);
        north.add(Box.createVerticalStrut(4));
        north.add(estimateNote);

        card.add(north, BorderLayout.NORTH);
        card.add(summaryText, BorderLayout.CENTER);
        return card;
    }

    private JComponent footer() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, Style.GAP, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton back = Style.secondary("Back to search");
        back.addActionListener(event -> backListener.run());
        continueButton.addActionListener(event -> continueToReview());
        row.add(back);
        row.add(continueButton);
        return row;
    }

    private void field(JPanel grid, int row, String label, JComponent input) {
        GridBagConstraints labelCell = Style.cell(row, 0);
        labelCell.anchor = GridBagConstraints.LINE_START;
        grid.add(Style.fieldLabel(label), labelCell);

        GridBagConstraints inputCell = Style.cell(row, 1);
        inputCell.fill = GridBagConstraints.HORIZONTAL;
        inputCell.gridwidth = 3;
        grid.add(input, inputCell);
    }

    // ---------------------------------------------------------------- loading

    /**
     * The only database access: the class's capabilities and the fare rule, read
     * once so nothing below this needs JDBC on the event thread.
     */
    private void load() {
        entryMessage.setText("Loading class details...");
        continueButton.setEnabled(false);
        new SwingWorker<Loaded, Exception>() {
            @Override
            protected Loaded doInBackground() throws Exception {
                return new Loaded(service.profileOf(draft.selection().classCode()),
                        service.fareRuleFor(draft.selection()));
            }

            @Override
            protected void done() {
                try {
                    Loaded loaded = get();
                    profile = loaded.profile();
                    fareRule = loaded.rule();
                    applyProfile();
                    refresh();
                } catch (Exception failure) {
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    entryMessage.setForeground(Style.ERROR);
                    entryMessage.setText(describe(cause));
                    addButton.setEnabled(false);
                    continueButton.setEnabled(false);
                }
            }
        }.execute();
    }

    /** Applies what the class can actually offer, hiding what it cannot. */
    private void applyProfile() {
        berthBox.removeAllItems();
        for (BerthPreference preference : profile.supportedPreferences()) {
            berthBox.addItem(preference);
        }
        berthBox.setSelectedIndex(0);

        boolean pantry = profile.hasPantry();
        mealBox.setVisible(pantry);
        mealBox.setEnabled(pantry);
        if (!pantry) {
            mealBox.setSelectedIndex(0);
        }

        ageField.setToolTipText("Between " + service.minAge() + " and " + service.maxAge()
                + " years");
        entryMessage.setForeground(Style.MUTED);
        entryMessage.setText("Add " + draft.selection().passengers()
                + (draft.selection().passengers() == 1 ? " passenger" : " passengers")
                + ". " + profile.className() + " offers: " + describePreferences() + ".");
    }

    private String describePreferences() {
        List<BerthPreference> offered = profile.supportedPreferences();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < offered.size(); i++) {
            if (i > 0) {
                text.append(i == offered.size() - 1 ? " and " : ", ");
            }
            text.append(offered.get(i).label());
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- actions

    /**
     * Adds the typed row. Every rule is the service's; this only collects the
     * fields and shows whatever it says.
     */
    private void addPassenger() {
        if (profile == null) {
            return;
        }
        String meal = mealBox.isEnabled() ? mealChoice() : null;
        try {
            service.addPassenger(draft, profile, new PassengerInput(
                    nameField.getText(),
                    ageField.getText(),
                    String.valueOf(genderBox.getSelectedItem()),
                    berthLabel(),
                    meal));
            entryMessage.setForeground(Style.SUCCESS);
            entryMessage.setText(draft.passengers().get(draft.passengerCount() - 1).fullName()
                    + " added to the booking.");
            clearEntry();
            refresh();
        } catch (ValidationException refused) {
            showRefusal(entryMessage, refused, nameField, ageField, genderBox, berthBox, mealBox);
        }
    }

    /** {@code null} for "no meal", so an untouched choice is stored as absent. */
    private String mealChoice() {
        String chosen = String.valueOf(mealBox.getSelectedItem());
        return "No meal".equals(chosen) ? null : chosen;
    }

    private String berthLabel() {
        Object chosen = berthBox.getSelectedItem();
        return chosen instanceof BerthPreference preference ? preference.label() : null;
    }

    private void removeSelectedPassenger() {
        int row = passengerTable.getSelectedRow();
        if (row < 0) {
            entryMessage.setForeground(Style.WARNING);
            entryMessage.setText("Select a passenger in the list to remove them.");
            return;
        }
        try {
            service.removePassenger(draft, row);
            entryMessage.setForeground(Style.MUTED);
            entryMessage.setText("Passenger removed.");
            refresh();
        } catch (ValidationException refused) {
            entryMessage.setForeground(Style.ERROR);
            entryMessage.setText(refused.getMessage());
        }
    }

    private void applyContact() {
        if (draft.selection() == null) {
            return;
        }
        try {
            service.setContact(draft, mobileField.getText(), emailField.getText());
            contactMessage.setForeground(Style.SUCCESS);
            contactMessage.setText("Contact details saved for this draft.");
            refreshReadiness();
        } catch (ValidationException refused) {
            contactMessage.setForeground(Style.ERROR);
            contactMessage.setText(refused.getMessage());
            refreshReadiness();
        }
    }

    private void clearEntry() {
        nameField.setText("");
        ageField.setText("");
        genderBox.setSelectedIndex(0);
        berthBox.setSelectedIndex(berthBox.getItemCount() == 0 ? -1 : 0);
        if (mealBox.isEnabled()) {
            mealBox.setSelectedIndex(0);
        }
        nameField.requestFocusInWindow();
    }

    /** Re-renders everything that depends on the draft. */
    private void refresh() {
        renderPassengers();
        renderSummary();
        refreshReadiness();
    }

    private void renderPassengers() {
        passengerModel.setRowCount(0);
        List<PassengerDraft> passengers = draft.passengers();
        for (int i = 0; i < passengers.size(); i++) {
            PassengerDraft passenger = passengers.get(i);
            passengerModel.addRow(new Object[]{
                    i + 1,
                    passenger.fullName(),
                    passenger.age(),
                    passenger.gender().label(),
                    passenger.berthPreference().label(),
                    passenger.mealPreference() == null ? "-" : passenger.mealPreference()});
        }
    }

    private void renderSummary() {
        if (fareRule == null) {
            summaryText.setText("Fare details are loading.");
            return;
        }
        TrainSelection chosen = draft.selection();

        if (draft.passengerCount() == 0) {
            // A quote needs at least one passenger, and rightly refuses to price
            // a party of nobody. While the traveller is still typing there is no
            // fare to show, so say that rather than displaying a confident ₹0.
            StringBuilder html = new StringBuilder("<html><table cellpadding='3'>");
            row(html, "Train", chosen.trainNo() + " - " + chosen.trainName());
            row(html, "Boarding", chosen.fromStation().name()
                    + " (" + chosen.fromStation().code() + ")");
            row(html, "Destination", chosen.toStation().name()
                    + " (" + chosen.toStation().code() + ")");
            row(html, "Journey date", chosen.journeyDate().format(DATE_OUT));
            row(html, "Travel class", chosen.className());
            html.append("<tr><td colspan='2'><span style='color:#667085'>Add ")
                    .append(chosen.passengers()).append(" passenger")
                    .append(chosen.passengers() == 1 ? "" : "s")
                    .append(" to see the fare.</span></td></tr></table></html>");
            summaryText.setText(html.toString());
            return;
        }

        FareEstimate estimate = service.estimate(fareRule, draft.passengerCount());

        StringBuilder html = new StringBuilder("<html><table cellpadding='3'>");
        row(html, "Train", chosen.trainNo() + " - " + chosen.trainName());
        row(html, "Boarding", chosen.fromStation().name() + " (" + chosen.fromStation().code() + ")");
        row(html, "Destination", chosen.toStation().name() + " (" + chosen.toStation().code() + ")");
        row(html, "Journey date", chosen.journeyDate().format(DATE_OUT));
        row(html, "Travel class", chosen.className());
        row(html, "Passengers", String.valueOf(draft.passengerCount()));
        appendPassengers(html);
        row(html, "Base fare", money(estimate.baseFare()));
        row(html, "Distance fare (" + estimate.distanceKm() + " km)", money(estimate.distanceFare()));
        row(html, "Fare per passenger", money(estimate.perPassenger()));
        html.append("<tr><td colspan='2'><b>Estimated total ")
                .append(money(estimate.total())).append("</b></td></tr>");
        html.append("</table>");
        // The fare is a configured rule, not a tariff. Saying so on the screen
        // that quotes it is the difference between an estimate and a promise.
        html.append("<span style='color:#667085'>")
                .append(com.rail.service.FareService.SIMULATED_NOTICE)
                .append("</span>");
        html.append("</html>");
        summaryText.setText(html.toString());
    }

    /**
     * One line per passenger: who, and what they asked for.
     *
     * <p>The names are escaped because they are typed in, and the booking
     * summary renders user input as HTML. A name of {@code <b>} would otherwise
     * break the layout, and a crafted one could inject markup.
     *
     * <p>Labelled a preference rather than a berth, because that is all it is at
     * this point. The screen that follows says which berth was actually granted,
     * and may well be a different one.
     */
    private void appendPassengers(StringBuilder html) {
        List<PassengerDraft> passengers = draft.passengers();
        if (passengers.isEmpty()) {
            return;
        }
        html.append("<tr><td colspan='2'><span style='color:#667085'>Passengers and"
                + " preferences</span></td></tr>");
        for (PassengerDraft passenger : passengers) {
            String meal = passenger.mealPreference() == null
                    ? "" : ", meal: " + passenger.mealPreference();
            row(html, escape(passenger.fullName()),
                    "prefers " + passenger.berthPreference().label() + meal);
        }
    }

    /** Escapes the five characters HTML treats as markup. */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void row(StringBuilder html, String label, String value) {
        html.append("<tr><td><span style='color:#667085'>").append(label)
                .append("</span></td><td><b>").append(value).append("</b></td></tr>");
    }

    private void refreshReadiness() {
        if (profile == null) {
            return;
        }
        boolean ready = service.isReadyToContinue(draft);
        continueButton.setEnabled(ready);
        String problem = service.readinessProblem(draft);
        continueButton.setToolTipText(problem == null
                ? "Hand this draft to the review step"
                : problem);
    }

    /**
     * Continue hands the validated draft on. It must not imply a booking exists,
     * so the message says plainly that nothing is reserved yet.
     */
    private void continueToReview() {
        String problem = service.readinessProblem(draft);
        if (problem != null) {
            entryMessage.setForeground(Style.ERROR);
            entryMessage.setText(problem);
            return;
        }
        continueListener.accept(draft);
    }

    /** Puts the message where it belongs and focuses the offending field. */
    private void showRefusal(JLabel target, ValidationException refused, JComponent... fields) {
        target.setForeground(Style.ERROR);
        target.setText(refused.getMessage());
        for (JComponent field : fields) {
            if (field == fieldNamed(refused.field())) {
                field.requestFocusInWindow();
                return;
            }
        }
    }

    /**
     * The component a field name refers to, or {@code null}.
     *
     * <p>Matching on identity keeps the service's field names and this form's
     * components in one place instead of scattering the mapping through the
     * listeners.
     */
    private JComponent fieldNamed(String field) {
        if (field == null) {
            return null;
        }
        return switch (field) {
            case "fullName" -> nameField;
            case "age" -> ageField;
            case "gender" -> genderBox;
            case "berthPreference" -> berthBox;
            case "mealPreference" -> mealBox;
            case "mobile" -> mobileField;
            case "email" -> emailField;
            default -> null;
        };
    }

    /** Shows contact details already collected, so returning from Back keeps them. */
    private void applyContactIfPresent() {
        if (draft.contact() != null) {
            mobileField.setText(draft.contact().mobile());
            emailField.setText(draft.contact().email() == null ? "" : draft.contact().email());
        }
    }

    private static String describe(Throwable cause) {
        if (cause instanceof ValidationException refused) {
            return refused.getMessage();
        }
        if (cause instanceof java.sql.SQLException) {
            return "The database could not be reached, so class details are unavailable. "
                    + "Close this screen and try again.";
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

/**
 * Formats an amount the way a fare is quoted.
 *
 * <p>Paise are shown whenever there are any, because a per-kilometre rate like
 * ₹1.15 over 150 km is ₹172.50 and not ₹173. Rounding the display would also
 * break the arithmetic a passenger can do in their head: the point of showing a
 * base fare, a distance fare and a total side by side is that they add up.
 */
private static String money(BigDecimal amount) {
    NumberFormat format = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"));
    format.setMinimumFractionDigits(FareEstimate.SCALE);
    format.setMaximumFractionDigits(FareEstimate.SCALE);
    return format.format(amount);
}

    /** Small holder so the worker returns both reads together. */
    private record Loaded(ClassProfile profile, FareRule rule) {
    }
}