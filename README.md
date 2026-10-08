# Train Reservation System

**Status: Milestone 9 complete - the interface is themed and animated within limits, the
failure messages are safe, thirteen self-checks pass against a real database, and the
operator guide below is complete. The waitlist remains out of scope for this build; see
14.7.**

| Milestone | State |
|---|---|
| 1 - audit and migration plan | complete |
| 2 - MySQL database and JDBC foundation | complete |
| 3 - authentication and main dashboard | complete |
| 4 - train search, routes, class availability | complete |
| 5 - passenger details, berth preferences, booking summary | complete |
| 6 - booking engine (reserve and confirm) | complete |
| 7 - history, cancellation, waitlist | complete (no waitlist - see 14.7) |
| 8 - administration console | complete |
| 9 - interface, testing, documentation, delivery | complete |

The repository still contains the working **Bus Ticket Booking System** (Java Swing,
123 passing self-checks). On top of it now sits a **railway application** with a
14-table MySQL schema, seeded demonstration data, a JDBC layer with no hardcoded
credentials, local account registration and login, a session-gated dashboard, a
train search that reads stations, timetables, fares and seat availability from the
database, a passenger form that produces a validated in-memory booking draft, a
booking engine that allocates berths and commits the booking in one transaction, and a
ticket screen with a transactional cancellation that releases the berths and records
the refund.

Sections 1-8 below are the Milestone 1 deliverable and are unchanged: the inventory,
target architecture, phased plan, risks, and file-change list. **Section 9 is the
Milestone 2 deliverable, section 10 is Milestone 3, section 11 is Milestone 4,
section 12 is Milestone 5, section 13 is Milestone 6, section 14 is Milestone 7, and
section 15 is Milestone 8.**
[Current implementation](#current-implementation) documents the legacy bus app, which
stays runnable throughout.

---

## What this is

A desktop **train reservation system** in Java Swing over MySQL 8: register, search the
timetable, pick seats, book, get a PNR, and print a ticket. There is also a **Bus Ticket
Booking System** in this repository - it is the original application, it stays runnable,
and `com.busticket.Main` still launches it. Everything documented below is the railway
application unless a line says otherwise.

**Not an IRCTC product.** See [Simulation disclaimer](#10-simulation-disclaimer).

### Features

| Area | What it does |
|---|---|
| Accounts | Local registration and login, bcrypt password hashes, in-memory session gate |
| Search | Origin/destination/date search, timetable, class availability, fares, coach layout |
| Booking | Up to 6 passengers, berth preference or RAC, one transaction for the whole booking |
| Ticket | 10-digit PNR, full ticket view, PNR status lookup, booking history |
| Cancellation | Transactional cancel that releases berths and records the refund |
| Administration | Trains, coaches, berths, fare rules, journeys, bookings; validation and safe deletes |
| Interface | One light theme with a navy sidebar, blue primary actions, railway illustrations, bounded status and working animations |

The design system is `com.busticket.ui.Style`; animations are a self-stopping
`javax.swing.Timer` in `com.rail.ui.StatusBanner` and `com.rail.ui.Working` - they never
decide whether a booking succeeded.

---

## 1. Prerequisites

| Requirement | Version used here |
|---|---|
| JDK | 21 installed, compiled with `--release 17`, so **17 or newer** |
| Maven | 3.9.x, no `settings.xml`, no proxy |
| MySQL | **8.0** server running on port 3306 |
| Driver | `mysql-connector-j`, already in the local Maven repository |

```powershell
java -version
mvn -v
```

Maven Central is **unreachable from this machine**, so builds run offline (`mvn -o`). If a
dependency is ever added, the driver JAR has to be installed into `~/.m2/repository` by
hand first.

---

## 2. Create the database

Three files, in this order. Use the MySQL client, Workbench, or MySQL Shell - the `mysql`
command is **not** on `PATH` here, so a full path such as
`"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"` is needed.

```sql
CREATE DATABASE railway CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p railway -e "source database/schema.sql"
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p railway -e "source database/sample_data.sql"
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p -e "source database/create_app_user.sql"
```

`create_app_user.sql` creates the least-privilege `railway_app` account the application
connects as. Replace `CHANGE_ME` with a password of your own before running it, and keep
the `GRANT`s exactly as written - the application needs `SELECT`, `INSERT`, `UPDATE`,
`DELETE` and `EXECUTE`, and nothing else. Section 15 lists the grants the administration
feature needs on top.

### Showing the database to somebody else

One file holds the whole thing - every `CREATE TABLE`, every constraint, every row - and it
opens in any editor, SQL viewer or Workbench on any machine:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqldump.exe" -u root -p `
    --single-transaction --no-tablespaces --triggers `
    --result-file=database\railway_demo_dump.sql railway
```

`database/railway_demo_dump.sql` is that file: 14 tables, the
`v_journey_berth_occupancy` view, and the current data. Anyone can read it, or load it
somewhere harmless:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p -e "CREATE DATABASE railway_copy"
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p railway_copy -e "source database/railway_demo_dump.sql"
```

**Use `--result-file`, never `>`.** PowerShell's `>` re-encodes a native program's output
as UTF-16, and the resulting file fails to load with `ASCII '\0' appeared in the statement`.
The same trap applies to `<` on the way in: it is a `cmd.exe` feature, so `-e "source ..."`
is the form that works in PowerShell.

The dump contains real data, including the contact name and mobile number on any booking
you have made, so treat it as private. Passwords are stored as bcrypt hashes, never in the
clear.

To browse it live instead, `mysql -u railway_app -p railway` and ask questions directly:

```sql
SHOW TABLES;                              -- what is here
DESCRIBE bookings;                        -- how one table is shaped
SHOW CREATE TABLE fare_rules\G            -- the exact DDL, keys and all
SELECT * FROM v_journey_berth_occupancy;   -- the view
SELECT pnr, boarding_station_code, destination_station_code, total_fare
  FROM bookings ORDER BY booking_date DESC;  -- what has been booked
```

---

## 3. Configure

```powershell
copy database\local.properties.example database\local.properties
notepad database\local.properties
```

Then set two lines in `database/local.properties`:

```properties
db.user=railway_app
db.password=the-password-you-chose
```

**Where the file must go:** `<project>/database/local.properties`, beside the application.
RailReserve searches two places, first hit wins:

1. `database/local.properties` relative to the directory you launched from
2. `database/local.properties` relative to the project the jar or classes came from

That second candidate is why the packaged jar works from any directory. It was missing
before, so a jar started anywhere but the project root reported "no database is configured"
while the file sat unread beside it. When nothing is found, the startup dialog names the
exact absolute path it wants.

**Every property it accepts** - all optional except the user and a way to reach the server:

| Property | Meaning | Default |
|---|---|---|
| `db.url` | Whole JDBC URL; wins over the three parts below | assembled from the parts |
| `db.host` | Server host name | `localhost` |
| `db.port` | Server port | `3306` |
| `db.name` | Schema name | `railway` |
| `db.user` | MySQL account | **none - there is no default** |
| `db.password` | That account's password | **none** |
| `db.driver` | JDBC driver class | `com.mysql.cj.jdbc.Driver` |
| `search.max.passengers` | Largest party per search and booking | `6` |
| `booking.min.age` | Youngest accepted passenger | `1` |
| `booking.max.age` | Oldest accepted passenger | `120` (the schema's own ceiling) |

A value that is not a positive whole number is ignored rather than trusted, so a typo
cannot remove a limit; an inverted age window falls back to both defaults.

`database/local.properties` holds the only real password on this machine. It is listed in
`.gitignore` and must never be committed; this project is not a git repository, so the
rule is yours to keep.

Settings resolve first-hit-wins, so nothing needs to be written to disk at all:

1. JVM system properties - `-Ddb.user=railway_app -Ddb.password=...`
2. Environment variables - `RAIL_DB_USER`, `RAIL_DB_PASSWORD`, `RAIL_DB_URL`
3. `database/local.properties`

There is **no default account and no default password**. With nothing configured the
application says exactly what to set instead of quietly trying something, and it never
writes a configuration file for you.

---

## 4. Build and run

```powershell
mvn -o clean package
java -jar target\bus-ticket-booking-0.1.0.jar
```

The login screen opens. Close it with the window control; the process exits cleanly and
prints nothing.

The jar can be started from any directory - the project folder, your home folder, a
shortcut - because the configuration file is found beside the jar as well as beside the
working directory (section 3). Only moving the jar *away* from `database/local.properties`
breaks that.

---

## 5. Accounts

**First account.** Register on the login screen - name, username, email, mobile, password.
A new registration is a `CUSTOMER`.

**Becoming an administrator.** Promotion is a deliberate database step, not a UI button:

```sql
UPDATE users SET role = 'ADMIN' WHERE username = 'your-username';
```

Sign out and back in - the session is created at login.

**Seeded demo rows.** `sample_data.sql` inserts `demo_user` and `demo_admin` with **no
password**, so neither can log in. They exist as reference rows for foreign keys and for
the administration screen's own lookups.

**Test fixture.** `com.rail.PersistenceSelfCheck` leaves one real account,
`persist.check`, with one confirmed booking for 2027-12-15. It is deliberate: the check
proves a committed booking survives a restart, and it is idempotent on re-runs.

---

## 6. Example workflow

1. **Register** and sign in. The dashboard is session-gated.
2. **Search Trains** - origin, destination, date, and up to 6 passengers.
3. Pick a train from the result. The timetable, per-class availability, fares, and coach
   layout all come from the database.
4. **Add passengers** - name and age per person, validated against the configured age
   window; choose a berth preference or RAC.
5. **Review and confirm.** The whole booking commits in one transaction, so a failure
   leaves nothing half-written.
6. **Note the PNR** - 10 digits. It appears on the ticket, in **My Bookings**, and works
   in **PNR Status**.
7. **Cancel** from the ticket or from history. The berths are released and the refund is
   recorded in `cancellations`.

Administrators additionally get **Open administration** on the dashboard: trains, coaches,
berths, fare rules, journeys and bookings, with the rules that stop a train being removed
while journeys still reference it.

---

## 7. Testing

Fourteen self-checks, no test framework - standard library only. Run them after
`mvn -o test-compile`:

```powershell
# legacy bus application, no database needed
java -cp "target\classes;target\test-classes;target\lib\*" com.busticket.ModelSelfCheck
# railway application
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.DatabaseSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.DbConfigSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.AuthSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.SearchSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.BookingDraftSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.ui.BookingConfirmationSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.BookingEngineSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.BookingManagementSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.PersistenceSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.ui.TicketScreenSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.AdminSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.ui.AdminScreenSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.ui.NavigationSelfCheck
```

Notes that matter when reading the output:

- The database suites **skip** and exit successfully when no database is configured, so an
  offline build stays green.
- `DbConfigSelfCheck` needs no database and no real credential. Run it from a second
  directory as well - it prints which of the two search locations matched, which is the
  regression test for "no database is configured" on a jar started from elsewhere.
- `PersistenceSelfCheck` **keeps** the one booking it commits - proving data survives a
  restart is its whole purpose, and the application account has no `DELETE`, so it cannot
  tidy up after itself. Expect exactly one extra booking under the account `persist.check`
  (contact name "Persistence Check") however many times you run it. `BookingEngineSelfCheck`
  counts from a snapshot taken at startup, so that row, and any booking you make yourself
  through the application, no longer make it report a leak.
- `DatabaseSelfCheck` ends with a refused-connection check: it proves the server refuses a
  bad account with SQLState `28000`, that the user is shown a sentence naming no account or
  SQL, and that the detail goes to `stderr` without the password.
- `PersistenceSelfCheck` **commits** its fixture and is idempotent; re-running it adds
  nothing.
- `AdminSelfCheck` includes DDL checks. On a database created before Milestone 8 they need
  `database/fix_ck_fare_specificity.sql` applied by an administrator - the application account
  cannot run `ALTER TABLE`. See 15.8.

---

## 8. When it will not start

Four different problems, four different sentences, because "no database is configured"
when the template is already there sends the operator copying a file they copied an hour
ago. Startup opens one connection before any window appears and classifies what comes back:

| What is wrong | What it says | What to do |
|---|---|---|
| No configuration file | the exact absolute path to create | copy the template there |
| Nothing listening on `db.host:db.port` | "MySQL could not be reached", naming the host and port | start the `MySQL80` service |
| Server refused the account (SQLState `28xxx`) | "MySQL refused the account" | check `db.user` and `db.password` |
| Schema absent (`Unknown database`) | names the schema it wanted | load `database/schema.sql` |
| Anything else | SQLState and vendor code, detail on the console | read the console |

No password appears in any of them, or in the `stderr` line: a Connector/J message names
the account and the host and never the password it was refused over. Nothing here changes
database permissions, and nothing connects as `root` on its own.

---

## 9. Known limitations

- **No waitlist.** Out of scope for this build, see 14.7.
- **No payment.** Fares are computed and a refund is *recorded*; no money moves.
- **No live train feed.** Schedules, fares, and berths are seeded sample data.
- **Availability is materialised on demand.** A `journeys` row exists for a date only
  after somebody searches it; the sample data seeds none.
- **Single-user local desktop app.** No server, no sessions shared between machines, no
  password reset flow - a DBA resets by updating `users`.
- **SQL connection failures are reported, not retried.** The message says what to check;
  fixing it is the operator's move.
- **The configuration file must stay beside the application.** The loader also accepts it
  from the working directory, so a jar copied *out* of the project loses its settings and
  says so; nothing else about the search is configurable.

---

## 10. Simulation disclaimer

This is a **simulation**: a self-contained teaching and demonstration project. It is **not
affiliated with, endorsed by, or connected to IRCTC or Indian Railways**, and it must not
be used to buy a real ticket. All stations, trains, schedules, coaches, fares, and berth
layouts in the sample data are invented for demonstration. Any resemblance to a real
service is coincidental.

---

## Running it

```powershell
# 1. Database must exist first - see section 2 above
#    database\local.properties must exist with your own credentials

# 2. Build and launch (opens the login screen)
mvn -o clean package
java -jar target\bus-ticket-booking-0.1.0.jar

# 3. Verify - every self-check, all fourteen (listed in full in section 7)
java -cp "target\classes;target\test-classes;target\lib\*" com.busticket.ModelSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.DatabaseSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.DbConfigSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.AuthSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.SearchSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.BookingDraftSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.ui.BookingConfirmationSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.BookingEngineSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.BookingManagementSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.PersistenceSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.ui.TicketScreenSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.AdminSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.ui.AdminScreenSelfCheck
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.ui.NavigationSelfCheck
```

The legacy bus demo is still one command away and needs no database:

```powershell
java -cp "target\classes;target\lib\*" com.busticket.Main
```

---

## 1. Audit of the current project

### 1.1 Build and environment (verified on this machine)

| Item | Value |
|---|---|
| Build tool | Apache Maven 3.9.14, no `settings.xml` (no mirror, no proxy) |
| JDK installed | 21.0.10 LTS |
| Compiler target | `maven.compiler.release` = **17** |
| Packaging | `jar`, main class `com.busticket.Main` |
| Dependencies | **none** — JDK standard library only |
| Source files | 16 main (2,263 lines) + 1 test (659 lines) |
| Version control | **none — this is not a git repository** |
| SQL / config files | **none exist** |

Verification run during this audit:

```
mvn -o compile                        -> COMPILE OK
mvn -o test-compile
java -cp "target\classes;target\test-classes" com.busticket.ModelSelfCheck
                                     -> All 123 checks passed.
```

Three environment findings that directly constrain the plan:

1. **MySQL 8.0 server is installed and running**, listening on port 3306
   (`MySQL80` service, `Running`). However the **`mysql` command-line client is not on
   `PATH`**, so schema setup must go through JDBC, the MySQL service's own tooling, or a
   full path to the client until the client is added.
2. **Maven Central is unreachable from this machine** — an HTTPS request to
   `repo.maven.apache.org` timed out. The local `~/.m2/repository` holds 3,100 files but
   **no MySQL driver**. Adding `mysql-connector-j` will therefore fail to resolve until
   either the network is opened, a mirror is configured, or the driver JAR is installed
   into the local repository by hand. **This blocks Milestone 3 and must be resolved
   first.**
3. **The project is not under version control.** There is no rollback path for a
   migration of this size. `git init` plus a commit of the current green state is the
   first action of Milestone 2.

### 1.2 Entry point and screen navigation

`Main.main` (21 lines) wraps construction of `MainFrame` in
`SwingUtilities.invokeLater`, so every screen and all current work runs on the EDT.

`MainFrame` owns a `CardLayout` with six named screens and holds the shared booking
context (`chosenSource`, `chosenDestination`, `journeyDate`, `chosenBus`) as instance
fields. Navigation is a fixed linear flow plus sidebar buttons:

```
Sidebar: [Home] [Book Tickets] [My Bookings]
                |
  ROUTE_SCREEN  ->  BUS_SCREEN  ->  SEAT_SCREEN  ->  PASSENGER_SCREEN  ->  CONFIRMATION_SCREEN
   RoutePanel       BusSelectionPanel  SeatSelectionPanel   PassengerPanel      ConfirmationPanel
                                                                       ^                    |
                                                                       +--- goBackToSeatSelection
   BUS_SCREEN <------------------------------------------------------------ goBackToBusSelection
   HISTORY_SCREEN -> BookingHistoryPanel (reachable from sidebar and from the confirmation screen)
```

Note: `Home` and `Book Tickets` both call `showRouteSelection()`; the two nav buttons are
indistinguishable in behaviour.

### 1.3 Domain model and booking logic

| Class | Responsibility | Bus-specific? |
|---|---|---|
| `model/Bus` | id, name, source, destination, departure/arrival `LocalTime`, `farePerSeat`, owns 20 `Seat` objects, `getTotalFare(n)`, `getRoute()` | Yes |
| `model/Seat` | number, `available` flag, `getDisplayNumber()` (`%02d`), static `formatSeatNumbers` | Yes (seat, not berth) |
| `model/Booking` | id, passenger name, mobile, optional email, journey date, `Bus`, `List<Integer> seatNumbers` | Yes |
| `data/BusData` | 12 hardcoded buses over 6 routes, static singleton list, `getBusesByRoute` | Yes |
| `service/BookingService` | all booking rules and all records, entirely static | Partly |
| `service/BookingResult` | success flag + message + booking | **No — reusable as-is** |
| `ui/Style` | colours, fonts, spacing, `RoundedPanel`, `RoundedButton`, `warn()` dialog | **No — reusable as-is** |

Booking logic that exists today, and must survive the migration:

- **Availability is per journey, not per vehicle.** `JOURNEY_SEATS` is keyed
  `"BUS03|2026-10-05"` (service/BookingService.java:156), so a seat taken on one date is
  free on another. `Seat.available` stays a property of the bus and is never mutated by a
  booking.
- **All-or-nothing commit.** `validateSelection` runs immediately before the commit
  (service/BookingService.java:86) so a failed group booking never half-commits. A `TreeSet`
  dedupes repeated seat numbers so nothing is charged or taken twice.
- **One version of the rules.** The UI never decides what is allowed; it calls
  `BookingService` and displays the returned message.
- **Validation:** passenger name required; mobile required, 10 digits starting 6-9, with
  `+91`/`91`/spaces/dashes normalised away; email optional but structurally checked.
- **Fare:** `farePerSeat × seatCount`, computed not stored, so it cannot drift.
- **Booking id:** `BT1001` upwards from a static counter; failures do not consume an id.

### 1.4 Existing tests

`src/test/java/com/busticket/ModelSelfCheck.java` is a 659-line framework-free harness of
**123 assertions**. It covers the sample data, model accessors, route-search rules, screen
hand-over, seat selection, passenger validation, and the full booking rule set including
the per-date availability model and the all-or-nothing guarantee. UI-dependent checks are
guarded by `GraphicsEnvironment.isHeadless()` and skip cleanly. `check()` throws on the
first failure, so a non-zero exit means a broken build.

This file is the project's safety net and the reason the migration can be done
incrementally. Its skip-if-unavailable idiom is also the right pattern for the future
database checks.

---

## 2. What is reused, modified, replaced

### 2.1 Reuse unchanged

| Component | Why it survives |
|---|---|
| `ui/Style.java` | 204 lines of theme, rounded panels and buttons with zero domain knowledge |
| `ui/MainFrame.java` shell | `CardLayout` navigation, sidebar, header, and the context-field pattern all generalise |
| `service/BookingResult.java` | already the right shape: success, message, optional payload |
| `ui/ConfirmationPanel.java` layout | ticket-style card, `CheckmarkPanel`; only the field labels change |
| `ui/BookingHistoryPanel.java` | `JTable` + empty-state pattern; gains columns and a Cancel action |
| `Main.java` | 21-line bootstrap; gains database startup and a failure dialog |
| `ModelSelfCheck` idiom | assert-and-count harness, no framework |

### 2.2 Modify in place

| Component | Change |
|---|---|
| `pom.xml` | add `mysql-connector-j` and `jbcrypt`; rename artifact; **the "zero dependencies" claim ends** |
| `ui/MainFrame.java` | new screen constants, railway context fields, extra nav entry for PNR lookup |
| `service/BookingService.java` | static in-memory maps → transactional, multi-passenger, PNR issuing |
| `model/Booking.java` | one passenger → many passengers and tickets, `BT1001` → PNR, add status |
| `data/BusData.java` | becomes `SeedData`/`schema.sql` + `seed.sql` |
| `ui/BusIllustrationPanel.java` | bus drawing → train drawing (swap the `paintComponent` code) |

### 2.3 Replace

| Bus concept | Railway replacement | Note |
|---|---|---|
| city pair `source`/`destination` | `Station` (code, name, city) | search by code or name |
| `Bus` (one route, one fare) | `Train` + `TrainStop` sequence + `TrainClass` | fare depends on class, not vehicle |
| `Bus.SEAT_COUNT = 20`, flat `Seat` | `Coach` → `Berth` with `BerthType` and `Position` | two-level hierarchy, new concept |
| — | `Journey` (train + date) | the bookable unit; replaces the `busId\|date` string key |
| one passenger per booking | `Passenger[]` per booking, each with berth preference | **major new concept** |
| `BT1001` | 10-digit PNR | collision-safe under concurrency |
| — | `FareService` (base fare by class + berth surcharge + quota) | new rules, no bus equivalent |
| — | `CancellationService` (refund, status change, berth release) | entirely new |
| — | waitlist / RAC | deferred, see Phase 8 |

**Deliberately not a rename.** `Bus` becomes `Train` plus `Coach`, `Berth`, `TrainClass`,
`TrainStop` and `Journey`, because trains genuinely differ: a vehicle no longer carries a
single flat fare, seats are organised into coaches, and one booking covers several
travellers who may each need a different berth type. Copying `Bus.java` to `Train.java`
and editing the names would produce a model that cannot express a real railway booking.

---

## 3. Target architecture

```
com.rail
├── Main.java                       Entry point; opens the DB and reports failure clearly
├── config/
│   └── DbConfig.java               Reads URL/user/password from env or system properties
├── db/
│   └── Database.java               Connection factory (DriverManager + try-with-resources)
├── model/
│   ├── Station.java                code, name, city
│   ├── Train.java                  number, name
│   ├── TrainStop.java              ordered stops with arrival/departure times
│   ├── TrainClass.java             SL / AC3 / AC2 / AC1 / GN, base fare, quota
│   ├── Coach.java                  coach identity, class, coach type
│   ├── Berth.java                  berth number, type (SEAT/SLEEPER/AC), position (LOWER/MID/UPPER)
│   ├── Journey.java                train + date — the bookable unit
│   ├── Passenger.java              name, age, gender, berth preference
│   ├── Booking.java                PNR, journey, passengers, tickets, total, status
│   ├── Ticket.java                 one passenger's berth on one booking
│   └── BookingStatus.java          CONFIRMED / CANCELLED
├── dao/
│   ├── StationDao.java
│   ├── TrainDao.java               trains joining stops for a station pair
│   ├── CoachDao.java               coaches and berths of a journey
│   ├── AvailabilityDao.java        free berths per journey/class/coach
│   ├── BookingDao.java             PNR issuing, booking + tickets insert, lookup
│   └── CancellationDao.java        status change, ticket release, refund record
├── service/
│   ├── SearchService.java          station and train search
│   ├── AvailabilityService.java
│   ├── BookingService.java         multi-passenger booking, one transaction
│   ├── CancellationService.java
│   └── FareService.java            base fare + berth surcharge
└── ui/
    ├── MainFrame.java              shell and navigation
    ├── Style.java                  unchanged
    ├── StationSearchPanel.java     replaces RoutePanel
    ├── TrainListPanel.java         replaces BusSelectionPanel
    ├── CoachSelectionPanel.java    replaces SeatSelectionPanel
    ├── PassengerListPanel.java     replaces PassengerPanel
    ├── ConfirmationPanel.java      PNR ticket
    ├── BookingHistoryPanel.java    history + Cancel
    ├── PnrLookupPanel.java         new: PNR status
    └── TrainIllustrationPanel.java replaces BusIllustrationPanel
```

Layer rule, carried over from the bus app and now stricter: **UI never decides what is
allowed.** Screens read input, call a service, and display the returned message. Services
own the rules. DAOs own SQL and never format user-facing text.

### 3.1 Database schema sketch

```sql
stations       (station_code PK, station_name, city)
trains         (train_no PK, train_name)
train_stops    (train_no FK, stop_seq, station_code FK,
                arrival_time, departure_time, distance_km,   PK(train_no, stop_seq))
train_classes  (class_code PK, class_name, base_fare, quota_seats, has_ac)
coaches        (coach_id PK, train_no FK, class_code FK, coach_type, coach_number)
berths         (berth_id PK, coach_id FK, berth_no, berth_type, position)
journeys       (journey_id PK, train_no FK, journey_date,     UNIQUE(train_no, journey_date))
bookings       (pnr_seq PK AUTO_INCREMENT, booking_date, status,
                total_fare, mobile, email)
passengers     (passenger_id PK, pnr_seq FK, full_name, age, gender, berth_preference)
tickets        (ticket_id PK, pnr_seq FK, passenger_id FK,
                journey_id FK, coach_id FK, berth_no, berth_type, position, status)
```

**The critical constraint.** In the bus app, "never sell the same seat twice for the same
journey" was guaranteed by an in-memory `Set`. In SQL the same guarantee is a unique index
on `(journey_id, coach_id, berth_no)` — but only for rows that are not cancelled, and
MySQL unique indexes cannot be partial. Two options:

- **Generated column (preferred).** `active_berth AS (IF(status = 'CONFIRMED',
  CONCAT(journey_id,'-',coach_id,'-',berth_no), NULL))` with `UNIQUE (active_berth)`.
  `NULL`s never collide in a MySQL unique index, so cancelled rows coexist freely and
  cancellation history is preserved.
- **Delete on cancellation.** Physically remove the ticket row and keep the event in a
  `refunds` table. Simpler, but loses the berth's booking history.

This is **decision D1** and must be settled at the start of Phase 5.

**PNR generation (decision D2).** Use `bookings.pnr_seq BIGINT AUTO_INCREMENT` and display
`LPAD(pnr_seq, 10, '0')`. Collision-free with no extra table and no `max(id)+1` race.
The current static `nextBookingNumber` counter cannot be reused — it is not safe under
concurrent transactions.

> **Both options above were tested and both failed, so the schema does something else.**
> The generated-column trick for D1 cannot work: a segment-aware hold is *not* a unique
> index at all (see 9.3), so the check moves into the booking transaction. And storing
> `LPAD(pnr, 10, '0')` as a generated column is rejected by MySQL — `ERROR 3109`, a
> generated column cannot reference an `AUTO_INCREMENT` column. Section 9.4 has the design
> that survived. Written before the schema existed; corrected by execution in 9.8.

### 3.2 Database configuration

Credentials are **never hardcoded and never committed**. `DbConfig` reads, in order:

1. system properties (`-Drail.db.url=...`), then
2. environment variables `RAIL_DB_URL`, `RAIL_DB_USER`, `RAIL_DB_PASSWORD`.

If any is missing it fails with a message naming the missing variable. Only
`schema.sql` and `seed.sql` — which contain no secrets — are committed.

---

## 4. Phased migration plan

Each phase ends with a compiling project and a passing self-check. No phase starts until
the previous one is green.

### Phase 0 — Safety net *(prerequisite, ~5 min)*
`git init`, `.gitignore` for `target/`, commit the current green state. Without this there
is no rollback for anything that follows.

**Exit:** clean baseline commit at 123/123.

### Phase 1 — Package rename *(mechanical, no behaviour change)*
Rename root package `com.busticket` → `com.rail` and the Maven coordinates. Pure rename,
no logic edits, verified by the unchanged 123 checks. Done once, early, so no later phase
commits churn.

**Exit:** compiles, 123/123, artifact renamed.

### Phase 2 — Database foundation
Add `mysql-connector-j` (**requires network or a manual driver install — see Risk R1**),
`DbConfig`, `Database`, `schema.sql`, `seed.sql` with plausible stations, trains, classes,
coaches and berths. Add a second harness `DatabaseSelfCheck` that follows the existing
`isHeadless()` idiom: it skips cleanly when no database is configured, so the build stays
green offline. The bus app must keep running throughout.

**Exit:** schema loads, connection check passes, bus app unaffected.

### Phase 3 — Railway domain model
Pure-Java `Station`, `Train`, `TrainStop`, `TrainClass`, `Coach`, `Berth`, `Journey`,
`Passenger`, `Booking`, `Ticket`, `BookingStatus`. No UI, no SQL. Extend the self-check to
cover the new model the way it covers the old one. **The bus model stays until each
railway class is covered by a passing check** — this is the "do not remove working
functionality until its replacement is understood" rule, enforced by the tests.

**Exit:** new model fully covered, old model still present.

### Phase 4 — Read path: stations, trains, DAOs
`StationDao`, `TrainDao`, `SearchService`. `StationSearchPanel` and `TrainListPanel` read
from the database via `SwingWorker`. Move DB reads off the EDT — see Risk R2.
`BusData` stops being the source of truth once search works from SQL.

**Exit:** station search and train list served from MySQL.

### Phase 5 — Availability and coach/berth selection *(settle D1 and D2 here)*
`CoachDao`, `BerthDao`, `AvailabilityDao`. `CoachSelectionPanel` picks class → coach →
berth. First real transaction: berth holds enforced by the unique index, with rollback.

**Exit:** a berth cannot be held twice for one journey; verified by a test that races two
inserts.

### Phase 6 — Booking: multi-passenger, fare, PNR
`PassengerListPanel` takes a list of passengers, each with a berth preference.
`FareService` computes base fare by class plus berth surcharge. `BookingService` inserts
booking, passengers and tickets in one transaction and returns the PNR. `ConfirmationPanel`
shows the PNR ticket.

**Exit:** multi-passenger booking commits atomically and issues a unique PNR.

### Phase 7 — History, PNR lookup, cancellation
`BookingHistoryPanel` gains a Cancel action; `CancellationService` releases berths and
records the refund; new `PnrLookupPanel` for status by PNR.

**Exit:** cancel → berth free again for that journey only; PNR lookup works.

### Phase 8 — Optional: waitlist and RAC
Deferred until the above is solid. Only add if the booking flow is genuinely complete.

### Phase 9 — Remove the bus application
Delete `Bus`, `Seat`, `BusData`, `BusIllustrationPanel`, the bus panels and the bus half of
`ModelSelfCheck`, once every railway path is green. Update the README.

**Exit:** no bus code remains; all checks pass.

### Test strategy

| Layer | How it is tested | Needs a database? |
|---|---|---|
| Model, fare, validation rules | extend `ModelSelfCheck` (assert-and-count, no framework) | No |
| Navigation and screen state | existing `isHeadless()`-guarded block | No |
| DAOs and transactions | new `DatabaseSelfCheck`, **skips cleanly when no DB is configured** | Yes |
| Schema | `schema.sql` + `seed.sql` applied to a scratch database | Yes |

Two databases, so development data and test data cannot collide: `railway_dev` and
`railway_test`. The project keeps its zero-framework rule; JUnit is not needed and will not
be added.

---

## 5. Risks

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| **R1** | ~~Maven Central unreachable; no MySQL driver cached~~ **RESOLVED in Milestone 2** — Central was reachable on retry, `mysql-connector-j` 8.4.0 resolved and is now in the local repository | — | Closed. If the network fails again, the driver is cached locally and `-o` still builds |
| **R2** | All current work runs on the EDT. Adding JDBC means network-latency queries freezing the UI | App appears to hang on every screen | Move every DB call onto `SwingWorker`; never touch JDBC from the EDT. Still open — lands in Milestone 3 |
| **R3** | No git repository | No rollback for a large migration | Phase 0. `.gitignore` now exists, but no commit has been made |
| **R4** | The "zero dependencies" property ends | README claim becomes false | **Happened in Milestone 2, as predicted.** Now **two** dependencies: `mysql-connector-j` and `jbcrypt`. Still no ORM, pool, DI or test framework |
| **R5** | Moving from in-memory maps to JDBC can regress the all-or-nothing guarantee | Double-sold berths — the core promise | `setAutoCommit(false)` with rollback, plus the overlap rule in 9.3 as the enforcement. Schema half is in place; the transaction half is Milestone 3 |
| **R6** | Domain is roughly 3× the bus domain: coaches, classes, berth types, multi-passenger, quota, cancellation | Schedule overrun | Phased plan above; each phase independently shippable |
| **R7** | `BookingService` and `BusData` are static mutable singletons | Untestable, un-shareable, no DI for DAOs | Services become instance classes taking DAO constructors as the DAOs are introduced |
| **R8** | No `mysql` CLI on `PATH`, though the MySQL 8.0 server is running | Cannot apply `schema.sql` the obvious way | **Partly resolved** — client found at `C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`. README 9.6 prepends it to `PATH` |
| **R9** | Static `nextBookingNumber` is not concurrency-safe | PNR collisions | Decision D2: `AUTO_INCREMENT` surrogate |
| **R10** | A 12-bus in-memory sample set does not scale to real train data | Slow queries, large result sets | Paging and indexed queries in `SearchService`; seed a realistic but modest data set |
| **R11** | `DATE` columns have no time zone, and train journeys cross midnight | Off-by-one-day availability bugs | Store journey dates as `DATE`, keep times as `TIME`, decide explicitly in Phase 2 how an overnight train is attributed |
| **R12** | Pressure to copy real IRCTC data or call its services | Legal and ethical problem | Out of scope. Seed data is hand-written and plausible. No IRCTC connection, no scraping, no private APIs |

---

## 6. Files likely to change

**Created**

```
src/main/java/com/rail/config/DbConfig.java
src/main/java/com/rail/db/Database.java
src/main/java/com/rail/model/{Station,Train,TrainStop,TrainClass,Coach,Berth,Journey,
                              Passenger,Booking,Ticket,BookingStatus}.java
src/main/java/com/rail/dao/{Station,Train,Coach,Berth,Availability,Booking,Cancellation}Dao.java
src/main/java/com/rail/service/{Search,Availability,Booking,Cancellation,Fare}Service.java
src/main/java/com/rail/ui/{StationSearchPanel,TrainListPanel,CoachSelectionPanel,
                           PassengerListPanel,PnrLookupPanel,TrainIllustrationPanel}.java
src/test/java/com/rail/DatabaseSelfCheck.java
src/main/resources/schema.sql
src/main/resources/seed.sql
.gitignore
```

**Modified**

```
pom.xml                                  connector + renamed coordinates
src/main/java/com/rail/Main.java         DB bootstrap and failure dialog
src/main/java/com/rail/ui/MainFrame.java         new screens, railway context, PNR nav
src/main/java/com/rail/ui/ConfirmationPanel.java PNR fields
src/main/java/com/rail/ui/BookingHistoryPanel.java  columns + Cancel action
src/test/java/com/rail/ModelSelfCheck.java        bus checks out, railway checks in
README.md                                         this document, per phase
```

**Deleted in Phase 9 only**

```
src/main/java/com/rail/model/Bus.java
src/main/java/com/rail/model/Seat.java
src/main/java/com/rail/data/BusData.java
src/main/java/com/rail/ui/BusSelectionPanel.java
src/main/java/com/rail/ui/SeatSelectionPanel.java
src/main/java/com/rail/ui/RoutePanel.java
src/main/java/com/rail/ui/BusIllustrationPanel.java
```

---

## 7. Open decisions

| # | Decision | Needed by |
|---|---|---|
| D1 | Cancelled tickets: generated-column unique index, or delete-and-archive? | Phase 5 |
| D2 | PNR format — confirm 10-digit `AUTO_INCREMENT` | Phase 6 |
| D3 | One booking = one passenger, or several? (plan assumes several) | Phase 6 |
| D4 | Overnight trains: attributed to the departure date? | Phase 6 |
| D5 | Quota and Tatkal windows — in scope, or display-only? | Phase 7 |
| D6 | Waitlist / RAC — in scope or dropped? (plan defers it) | Phase 8 |

**Settled by Milestone 2:** D4 — implemented as `train_stops.day_offset`, with journeys
attributed to the departure date. D2 — implemented as an `AUTO_INCREMENT` PNR, with the
10-digit form formatted in Java rather than stored (MySQL forbids a generated column over
`AUTO_INCREMENT`). D1 — superseded: segment overlap cannot be enforced by a unique index at
all, so the booking transaction owns the rule instead (see 9.3).

---

## 8. Non-goals

- No connection to IRCTC or any private railway service, and no scraping of their data.
- No real payment gateway. Fares are calculated and displayed only.
- No rewrite from scratch. Every reusable component in section 2.1 survives the migration.
- No JUnit, no ORM, no connection pool, no DI framework. `DriverManager` with
  try-with-resources is sufficient for a single-user desktop application.
- No default administrator account and no default password, ever (see 10.6).
- No invented dashboard figures: counts come from the database or the screen says it
  has nothing to show.

---

## 9. Milestone 2 — MySQL database and JDBC foundation

### 9.1 What was built

| File | Purpose |
|---|---|
| `database/schema.sql` | 14 tables + 1 view. Drops and recreates the single `railway` database. |
| `database/sample_data.sql` | Seeded demonstration data. Explicitly **not** live availability. |
| `database/create_app_user.sql` | Creates the least-privilege application account. |
| `database/local.properties.example` | Config template, no secrets. Copy to `local.properties`. |
| `src/main/java/com/rail/db/DbConfig.java` | Resolves URL/user/password from three sources. |
| `src/main/java/com/rail/db/DatabaseConnection.java` | The single place a connection is opened. |
| `src/test/java/com/rail/DatabaseSelfCheck.java` | Connection, schema, seed and overlap-rule checks. |
| `.gitignore` | Excludes `target/` and `database/local.properties`. |
| `pom.xml` | Adds `mysql-connector-j` 8.4.0; `java -jar` still works via `target/lib`. |

**Packages.** New code lives under `com.rail`. The legacy bus app is still under
`com.busticket`, untouched and still green. The package rename remains pending from the
Milestone 1 plan (Phase 1); doing it now would have meant touching working code outside
this milestone's scope.

### 9.2 Schema

14 tables, all InnoDB/utf8mb4:

| Table | Holds |
|---|---|
| `users` | logins; `password_hash` NULL means "no password set" |
| `travel_classes` | SL, CC, 3A, 2A, 1A and their demo rake sizes |
| `stations` | 2-4 letter code, name, city, zone |
| `trains` | number, name, type, distance |
| `train_stops` | ordered route: `stop_seq`, arrival/departure, `day_offset`, cumulative km |
| `coaches` | rake per train; `class_code` FK decides which classes a train offers |
| `berths` | 1-based per coach, with `berth_type` and `position` |
| `journeys` | **one train + one date** — the availability anchor |
| `journey_coaches` | per-journey rake; a withdrawn coach is `DETACHED`, never deleted |
| `fare_rules` | class defaults plus route overrides, most-specific-wins |
| `bookings` | PNR, journey, segment, class, total, status, contact |
| `passengers` | people, with the **requested** berth preference |
| `seat_reservations` | the **allocated** berth, with its own travel segment |
| `cancellations` | cancellation and refund record; nothing is deleted |

Four modelling points worth knowing:

- **A train does not serve every class.** It serves exactly the classes its `coaches`
  rows declare. `DatabaseSelfCheck` asserts that at least one train offers fewer than
  all five.
- **Passenger preference and berth allocation are separate columns in separate tables.**
  A preference survives as evidence after allocation settles on something else.
- **Overnight journeys** use `day_offset`. A train leaving 21:00 and arriving 01:00 has
  `day_offset = 1` on later stops and is attributed to its **departure** date.
- **Cancellation never deletes.** Bookings and reservations survive with
  `status = 'CANCELLED'`; every foreign key in that path is `ON DELETE RESTRICT`.

### 9.3 Segment overlap — the core availability rule

A berth may legitimately be sold twice on one journey when the two bookings do not
overlap in time: `CSTM→PUNE` (stops 1–4) and `PUNE→BZA` (stops 4–7) never share the seat.
MySQL cannot express "these intervals must not intersect" with a unique index, so each
`seat_reservations` row stores `from_stop_seq` / `to_stop_seq` and the booking
transaction of Milestone 3 rejects a clash with:

```sql
SELECT COUNT(*) FROM seat_reservations
 WHERE journey_id = ? AND berth_id = ? AND status IN ('CONFIRMED','RAC')
   AND from_stop_seq < ?   -- requested to_stop_seq
   AND to_stop_seq   > ?;  -- requested from_stop_seq
```

`(journey_id, berth_id, status)` is indexed so this is a lookup, not a scan. The rule is
implemented once in `DatabaseSelfCheck.countOverlaps` so schema, index and rule cannot
drift apart.

### 9.4 PNR

`bookings.pnr` is `BIGINT UNSIGNED AUTO_INCREMENT` — collision-free under concurrent
transactions, which the legacy static counter could not guarantee.

There is deliberately **no** stored or generated 10-character PNR column. MySQL rejects a
generated column that references an `AUTO_INCREMENT` column, so the first draft of this
schema could not even be created. Keeping a second copy of the number would also be a
column that exists only to drift. The 10-digit display form is produced in Java with
`String.format("%010d", pnr)`, so there is exactly one source of truth.

### 9.5 Credentials

No credential is committed. `DbConfig` resolves, first hit wins:

1. system properties — `-Ddb.user=... -Ddb.password=...`
2. environment variables — `RAIL_DB_USER`, `RAIL_DB_PASSWORD`, `RAIL_DB_URL`, …
3. `database/local.properties` — gitignored, the only file allowed to hold a password

There is no default user and no default password, so an unconfigured machine reports what
to do instead of silently trying something. `describe()` reports the source, URL and user
but never the password.

**The application must not use the MySQL root account.** `database/create_app_user.sql`
creates `railway_app` with `SELECT` on the schema plus `INSERT`/`UPDATE` on only the six
operational tables — no `CREATE`, `ALTER`, `DROP` or `GRANT`. `DatabaseConnection` prints
a warning if the configured account is root.

Demo users in `sample_data.sql` ship with `password_hash` **NULL**, i.e. no password set
and unable to log in. No default password exists anywhere in the project.

### 9.6 Database setup

The MySQL 8.0 server is running on port 3306 on this machine, and the client is at
`C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe` (not on `PATH`).

```powershell
$env:Path += ";C:\Program Files\MySQL\MySQL Server 8.0\bin"

# 1. Load the schema (destructive: drops and recreates the `railway` database)
mysql -u root -p --execute="source database/schema.sql"

# 2. Load the demonstration data
mysql -u root -p --execute="source database/sample_data.sql"

# 3. Create the least-privilege application account.
#    Edit database\create_app_user.sql first and replace CHANGE_ME_STRONG_PASSWORD.
mysql -u root -p --execute="source database/create_app_user.sql"

# 4. Configure the application locally (this file is gitignored)
copy database\local.properties.example database\local.properties
notepad database\local.properties      # set db.user and db.password

# 5. Run the checks
mvn -q clean package
javac -cp "target\classes" -d target\test-classes src\test\java\com\rail\DatabaseSelfCheck.java
java -cp "target\classes;target\test-classes;target\lib\*" com.rail.DatabaseSelfCheck
```

`database/local.properties` must sit beside the application, at
`<project>/database/local.properties`. RailReserve looks for it beside the directory you
started it from **and** beside the jar or the compiled classes, so a jar launched from
anywhere on the machine still finds it as long as the jar has not been moved away from the
project. The "no database is configured" dialog names the exact absolute path it wants when
it cannot find the file. See section 3 for every property name it accepts.

Use `--execute="source …"` rather than the more familiar `mysql -u root -p < file.sql`.
The `<` redirect is a `cmd.exe` feature; PowerShell reserves that character, so the
familiar form throws a parser error. Run these from the project root — the paths above are
relative.

Sanity check without the application:

```powershell
mysql -u root -p -e "USE railway; SHOW TABLES; SELECT COUNT(*) AS berths FROM berths;"
```

### 9.7 Verification status

Everything below was **actually executed** against the local MySQL 8.0.40 server on port
3306, using the least-privilege `railway_app` account:

| Check | Result |
|---|---|
| `mvn clean package` with `mysql-connector-j` | **passes** |
| `mysql-connector-j` 8.4.0 resolves from Maven Central | **passes** — R1 from Milestone 1 is resolved |
| `java -jar` manifest classpath (`lib/…`) | **verified** — driver sits beside the jar |
| Legacy `ModelSelfCheck` | **passes, 123/123** — bus app unaffected |
| `database/schema.sql` loaded into MySQL | **passes** |
| `database/sample_data.sql` loaded into MySQL | **passes** |
| `SHOW GRANTS` confirms least privilege | **verified** — `SELECT` on the schema, `INSERT`/`UPDATE` on 6 tables, no DDL |
| `com.rail.DatabaseSelfCheck` against the live server | **passes, 70/70** |
| Transaction rollback leaves the database clean | **verified** — `journeys`, `bookings`, `passengers`, `seat_reservations`, `cancellations` all back to 0 |

The 70 checks cover the connection, all 14 tables and the view, 27 foreign keys, 22 CHECK
constraints, the seed-data invariants, berth-layout invariants, route integrity, and a full
booking transaction including the overlap rule.

### 9.8 Five bugs that only execution could find

Reviewing this code would not have caught any of these. Each one passed inspection and
failed the moment it met a real server:

1. **`pnr_display` was not creatable.** MySQL forbids a generated column referencing an
   `AUTO_INCREMENT` column (`ERROR 3109`). The whole schema aborted at the `bookings` table.
2. **`station_code CHAR(3)` could not hold real station codes.** `PUNE` and `CSTM` are 4
   characters, so the seed data failed with `ERROR 1406`. Widened to `VARCHAR(4)`.
3. **The CHECK constraint then excluded a real code.** Constraining the length to `3..4`
   rejected `ST` (Surat, a genuine 2-letter code, `ERROR 3819`). Now `2..4`.
4. **The self-check looked for `TABLE_TYPE = 'TABLE'`.** The actual value is
   `BASE TABLE`, so the schema section reported "table users exists" as failing against a
   database that demonstrably contained it. The view check used `VIEW` and passed, which is
   exactly why the typo stayed hidden.
5. **An unbound parameter and a leaked statement.** The `journey_coaches` insert declared
   `?` for `journey_id` and never bound it (`No value specified for parameter 1`), and built
   its `PreparedStatement` inline, so it was never closed.

A sixth had been caught earlier by running the unconfigured path: `DbConfig.load()`
dereferenced a null `Properties` when no config file existed, breaking the skip path for any
unconfigured machine. `readProperties` now always returns a non-null instance.

Bug 4 is the one worth remembering. It was a check that reported a confident, specific,
false failure — and the only reason it surfaced is that the script was finally run against a
real server instead of being read.

### 9.8 Sample data, and what it is not

Seeded: 5 travel classes, 6 stations (Mumbai → Kalyan → Pune → Nashik Road → Kopargaon →
Surat), 4 trains, 17 stops, 18 coaches, 414 generated berths, 8 fare rules, 2 demo users.

Seeded **zero** rows into `journeys`, `journey_coaches`, `bookings`, `passengers`,
`seat_reservations` and `cancellations`. A journey is created on demand when a date is
first searched or booked, so seeding fake confirmed reservations would be exactly the
fabricated live availability this project must not present. Instead `DatabaseSelfCheck`
exercises the full booking path — journey, rake, booking, passenger, reservation, and the
overlap rule — inside a transaction it then **rolls back**, so the schema is proven to work
and the database is left untouched.

Station codes and train numbers resemble real services so the schema is easy to picture.
The schedules, fares and rake compositions are invented. This is not scraped from, and not
connected to, any railway operator.

---

## 10. Milestone 3 — authentication and main dashboard

### 10.1 Password hashing, and why jBCrypt

`org.mindrot:jbcrypt:0.4` is the project's second dependency. The JDK has no password
hashing, and BCrypt cannot be improvised — a hand-rolled hash is how plaintext ends up
in a database years later.

Spring Security would do the same job but drags in a web framework, a DI container and
dozens of transitive jars. jBCrypt is one self-contained jar with no dependencies.

- Cost factor **12** (2^12 rounds). Deliberately not higher: this is a single-user
  desktop app where a 4× slower login would be felt as an unresponsive UI.
- BCrypt only reads the first 72 bytes of a password. That is a property of the
  algorithm, and `AuthService` caps passwords below that limit.
- The hash is salted per password by the library, so two accounts with the same
  password have different hashes.

### 10.2 What was built

| File | Purpose |
|---|---|
| `com/rail/model/Role.java` | `CUSTOMER` / `ADMIN`, mapped to the stored enum |
| `com/rail/model/User.java` | Session identity — **no hash, no email, no mobile** |
| `com/rail/model/StoredUser.java` | A users row *including* the hash |
| `com/rail/model/BookingSummary.java` | One booking, as the dashboard shows it |
| `com/rail/security/PasswordHasher.java` | The only place a password is ever hashed |
| `com/rail/db/UserDao.java` | Users table reads and inserts |
| `com/rail/db/BookingDao.java` | Booking reads, always scoped by `user_id` |
| `com/rail/db/ConnectionSource.java` | One-method seam so checks can run in a transaction |
| `com/rail/service/AuthService.java` | Registration, login, and the permission gate |
| `com/rail/service/SessionManager.java` | The current user; `requireUser`, `requireAdmin` |
| `com/rail/service/AccessDeniedException.java` | Checked, so a missing check will not compile |
| `com/rail/service/BookingQueryService.java` | Booking history, always self-scoped |
| `com/rail/ui/*` | Login, registration, dashboard, Java2D artwork, nav |
| `com/rail/Main.java` | Entry point; `java -jar` now opens the login screen |

### 10.3 Screens

All three follow the brief: navy sidebar, light background, blue primary buttons,
rounded white panels, one type scale, one spacing unit.

- **Login** — navy brand panel with a Java2D locomotive, white card, Enter submits.
- **Registration** — same shell, one field more.
- **Dashboard** — navy sidebar (Home, Search Trains, My Bookings, PNR Status, Logout),
  light working area, greeting with the traveller's real name.

Icons are drawn with Java2D (`NavIcon`), as is the locomotive and the round rail mark
(`RailwayArt`). No image files, so the jar stays self-contained and sharp on HiDPI.

**Animations: one.** An error strip flashes twice using a `Timer` driving a single
repaint. Nothing animates that could delay a paint or block the EDT. All database work
runs on a background thread, because a BCrypt verification is slow enough to be felt.

### 10.4 Security

**Passwords.** Hashed with BCrypt, never stored in plaintext, never logged, never
returned to a caller, never held in a field. `User` — the type the session and the UI
see — has no hash accessor at all. `UserDao.findProfile` does not even select the
column, so a display bug could not leak one.

**Login failures are indistinguishable.** A wrong password, an unknown username, a
disabled account, and an account whose hash is `NULL` all produce the same sentence and
the same wording. Unknown usernames additionally burn equivalent BCrypt time against a
dummy hash, so the *duration* of a failed attempt does not reveal whether the account
exists either.

A `NULL` `password_hash` — which is what `sample_data.sql` ships — means "no password
set" and is refused. It is never read as "any password is fine".

**Permissions are enforced in the service layer.** Hiding a button is presentation only:

| Rule | Enforced by | Result if the UI is bypassed |
|---|---|---|
| Dashboard needs a session | `DashboardFrame` constructor calls `requireUser()` | refuses to be constructed |
| Only your own bookings | `BookingQueryService.myBookings()` takes the id from the session | not overridable by a caller |
| Someone else's bookings need admin | `requireSelfOrAdmin` | throws `AccessDeniedException` |
| Whole-table counts need admin | `AuthService.adminAllBookingsCount()` calls `requireAdmin()` | throws |

Isolation is a `WHERE user_id = ?` in SQL, so another traveller's booking is never
loaded into memory to be filtered out afterwards. `AccessDeniedException` is **checked**,
so forgetting a check is a compile error rather than a silent hole.

**Least privilege.** `railway_app` gained `INSERT` on `users` (registration needs it) and
nothing else. It still has **no `UPDATE` and no `DELETE` on `users`**, so the application
cannot rewrite or remove an account — and cannot manufacture an administrator.

**No default admin password exists anywhere in this project.** See 10.6.

### 10.5 Verification

All three suites were run against the live MySQL 8.0.40 server.

| Suite | Result |
|---|---|
| `com.rail.DatabaseSelfCheck` | **70/70** — unchanged by this milestone |
| `com.rail.AuthSelfCheck` | **63/63** — new |
| `com.busticket.ModelSelfCheck` | **123/123** — legacy bus app untouched |

`AuthSelfCheck` covers registration, duplicate username, duplicate email, nine validation
failures, correct login, wrong password, unknown user, empty password, `NULL`-hash
refusal, logout, and the full permission matrix for both roles.

It runs the whole scenario inside **one transaction on one connection and rolls it
back**, verified afterwards by re-reading the row count: *2 users before, 2 after*. No
test account is left behind, and the check needs no `DELETE` privilege.

That required one design decision worth recording. The services use
try-with-resources, which is right in production because `DatabaseConnection` hands out a
fresh connection each time — but it means a service closes whatever it borrows. So the
check supplies a connection wrapped in a `java.lang.reflect.Proxy` that ignores
`close()`, and closes the real one itself after the rollback. `ConnectionSource` exists
for exactly this and is the only test-shaped thing in the production classes.

### 10.6 Creating an administrator — the safe process

There is **no seeded admin account and no default password**. `sample_data.sql` creates
`demo_admin` with a `NULL` hash, which cannot be signed into.

To make a real administrator:

```powershell
# 1. Register normally through the application's own registration screen.
#    You now know that account's password; nobody else does.

# 2. As a DBA, promote just that account:
mysql -u root -p --execute="source database/schema.sql"   # only if you need a clean reload
mysql -u root -p railway --execute="UPDATE users SET role='ADMIN' WHERE username='your_username';"
```

Why this shape:

- The password is chosen by the person who will use the account, never shipped in a
  script or a migration.
- Nothing in the application can do this — `AuthService.register` has no path that
  writes `ADMIN`, and `railway_app` has no `UPDATE` on `users`.
- It is reversible and auditable: one `UPDATE` by a DBA.

To demote, set the role back to `CUSTOMER`.

### 10.7 Nothing on the dashboard is invented

Every figure is counted from `bookings`, and every list is a real query. A traveller who
has never booked anything sees zeroes and an explicit empty state — there are no sample
bookings, no placeholder rows and no plausible-looking statistics anywhere.

**Search Trains** was the one screen with no data behind it at this point, because the
feature did not exist yet. It said so plainly instead of showing a fake form backed by
nothing. **It is built in section 11**, and now reads real stations, timetables, fares and
seat counts.

**PNR Status** is fully functional against real data: it looks up a PNR scoped to the
signed-in traveller in SQL, and returns "No booking found for that PNR" for both a
non-existent PNR and somebody else's, so it cannot be used to discover valid PNRs.

### 10.8 Four bugs this milestone's own checks caught

- **`railway_app` had no `INSERT` on `users`.** Registration would have failed at
  runtime with a MySQL access-denied error. Found by reading the grants against what the
  new code actually writes.
- **The services closed the connection they were given.** Fine in production, fatal for
  a transactional check. Fixed with the `ConnectionSource` seam described in 10.5.
- **The permission checks ran while still signed in**, so three "signed-out caller is
  refused" assertions would have passed for entirely the wrong reason.
- **`NavButton` could not shadow Swing's public `setSelected`/`isSelected`** with weaker
  access. Renamed to `setChosen`.

The last two were found before they reached a database, but only because the checks were
written to be run rather than read — the same lesson as section 9.8.

## 11. Milestone 4 - train search, routes, timetables and class availability

### 11.1 What was built

| Piece | Where |
|---|---|
| Station and class dropdowns | `stations` / `travel_classes`, loaded at runtime |
| Ordered-segment search | `TrainDAO.findRouteMatches` |
| Validation | `TrainSearchService.validate` |
| Fare resolution | `TrainDAO.farePerPassenger` |
| Segment availability | `TrainDAO.segmentBerths` |
| Screens | `TrainSearchPanel`, plus the route dialog |
| Checks | `SearchSelfCheck`, 85 of them |

No station, class, train, fare or seat count is hardcoded in the application. Every value on
the search screen came out of the database on this machine.

### 11.2 Station choices come from the database

`TrainDAO.listStations()` is the only source for the origin and destination dropdowns, and
`listAllClasses()` for the class dropdown. A station added to the `stations` table appears in
the UI with no code change. The checks assert that every returned code matches the schema's
own `[A-Z]{2,4}` rule, so a malformed row is visible rather than silently offered.

### 11.3 Ordering, and journeys that cross midnight

Ordering is one clause:

```sql
JOIN train_stops d ON d.train_no = o.train_no
                  AND d.station_code = ?
                  AND d.stop_seq > o.stop_seq
```

Because `uq_stop_station` guarantees a train visits each station at most once, this join
cannot fan out. Reversing the endpoints finds no service in the seeded timetable, and that is
correct: every train runs outward from CSTM, so no pair is served in both directions.

Midnight is handled with `train_stops.day_offset` rather than by comparing clock times.
Train 12952 leaves CSTM at 21:15 and reaches ST at 08:45 with `day_offset = 1`:

```
naive subtraction   08:45 - 21:15  =  -450 minutes   (nonsense)
day-offset aware    (1*1440 + 525) - (0 + 1275)  =  690 minutes  =  11 h 30 min
```

`TrainSearchService.minutesBetween` does the second calculation. The check asserts all three
facts: the arrival is attributed to day 2, the duration is 690, and the duration is *not* the
naive clock difference.

### 11.4 Availability is scoped to the segment

This is the part most likely to be subtly wrong, so it is worth stating precisely. A berth is
free for the requested segment only if no confirmed reservation overlaps it:

```sql
AND sr.from_stop_seq < :toSeq      -- the sale starts before our segment ends
AND sr.to_stop_seq   > :fromSeq    -- and ends after our segment starts
```

That half-open interval test is what makes a berth sold for PUNE→NAS still show as free for
CSTM→PUNE. The checks sell a berth over stops 1–5, then sell a *second* berth over stops 2–3
only, and assert that the stops 1–2 segment still reports free berths while the whole-route
segment does not.

The count is scoped four ways at once, as the brief requires: **train** (`journey_coaches`),
**journey date** (`journeys`), **class** (`coaches.class_code`) and **segment** (the overlap
test). Only `ATTACHED` coaches count, matching the `v_journey_berth_occupancy` view.

### 11.5 Availability is a simulation, and says so

Berth counts come from this project's own `berths`, `journey_coaches` and
`seat_reservations` tables. **This is not live IRCTC availability** and the screen says so
under the search form, in the dialog that lists results, and in this file. A class with no
published fare rule is left out of the results entirely rather than offered at zero, because
booking it would mean charging nothing.

### 11.6 Journey rows are created on demand

`journeys` has one row per train per date, so searching a date that has never been searched
has to create one. `TrainDAO.ensureJourney` does this with `INSERT IGNORE` against the
`(train_no, journey_date)` unique key, so searching the same train and date twice reuses the
row instead of creating a rival. It also attaches the rake to `journey_coaches`.

The transaction is committed **only when the DAO opened the connection in autocommit mode**.
Handed a connection already inside a caller's transaction — which is what `SearchSelfCheck`
does — it leaves committing and rolling back alone rather than silently breaking the
caller's atomicity.

A consequence worth knowing: searching leaves a `journeys` row behind. That is the intended
design from section 9, not leakage, and the self-check rolls its own rows back (verified: 0
before, 0 after).

### 11.7 Fare resolution

`fare_rules` is resolved most-specific-wins, ordered route-and-distance, then route, then
origin, then class default, with the most recent `valid_from` breaking ties. The charged
distance is always the distance actually travelled, never the distance a rule happens to be
pinned to.

The seeded data proves this both ways. For 2A over CSTM→PUNE (150 km) the pinned rule applies
and the fare is `85.00 + 3.90 × 150 = 670`. The 2A class default would have given
`70.00 + 3.60 × 150 = 610`. The checks assert 670, and assert that the pinned rule does *not*
leak onto a segment it was not pinned to.

### 11.8 Passenger limit

The cap is configuration, not a constant buried in the UI:

```properties
# database/local.properties
search.max.passengers=6
```

Precedence is system property, then environment variable (`RAIL_SEARCH_MAX_PASSENGERS`), then
that file, then the default of 6. A value that is not a positive whole number is **ignored in
favour of the default** — a typo must not silently remove the limit. The spinner is bounded by
the same number, and the checks assert both the refusal above the limit and acceptance at
exactly the limit.

### 11.9 No SQL in a Swing event handler

Every database call in the UI sits inside a `SwingWorker.doInBackground`. This milestone also
fixed the four dashboard screens that were still querying on the event thread (home figures,
recent bookings, my bookings, PNR lookup), which had been outstanding since Milestone 3. A
`fillLater` helper fills a placeholder slot and repaints when the read returns, so the window
stays responsive and a failure has somewhere to be reported.

### 11.10 Verification

| Suite | Result |
|---|---|
| `com.rail.SearchSelfCheck` | **85/85** |
| `com.rail.DatabaseSelfCheck` | 70/70 — unchanged |
| `com.rail.AuthSelfCheck` | 63/63 — unchanged |
| `com.busticket.ModelSelfCheck` | 123/123 — unchanged |
| UI smoke | Search form, result cards, route dialog, state preservation, all validation messages |

Every case the brief lists is covered: valid routes, reversed order, identical stations,
invalid dates, past dates, unsupported classes, and routes with no matching train. The
no-matching-train cases are **discovered from the database** rather than hardcoded, so they
stay real if the timetable changes.

The UI smoke test also drove the form end to end and confirmed the fares on screen are
₹670 / ₹450 / ₹225 for 2A / 3A / SL over CSTM→PUNE — which is the fare-specificity rule
visible through the UI.

### 11.11 Three bugs this milestone's own checks caught

- **Bad input was reported as a database outage.** The search worker wrapped every failure in
  `SQLException`, so *"Origin and destination must be different stations"* reached the screen
  as *"Could not reach the database"* — sending the traveller off to debug MySQL. Caught by
  the UI smoke test, not the service checks, which is an argument for having both.
- **An unknown class passed validation.** `validate` only rejected a blank class, so a typo
  like `3a` for `3A` silently matched nothing and was reported as "no trains found". Now
  checked against `travel_classes`.
- **The passenger limit could be typo'd away.** The configured value is now parsed defensively
  and falls back to the default.

### 11.12 State preserved across navigation

`DashboardFrame` creates **one** `TrainSearchPanel` for the life of the window and re-parents
it, rather than rebuilding it per visit. The route, date and party size therefore survive
navigating to Home and back. The UI smoke test asserts this explicitly.

### 11.13 Known limitations

- A class whose journey is not `SCHEDULED` reports zero availability, so a cancelled train
  reads as "no seats". Distinguishing that state belongs to the booking milestone, when
  cancellations can actually be recorded.
- `trains.runs_daily = FALSE` excludes a train from search results. The schema records no
  weekly running pattern, so there is no honest way to claim such a train runs on a given
  date.
- The seeded timetable has no return-direction services, so a reversed search legitimately
  finds nothing. Adding them is a data change, not a code change.

---

## 12. Milestone 5 - passenger details, berth preferences and booking summary

Selecting a class on the search screen now opens a passenger form. It ends in a **validated
booking draft**. Nothing is booked, no seat is reserved, and no row is written to `bookings`,
`passengers` or `seat_reservations`.

### 12.1 What is collected, and what is deliberately not

Per passenger: full name, age, gender, berth preference, and a meal choice when the class has a
pantry. Separately, one booking contact: mobile (required) and email (optional).

**No concession and no special-assistance fields**, because neither can be handled accurately
here. There is no column for either, and this project's `fare_rules` cannot price a concession —
offering the field would mean collecting an answer the application could not honour. The same
reasoning excludes passport numbers, dates of birth and addresses: none is needed to reserve a
seat, and each would be another piece of personal data to store and protect.

### 12.2 A preference is never an allocation

`BerthPreference` is a *request*. The form, the summary and the table all label it a preference,
and the model javadoc says outright that it is not a berth. This project performs **no automatic
allocation at all**, so there is nothing that could turn a preference into a seat even in
principle. A lower-berth request that cannot be filled stays a request.

### 12.3 Preferences are read from the coaches, not the class name

This is the part most likely to be wrong, and the data disagreed with the obvious assumption:

| Class | `is_sleeper` | Berth positions its coaches actually contain | Offered |
|---|---|---|---|
| `1A` AC First Class | yes | LOWER, UPPER | No Preference, Lower, Upper |
| `2A` AC 2 Tier | yes | LOWER, UPPER | No Preference, Lower, Upper |
| `3A` AC 3 Tier | yes | LOWER, MIDDLE, UPPER | No Preference, Lower, Middle, Upper |
| `SL` Sleeper | yes | LOWER, MIDDLE, UPPER | No Preference, Lower, Middle, Upper |
| `CC` Chair Car | no | WINDOW, AISLE | No Preference, Window, Aisle |

Two traps, both asserted directly in the checks:

- **AC 2-Tier is a sleeper class with no middle berths.** A preference list derived from
  `is_sleeper` would offer a middle berth on 2A that no coach in the database contains.
- **Chair Car is seated.** It must offer window and aisle, and must not offer a lower berth at
  all, despite `berths.position` having room for one.

So `TrainDAO.classProfile` reads the distinct `berths.position` values present in the class's
coaches, and `BerthPreference.forPosition` maps them. `ClassProfile`'s constructor guarantees
`No Preference` is always first, because it is the one choice every class has.

### 12.4 Side Lower and Side Upper are correctly not offered

The brief lists Side Lower and Side Upper among the preferences. **Neither appears in this
application**, for two independent reasons:

1. No coach in the sample data has a `SIDE` berth, so there is nothing to prefer.
2. `berths.position` and `passengers.berth_preference` can only express a single generic `SIDE`.
   The schema cannot distinguish side-lower from side-upper at all.

Both side variants are still modelled in `BerthPreference`, and `forPosition` returns them if a
side berth is ever added, because `SIDE` maps to both. The check asserts against the data rather
than hardcoding their absence, so adding a side coach makes them appear without a code change.

### 12.5 Meals follow the pantry flag

`travel_classes.has_pantry` decides whether the meal field is shown at all: offered for 1A, 2A and
3A, hidden for SL and CC. A meal typed for a train without catering is **refused rather than
silently dropped** — discarding something a passenger deliberately chose is worse than telling
them the train cannot serve it.

The meal choices are illustrative labels. This project has no catering data, so nothing is stored
yet; the choice is carried in the draft for the booking stage.

### 12.6 Contact details are separate from passenger identity

`BookingContact` holds one mobile and one optional email for the whole booking. Folding these into
each passenger would copy one person's number across every traveller in the party.

### 12.7 Validation lives in the service

Every rule is in `BookingPreparationService`; the Swing handlers only collect fields and display
what the service says. This is why the same checks can run without a screen, and why removing a
control from the form does not remove the rule.

| Rule | Behaviour |
|---|---|
| Name required | blank, whitespace-only, empty and `null` all refused |
| Age | whole years only; `12.5`, `twelve`, `-5` and a 1000-digit value all refused |
| Age range | configurable; `DbConfig` clamps the maximum to the schema's own `CHECK (age <= 120)` |
| Gender | one of the three values `passengers.gender` allows, matched case-insensitively |
| Berth preference | refused when the class has no such berth, with the reason named |
| Mobile | ten digits; punctuation and spaces ignored, a leading `91` stripped |
| Email | optional, but must look like an address when given |
| Party size | fixed by the search screen; a seventh passenger is refused, not silently dropped |

The age maximum is clamped rather than trusted, because a configured maximum above 120 would
approve an age that validation accepts and the database then rejects. An inverted range (minimum
above maximum) falls back to the defaults instead of rejecting every age there is.

### 12.8 The draft is in memory, and cannot become a booking

`BookingDraft` has no connection to the database and no save method — there is no code path from
the passenger screen to a confirmed booking. Being lost when the application exits is honest for
this stage: a draft that survived a restart would need somewhere to live, and every such place is
a bookings-shaped table.

The party size was fixed on the search screen, so the passenger form must supply exactly that many
passengers before Continue enables. Continue is disabled until then, and when it does fire it
reports plainly that nothing is booked and no seat is reserved.

### 12.9 Navigation

The draft is held by `DashboardFrame`, not by `PassengerPanel`, so going Back to search and
returning keeps every passenger, preference and contact detail. Choosing a **different** train
starts a new draft, because a passenger list belongs to the train and class it was chosen for;
choosing the **same** train again returns to the passengers already entered instead of quietly
discarding them.

### 12.10 Fare breakdown

The summary shows base fare, distance fare, per-passenger fare and the total. Each half of the
`fare_rules` row is rounded once, so the displayed components add up to the displayed total —
rounding the total instead would let the summary show figures that do not add up. `TrainDAO` now
exposes `fareRule` for the components, with `farePerPassenger` delegating to it, so there is still
exactly one fare query.

Every figure is labelled **estimated**. Concessions, catering, quota and tatkal premiums and
refunds are all excluded, because this project cannot price any of them.

### 12.11 Verification

| Suite | Result |
|---|---|
| `com.rail.BookingDraftSelfCheck` | **122/122** |
| `com.rail.SearchSelfCheck` | 85/85 — unchanged |
| `com.rail.DatabaseSelfCheck` | 70/70 — unchanged |
| `com.rail.AuthSelfCheck` | 63/63 — unchanged |
| `com.busticket.ModelSelfCheck` | 123/123 — unchanged |
| UI smoke | 48/48 — preferences per class, refusals, add/remove, contact, Back, Continue |

Every case the brief lists is covered: adding and removing passengers, invalid ages, empty names,
invalid contact details, different berth preferences, and navigation backwards. The checks also
assert that **no `bookings`, `seat_reservations` or `passengers` row was created**, by counting
before and after.

### 12.12 Bugs this milestone's own checks caught

- **A shared draft made every validation check vacuous.** The first version of the checks added
  eight passengers to a draft whose party size was two, so all of them were refused on *party
  size* and the assertions passed or failed for entirely the wrong reason. Each check now starts
  from its own empty draft. A check that passes for the wrong reason is worse than no check.
- **A refused contact could look like it had been saved.** The check asserted an email survived a
  rejection, but the preceding successful call had stored no email at all. The service was right
  and the check was wrong; it now sets a distinct baseline first.
- **An age of 30 was expected to be refused by a narrower range of 18–60.** It is inside that
  range. The check now uses 100, which is inside the default range and outside the narrow one,
  and separately confirms 30 is accepted by both.

### 12.13 Known limitations

- Berth preferences are collected but never allocated. There is no allocation engine in this
  project, so the summary cannot say whether a request can be met.
- `passengers.berth_preference` stores one `SIDE` value for both side variants, so a stored side
  preference cannot be read back as lower or upper. Another reason preferences stay advisory.
- Meal preferences are held in the draft only; there is no column for them yet.
- The draft is lost on exit, by design. See 12.8.
- Contact name is not collected. `bookings.contact_name` is required, and the booking stage will
  take it from the signed-in account rather than asking the traveller to type it twice.

---

## 13. Milestone 6 - the booking engine

### 13.1 What was built

`BookingService.confirm(draft)` takes the draft Milestone 5 produced and, if it still holds up,
writes a real booking:

| Step | What happens |
|---|---|
| Re-validate | passengers re-checked against the configured age range, contact re-checked, date re-checked |
| Re-read | journey status and fares re-read from the database, never taken from the form |
| Lock | the journey's inventory and its reservations are read `FOR UPDATE` |
| Allocate | one berth per passenger, preference first, then a documented fallback |
| Write | one `bookings` row, one `passengers` row each, one `seat_reservations` row each |
| Commit | and only then a `ConfirmedBooking`, which the confirmation screen renders |

Every one of those steps is inside a single transaction. A failure anywhere rolls the whole thing
back, so a booking is never half written - there is no state in which a PNR exists but a
passenger does not, or a berth is held for a booking that was then refused.

### 13.2 How the same berth is never sold twice

`seat_reservations` has no unique constraint that could stop two bookings taking the same berth on
overlapping segments - MySQL CHECK constraints cannot contain a subquery - so the guarantee lives
in `AvailabilityDAO` instead. Inside the transaction it takes two locking reads:

1. every attached coach of the journey, in `coach_id` order, `FOR UPDATE`. This is the statement
   that makes two concurrent bookings queue up behind each other.
2. the reservations held on the requested segment, `FOR UPDATE`. A locking read is always a
   *current* read, so it sees rows committed by whoever held the lock a moment ago. A plain SELECT
   would not: under REPEATABLE READ it would keep reporting the answer from before the wait, which
   is the bug that makes naive implementations sell the same berth twice.

When the first booking commits, the second resumes, sees the reservation that was just written,
and allocates something else or refuses cleanly.

**The privilege discovery that shaped this.** The application account is granted `SELECT` on the
database and `INSERT`/`UPDATE` on the writable tables. That is not enough for a locking read on
every table: `SELECT ... FOR UPDATE` is *refused* on `coaches`, `trains`, `stations` and
`fare_rules` ("SELECT with locking clause command denied"), and accepted on `journeys`,
`journey_coaches`, `seat_reservations`, `bookings` and `passengers`. The original single-statement
version of this code joined `coaches` and therefore could not run at all. The locking reads now sit
on the writable inventory tables, which both can lock and which are the ones whose contents
actually change while a booking is in flight. Master data needs no lock: nobody edits a coach
while a ticket is being sold.

### 13.3 Allocation: honour, fall back, or refuse

A preference is a request, never a promise. For each passenger in order:

1. a free berth at the requested position, if there is one;
2. otherwise a free berth of the same berth *type* in another position;
3. otherwise the booking is refused, and the refusal names how many berths are actually free.

The two sides are kept apart on purpose. `passengers.berth_preference` records what was asked for
and `seat_reservations.position` records what was given, so the fallback is evidence rather than a
silent substitution. `ConfirmedBooking.preferencesNotHonoured()` reports the count, and the
confirmation screen prints `(fallback)` beside any berth that is not what was asked for.

**Capacity policy is refuse, never partial.** There is no RAC and no waitlist in this milestone:
the schema allows those statuses but this build never writes them, and a check asserts that no row
anywhere claims `RAC` or `WAITLISTED`. Either the whole party has berths or nothing is booked.

### 13.4 The PNR is supplied, not handed out in order

`bookings.pnr` is an `AUTO_INCREMENT` primary key, so the stored value is sequential and useless
to quote. The displayed PNR is therefore generated in Java as a 10-digit number and is *checked*
against the key: a collision is a MySQL duplicate-key error (vendor code 1062) on the insert, and
the engine asks for another number, up to a small bound. The check is scoped to the booking insert
alone and looks at the error code rather than the message text, so a different unique-constraint
failure cannot be mistaken for a PNR collision.

### 13.5 Money

Every fare figure is `BigDecimal` at scale 2, matching the `DECIMAL(12,2)` columns. Each component
is rounded once, where it is computed, so `perPassenger` is exactly `baseFare + distanceFare` and
`total` is exactly `perPassenger * passengers`. `double` is not used anywhere on this path: it
cannot represent 0.1 exactly, and a total that disagrees with the sum of its printed parts is
exactly the sort of thing a passenger notices.

### 13.6 The confirmation screen can only show what is committed

`ConfirmedBooking` is constructed from the rows the transaction has just read back, so its
existence is the proof that the database agreed. Nothing renders a PNR from the draft, and the
booking runs on a background thread: a click on Continue shows "hold on", and the confirmation
screen is built in the callback after `confirm` returns. A refusal keeps the draft intact, so the
traveller can correct one passenger and try again. Continue is single-shot while a booking is in
flight, because a second click would book twice.

### 13.7 Verification

| Suite | Result |
|---|---|
| `com.rail.BookingEngineSelfCheck` | **131/131** |
| `com.rail.ui.BookingConfirmationSelfCheck` | **10/10** |
| `com.rail.BookingDraftSelfCheck` | 124/124 |
| `com.rail.SearchSelfCheck` | 85/85 |
| `com.rail.DatabaseSelfCheck` | 71/71 |
| `com.rail.AuthSelfCheck` | 63/63 |
| `com.busticket.ModelSelfCheck` | 123/123 |

`BookingEngineSelfCheck` runs 21 scenarios against the real database: single and multi-passenger
bookings, preference honoured and preference unavailable, overlapping and adjacent segments,
insufficient inventory, two-connection locking, an occupied berth never reissued, PNR collision,
PNR uniqueness, fare arithmetic, statuses, stale and incomplete drafts, a missing session, a
cancelled journey, and a journey that no longer exists.

Every scenario runs inside a transaction that is rolled back afterwards, and a `commit()` on the
probed connection is counted rather than performed, so the checks write real rows, assert against
real state, and leave nothing behind. Two facts are asserted at the end of every run: no
`bookings`, `passengers` or `seat_reservations` row survives, and no `RAC` or `WAITLISTED` status
was ever written. One journey row *is* left behind, created through the production search path,
because the two-connection locking check needs committed inventory to contend over and the account
has no `DELETE`.

`BookingConfirmationSelfCheck` covers what those checks cannot: that a committed booking *renders*.
It builds the real `DashboardFrame`, renders a `ConfirmedBooking`, and reads the labels back off the
screen, so a PNR, a berth or a fallback cannot be missing from the ticket without a check noticing.
It writes nothing, and it skips itself on a headless machine.

### 13.8 Bugs this milestone's own checks caught

- **The locking query could not run at all.** Every booking failed with "The booking could not be
  completed" until the underlying error was surfaced: the account is refused a locking read on
  `coaches`. The safe, deliberately vague booking message was hiding a privilege problem. The
  checks now print the root cause of an unexpected failure, because a refusal message is designed
  for a passenger, not for whoever has to fix it.
- **The free-berth count was read after allocation had drained the list.** The refusal message
  reported zero free berths instead of the real figure, because the count was taken from the same
  list the allocation had just consumed. It is now captured before allocation starts.
- **A refused booking rolled back the checker's fixture.** The engine correctly rolls back its own
  transaction, which on a single shared test connection also discards rows an earlier step had
  written. The assertions were asserting something that could never be true; they now assert what
  the refusal actually guarantees.
- **Two scenarios were booking a party of the wrong size.** A party of one was submitted against a
  selection for two, so it was refused on party size and the fallback check passed or failed for
  entirely the wrong reason. Each booking now uses a selection of its own size.
- **A journey cannot be made to vanish with `DELETE`.** The scenario that verified the
  "journey no longer exists" path tried to delete the row and was refused the privilege. Moving
  the draft to a date nobody has searched for reaches the identical code path without needing a
  privilege the application does not have.
- **`DatabaseSelfCheck` asserted the `journeys` table was empty.** It no longer is, and that is
  correct: a journey materialised by the real search path is not faked inventory. It now asserts
  every journey present is a real one attached to real coaches, and scopes its rollback check to
  the journey it created.

### 13.9 Known limitations

- The journey-level lock is coarser than it needs to be: two bookings in *different* classes on the
  same journey queue behind each other. Correct, and fine for one desktop user. Narrowing it means
  one locking statement per class, which must still agree on lock order or two transactions can
  deadlock.
- Overlap protection is application-level. A second application writing reservations directly,
  bypassing this service, would not be serialised.
- Fares remain a simulation and still exclude concessions, catering, quota premiums, GST and
  refunds. See 11.7.
- Cancellation and status changes are not implemented, so a `CONFIRMED` booking has no path back
  out yet. That is Milestone 7 - now built; see section 14.
- A booking cannot be changed after it is confirmed. The only route is a cancellation, which
  exists as of Milestone 7.

---

## 14. Milestone 7 - history, PNR lookup and cancellation

### 14.1 What was built

- **A ticket, not a row.** `BookingDetail` is the read model behind both the PNR lookup and My
  Bookings: PNR, train, resolved station names, journey date, class, the status as stored, the fare
  paid, and every passenger with the coach, berth, position and their own reservation status.
  Because both screens read it through one query, they cannot disagree about the same booking.
- **`BookingQueryService.myTicketByPnr`** - one booking in full, scoped to the session.
- **`CancellationService`** - cancels a whole booking in one transaction and returns the ticket as
  it now stands.
- **`RefundPolicy`** - the project's own refund rules, in one pure class, configurable.
- **`CancellationDAO`** - reads and appends the cancellation ledger. Rows are appended, never
  edited or removed.
- **The ticket screen** - reached from the PNR lookup or from a `Ticket` button on every row of My
  Bookings, with a cancel action, a refund preview dialog, and a way back to the list.

### 14.2 Ownership is enforced in SQL

`user_id` is part of the `WHERE` clause on all three reads - the booking, its passengers, and its
cancellation. There is no "fetch everything then filter in Java" path, so another traveller's
ticket is never loaded into memory at all. A PNR that belongs to somebody else produces the same
"no booking found for that PNR" as a PNR that does not exist, because telling the two apart would
confirm a guessed PNR is real.

The passenger read joins `bookings` as well as `passengers`, so a caller cannot pass any PNR and
read any passenger list. That join is the only thing standing between a valid PNR and someone
else's names and ages, which is why it is there rather than being trusted to the caller.

### 14.3 One transaction, and the lock that makes it safe

Cancellation is: lock, decide, release, record, commit.

```java
String lockedStatus = bookings.lockStatusForUser(pnr, userId);   // SELECT ... FOR UPDATE
...
bookings.cancelReservations(pnr);          // berths released
bookings.markCancelled(pnr);                // booking status
new CancellationDAO(connection).insert(...); // the ledger row
connection.commit();
```

`lockStatusForUser` locks the booking's own row before the status is read, so two cancellations of
the same booking run one after the other rather than side by side: the second waits, then reads the
`CANCELLED` the first one wrote, and is refused. That is what makes a duplicate refund impossible,
and it is why there is no check-then-act window anywhere in the service - the check *is* the locked
read. Only one booking is locked, so two travellers cancelling different berths of the same train do
not block each other.

Any failure rolls the whole thing back, so there is no state in which the money has been refunded
but the berth is still sold, or the berth is free but the booking still reads confirmed. Berths are
released *first*: if that fails the berths are still held, which is the only safe direction to fail
in - never free a berth that is still sold.

The lock reads one table on purpose. The same lock on the full ticket query would drag `trains`,
`stations` and `travel_classes` into the lock set with their joined rows, and this build's database
account is not permitted to lock those - the same privilege problem 13.8 documents. The ticket is
read immediately afterwards, while the lock is still held, so it cannot go stale.

### 14.4 What a cancellation does, and what it leaves alone

| | |
|---|---|
| Booking row | `status` becomes `CANCELLED`. Never deleted - a cancelled ticket still has to be printable. |
| Passenger rows | untouched. There is no `passengers.status` column in the schema, so passenger state is exactly what their reservation says. |
| Reservation rows | `status` becomes `CANCELLED`. That is what frees the berth: availability counts `CONFIRMED` and `RAC` only. |
| Other bookings | untouched. The update is scoped to one PNR, and every reservation in the table belongs to exactly one booking. |
| Ledger | one appended row with the time, who cancelled it, why, the refund, the charges and the refund status. |

`cancellations.passenger_id` and `reservation_id` are left null for a whole-booking cancellation,
which is how the row says "the entire ticket" without needing a second flag column.

### 14.5 The refund rules, and what they are not

**These are not IRCTC rules.** Nothing here is an official refund policy, a real fare rule, or
anything a passenger could rely on at a counter. They are this project's own rules, invented so that
cancellation has a defined, testable, honest outcome, and they are written down here so nobody has
to read the code to know what they will get. No payment gateway is involved and no real money moves.

| When the passenger cancels | Refund | Kept as charges |
|---|---|---|
| 7 or more days before departure | the whole fare, less a flat 50.00 per passenger | 50.00 per passenger |
| 1 to 6 days before departure | 50% of the fare | the other 50% |
| after departure | nothing | the whole fare |

The charge never exceeds the fare paid: a 30.00 booking cancelled early loses its 30.00 and is
refunded nothing, rather than being charged 50.00 for something that cost 30.00. The refund and the
charges always add back up to exactly what was paid, and both are `BigDecimal` at scale 2, rounded
half-up once at the end.

The two thresholds are configurable, from a system property or the equivalent environment variable,
so the policy can be retuned without a rebuild. A missing or unusable value falls back to the
default rather than being silently accepted:

```
# database/local.properties is not used for these; pass them to the JVM or set them in the shell
-Dcancellation.cutoff.days=10
-Dcancellation.late.refund.percent=40
RAIL_CANCELLATION_CUTOFF_DAYS=10
RAIL_CANCELLATION_LATE_PERCENT=40
```

### 14.6 Eligibility, and the two answers a quote must not give

A booking may be cancelled while it is `CONFIRMED` and its journey has not departed. A cancelled
*service* is deliberately still cancellable - a train that will not run is the clearest case of a
passenger wanting their money back.

`quote(pnr)` reads the booking without a lock and works out the figures for the confirmation dialog;
`cancel(pnr, reason)` recalculates them inside its own transaction and the committed figures are the
ones on the ledger row. The two do not share code, because a dialog that worked out a refund by
calling the thing it is about to do would be showing a passenger a number produced by a
cancellation they have not agreed to yet. The checks assert that the quote and the record agree, and
that cancelling a booking whose quote was already taken still records what was quoted.

### 14.7 The waitlist, and why there is none here

The plan made the waitlist conditional on Milestone 6 having produced one. It did not: Milestone 6
reserves a berth or refuses the booking outright, and never writes a `WAITLISTED` row. So there is
nothing to promote, and a cancellation that freed a berth has nobody queued for it.

This is a real limitation, not a completed feature. When a waitlist is added, promotion belongs
inside `CancellationService.cancel`, in the same transaction that releases the berth - otherwise the
freed berth sits empty while somebody is queued for it, which is the bug the current design is
positioned to avoid. The reservation schema already allows a `WAITLISTED` status and a null
`coach_id`/`berth_id` shape for it.

### 14.8 Verification

| Suite | Result |
|---|---|
| `com.rail.BookingManagementSelfCheck` | **135/135** |
| `com.rail.ui.TicketScreenSelfCheck` | **33/33** |
| `com.rail.BookingEngineSelfCheck` | 131/131 |
| `com.rail.ui.BookingConfirmationSelfCheck` | 10/10 |
| `com.rail.BookingDraftSelfCheck` | 124/124 |
| `com.rail.SearchSelfCheck` | 85/85 |
| `com.rail.DatabaseSelfCheck` | 71/71 |
| `com.rail.AuthSelfCheck` | 63/63 |
| `com.busticket.ModelSelfCheck` | 123/123 |

`BookingManagementSelfCheck` runs 13 scenarios against the real database: the refund arithmetic and
its configuration at both sides of the cutoff, a ticket read in full, an unknown PNR, another
account's PNR, the list agreeing with the ticket, history scoped to the account for both a traveller
and an administrator, an eligible cancellation, the quote matching the record, a cancellation inside
the cutoff, a refused cancellation after departure, a refused second cancellation, a refused
cancellation of another account's booking, history preserved after cancelling, berths released
without touching an overlapping booking, and an induced failure that must release nothing.

The induced failure is the important one: the ledger insert is made to fail *after* the reservations
have been released and the booking marked cancelled, so only a real rollback can put it back
together. The check then reads the booking, its reservations, its berths, its fare and the ledger
from a connection the failing service never touched, and finds everything intact.

`TicketScreenSelfCheck` covers what no database check can: that the ticket *renders*. It builds the
real `DashboardFrame` for a confirmed booking, a cancelled one and a departed one, and reads the
labels and button captions back off the screen, so a missing PNR, a hidden refund, a cancel button
that should not be there, or a dead end with no way back to the list cannot pass unnoticed. It
writes nothing and skips itself on a headless machine.

Every scenario runs inside a transaction that is rolled back afterwards, with the service's `commit`
recorded rather than performed, so the checks write real rows, assert against real state and leave
nothing behind.

### 14.9 Bugs this milestone's own checks caught

- **The refund status was written as a constant's *name*, not its value.** `RefundPolicy` passed
  `"REFUND_STATUS_PROCESSED"` where the column wanted `PROCESSED`, so every late-cancellation
  insert failed with `Data truncated for column 'refund_status'` - which the service reported, by
  design, as the vague "the booking could not be cancelled". The two names now share a constant, so
  the value and the name cannot drift apart.
- **A cancellation charge could exceed the fare.** A 30.00 booking cancelled early would have been
  refunded 0.00 *and* charged 50.00. The charge is now capped at the fare paid.
- **The refund status for a departed journey was checked against the wrong literal** in the checks
  themselves, and passed. It was replaced by asserting the stored value from the database.
- **The privilege problem from 13.8 recurred as a design constraint.** The obvious way to lock the
  booking - `SELECT ... FOR UPDATE` on the ticket query - cannot run under these grants, because the
  joins pull in tables the account cannot lock. It surfaced as an unexplained refusal, and the fix
  was the single-table lock described in 14.3.
- **The ownership checks were passing for the wrong reason.** The check that a traveller is refused
  another account's history evaluated the target id *inside* the session switch, so it compared the
  traveller against itself and proved nothing. The id is now captured before the switch. A related
  check could not ask the question at all, because the session user is an administrator; both roles
  are now signed in deliberately, and the session is restored afterwards.
- **The "refused, so nothing changed" checks asserted something that could not be true.** A
  refusal rolls the service's transaction back, and on a shared test connection that discards the
  fixture too. Those checks now assert what the refusal actually guarantees - the message, and the
  rollback - and read the evidence from a connection the service never touched.

### 14.10 Known limitations

- No waitlist, so a freed berth is not offered to anybody. See 14.7.
- Cancellation is whole-booking only. A single passenger of a party cannot be cancelled, so
  `PARTIALLY_CANCELLED` is never written even though the schema allows it.
- A cancelled *service* can still be cancelled by a passenger, but the application never cancels
  services itself, so this only arises if an administrator does it in the database.
- Refunds are recorded, not paid. There is no payment gateway, no refund reference and no
  reconciliation against one.
- The cancellation lock is proven to block by the booking engine's own two-connection check, not by
  a cancellation-specific race test: observing two concurrent cancellations would need both to
  commit, and committed cancellations cannot be cleaned up under these grants.
- Fares and refunds remain a simulation. See 11.7 and 14.5.

---

## 15. Milestone 8 - administration

### 15.1 What was built

Six tabs behind one administrator-only window: **Overview**, **Trains**, **Stops**, **Rake**,
**Fares** and **Stations**. Every figure on them is counted from the database; nothing is typed in,
cached, or seeded. The window is reachable from the administrator-only card on the home screen and
refuses to open for anyone else.

| Layer | Class | What it does |
|---|---|---|
| Screens | `AdminFrame` | The six tabs, the forms, the read-only tables |
| Service | `AdminService` | The role check, validation, transactions, every policy |
| DAO | `AdminDAO` | Prepared SQL and nothing else |
| Records | `AdminView` | Inputs, rows, the summary, and the two ENUM mirrors |

Every public method on `AdminService` begins with `SessionManager.get().requireAdmin()`, before any
validation and before any SQL. A hidden tab, or a window left open on a session that has since
ended, buys nobody anything: the service decides, and the screen only asks.

Reads run on a worker thread, as everywhere else in this application. Writes run on the event
thread - each is one row, and a second thread pool would buy no visible gain.

### 15.2 Least privilege, in the grants and not only in the code

`database/create_app_user.sql` was given more privileges for this milestone, and no more than the
features need:

| Table | Granted | Why |
|---|---|---|
| `trains`, `stations` | `INSERT, UPDATE` | Trains are deactivated and stations renamed; neither is deletable |
| `train_stops`, `coaches`, `berths`, `fare_rules` | `INSERT, UPDATE, DELETE` | The only removals this application offers |

There is deliberately **no `DELETE` on `trains` or `stations`**. A booked ticket names its train,
its stops and its berths; removing any of those would leave it pointing at nothing, so the
application does not offer it and the account cannot do it by accident.

### 15.3 The policies, and the sentences that enforce them

| Change | Rule |
|---|---|
| Train number, station code | Immutable once created - both are foreign-key targets |
| Train | Deactivated, never deleted. Deactivating keeps every ticket readable |
| Station | No delete at all |
| Route of a train with bookings | Cannot be rebuilt; its stop *timings* can be corrected |
| Coach or berth with history | Cannot be removed; the refusal names the count |
| Fare rule already in force | Cannot be deleted - add a replacement and give the old one an end date |
| Fare rule specificity | A destination with no origin is refused, by `ck_fare_specificity` |

Two layers enforce these. The service checks the first four and writes its own sentences; the schema
refuses the rest even if somebody bypasses the application.

### 15.4 The forms read their options from the column

`AdminView.TRAIN_TYPES` and `AdminView.BERTH_TYPES` mirror the `ENUM` declarations in
`schema.sql`, and `AdminSelfCheck` compares both against `information_schema` - so a column that
gains a value cannot leave the application offering an invalid one. Travel classes are not an enum
in the schema, so those come from `fare_rules` and `coaches` as they are added.

Dates and times are typed as `yyyy-mm-dd` and `HH:mm`. That is the schema's own notation, so nothing
is left to guess, and there is no date-picker dependency to keep in step with it.

### 15.5 The six tabs in one table

| Tab | Reads | Writes |
|---|---|---|
| Overview | Counts of trains, stations and bookings by status | - |
| Trains | The train list with stop, class and booking counts | Add, edit, activate, deactivate |
| Stops | One train's route | Re-time a stop |
| Rake | A train's coaches and a coach's berths | Add or remove a coach or berth |
| Fares | Every fare rule | Add, edit, remove |
| Stations | The station list with use counts | Add, edit |

### 15.6 No audit trail

Nothing here records *who* changed *what*, or *when*. An administrator's change is indistinguishable
from one made directly in the database. Adding an audit table is real work - who, what, before, after,
on which connection - and it was not asked for, so it is not here. If it is wanted, `AdminService` is
the only place that would have to change: every write already passes through one method per operation.

### 15.7 Creating an administrator

There is no seeded administrator and no default password. `sample_data.sql` contains a `demo_admin`
row whose password hash is `NULL`, so the account exists and cannot be signed into - the checks assert
both that no administrator has a password set and that no role outside the schema's own `ENUM` was
written.

An administrator is created the safe way, exactly as in 10.6: register normally through the
application's own registration screen, then promote just that account as a DBA:

```sql
UPDATE users SET role = 'ADMIN' WHERE username = 'your_username';
```

### 15.8 Existing databases: one correction to apply by hand

`schema.sql` loads destructively, so a database created before this milestone carried the
original, wrong `ck_fare_specificity`, which had its two columns the wrong way round. Apply it once
with the script in `database/fix_ck_fare_specificity.sql`, as an account holding `ALTER` on
`railway.fare_rules` - root, or your own administrator account:

```powershell
& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p --execute="source database/fix_ck_fare_specificity.sql"
```

The script contains the two statements and nothing else:

```sql
ALTER TABLE fare_rules DROP CHECK ck_fare_specificity;
ALTER TABLE fare_rules ADD CONSTRAINT ck_fare_specificity CHECK (
    destination_station_code IS NULL OR origin_station_code IS NOT NULL
);
```

`mysql` prompts for the password, so no credential is ever written into the command line, the
script, or this file. The application's own account is refused on purpose:

```
ERROR 42000: ALTER command denied to user 'railway_app'@'localhost' for table 'fare_rules'
```

Confirm the result with `SHOW CREATE TABLE fare_rules;` and look for
`destination_station_code IS NULL OR origin_station_code IS NOT NULL`. MySQL 8 has no
`DROP CHECK IF EXISTS`, so running the script a second time fails on the `DROP` with
`ERROR 1091`; that is harmless and means there is nothing left to do.

`AdminSelfCheck` proves the constraint by trying to insert a destination with no origin and being
refused, so this failure cannot be missed: the check fails until the correction is applied.

### 15.9 Verification

| Suite | Result |
|---|---|
| `com.rail.AdminSelfCheck` | **169/170** |
| `com.rail.ui.AdminScreenSelfCheck` | **19/19** |

The one failure at the time of writing was `ck_fare_specificity`, which needed the DBA step in 15.8. That migration has since been applied and the suite is 170/170.

`AdminSelfCheck` runs 170 checks inside a transaction that is rolled back afterwards, with the
service's `commit` recorded rather than performed, so it writes real rows, asserts against real state
and leaves nothing behind. It covers the role check on all 21 administrative operations, every CRUD
path, duplicate and stale-id refusals, route and time validation, the destructive-change guards
against booked data, the schema constraints as a second layer, the two ENUM mirrors, and that a
refused operation wrote no row at all.

`AdminScreenSelfCheck` covers what no database check can: that the window refuses to open for a
traveller, and that what it offers matches the policies above - no delete button for a train or a
station anywhere, deactivation instead, and a berth removal that names a berth rather than a coach. It
writes nothing and skips itself on a headless machine.

A refused service call rolls back the whole enclosing transaction, so a scenario that books a berth in
order to prove it cannot then be removed has to be told apart from one that rolls back afterwards. The
checks do that by running each such refusal as its own scenario, and by reading through a connection
proxy that never saw the service's commit.

### 15.10 Bugs this milestone's own checks caught

- **`listBerths` queried before it bound its parameter.** The statement was executed and then given a
  parameter, so the coach id was never part of the query and every coach's berths came back for the
  first coach. Preparation and binding now happen before `executeQuery`.
- **A route's first stop had a null arrival time, and the validator dereferenced it.** Validating a
  train with one stop threw a `NullPointerException` instead of a sentence. The validator now treats
  a null arrival as "not yet arrived", which is what it means at an origin.
- **`editStation` called a station a duplicate of itself.** The uniqueness check ran before the update
  excluded the row being edited, so saving a station without changing its name was refused. It now
  excludes itself, as `editTrain` already did.
- **A stale coach or berth id reported success.** Both were deleted by nothing and reported as changed;
  they are now refused, since a caller passing a berth that no longer exists has a bug worth hearing
  about.
- **The fare specificity check was written the wrong way round in the schema.** It refused a rule with
  a destination and no origin only by accident of its column order; see 15.8.
- **The forms offered enum values the column did not have.** `AdminView` mirrored the enums by hand,
  so nothing noticed when they drifted. The mirrors are now compared against `information_schema`.

### 15.11 Known limitations

- No audit trail. See 15.6.
- One fare rule per class and origin/destination pair per validity window: overlapping windows are
  refused by the resolution order rather than detected as an overlap, so the cheapest rule wins and
  the administrator is not told two rules compete.
- Removing a berth or coach is per row. There is no bulk rake replacement, which would need a
  transaction that rewrites a whole train's layout and refuses if anything is sold.
- A route cannot be rebuilt once a train has bookings, so a mistake in the *order* of stops on a
  booked train is corrected by cancelling its journeys in the database, not from this window.
- A database created before Milestone 8 still needs the 15.8 migration; this one has had it, so
  `AdminSelfCheck` is 170/170.

---

## 16. Milestone 9 - interface, testing, documentation, delivery

### 16.1 What was inspected before it was changed

The interface already had the requested shape: a light content area against a navy sidebar, blue
primary actions, one shared set of fonts, spacing and borders in `Style`, and `RailwayArt` for the
illustrations. So Milestone 9 changed almost no layout. What was missing was elsewhere - three
places showed a raw driver message, slow work had no visible sign of life, three of the eighteen
required scenarios had no check behind them, and the setup instructions were scattered across
sections written for the build rather than for an operator.

### 16.2 Failure messages that name nothing

`com.rail.ui.DatabaseFailure` is the one place a database failure becomes a sentence a traveller
is told. Connector/J's text names the account, the host and often the statement; a user reading
`Access denied for user 'railway_app'@'localhost'` learns nothing they can act on. The sentence is
written once and carries no part of the driver's message; the detail goes to `stderr` for whoever
is at the console. `DatabaseSelfCheck` proves both halves: the returned sentence contains no
account, no driver class, no JDBC URL and no SQL, and the `stderr` line does contain the detail
and still not the password.

Three call sites were fixed: the search button's "no trains" message, the timetable's status line,
and the administration window's catch-all.

### 16.3 A sign of life, bounded

`com.rail.ui.Working` is a `javax.swing.Timer` that appends a dot every 320 ms up to eight dots and
then stops itself. It runs on the event thread, where Swing wants to be touched, and it is only ever
a label's text - it cannot affect whether a booking succeeds. It is used while the search button
waits and while the administration window loads the coaches for a chosen train. `StatusBanner`'s
existing flash was already bounded and already self-stopping, so it was left alone.

### 16.4 The three gaps in coverage, and the two suites that closed them

`com.rail.PersistenceSelfCheck` - a committed booking, read back after the session is ended, the
services rebuilt and every connection dropped and reopened, then the account signed in again. It
checks the ticket, the history entry, the held berth, that the booking belongs to the account that
made it, and that the stored password is a bcrypt hash rather than the password. It commits one
fixture account and one booking for 2027-12-15 and is idempotent: a second run creates nothing.

`com.rail.ui.NavigationSelfCheck` - clicks the sidebar rather than reading a caption list, and
resizes a shown window across four sizes. Two things it had to be careful about: a window that has
never been displayed ignores `setSize`, so every size assertion would have passed for the wrong
reason; and Swing clamps a size up to the minimum and the window manager clamps it down to the
work area, so the assertion is the range the window is entitled to occupy, not the size requested.
The collapse check names the offending component and its size instead of counting, and it looks
only at `com.rail` and `com.busticket` components - the look and feel's own scroll-bar arrows are
given no size whenever a bar is not needed, and that says nothing about this layout.

### 16.5 Bugs these checks caught, including in the checks

- **A raw `SQLException` message on the search screen, the timetable and the administration
  window.** Fixed by 16.2.
- **`DatabaseSelfCheck` counted every booking in the database and demanded zero.** True before
  the persistence fixture existed, false after it, and it failed for the wrong reason: the point is
  that nothing *seeded* pretends to be real data. It now asserts that every booking and every
  passenger belongs to a real account and a real booking, and the rollback checks are scoped to
  the PNR they created.
- **`checkForeignKeysAreIndexed` grouped index rows with a placeholder list and an empty branch** -
  a first draft that compiled and did nothing. Replaced with one pass that starts a new list when
  the table or index name changes.
- **Two assertions in the new checks were wrong before they were ever run.** "No SQL text" failed
  because the friendly sentence says "MySQL", which contains "SQL"; and the stderr assertion looked
  for the driver class name, which Connector/J does not put in a refused connection's text.
- **`NavigationSelfCheck` could not compile twice**: `DashboardFrame::new` throws
  `AccessDeniedException`, which a `Supplier` cannot carry, and the sidebar check compared a
  constant to nothing.

### 16.6 Documentation

Sections at the top of this file are the operator guide: what the project is, prerequisites,
database creation, configuration, build and run, accounts, an example workflow, the thirteen
suites, known limitations, and the simulation disclaimer. The long sections below are unchanged;
this milestone did not rewrite them.

### 16.7 Known limitations of this milestone

- **Visual inspection is pending a person.** The rendered component tree was checked
  programmatically; nobody has looked at the running window, so its appearance is unjudged.
- `Working` is a text ellipsis, not a spinner, and the search button stays disabled while it runs.
  Anything that must not be re-entrant is guarded in the action listener, not by the animation.
- The persistence fixture is a committed row, which is why `DatabaseSelfCheck` and
  `BookingEngineSelfCheck` count rows *excluding* it rather than demanding an empty table. A
  fixture that leaves nothing behind would remove that coupling, at the cost of the demonstration
  that committed data really is still there.

### 16.8 Verification

Run on the packaged build, JDK 21 targeting 17, against the live `railway` schema:

| Suite | Result |
|---|---|
| `com.busticket.ModelSelfCheck` | 123/123 |
| `com.rail.DatabaseSelfCheck` | 82/82 |
| `com.rail.AuthSelfCheck` | 63/63 |
| `com.rail.SearchSelfCheck` | 85/85 |
| `com.rail.BookingDraftSelfCheck` | 124/124 |
| `com.rail.ui.BookingConfirmationSelfCheck` | 10/10 |
| `com.rail.BookingEngineSelfCheck` | 131/131 |
| `com.rail.BookingManagementSelfCheck` | 135/135 |
| `com.rail.PersistenceSelfCheck` | 29/29 first run, 24/24 on a re-run |
| `com.rail.ui.TicketScreenSelfCheck` | 33/33 |
| `com.rail.AdminSelfCheck` | **170/170** |
| `com.rail.ui.AdminScreenSelfCheck` | 19/19 |
| `com.rail.ui.NavigationSelfCheck` | 41/41 |

1040 checks, all green.

`PersistenceSelfCheck` prints fewer checks on a second run because the fixture already exists, so
the registration branch is skipped. It creates nothing either time.

`mvn -o clean package -DskipTests` succeeds, and the jar launches, stays up, and writes nothing
to stdout or stderr.

The migration in 15.8 has been applied. `database/fix_ck_fare_specificity.sql` was run by a
MySQL administrator against `Hemil-Victus:3306`, and `SHOW CREATE TABLE fare_rules` now reports:

```
CONSTRAINT `ck_fare_specificity` CHECK (((`destination_station_code` is null) or (`origin_station_code` is not null)))
```

`AdminSelfCheck` went from 169/170 to 170/170 on the next run, which is the check that proves it:
the suite tries to insert a destination with no origin and is refused. Nothing else in the suite
changed.

### 16.9 What was verified, and how

Not claimed without doing it:

- **The interface renders.** The login window was shown and its component tree read back: navy
  sidebar `#14243a` beside a `#f4f7fb` content panel, Segoe UI throughout, every field and button
  given a real size, nothing collapsed. The dashboard was shown by `NavigationSelfCheck` and laid
  out at 1280x800, 1024x700, 900x640 and 1440x900.
- **Visual inspection is pending.** The rendered component tree was read back programmatically, but
  **no person has looked at the running application**. Nobody has judged the spacing, contrast, or
  whether anything reads as visually broken; that check stays open until a human opens the jar and
  looks at it.
- **Database residue.** `users 3` (`demo_user`, `demo_admin`, `persist.check`), `bookings 1` (the
  fixture), `journeys 4` with `journey_coaches 20`. The journeys are not junk: availability is
  materialised on demand, so every date a suite searched for now has real inventory behind it.

---

*The bus application as it exists today, documented so it stays runnable during the
migration. All of this is removed in Phase 9.*

### Technology

Java (source level 17, built on JDK 21), Java Swing (`java.desktop`), Apache Maven,
**no third-party dependencies**, storage in memory only.

### Structure

```
├── pom.xml
├── README.md
└── src
    ├── main/java/com/busticket
    │   ├── Main.java                  Entry point, starts the GUI on the Swing thread
    │   ├── data/BusData.java          The 12 sample buses, written in code
    │   ├── model/{Bus,Seat,Booking}.java
    │   ├── service/{BookingResult,BookingService}.java
    │   └── ui/{MainFrame,Style,RoutePanel,BusSelectionPanel,SeatSelectionPanel,
    │            PassengerPanel,ConfirmationPanel,BookingHistoryPanel,
    │            BusIllustrationPanel}.java
    └── test/java/com/busticket/ModelSelfCheck.java
```

### Sample data

Twelve buses on six routes, 20 seats each, 240 seats in total.

| Route | Buses |
|---|---|
| Mumbai → Pune | Shivneri Express 06:00–09:00 (Rs 350), VRL Luxury 18:30–21:45 (Rs 450) |
| Pune → Mumbai | Neeta Travels 07:15–10:30 (Rs 350), Orange Tours 20:00–23:15 (Rs 500) |
| Mumbai → Nashik | Rajhans Express 05:45–08:15 (Rs 200), Galaxy Travels 16:00–18:30 (Rs 275) |
| Nashik → Mumbai | Sai Travels 06:30–09:15 (Rs 220), Konduskar 19:00–21:45 (Rs 300) |
| Mumbai → Surat | Yash Travels 08:00–12:00 (Rs 400), Pooja Travels 21:00–01:00 (Rs 550) |
| Pune → Nashik | Shivneri Express 09:00–11:30 (Rs 250), VRL Luxury 17:30–20:00 (Rs 325) |

### Build and run

```bash
mvn clean package
java -jar target/bus-ticket-booking-0.1.0.jar
```

Without Maven:

```bash
javac -d out src/main/java/com/busticket/Main.java src/main/java/com/busticket/data/BusData.java src/main/java/com/busticket/model/Bus.java src/main/java/com/busticket/model/Seat.java src/main/java/com/busticket/model/Booking.java src/main/java/com/busticket/service/BookingResult.java src/main/java/com/busticket/service/BookingService.java src/main/java/com/busticket/ui/MainFrame.java src/main/java/com/busticket/ui/Style.java src/main/java/com/busticket/ui/RoutePanel.java src/main/java/com/busticket/ui/BusSelectionPanel.java src/main/java/com/busticket/ui/SeatSelectionPanel.java src/main/java/com/busticket/ui/PassengerPanel.java src/main/java/com/busticket/ui/ConfirmationPanel.java src/main/java/com/busticket/ui/BookingHistoryPanel.java src/main/java/com/busticket/ui/BusIllustrationPanel.java
java -cp out com.busticket.Main
```

### Running the self-check

```bash
mvn -q clean package
javac -cp target/classes -d target/test-classes src/test/java/com/busticket/ModelSelfCheck.java
java -cp "target/classes;target/test-classes" com.busticket.ModelSelfCheck
```

Ends with `All 123 checks passed.` and exits non-zero on any failure. On Linux and macOS
use `:` instead of `;` as the classpath separator.

### Sample booking

1. Run the jar.
2. Choose source `Mumbai`, destination `Pune`, date `25/12/2026`.
3. **Search Buses** → click **Select** on *Shivneri Express*.
4. Click seats **05** and **09**; the total becomes `Rs 700`.
5. **Continue** → name `Asha Patil`, mobile `9876543210`, email `asha@example.com`.
6. **Confirm Booking** → booking id `BT1001`, seats `05, 09`, total `Rs 700`.
7. **New Booking**, search the same route and date: availability has dropped to `18 of 20`
   and seats 05 and 09 are greyed out. A different date shows them free again.

### Known limitations

- All data is lost when the application closes; bookings live in static in-memory
  collections and a booking id is only meaningful within one run.
- No payment, no cancellation or refund, no login or user accounts.
- The 12 buses are fixed sample data and cannot be changed from the UI.
- One journey date per search; no return journeys.