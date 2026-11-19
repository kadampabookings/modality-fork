-- V0171: intentionally empty - a placeholder, so the gap in the version numbers explains itself.
--
-- WHY THIS NUMBER IS EMPTY. V0171 was first used by "erase every date of birth" (person.minor three-state flag,
-- per-booking ages; branch dob-to-age, 18 Nov 2026). That work is not going to production yet (David, 19 Nov 2026),
-- while later migrations are: V0172 (DataProtection grant alert), V0173 (googlemail folding, Bruno), V0174-V0176
-- (erasing personal data by organisation: drop the erasure_request register, anonymise_person/booker scoped by
-- organisation, person_erasure_approval). So the date-of-birth migration was moved to a number above V0176, and
-- this one ships as a no-op to keep the sequence readable.
--
-- RULE FOR THE DATE-OF-BIRTH MIGRATION when it ships: it restates anonymise_person(), so it must be generated from
-- the newest definition at that time - V0175__anonymise_by_organisation.sql today - keeping the organisation scope,
-- and adding its own two lines (q.minor among the account's members, minor = NULL in the person rewrite).
-- Restating it from an older body would silently remove erasure by organisation.
--
-- Databases that applied the old V0171 (erase_birthdates) before the move - developers' local copies only, never
-- staging or production - fix their db_migration row with the date-of-birth session's local fix-up.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction. Safe to re-run.

SELECT 1;
