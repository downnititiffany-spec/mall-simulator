-- V35: enforce one ACTIVE RuntimeProfile at the database boundary.
--
-- The nullable generated key maps every ACTIVE row to 1 and every other state to NULL.
-- MySQL's unique index therefore allows any number of non-ACTIVE profiles but at most
-- one ACTIVE profile, including when concurrent activate transactions race.
-- If legacy data already has multiple ACTIVE rows, this migration intentionally fails
-- without changing profile statuses; reconcile those rows explicitly before retrying.
ALTER TABLE runtime_profile
    ADD COLUMN active_profile_guard TINYINT
        GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_runtime_profile_single_active (active_profile_guard);
