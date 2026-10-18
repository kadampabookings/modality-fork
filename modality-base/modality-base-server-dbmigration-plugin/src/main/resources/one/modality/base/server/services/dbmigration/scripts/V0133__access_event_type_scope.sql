-- An intermediate grant scope: all events of one type, between "the whole organisation" and "one
-- event".
--
-- WHY: an access row scopes a role to an organisation and, optionally, to a single event. A centre
-- that wants someone to run its retreats has to name every retreat, and name next year's again when
-- it exists. An event type sits exactly between the two and is already the thing people say.
--
-- Part 1 of the model (several values per grant) needed no schema at all -- a set of access rows is
-- already read as a disjunction -- and shipped first. This is part 2, the real new dimension. The
-- design, its blast radius and the six steps it takes are docs/security/grant-scope-model.md in the
-- aggregate repo; the trap recorded there is that a column missing from DomainModelSnapshot.json does
-- not resolve in DQL and fails at RUNTIME, not at build, so the model change ships with this.
--
-- AT MOST ONE SCOPE KEY, enforced here rather than trusted: a row carrying both an event and an event
-- type would have to mean either "that event, and also every event of that type" or "that event, but
-- only if it is of that type", and the membership queries read it as neither. The CHECK makes the
-- ambiguous row unwritable instead of leaving the engine to pick.
--
-- NULLABLE, like `event_id`: a row with neither key is organisation-wide, which is what most rows are
-- and what every existing row stays. No existing row changes meaning.
--
-- A TYPE CAN BE GLOBAL: `event_type.organization_id` is nullable. Whether an event-type grant made
-- against a global type reaches every organisation's events of that type is a question for the
-- membership queries, not for this column -- they keep their own organisation conjunct, so it does
-- not. Written down because the column alone does not say so.
--
-- THE LEGACY BACK OFFICE IS LEFT BROKEN FOR THIS SCOPE, by decision (2026-10-18). Its assign dialog
-- (AssignUserAndRoleToOrganizationDialog) writes `event` and never `eventType`, so editing a
-- type-scoped row there either trips the CHECK above -- the administrator sees a raw constraint error
-- -- or, when "whole organisation" is chosen, clears `event_id` and leaves `event_type_id` set, so the
-- row stays type-scoped while the screen reports otherwise. Its scope badge likewise reads "Entire
-- Organization" for any row without an event, and its query does not select `eventType` at all. The
-- screen is no longer used (the React /users page replaced it), so it is not being fixed; one line
-- would (`setEventType(null)` beside `setEvent(...)`) if that ever changes. Recorded here because a
-- raw CHECK error from a screen nobody maintains is otherwise a mystery to whoever next opens it.
--
-- read_only is orthogonal and untouched: an event-type grant can be read-only exactly as an event
-- grant can.
--
-- Anonymiser: the new column is classified in scripts/gdpr-anonymise/10-anon-helpers.sql as not-pii
-- with its reason, in the same change. An integer id passes the automated check unseen, so the
-- classification is the only record that anybody considered it.
--
-- Schema dump: this adds a column, so scripts/kbs-database-structure.sql must be re-taken with
-- scripts/refresh-schema-dump.sh before the 14-day grace in scripts/ci/check-schema-dump-current.py
-- runs out, or repo-checks.yml fails. That dump comes from production, so it is David's to run.

ALTER TABLE authorization_organization_user_access
  ADD COLUMN event_type_id integer REFERENCES event_type (id);

ALTER TABLE authorization_organization_user_access
  ADD CONSTRAINT access_one_scope_key
  CHECK (event_id IS NULL OR event_type_id IS NULL);
