package com.rail.ui;

import com.busticket.ui.Style;
import com.rail.db.TrainDAO;
import com.rail.model.ClassAvailability;
import com.rail.model.Station;
import com.rail.model.StopInfo;
import com.rail.model.TrainSearchCriteria;
import com.rail.model.TrainSearchResult;
import com.rail.model.TrainSelection;
import com.rail.service.AccessDeniedException;
import com.rail.service.ValidationException;
import com.rail.service.TrainSearchService;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.Window;
import java.awt.Dialog.ModalityType;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The train search screen: criteria form, result cards and route viewer.
 *
 * <p>Every database call goes through a {@link SwingWorker}, so no JDBC runs on
 * the event dispatch thread and the window keeps repainting while a search is in
 * flight.
 *
 * <p>The form keeps whatever the traveller typed. Navigating to another screen
 * and back does not clear the route, date or party size, because the panel
 * instance is reused rather than rebuilt.
 *
 * <p>Availability figures come from this project's own seat tables and are
 * labelled as such on screen. They are not live IRCTC availability.
 */
public final class TrainSearchPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter DATE_IN =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter DATE_OUT = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Locale LOCALE = Locale.forLanguageTag("en-IN");

    private final transient TrainSearchService service = new TrainSearchService();

    private final JComboBox<Station> fromBox = new JComboBox<>();
    private final JComboBox<Station> toBox = new JComboBox<>();
    private final JComboBox<TrainDAO.ClassRow> classBox = new JComboBox<>();
    private final JTextField dateField = new JTextField(10);
    private final JSpinner passengers = new JSpinner(
            new SpinnerNumberModel(1, 1, Math.max(1, service.maxPassengers()), 1));
    private final JButton searchButton = Style.primary("Search Trains");
    private final JLabel formMessage = Style.muted(" ");

    private final JPanel resultList = new JPanel();

    /** Notified when the traveller commits to a train and class. */
    private transient Consumer<TrainSelection> selectionListener = selected -> { };

    public TrainSearchPanel() {
        super(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);

        add(form(), BorderLayout.NORTH);

        resultList.setOpaque(false);
        resultList.setLayout(new BoxLayout(resultList, BoxLayout.Y_AXIS));
        resultList.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.add(resultList, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);

        showPlaceholder("Search for a train to see live fares and seat counts.");
        searchButton.addActionListener(event -> runSearch());
        loadReferenceData();
    }

    /** Registers the callback fired when a train and class are selected. */
    public void onSelection(Consumer<TrainSelection> listener) {
        this.selectionListener = listener == null ? selected -> { } : listener;
    }

    // ---------------------------------------------------------------- form

    private JComponent form() {
        JPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 12));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel grid = new JPanel(new java.awt.GridBagLayout());
        grid.setOpaque(false);
        int row = 0;
        addField(grid, row++, "From", fromBox);
        addField(grid, row++, "To", toBox);
        addField(grid, row++, "Journey date", dateField);
        addField(grid, row++, "Travel class", classBox);
        addField(grid, row++, "Passengers", passengers);

        JButton swap = Style.secondary("Swap stations");
        swap.setToolTipText("Reverse the origin and destination");
        swap.addActionListener(event -> {
            Station a = (Station) fromBox.getSelectedItem();
            Station b = (Station) toBox.getSelectedItem();
            if (a != null && b != null) {
                int ia = fromBox.getSelectedIndex();
                int ib = toBox.getSelectedIndex();
                fromBox.setSelectedIndex(ib);
                toBox.setSelectedIndex(ia);
            }
        });
        addField(grid, row, "", swap);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.setOpaque(false);
        actions.add(searchButton);

        JPanel note = new JPanel(new BorderLayout());
        note.setOpaque(false);
        note.add(formMessage, BorderLayout.CENTER);
        JLabel disclaimer = Style.muted(
                "Fares and availability come from this project's own seat tables. "
                + "This is a simulation and is not live IRCTC availability.");
        disclaimer.setFont(Style.font(java.awt.Font.ITALIC, 11f));
        note.add(disclaimer, BorderLayout.SOUTH);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(actions, BorderLayout.NORTH);
        south.add(note, BorderLayout.SOUTH);

        card.add(grid, BorderLayout.CENTER);
        card.add(south, BorderLayout.SOUTH);
        return card;
    }

    private void addField(JPanel grid, int row, String label, JComponent field) {
        GridBagConstraints labelCell = Style.cell(row, 0);
        labelCell.anchor = GridBagConstraints.LINE_START;
        JLabel caption = Style.fieldLabel(label);
        grid.add(caption, labelCell);

        GridBagConstraints fieldCell = Style.cell(row, 1);
        fieldCell.fill = GridBagConstraints.HORIZONTAL;
        fieldCell.weightx = 1;
        grid.add(field, fieldCell);

        GridBagConstraints filler = Style.cell(row, 2);
        filler.weightx = 0.6;
        grid.add(Box.createHorizontalStrut(16), filler);
    }

    // ---------------------------------------------------------------- reference data

    /**
     * Loads stations and classes off the EDT.
     *
     * <p>A database failure here leaves the dropdowns empty and says so, rather
     * than presenting an empty combo as if the network had no stations.
     */
    private void loadReferenceData() {
        searchButton.setEnabled(false);
        formMessage.setText("Loading stations and classes...");
        new SwingWorker<Object[], SQLException>() {
            @Override
            protected Object[] doInBackground() throws SQLException {
                return new Object[]{service.listStations(), service.listClasses()};
            }

            @Override
            protected void done() {
                searchButton.setEnabled(true);
                try {
                    Object[] loaded = get();
                    fillFrom(loaded[0]);
                    fillClasses(loaded[1]);
                    formMessage.setText(" ");
                } catch (java.util.concurrent.ExecutionException failed) {
                    reportDatabaseFailure(failed.getCause());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    @SuppressWarnings("unchecked")
    private void fillFrom(Object loaded) {
        List<Station> stations = (List<Station>) loaded;
        fromBox.removeAllItems();
        toBox.removeAllItems();
        for (Station station : stations) {
            fromBox.addItem(station);
            toBox.addItem(station);
        }
        if (stations.size() > 1) {
            fromBox.setSelectedIndex(0);
            toBox.setSelectedIndex(1);
        }
    }

    @SuppressWarnings("unchecked")
    private void fillClasses(Object loaded) {
        List<TrainDAO.ClassRow> classes = (List<TrainDAO.ClassRow>) loaded;
        classBox.removeAllItems();
        for (TrainDAO.ClassRow row : classes) {
            classBox.addItem(row);
        }
        if (!classes.isEmpty()) {
            classBox.setSelectedIndex(0);
        }
    }

    // ---------------------------------------------------------------- search

    private void runSearch() {
        TrainSearchCriteria criteria = readCriteria();
        searchButton.setEnabled(false);
        formMessage.setText("Searching...");
        showPlaceholder(Working.indicator("Searching " + criteria.summary()));

new SwingWorker<List<TrainSearchResult>, Exception>() {
            @Override
            protected List<TrainSearchResult> doInBackground() throws Exception {
                // Propagated as-is: wrapping a rejected search in a
                // SQLException would report bad input as a database outage and
                // send the traveller off to debug MySQL.
                return service.search(criteria);
            }

            @Override
            protected void done() {
                searchButton.setEnabled(true);
                try {
                    List<TrainSearchResult> found = get();
                    render(found, criteria);
                } catch (java.util.concurrent.ExecutionException failed) {
                    Throwable cause = failed.getCause();
                    if (cause instanceof ValidationException refused) {
                        showValidation(refused);
                    } else if (cause instanceof AccessDeniedException denied) {
                        showValidation(new ValidationException(null, denied.getMessage()));
                    } else {
                        reportDatabaseFailure(cause);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    private TrainSearchCriteria readCriteria() {
        Station from = (Station) fromBox.getSelectedItem();
        Station to = (Station) toBox.getSelectedItem();
        TrainDAO.ClassRow chosenClass = (TrainDAO.ClassRow) classBox.getSelectedItem();
        int party = ((Number) passengers.getValue()).intValue();
        return new TrainSearchCriteria(
                from == null ? "" : from.code(),
                to == null ? "" : to.code(),
                parseDate(dateField.getText()),
                chosenClass == null ? "" : chosenClass.classCode(),
                party);
    }

    /**
     * Strict, so an impossible date such as 2026-02-30 is a parse failure rather
     * than being silently rolled into March.
     */
    private static LocalDate parseDate(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed, DATE_IN);
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    private void showValidation(ValidationException refused) {
        formMessage.setForeground(Style.ERROR);
        formMessage.setText(refused.getMessage());
        showPlaceholder(refused.getMessage());
    }

private void reportDatabaseFailure(Throwable cause) {
        formMessage.setForeground(Style.ERROR);
        formMessage.setText("Could not reach the database.");
        // The driver names the account and often the statement; that belongs on
        // the console, not on a screen a traveller is reading.
        showPlaceholder(DatabaseFailure.report("The search", cause));
    }

    // ---------------------------------------------------------------- results

    private void render(List<TrainSearchResult> results, TrainSearchCriteria criteria) {
        resultList.removeAll();
        formMessage.setForeground(Style.MUTED);

        if (results.isEmpty()) {
            formMessage.setText("No trains found.");
            showPlaceholder("No trains run from " + criteria.fromCode() + " to "
                    + criteria.toCode() + " in class " + criteria.classCode().toUpperCase()
                    + " on " + readableDate(criteria.journeyDate()) + ".");
            return;
        }

        formMessage.setText(results.size() + (results.size() == 1
                ? " train found." : " trains found."));
        for (TrainSearchResult result : results) {
            resultList.add(card(result, criteria));
            resultList.add(Box.createVerticalStrut(12));
        }
        revalidate();
        repaint();
    }

    private JComponent card(TrainSearchResult train, TrainSearchCriteria criteria) {
        JPanel card = Style.card();
        card.setLayout(new BorderLayout(0, 10));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);

        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel number = Style.fieldValue(train.trainNo() + "  " + train.trainName());
        number.setFont(Style.font(java.awt.Font.BOLD, 16f));
        titles.add(number);
        titles.add(Box.createVerticalStrut(4));
        titles.add(Style.muted(train.fromStation().display() + "  to  "
                + train.toStation().display()
                + "   -   " + train.distanceKm() + " km   -   "
                + formatDuration(train.durationMinutes())));

        JLabel clock = new JLabel("<html><div style='text-align:right'>"
                + "<b style='font-size:16px'>" + TIME.format(train.departureTime()) + "</b>"
                + "  &rarr;  "
                + "<b style='font-size:16px'>" + TIME.format(train.arrivalTime()) + "</b>"
                + (train.crossesMidnight() ? "<br/><span style='font-size:11px'>arrives next day</span>" : "")
                + "</div></html>");
        clock.setHorizontalAlignment(SwingConstants.RIGHT);

        header.add(titles, BorderLayout.CENTER);
        header.add(clock, BorderLayout.EAST);
        card.add(header, BorderLayout.NORTH);

        JPanel classes = new JPanel();
        classes.setOpaque(false);
        classes.setLayout(new BoxLayout(classes, BoxLayout.Y_AXIS));
        for (ClassAvailability offered : train.classes()) {
            classes.add(classRow(train, offered, criteria));
            classes.add(Box.createVerticalStrut(6));
        }
        card.add(classes, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        JButton route = Style.secondary("View Route");
        route.addActionListener(event -> showRoute(train));
        buttons.add(route);

        JButton back = Style.secondary("Change search");
        back.addActionListener(event -> {
            resultList.removeAll();
            showPlaceholder("Adjust the search above and search again.");
        });
        buttons.add(back);

        card.add(buttons, BorderLayout.SOUTH);
        return card;
    }

    private JComponent classRow(TrainSearchResult train, ClassAvailability offered,
                                TrainSearchCriteria criteria) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JLabel name = Style.asValue(Style.fieldLabel(offered.className()));
        name.setPreferredSize(new Dimension(230, 22));

        JLabel fare = Style.fieldValue(money(offered.fare()) + " per passenger");
        fare.setPreferredSize(new Dimension(170, 22));

        JLabel seats = Style.muted(offered.statusFor(criteria.passengers()));
        seats.setPreferredSize(new Dimension(260, 22));

        JPanel middle = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
        middle.setOpaque(false);
        middle.add(name);
        middle.add(fare);
        middle.add(seats);

        JButton select = Style.primary("Select " + offered.classCode());
        boolean bookable = offered.seats(criteria.passengers());
        select.setEnabled(bookable);
        select.setToolTipText(bookable
                ? "Choose this train and class"
                : offered.statusFor(criteria.passengers()));
        select.addActionListener(event -> choose(train, offered, criteria));

        row.add(middle, BorderLayout.CENTER);
        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        east.setOpaque(false);
        east.add(select);
        row.add(east, BorderLayout.EAST);
        return row;
    }

    private void choose(TrainSearchResult train, ClassAvailability offered,
                        TrainSearchCriteria criteria) {
        TrainSelection selection = new TrainSelection(train, offered,
                criteria.journeyDate(), criteria.passengers());
        selectionListener.accept(selection);
    }

private void showPlaceholder(String text) {
        JLabel label = Style.muted(text);
        label.setFont(Style.font(java.awt.Font.PLAIN, 13f));
        showPlaceholder(label);
    }

    /**
     * The same card, holding something other than a sentence - a working
     * indicator, which paints its own ellipsis.
     */
    private void showPlaceholder(JComponent body) {
        resultList.removeAll();
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setAlignmentX(Component.LEFT_ALIGNMENT);
        holder.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        Style.RoundedPanel card = Style.card();
        card.setLayout(new BorderLayout());
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        card.add(body, BorderLayout.CENTER);
        holder.add(card, BorderLayout.NORTH);
        resultList.add(holder);
        revalidate();
        repaint();
    }

// ---------------------------------------------------------------- route viewer

    /**
     * Shows the full timetable for a train.
     *
     * <p>Loaded off the EDT, in a modal dialog so the search results underneath
     * stay intact when it closes.
     */
    private void showRoute(TrainSearchResult train) {
        Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Route and timetable - " + train.trainNo(), ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(0, 12));

        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"#", "Station", "Arrives", "Departs", "Day", "Distance"}, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);

        JLabel status = Style.muted("Loading timetable...");
        // Both the heading and the status share the north cell, so they need a
        // container: adding twice to BorderLayout.NORTH would drop the heading.
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(false);
        header.add(Style.screenHeading(train.trainNo() + "  " + train.trainName()),
                BorderLayout.NORTH);
        header.add(status, BorderLayout.SOUTH);
        dialog.add(header, BorderLayout.NORTH);

        new SwingWorker<List<StopInfo>, SQLException>() {
            @Override
            protected List<StopInfo> doInBackground() throws SQLException {
                return service.routeOf(train.trainNo());
            }

            @Override
            protected void done() {
                try {
                    List<StopInfo> stops = get();
                    for (StopInfo stop : stops) {
                        model.addRow(new Object[]{
                                stop.stopSeq(),
                                stop.stationCode() + " - " + stop.stationName(),
                                stop.arrivalTime() == null ? "-" : TIME.format(stop.arrivalTime()),
                                TIME.format(stop.departureTime()),
                                dayLabel(stop.dayOffset()),
                                stop.distanceKm() + " km"});
                    }
                    status.setText(stops.size() + " stops. "
                            + "Times and day offsets are as scheduled in the database.");
} catch (java.util.concurrent.ExecutionException failed) {
                    status.setForeground(Style.ERROR);
                    status.setText(DatabaseFailure.report("The timetable", failed.getCause()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();

        JScrollPane scroll = Style.scroll(table);
        dialog.add(scroll, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.setOpaque(false);
        JButton close = Style.secondary("Close");
        close.addActionListener(event -> dialog.dispose());
        south.add(close);
        dialog.add(south, BorderLayout.SOUTH);

        dialog.setSize(660, 520);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static String dayLabel(int dayOffset) {
        if (dayOffset == 0) {
            return "Day 1";
        }
        return "Day " + (dayOffset + 1);
    }

    // ---------------------------------------------------------------- formatting

    private static String money(BigDecimal amount) {
        NumberFormat format = NumberFormat.getCurrencyInstance(LOCALE);
        format.setMaximumFractionDigits(0);
        return format.format(amount);
    }

    static String formatDuration(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        if (hours == 0) {
            return rest + " min";
        }
        return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }

    private static String readableDate(LocalDate date) {
        return date == null ? "the chosen date" : date.format(DATE_OUT);
    }
}