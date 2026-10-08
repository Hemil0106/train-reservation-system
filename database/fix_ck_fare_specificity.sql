-- =====================================================================
-- Milestone 9 migration: correct ck_fare_specificity on fare_rules.
--
-- WHAT THIS FIXES
-- ---------------
-- The check as originally written read:
--
--   CHECK (origin_station_code IS NULL OR destination_station_code IS NOT NULL)
--
-- which is the rule the wrong way round. It refused an ordinary
-- route fare (origin CSTM, destination "anywhere") and allowed the
-- half-filled row nobody should be able to save: a destination with
-- no origin. It survived in databases created before Milestone 8;
-- database/schema.sql already carries the corrected definition, so
-- this script exists only for those older databases.
--
-- The corrected rule: a destination-specific fare always needs an
-- origin, so the pair can never be half-filled in the other direction.
--
--   CHECK (destination_station_code IS NULL OR origin_station_code IS NOT NULL)
--
-- WHO CAN RUN THIS
-- ----------------
-- A database administrator, or any account holding ALTER on
-- railway.fare_rules. The application's own account (railway_app)
-- is deliberately NOT granted ALTER - it needs DML and nothing more -
-- so it will refuse this script with:
--
--   ERROR 42000: ALTER command denied to user 'railway_app'@'localhost'
--
-- No credentials are stored in or requested by this file, and nothing
-- here attempts to raise its own privileges. Run it as an
-- administrator, over a connection you have already authenticated.
--
-- HOW TO RUN IT
-- -------------
--   mysql -u root -p --execute="source database/fix_ck_fare_specificity.sql"
--
-- ONE SHOT ONLY
-- -------------
-- MySQL 8 has no DROP CHECK IF EXISTS, so re-running this against an
-- already-corrected table fails on the DROP with:
--
--   ERROR 1091: Can't DROP 'ck_fare_specificity'
--
-- That is harmless - it means there is nothing left to do. Check the
-- current definition before and after with:
--
--   SHOW CREATE TABLE fare_rules;
--
-- No data is read, written or dropped by this script.
-- =====================================================================

USE railway;

ALTER TABLE fare_rules
  DROP CHECK ck_fare_specificity;

ALTER TABLE fare_rules
  ADD CONSTRAINT ck_fare_specificity CHECK (
      destination_station_code IS NULL
      OR origin_station_code IS NOT NULL
  );