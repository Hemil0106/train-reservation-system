-- =====================================================================
-- Create the application's own MySQL account.
--
-- Run this ONCE, as a DBA account (root is fine here - this is
-- administration, not application traffic):
--
--   mysql -u root -p --execute="source database/create_app_user.sql"
--
-- The application then connects as this least-privilege account. It is
-- deliberately NOT root: root can drop the schema, and nothing in this
-- application should ever need to.
--
-- ORDER MATTERS: schema.sql must be loaded first, because every grant
-- below names an object inside the railway database. Re-run schema.sql
-- later and the tables come back, but the grants survive only if they
-- were applied to the railway.* wildcard as shown here.
--
-- BEFORE YOU RUN THIS
-- ------------------
-- Replace every CHANGE_ME_STRONG_PASSWORD below with a real password of
-- your choosing. This file is committed to source control, so a password
-- left in it would be a leaked credential. A unique random password is
-- fine; nothing here is typed by a human.
--
-- To generate one, for example:
--   mysql -u root -p -N --execute="SELECT SHA2(RAND(), 256)"
-- =====================================================================

-- ---------------------------------------------------------------------
-- The account. Identified on 'localhost' because this is a desktop
-- application connecting to a local or tunneled server.
-- ---------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'railway_app'@'localhost'
    IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';

-- If the account already existed with an old password, reset it here by
-- uncommenting and editing:
-- ALTER USER 'railway_app'@'localhost' IDENTIFIED BY 'CHANGE_ME_STRONG_PASSWORD';

-- ---------------------------------------------------------------------
-- Privileges: read everything, write only what the app actually writes.
--
-- No CREATE, ALTER, DROP, GRANT or FILE. The application cannot damage
-- the schema even if it tries.
--
-- users gets INSERT but NOT UPDATE or DELETE. Registration creates an
-- account; nothing in the application may afterwards silently rewrite
-- somebody else's row, and no code path deletes one. (Promoting an
-- account to ADMIN is deliberately a DBA action - see README 10.6.)
-- ---------------------------------------------------------------------
GRANT SELECT ON railway.* TO 'railway_app'@'localhost';

GRANT INSERT        ON railway.users             TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE ON railway.bookings          TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE ON railway.passengers        TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE ON railway.seat_reservations TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE ON railway.cancellations     TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE ON railway.journeys          TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE ON railway.journey_coaches   TO 'railway_app'@'localhost';

-- ---------------------------------------------------------------------
-- Milestone 8: the administration area.
--
-- These are the tables an administrator configures. They are writable by
-- the same account, because "who is allowed to write here" is decided by
-- the ROLE CHECK IN THE SERVICE LAYER, not by a different database user.
-- Splitting the accounts would move the authorisation decision into
-- MySQL, where "is this session an admin" cannot be expressed, and would
-- hand every connection the power to reconfigure the railway whether or
-- not anybody was signed in. One account, one role check.
--
-- Still no DELETE on `trains` or `stations`: a train is deactivated and a
-- station is renamed, never removed, because both are foreign-key targets
-- for bookings that may already exist. `ON DELETE RESTRICT` on those
-- references is the backstop that makes an accidental deletion
-- impossible even for a DBA.
--
-- DELETE is granted only where a child row has no history of its own,
-- which is exactly what schema.sql says of these three tables:
--
--   train_stops  a route is rebuilt by replacing the list. Bookings
--                reference (train_no, stop_seq), so fk_bookings_*
--                ON DELETE RESTRICT refuses to drop a stop any ticket
--                names - and the service checks first to say so in
--                words rather than surfacing a driver error.
--   coaches      a rake is corrected by removing a coach. berths cascade.
--                seat_reservations.coach_id ON DELETE RESTRICT refuses
--                a coach that has ever sold a berth.
--   berths       removing an unused berth. seat_reservations.berth_id
--                ON DELETE RESTRICT refuses one that has.
--
-- fare_rules is deletable because nothing references it: a booking keeps
-- the amount it was charged in seat_reservations, so removing a rule
-- cannot rewrite history. The service still refuses to delete a rule that
-- has already taken effect, and says to supersede it instead.
--
-- `travel_classes` is NOT writable. Which classes a train offers is
-- derived from its coaches, so configuring a rake is how a class is
-- configured for a train; the class catalogue itself stays fixed.
GRANT INSERT, UPDATE          ON railway.trains       TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE          ON railway.stations    TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE, DELETE  ON railway.train_stops TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE, DELETE  ON railway.coaches     TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE, DELETE  ON railway.berths      TO 'railway_app'@'localhost';
GRANT INSERT, UPDATE, DELETE  ON railway.fare_rules  TO 'railway_app'@'localhost';

-- ---------------------------------------------------------------------
-- Confirm the result. Expect to see SELECT, the users INSERT, the six
-- write grants, and nothing else.
--
--   mysql -u root -p -e "SHOW GRANTS FOR 'railway_app'@'localhost';"
-- =====================================================================

FLUSH PRIVILEGES;