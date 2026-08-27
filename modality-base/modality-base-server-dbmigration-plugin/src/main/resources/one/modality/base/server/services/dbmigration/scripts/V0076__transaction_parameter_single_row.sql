-- V0076: one row in transaction_parameter, and never NULL.
--
-- WAS V0075, RENUMBERED. Version 75 was taken by V0075__revoke_client_minted_booking_access_codes.sql,
-- applied to staging on 2026-08-26. Two scripts sharing a number is not a merge conflict anyone sees:
-- both files can coexist, git merges them cleanly, and the collision only surfaces at boot as a CHECKSUM
-- MISMATCH on the loser — which fails before any write, so it leaves no db_migration row and no database
-- activity, only a readiness gate that never opens.
--
-- WHAT THIS IS ABOUT
--   A submit batch opens with `select set_transaction_parameters(<bool>)`, which creates a
--   per-transaction temp table that the trigger machinery reads through get_transaction_parameter().
--   Two consumers act on it, and only these two:
--     * trigger_document_auto_ref()       — true skips EVENT_ON_HOLD, EVENT_CLOSED and DOUBLEBOOKING
--     * deferred_allocate_document_line() — true drops the bed-count test and the room-eligibility
--                                           filter (offline, gender, ordained/lay, reserved pools)
--   Staff are MEANT to have those bypasses: V0058 and V0069 say so deliberately. This script does not
--   change who may bypass what. It closes two ways the flag can end up saying something nobody chose.
--
-- DEFECT 1 — the setter accumulates rows
--   `create temporary table if not exists ...` followed by an UNCONDITIONAL insert means a second call
--   in the same transaction APPENDS a second row instead of replacing the first, and the reader does
--   `select into ... from transaction_parameter` with no ORDER BY and no LIMIT — so which row wins is
--   whatever the scan yields first, in practice the first inserted.
--   Fixed by inserting only into an empty table: FIRST CALL WINS, explicitly. That is the existing
--   effective behaviour made deterministic, and it is the safe direction rather than an arbitrary one:
--   the server emits its preamble as statement 0 of the batch (UpdateStore.submitChanges puts
--   initialSubmits at index 0), so a preamble appearing later cannot override it. Last-write-wins would
--   invert precisely that, letting an appended statement overrule the server's choice.
--   A second call carrying a DIFFERENT value is not an error, but it is not normal either, so it leaves
--   a NOTICE in the log rather than passing silently.
--
-- DEFECT 2 — an empty table reads as NULL, which behaves like true
--   `is_backend bool := false` looks like a default but is overwritten: a non-STRICT SELECT INTO that
--   matches no row assigns NULL. get_transaction_parameter() then returns NULL, and
--   trigger_document_auto_ref's guard `if (get_transaction_parameter() = false)` evaluates to NULL, so
--   the IF is NOT taken and all three booking validations are skipped — the true-flag outcome, reached
--   without the flag ever being set to true.
--   Unreachable through set_transaction_parameters, which always inserted a row, but reachable by
--   anything that creates the temp table itself. Fixed with coalesce(..., false): unknown means front
--   office, which is the side that enforces.
--
-- ABSENT TABLE IS UNCHANGED
--   With no preamble at all the relation does not exist, the SELECT raises 42P01, and because
--   deferred_allocate_document_line is DEFERRABLE INITIALLY DEFERRED that failure lands at COMMIT and
--   rolls the transaction back. Several scripts in the aggregate repo's scripts/ folder rely on this as
--   a safety net and say so; it stays exactly as it was.
--
-- WHAT THIS DOES NOT FIX
--   The value is still CHOSEN BY THE CALLER, which is the actual security problem:
--   ServerDocumentServiceProvider picks the preamble from request.backoffice(), and a raw submit batch
--   can carry its own. Deriving it from a verified principal AND closing the raw-statement door are
--   tracked in docs/design/server-side-authorization-spec.md. Both are needed — either alone leaves the
--   other route open — and this script is neither of them. It removes the ambiguity underneath them.
--
-- BASED ON the V0001 baseline bodies, which no migration has redefined since.

CREATE OR REPLACE FUNCTION public.set_transaction_parameters(backend boolean) RETURNS boolean
    LANGUAGE plpgsql
AS $$
DECLARE
    existing_backend bool;
BEGIN
    create temporary table if not exists transaction_parameter (backend bool) ON COMMIT DROP;
    -- Aliased: an unqualified `backend` here would be ambiguous between the column and the parameter.
    select into existing_backend tp.backend from transaction_parameter tp limit 1;
    if not found then
        insert into transaction_parameter (backend) values (backend);
    elsif existing_backend is distinct from backend then
        raise notice 'set_transaction_parameters(%) ignored: transaction already set to %',
            backend, existing_backend;
    end if;
    RETURN true;
END;
$$;

CREATE OR REPLACE FUNCTION public.get_transaction_parameter() RETURNS boolean
    LANGUAGE plpgsql
AS $$
DECLARE
    is_backend bool;
BEGIN
    select into is_backend tp.backend from transaction_parameter tp limit 1;
    -- No row, or a NULL row, means nobody said: answer front office, the side that enforces.
    RETURN coalesce(is_backend, false);
END;
$$;
