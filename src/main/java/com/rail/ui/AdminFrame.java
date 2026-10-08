package com.rail.ui;

import com.busticket.ui.Style;
import com.rail.model.AdminView;
import com.rail.model.StopInfo;
import com.rail.service.AccessDeniedException;
import com.rail.service.AdminService;
import com.rail.service.SessionManager;
import com.rail.service.ValidationException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * The administration window: six tabs, one per thing an administrator changes.
 *
 * <h2>Nothing here decides anything</h2>
 *
 * <p>Every button calls {@link AdminService} and renders what comes back. The
 * role check, the validation and the transaction live in the service, which
 * re-checks the role on every call - so a hidden tab, or a window left open on
 * a session that has since ended, buys nobody anything. A refusal is shown as
 * the sentence the service wrote, because that sentence is the part written for
 * an administrator to act on.
 *
 * <h2>Deliberately absent</h2>
 *
 * <p>There is no delete button for a train or a station. Trains are
 * deactivated, stations are renamed or moved, and rake and fare rows are
 * removed only where the schema allows it. The reasons are in the README; the
 * short version is that a booked ticket names its train, its stops and its
 * berths, and removing any of those would leave it pointing at nothing.
 *
 * <p>Reads run on a worker thread, as everywhere else here. Writes run on the
 * event thread: each is one row, and a second thread pool would buy no visible
 * gain.
 */
public final class AdminFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final transient AdminService admin = new AdminService();
    private final transient StatusBanner banner = new StatusBanner();
    private final transient JTabbedPane tabs = new JTabbedPane();

    /** What each tab has to re-read after a change, in tab order. */
    private final transient List<Runnable> refreshers = new ArrayList<>();

    public AdminFrame() throws AccessDeniedException {
        super("RailReserve - Administration");
        // The window itself refuses to open for anyone else, the same way the
        // dashboard does. The service would refuse every call regardless.
        SessionManager.get().requireAdmin();

        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(1180, 740);
        setMinimumSize(new Dimension(980, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Style.BACKGROUND);

        tabs.addTab("Overview", overviewTab());
        tabs.addTab("Trains", trainsTab());
        tabs.addTab("Stops", stopsTab());
        tabs.addTab("Rake", rakeTab());
        tabs.addTab("Fares", faresTab());
        tabs.addTab("Stations", stationsTab());

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Style.BACKGROUND);
        root.add(Style.scroll(padded(tabs)), BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(Style.BACKGROUND);
        south.setBorder(BorderFactory.createEmptyBorder(0, 24, 16, 24));
        south.add(banner, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);

        setContentPane(root);
    }

    // ---------------------------------------------------------------- overview

    private JComponent overviewTab() {
        JPanel rows = column();
        rows.add(Style.sectionHeading("Overview"));
        rows.add(Box.createVerticalStrut(4));
        rows.add(Style.muted("Counted from the database. Nothing here is cached or typed in."));

        JComponent slot = slot();
        rows.add(Box.createVerticalStrut(14));
        rows.add(slot);
        load(slot, admin::summary, AdminFrame::summaryCards, "Could not read the overview.");

        JButton refresh = Style.secondary("Refresh");
        refresh.addActionListener(event -> reload());
        rows.add(Box.createVerticalStrut(14));
        rows.add(refresh);
        return padded(rows);
    }

    private static JComponent summaryCards(AdminView.Summary summary) {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        String[][] figures = {
                {"Trains", String.valueOf(summary.trains())},
                {"Active trains", String.valueOf(summary.activeTrains())},
                {"Stations", String.valueOf(summary.stations())},
                {"Active stations", String.valueOf(summary.activeStations())},
                {"Bookings", String.valueOf(summary.bookings())},
                {"Bookings by status", summary.bookingsByStatus().toString()}};
        for (int i = 0; i < figures.length; i++) {
            grid.add(statCard(figures[i][1], figures[i][0]), gridCell(i / 3, i % 3));
        }
        return grid;
    }

    private static JComponent statCard(String figure, String caption) {
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel value = new JLabel(figure);
        value.setFont(Style.font(Font.BOLD, 18));
        value.setForeground(Style.TEXT);
        value.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(value);

        JLabel label = new JLabel(caption);
        label.setFont(Style.font(Font.PLAIN, 12));
        label.setForeground(Style.MUTED);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(label);
        return card;
    }

    // ---------------------------------------------------------------- trains

    private JComponent trainsTab() {
        JPanel rows = column();
        rows.add(Style.sectionHeading("Trains"));

        JComponent slot = slot();
        rows.add(Box.createVerticalStrut(12));
        rows.add(slot);
        load(slot, admin::trains, AdminFrame::trainTable, "Could not read the trains.");

        JTextField number = field(9);
        JTextField name = field(24);
        JComboBox<String> type = new JComboBox<>(AdminView.TRAIN_TYPES.toArray(new String[0]));
        JTextField distance = field(7);
        JCheckBox daily = new JCheckBox("Runs daily");

        rows.add(Box.createVerticalStrut(18));
        rows.add(card(form(new String[]{"Number", "Name", "Type", "Distance (km)", ""},
                new JComponent[]{number, name, type, distance, daily},
                new JButton[]{
                        action("Add train", () -> admin.addTrain(new AdminView.TrainInput(
                                number.getText(), name.getText(), chosen(type),
                                daily.isSelected(), whole(distance, "Distance")))),
                        action("Save changes", () -> admin.editTrain(new AdminView.TrainInput(
                                number.getText(), name.getText(), chosen(type),
                                daily.isSelected(), whole(distance, "Distance")))),
                        action("Deactivate", () -> admin.setTrainActive(number.getText(), false)),
                        action("Activate", () -> admin.setTrainActive(number.getText(), true))})));

        rows.add(Box.createVerticalStrut(8));
        rows.add(Style.muted("A train number cannot be changed: journeys, bookings and reservations "
                + "all refer to it. Deactivating keeps every ticket readable while taking the "
                + "train out of search."));
        return padded(rows);
    }

    private static JComponent trainTable(List<AdminView.TrainRow> rows) {
        List<String[]> cells = new ArrayList<>();
        for (AdminView.TrainRow row : rows) {
            cells.add(new String[]{row.trainNo(), row.trainName(), row.trainType(),
                    row.runsDaily() ? "Daily" : "Selected days",
                    row.distanceKm() == null ? "-" : String.valueOf(row.distanceKm()),
                    String.valueOf(row.stopCount()),
                    row.classes().isEmpty() ? "-" : row.classes(),
                    row.active() ? "Active" : "Inactive",
                    String.valueOf(row.bookings())});
        }
        return table(new String[]{"Number", "Name", "Type", "Runs", "Distance", "Stops", "Classes",
                "State", "Bookings"}, cells);
    }

    // ---------------------------------------------------------------- stops

    private JComponent stopsTab() {
        JPanel rows = column();
        rows.add(Style.sectionHeading("Timetable"));
        rows.add(Style.muted("Choose a train to read and re-time its route."));

        JComboBox<String> trains = trainPicker();
        JComponent slot = slot();
        rows.add(Box.createVerticalStrut(10));
        rows.add(trains);
        rows.add(Box.createVerticalStrut(10));
        rows.add(slot);
        Runnable refreshStops = () -> read(slot, () -> admin.stops(chosen(trains)),
                AdminFrame::stopTable, "Could not read that train's stops.");
        refreshers.add(refreshStops);
        refreshStops.run();
        trains.addActionListener(event -> refreshStops.run());
        rows.add(Box.createVerticalStrut(18));

        JTextField stopNo = field(4);
        JTextField arrival = field(6);
        JTextField departure = field(6);
        JTextField dayOffset = field(4);
        JTextField distance = field(7);

        rows.add(card(form(
                new String[]{"Stop #", "Arrives", "Departs", "Day +", "Distance (km)", ""},
                new JComponent[]{stopNo, arrival, departure, dayOffset, distance},
                new JButton[]{action("Save stop", () -> admin.editStopTiming(
                        chosen(trains), requiredWhole(stopNo, "Stop number"),
                        clock(arrival), clock(departure),
                        requiredWhole(dayOffset, "Day offset"),
                        requiredWhole(distance, "Distance")))})));
        rows.add(Box.createVerticalStrut(8));
        rows.add(Style.muted("The station a stop is at is fixed once the route is built - changing "
                + "it would renumber the stops a ticket names. Leave the arrival empty for the "
                + "first stop. \"Day +\" is how many days after departure a leg runs, so a train "
                + "leaving at 21:15 and reaching the next station at 01:40 is day +1. Arriving "
                + "before the previous stop departs is refused."));
        return padded(rows);
    }

    private static JComponent stopTable(List<StopInfo> stops) {
        List<String[]> cells = new ArrayList<>();
        for (StopInfo stop : stops) {
            cells.add(new String[]{String.valueOf(stop.stopSeq()), stop.stationCode(),
                    stop.stationName(), clockText(stop.arrivalTime()), clockText(stop.departureTime()),
                    String.valueOf(stop.dayOffset()), String.valueOf(stop.distanceKm())});
        }
        return table(new String[]{"#", "Code", "Station", "Arrives", "Departs", "Day +", "Distance"},
                cells);
    }

    // ---------------------------------------------------------------- rake

    private JComponent rakeTab() {
        JPanel rows = column();
        rows.add(Style.sectionHeading("Coaches and berths"));
        rows.add(Style.muted("Choose a train to read and change its rake."));

        JComboBox<String> trains = trainPicker();
        JComboBox<Long> coach = new JComboBox<>();
        JComboBox<Long> berth = new JComboBox<>();
        JComboBox<String> classes = new JComboBox<>();
        JComponent coachesSlot = slot();
        JComponent berthsSlot = slot();

        // The berths belong to whichever coach is chosen, so this read runs
        // whenever that choice changes and whenever anything else is reloaded.
        // It reads rather than loads: the refresher is registered once, below,
        // instead of once per call.
        Runnable refreshBerths = () -> read(berthsSlot, () -> admin.berths(chosenId(coach)), found -> {
            fill(berth, found.stream().map(AdminView.BerthRow::berthId).toList());
            return berthTable(found);
        }, "Choose a coach to read its berths.");
        refreshers.add(refreshBerths);

        load(coachesSlot, () -> admin.coaches(chosen(trains)), found -> {
            fill(coach, found.stream().map(AdminView.CoachRow::coachId).toList());
            refreshBerths.run();
            return coachTable(found);
        }, "Could not read that train's coaches.");

        rows.add(Box.createVerticalStrut(10));
        rows.add(trains);
        rows.add(Box.createVerticalStrut(10));
        rows.add(coachesSlot);
        rows.add(Box.createVerticalStrut(14));
        rows.add(berthsSlot);
        trains.addActionListener(event -> reload());
        coach.addActionListener(event -> refreshBerths.run());

        JTextField coachNo = field(7);
        JTextField coachType = field(16);
        JTextField position = field(5);
        JCheckBox toilet = new JCheckBox("Toilet");
        rows.add(Box.createVerticalStrut(18));
        rows.add(card(form(
                new String[]{"Coach no.", "Class", "Coach type", "Position", "Toilet", ""},
                new JComponent[]{coachNo, classes, coachType, position, toilet},
                new JButton[]{action("Add coach", () -> admin.addCoach(new AdminView.CoachInput(
                        chosen(trains), coachNo.getText(), chosen(classes), coachType.getText(),
                        requiredWhole(position, "Position"), toilet.isSelected())))})));

        JTextField berthNo = field(4);
        JComboBox<String> berthType = new JComboBox<>(AdminView.BERTH_TYPES.toArray(new String[0]));
        JTextField berthPosition = field(9);
        rows.add(Box.createVerticalStrut(12));
        rows.add(card(form(
                new String[]{"Berth no.", "Type", "Position", "In coach", "Berth", ""},
                new JComponent[]{berthNo, berthType, berthPosition, coach, berth},
                new JButton[]{
                        action("Add berth", () -> admin.addBerth(new AdminView.BerthInput(
                                chosenId(coach), requiredWhole(berthNo, "Berth number"),
                                chosen(berthType), berthPosition.getText()))),
                        action("Remove berth", () -> admin.removeBerth(chosenId(berth))),
                        action("Remove coach", () -> admin.removeCoach(chosenId(coach)))})));
        rows.add(Box.createVerticalStrut(8));
        rows.add(Style.muted("A coach or berth that has sold anything cannot be removed; the "
                + "refusal names the count. Positions are checked against the class: a sleeper "
                + "takes LOWER or UPPER, a seated class takes WINDOW, AISLE or MIDDLE. "
                + "\"In coach\" is the coach a berth is added to or a coach removed; \"Berth\" is "
                + "the berth removed."));

        load(null, admin::travelClasses, codes -> {
            fill(classes, codes);
            return new JPanel();
        }, "Could not read the travel classes.");
        return padded(rows);
    }

    private static JComponent coachTable(List<AdminView.CoachRow> coaches) {
        List<String[]> cells = new ArrayList<>();
        for (AdminView.CoachRow coach : coaches) {
            cells.add(new String[]{coach.coachNumber(), coach.classCode(), coach.coachType(),
                    String.valueOf(coach.position()), coach.hasToilet() ? "Yes" : "No",
                    String.valueOf(coach.berthCount()), String.valueOf(coach.soldBerths())});
        }
        return table(new String[]{"Coach", "Class", "Type", "Position", "Toilet", "Berths", "Sold"},
                cells);
    }

    private static JComponent berthTable(List<AdminView.BerthRow> berths) {
        List<String[]> cells = new ArrayList<>();
        for (AdminView.BerthRow berth : berths) {
            cells.add(new String[]{String.valueOf(berth.berthNumber()), berth.berthType(),
                    berth.position(), berth.soldCount() > 0 ? berth.soldCount() + " sold" : "Free"});
        }
        return table(new String[]{"Berth", "Type", "Position", "State"}, cells);
    }

    // ---------------------------------------------------------------- fares

    private JComponent faresTab() {
        JPanel rows = column();
        rows.add(Style.sectionHeading("Fare rules"));

        JComponent slot = slot();
        rows.add(Box.createVerticalStrut(12));
        rows.add(slot);
        load(slot, admin::fareRules, AdminFrame::fareTable, "Could not read the fare rules.");

        JTextField ruleId = field(6);
        JComboBox<String> classes = new JComboBox<>();
        JTextField origin = field(6);
        JTextField destination = field(6);
        JTextField distance = field(6);
        JTextField base = field(8);
        JTextField perKm = field(8);
        JTextField quota = field(5);
        JTextField tatkal = field(5);
        JTextField validFrom = field(11);
        JTextField validTo = field(11);
        load(null, admin::travelClasses, codes -> {
            fill(classes, codes);
            return new JPanel();
        }, "Could not read the travel classes.");

        rows.add(Box.createVerticalStrut(18));
        rows.add(card(form(new String[]{"Rule", "Class", "Origin", "Destination", "Distance",
                        "Base", "Per km", "Reserved", "Tatkal", "From", "To", ""},
                new JComponent[]{ruleId, classes, origin, destination, distance, base, perKm, quota,
                        tatkal, validFrom, validTo},
                new JButton[]{
                        action("Save rule", () -> admin.saveFareRule(fareFrom(ruleId, classes,
                                origin, destination, distance, base, perKm, quota, tatkal, validFrom,
                                validTo))),
                        action("Remove rule", () -> admin.removeFareRule(
                                requiredWhole(ruleId, "Rule number")))})));
        rows.add(Box.createVerticalStrut(8));
        rows.add(Style.muted("Leave the rule number empty to add a rule; type an existing number to "
                + "change it. Leave origin and destination empty for a rule that prices any route; "
                + "a destination with no origin is refused. Dates are yyyy-mm-dd. A rule already in "
                + "force cannot be removed: add a replacement and give the old one an end date."));
        return padded(rows);
    }

    /** A rule number of zero means a rule that does not exist yet. */
    private static AdminView.FareRule fareFrom(JTextField ruleId, JComboBox<String> classes,
                                               JTextField origin, JTextField destination,
                                               JTextField distance, JTextField base,
                                               JTextField perKm, JTextField quota,
                                               JTextField tatkal, JTextField validFrom,
                                               JTextField validTo) throws ValidationException {
        return new AdminView.FareRule(ruleId.getText().trim().isEmpty()
                        ? 0 : requiredWhole(ruleId, "Rule number"),
                chosen(classes), blankToNull(origin.getText()), blankToNull(destination.getText()),
                whole(distance, "Distance"), money(base, "Base fare"), money(perKm, "Per-km fare"),
                requiredWhole(quota, "Reserved quota"), requiredWhole(tatkal, "Tatkal quota"),
                date(validFrom, "Start date"), date(validTo, "End date"));
    }

    private static JComponent fareTable(List<AdminView.FareRule> rules) {
        List<String[]> cells = new ArrayList<>();
        for (AdminView.FareRule rule : rules) {
            cells.add(new String[]{String.valueOf(rule.ruleId()), rule.classCode(),
                    rule.origin() == null ? "Any" : rule.origin(),
                    rule.destination() == null ? "Any" : rule.destination(),
                    rule.distanceKm() == null ? "-" : String.valueOf(rule.distanceKm()),
                    rule.baseFare().toPlainString(), rule.perKmFare().toPlainString(),
                    String.valueOf(rule.quotaSeats()), String.valueOf(rule.tatkalSeats()),
                    rule.validFrom() == null ? "-" : DATE.format(rule.validFrom()),
                    rule.validTo() == null ? "Open" : DATE.format(rule.validTo())});
        }
        return table(new String[]{"Rule", "Class", "Origin", "Destination", "Distance", "Base",
                "Per km", "Reserved", "Tatkal", "From", "To"}, cells);
    }

    // ---------------------------------------------------------------- stations

    private JComponent stationsTab() {
        JPanel rows = column();
        rows.add(Style.sectionHeading("Stations"));

        JComponent slot = slot();
        rows.add(Box.createVerticalStrut(12));
        rows.add(slot);
        load(slot, admin::stations, AdminFrame::stationTable, "Could not read the stations.");

        JTextField code = field(6);
        JTextField name = field(24);
        JTextField city = field(18);
        JTextField zone = field(8);

        rows.add(Box.createVerticalStrut(18));
        rows.add(card(form(new String[]{"Code", "Name", "City", "Zone", ""},
                new JComponent[]{code, name, city, zone},
                new JButton[]{
                        action("Add station", () -> admin.addStation(new AdminView.StationInput(
                                code.getText(), name.getText(), city.getText(),
                                blankToNull(zone.getText())))),
                        action("Save changes", () -> admin.editStation(new AdminView.StationInput(
                                code.getText(), name.getText(), city.getText(),
                                blankToNull(zone.getText()))))})));
        rows.add(Box.createVerticalStrut(8));
        rows.add(Style.muted("A code is 2 to 4 capital letters and cannot be changed once created, "
                + "because bookings and fare rules refer to it. There is no delete button on "
                + "purpose: a station that a train calls at, a booking names or a fare rule prices "
                + "cannot be removed, and the application account holds no DELETE on stations at all."));
        return padded(rows);
    }

    private static JComponent stationTable(List<AdminView.StationRow> stations) {
        List<String[]> cells = new ArrayList<>();
        for (AdminView.StationRow station : stations) {
            cells.add(new String[]{station.code(), station.name(), station.city(),
                    station.zone() == null ? "-" : station.zone(),
                    String.valueOf(station.stopUses()), String.valueOf(station.bookingUses()),
                    station.active() ? "Active" : "Inactive"});
        }
        return table(new String[]{"Code", "Name", "City", "Zone", "Trains", "Bookings", "State"},
                cells);
    }

    // ---------------------------------------------------------------- wiring

    /**
     * One button that runs one change, then reloads every tab.
     *
     * <p>A single place to turn a refusal into something an administrator can
     * act on, and a single place to refresh afterwards, so no button can forget
     * either.
     */
    private JButton action(String text, Change change) {
        JButton button = Style.primary(text);
        button.addActionListener(event -> {
            try {
                banner.clear();
                change.run();
                banner.showSuccess("Saved.");
                reload();
            } catch (ValidationException refused) {
                banner.showError(refused.getMessage());
            } catch (AccessDeniedException refused) {
                banner.showError("That action is restricted to administrators.");
            } catch (Exception failed) {
                // Everything the service refuses arrives as a ValidationException
                // or an AccessDeniedException above, with a sentence written for an
                // administrator. Anything left is the database, and the database's
                // own message is a configuration detail rather than an explanation.
                banner.showError(DatabaseFailure.report("That change", failed));
            }
        });
        return button;
    }

    @FunctionalInterface
    private interface Change {
        void run() throws Exception;
    }

    private void reload() {
        refreshers.forEach(Runnable::run);
    }

    /**
     * Registers a read as a refresher, then runs it.
     *
     * <p>Registering is what makes a change made in one tab visible in every
     * other one without this method knowing what it loaded. A {@code null} slot
     * registers without rendering, which is how the two drop-downs that have
     * nothing to show are filled.
     */
    private <T> void load(JComponent slot, Callable<T> query,
                          Function<T, JComponent> render, String failureMessage) {
        refreshers.add(() -> read(slot, query, render, failureMessage));
        read(slot, query, render, failureMessage);
    }

    /** One read: off the event thread, rendered into {@code slot} on it. */
    private <T> void read(JComponent slot, Callable<T> query,
                          Function<T, JComponent> render, String failureMessage) {
        if (slot != null) {
            slot.removeAll();
            slot.add(Working.indicator("Loading"), BorderLayout.NORTH);
            slot.revalidate();
            slot.repaint();
        }
        new SwingWorker<T, Exception>() {
            @Override
            protected T doInBackground() throws Exception {
                return query.call();
            }

            @Override
            protected void done() {
                if (slot == null) {
                    return;
                }
                slot.removeAll();
                try {
                    slot.add(render.apply(get()), BorderLayout.NORTH);
                } catch (Exception failure) {
                    slot.add(Style.muted(failureMessage), BorderLayout.NORTH);
                }
                slot.revalidate();
                slot.repaint();
            }
        }.execute();
    }

    // ---------------------------------------------------------------- rendering

    /** A read-only table: these show rows, the forms beside them edit them. */
    private static JComponent table(String[] columns, List<String[]> rows) {
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        rows.forEach(model::addRow);

        JTable table = new JTable(model);
        table.setFont(Style.font(Font.PLAIN, 13));
        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setBackground(Style.WHITE);
        table.setGridColor(Style.BORDER);
        table.setShowVerticalLines(false);
        table.getTableHeader().setFont(Style.font(Font.BOLD, 12));
        table.getTableHeader().setReorderingAllowed(false);

        JScrollPane pane = new JScrollPane(table);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getViewport().setBackground(Style.WHITE);
        return pane;
    }

    /**
     * One row of labelled fields and buttons.
     *
     * <p>A grid rather than a nested panel per field: a form is a grid, and
     * anything more would be the same layout in more code.
     */
    private static JComponent form(String[] labels, JComponent[] fields, JButton[] buttons) {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);

        GridBagConstraints row = new GridBagConstraints();
        row.gridy = 0;
        row.anchor = GridBagConstraints.LINE_START;
        row.fill = GridBagConstraints.HORIZONTAL;

        for (int i = 0; i < labels.length - 1; i++) {
            JPanel cell = new JPanel(new BorderLayout(0, 3));
            cell.setOpaque(false);
            cell.add(Style.fieldLabel(labels[i]), BorderLayout.NORTH);
            cell.add(fields[i], BorderLayout.CENTER);

            GridBagConstraints at = new GridBagConstraints();
            at.gridx = i;
            at.gridy = 0;
            at.anchor = GridBagConstraints.LINE_START;
            at.fill = GridBagConstraints.HORIZONTAL;
            at.insets = new Insets(0, 0, 0, 14);
            grid.add(cell, at);
        }

        GridBagConstraints end = new GridBagConstraints();
        end.gridx = labels.length - 1;
        end.gridy = 0;
        end.anchor = GridBagConstraints.LINE_START;
        end.insets = new Insets(0, 0, 0, 4);
        grid.add(buttonRow(buttons), end);
        grid.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        return grid;
    }

    private static JComponent buttonRow(JButton[] buttons) {
        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        for (JButton button : buttons) {
            row.add(button);
            row.add(Box.createHorizontalStrut(8));
        }
        return row;
    }

    private static JPanel card(JComponent content) {
        Style.RoundedPanel panel = Style.card();
        panel.setLayout(new BorderLayout());
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static JComponent padded(JComponent view) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Style.BACKGROUND);
        panel.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));
        panel.add(view, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel column() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JComponent slot() {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setAlignmentX(Component.LEFT_ALIGNMENT);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return holder;
    }

    private static GridBagConstraints gridCell(int row, int column) {
        GridBagConstraints at = new GridBagConstraints();
        at.gridx = column;
        at.gridy = row;
        at.weightx = 1;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.insets = new Insets(0, 8, 12, 8);
        return at;
    }

    private static JTextField field(int columns) {
        JTextField field = new JTextField(columns);
        field.setFont(Style.font(Font.PLAIN, 13));
        return field;
    }

    // ---------------------------------------------------------------- pickers

    /** A train drop-down, filled from the database on a worker thread. */
    private JComboBox<String> trainPicker() {
        JComboBox<String> picker = new JComboBox<>();
        load(null, admin::trains,
                rows -> {
                    fill(picker, rows.stream().map(AdminView.TrainRow::trainNo).toList());
                    return new JPanel();
                }, "Could not read the trains.");
        return picker;
    }

    /** Replaces a drop-down's contents, keeping the selection if it survives. */
    private static <T> void fill(JComboBox<T> picker, List<T> values) {
        Object chosen = picker.getSelectedItem();
        picker.removeAllItems();
        values.forEach(picker::addItem);
        if (chosen != null && values.contains(chosen)) {
            picker.setSelectedItem(chosen);
        }
    }

    private static String chosen(JComboBox<?> picker) throws ValidationException {
        Object value = picker.getSelectedItem();
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ValidationException("field", "Choose a value first.");
        }
        return String.valueOf(value);
    }

    private static long chosenId(JComboBox<Long> picker) throws ValidationException {
        Object value = picker.getSelectedItem();
        if (value == null) {
            throw new ValidationException("field", "Choose a coach first.");
        }
        return (Long) value;
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Reads an integer the administrator typed.
     *
     * <p>An empty box is {@code null} rather than zero for the nullable
     * columns - a distance nobody filled in is unknown, not zero kilometres -
     * and anything unparseable becomes a {@link ValidationException} the banner
     * can show, not a {@code NumberFormatException} it cannot.
     */
    private static Integer whole(JTextField field, String caption) throws ValidationException {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException notANumber) {
            throw new ValidationException(caption, caption + " must be a whole number.");
        }
    }

    private static int requiredWhole(JTextField field, String caption) throws ValidationException {
        Integer value = whole(field, caption);
        if (value == null) {
            throw new ValidationException(caption, caption + " is required.");
        }
        return value;
    }

    private static BigDecimal money(JTextField field, String caption) throws ValidationException {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            throw new ValidationException(caption, caption + " is required.");
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException notAnAmount) {
            throw new ValidationException(caption, caption + " must be an amount, like 275.50.");
        }
    }

    private static LocalTime clock(JTextField field) throws ValidationException {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(text.length() == 4 ? "0" + text : text, TIME);
        } catch (RuntimeException notATime) {
            throw new ValidationException("Time", "Times are 24-hour HH:mm, like 21:15.");
        }
    }

    private static LocalDate date(JTextField field, String caption) throws ValidationException {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException notADate) {
            throw new ValidationException(caption, caption + " is a date like 2026-04-01.");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String clockText(LocalTime time) {
        return time == null ? "-" : TIME.format(time);
    }
}