-- V0071: tighten event_type.ongoing (V0067) to NOT NULL DEFAULT false.
--
-- The flag shipped nullable, but nothing uses the null-vs-false distinction
-- (every reader is a null-safe "= true" check), the table's other booleans
-- (recurring, deprecated) are NOT NULL DEFAULT false, a nullable boolean
-- invites the classic "where !ongoing silently drops NULL rows" three-valued
-- logic trap, and KBS2's editor renders nullable booleans as an awkward
-- tri-state checkbox. Two states are all this flag means.
--
-- event_type is a ~70-row config table, so the ACCESS EXCLUSIVE locks below
-- are instantaneous (unlike the V0068/V0069 hot-table incident).

alter table event_type alter column ongoing set default false;

update event_type set ongoing = false where ongoing is null;

alter table event_type alter column ongoing set not null;
