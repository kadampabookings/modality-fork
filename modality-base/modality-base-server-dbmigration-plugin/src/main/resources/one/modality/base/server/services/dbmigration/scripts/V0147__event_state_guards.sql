-- V0147: guards on event.state that no writer can get round -- a removal's seal, a reconciled event's closure, a
-- removed event's closure, and an erased event's closure.
--
-- WHY. Three properties the back office relies on were held only by the screens that write event.state, and the
-- screens are not the only writers: the event form's Published toggle, the series lock, a Recurring Events row
-- left open on stale data, and the legacy Java back office all write the column directly.
--   1. FINALISED means "this event's guests' personal data has been removed" (decision D3), and it is written
--      only by a committed removal of a RECONCILED event (EventErasureRunner; V0146's ledger records the run). A
--      writer that moves a FINALISED event back to OPEN or CLOSED, or clears it, erases that fact; reopening it lets
--      new guests' data land in an event recorded as cleared. And a client move that lands on the row while a
--      removal is running, then commits after it, overwrites the seal the removal has just written.
--   2. A soft-deleted event (event.removed, V0112) is kept closed to bookings only by its state: nothing reads
--      `removed` at booking time -- not trigger_document_auto_ref (V0145), not the booking policy load, not the
--      front-office gate. A writer that moves a deleted event to OPEN, or to anything outside the closed family,
--      reopens it to anyone holding its id or slug link.
--   3. An event whose data was removed by a SUCCEEDED commit but that is not FINALISED (a CLOSED event erased by
--      date, a legacy event with no state) would collect new personal data again if reopened -- while the
--      lifecycle page still shows it as removed. And a commit still RUNNING is as good as succeeded for this: a
--      reopen landing during its 1.5 to 10 minutes would otherwise slip in between the start's check and its commit.
--   4. RECONCILED means the event's accounts are settled. The lifecycle's transition table lets it go back to CLOSED
--      (the undo) or on to FINALISED (the removal), nowhere else; but a stale event form with Published touched, or
--      the legacy Java back office's Published switch, moves it straight to OPEN -- reopening settled accounts to
--      new bookings.
--
-- WHAT. One BEFORE INSERT OR UPDATE OF state, removed trigger on event, trigger_event_state_guards().
-- On INSERT it refuses only a row born sealed -- state FINALISED (EVENT_FINALISED_BY_REMOVAL_ONLY) or ARCHIVED
-- (EVENT_ARCHIVED_FROM_FINALISED_ONLY) -- and lets every other new row in: duplicate_event()'s DRAFT copies, the
-- back office's new events, legacy rows with no state. (The staging refresh is unaffected: a plain pg_dump loads the
-- data before it creates the triggers.)
-- On UPDATE it looks only at a row whose state or removed flag actually CHANGES: an UPDATE that leaves both as they
-- were is let through untouched, whatever else it writes and whatever the row already holds (a legacy row with
-- removed = true and no state included). Then, in this order:
--   EVENT_STATE_SEALED               a FINALISED or ARCHIVED event may only move within that pair (the archive
--                                    toggle); reopening it, moving it back to CLOSED or clearing it is refused.
--   EVENT_ARCHIVED_FROM_FINALISED_ONLY
--                                    ARCHIVED is entered only from FINALISED. With the INSERT rule, the sealed pair
--                                    is then reachable only through a removal's FINALISED -- which is what makes the
--                                    way back from ARCHIVED to FINALISED, exempt from the next rule, safe.
--   EVENT_FINALISED_BY_REMOVAL_ONLY  FINALISED (other than back from ARCHIVED) is accepted only from RECONCILED and
--                                    only inside a removal transaction for THIS event: the runner sets
--                                    kbs.erase_confirm = 'ERASE EVENT <id>' (transaction-local; V0077's statement of
--                                    intent) before it seals. So a legacy event (NULL state) is never sealed: not
--                                    FINALISED, and -- by the rule above -- not ARCHIVED either.
--   EVENT_RECONCILED_CLOSED_ONLY     a RECONCILED event moves only to CLOSED or to FINALISED (the latter still under
--                                    the removal-only rule just above).
--   EVENT_REMOVED_STAYS_CLOSED       a removed event stays in the closed family (CLOSED, RECONCILED, FINALISED,
--                                    ARCHIVED -- V0145's set) whenever its state changes or it is being removed. The
--                                    back office's soft delete writes {removed: true, state: 'CLOSED'} in one UPDATE,
--                                    or {removed: true} alone when the state is already in that family, so it passes;
--                                    un-deleting (removed back to false) is not this rule's business.
--   EVENT_DATA_REMOVED_STAYS_CLOSED  once the event has a commit in event_erasure_run that SUCCEEDED or is still
--                                    RUNNING, a state change must land in the closed family. A running commit counts
--                                    because its ledger row is committed before the run's BEGIN, so a concurrent writer
--                                    sees it; a failed, refused or aborted commit does not. One EXISTS on
--                                    event_erasure_run_event_started_idx, asked only when the new state would be outside
--                                    the family (opening an event). The runner closes the rest of the window itself: in
--                                    its commit transaction it locks the event row FOR SHARE and re-checks the state
--                                    before erasing (EventErasureRunner).
--
-- EVERY WRITER, RACE-FREE. A trigger sits below every client -- the React back office, the legacy Java one, DQL and
-- raw SQL alike. A BEFORE ROW trigger sees OLD as the row version it is about to replace, read under the row lock:
-- a writer that waited on a removal's lock re-reads the row once the removal commits, and meets the seal instead of
-- overwriting it.
--
-- NOT COVERED, on purpose. INSERT, beyond refusing a row born sealed: a new row has no history to protect, so its
-- other states -- duplicate_event()'s DRAFT copies included -- and its removed flag are not judged at birth.
-- DELETE: the foreign keys into event decide hard against soft (the back office falls back to removed = true on
-- 23503; V0112 itself only adds the flag, its key rebuild was taken out). Every other transition. The 40001 half
-- of the race: a client that moves a RECONCILED event after a removal's snapshot makes that removal fail with a
-- serialization error and roll back whole -- a failed run, not a damaged record.
--
-- NO OVERRIDE. There is deliberately no setting, role or flag through which an application gets round these rules;
-- kbs.erase_confirm admits the one move a removal makes, for the one event it names, and nothing else. Undoing a
-- seal is a DBA's job, done by hand in a reviewed session (ALTER TABLE public.event DISABLE TRIGGER
-- event_state_guards; the fix; ENABLE TRIGGER) -- a decision with a name on it, not a code path.
--
-- PRIVILEGES. The function runs with the privileges of whoever updates the row (not SECURITY DEFINER, like every
-- other trigger function here), and the last rule reads event_erasure_run, whose PUBLIC grants V0146 revoked.
-- Every application role reaches the schema as a member of its owner (V0090), and V0146 hands the ledger to that
-- owner, so they all can; a role that could not would fail closed -- its state change refused -- not open.
--
-- LOCKS. CREATE TRIGGER takes SHARE ROW EXCLUSIVE on event until the migration batch commits: event reads go on,
-- event writes wait. Guarded by a catalogue lookup (V0109's pattern), so the file can be applied by hand in a quiet
-- window first and the boot then re-runs it as a no-op that takes no lock.
--
-- CHECKED on 2026-10-28 against the migrations through V0144 and the 2026-10-22 production schema dump.
--   * event has one other trigger: check_room_configurations (V0122; its function,
--     trigger_event_check_room_configurations(), last redefined in V0123), BEFORE UPDATE OF start_date, end_date,
--     pre_date, post_date, type_id. Different columns, so most writes fire only one of the two. An UPDATE that sets
--     both fires check_room_configurations first (row triggers of the same timing fire in name order); it only
--     reads and raises, and returns NEW unchanged, so this trigger still judges the row as written. No conflict.
--   * No database function writes event.state or event.removed: copy_event and shift_event_for write other
--     columns, and duplicate_event (last redefined in V0144) only INSERTs, with state 'DRAFT' and removed left at
--     its default -- a row the INSERT rule lets in.
--   * The staging anonymiser neither writes event nor disables its triggers (event is not in 20-anonymise.sql's
--     section 4 list).
--   * Data: on 2026-09-25 (db_migration 113) staging held no removed event, no FINALISED or ARCHIVED one and no
--     RECONCILED one; not re-checked since. Creating the trigger judges no existing row: a row that already breaks
--     a rule is judged only when its state or removed flag next changes.
-- The raise names are new: a refused write fails as that writer's submit, and the back office maps the names and
-- hides the moves it would refuse (event-lifecycle contract §6); any other writer sees a failed database write.
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

CREATE OR REPLACE FUNCTION public.trigger_event_state_guards() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    state_changed boolean;
    closed_family boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- A new event is never born sealed: FINALISED is written by a removal, ARCHIVED only follows FINALISED.
        IF NEW.state = 'FINALISED' THEN
            RAISE EXCEPTION 'EVENT_FINALISED_BY_REMOVAL_ONLY';
        END IF;
        IF NEW.state = 'ARCHIVED' THEN
            RAISE EXCEPTION 'EVENT_ARCHIVED_FROM_FINALISED_ONLY';
        END IF;
        RETURN NEW;
    END IF;
    -- Nothing this trigger guards has changed: let the row through, whatever it holds.
    IF OLD.state IS NOT DISTINCT FROM NEW.state AND OLD.removed IS NOT DISTINCT FROM NEW.removed THEN
        RETURN NEW;
    END IF;
    state_changed := OLD.state IS DISTINCT FROM NEW.state;
    closed_family := coalesce(NEW.state IN ('CLOSED', 'RECONCILED', 'FINALISED', 'ARCHIVED'), false);
    IF state_changed THEN
        -- Sealed: the event's personal data has been removed, and the only move left is the archive toggle.
        IF OLD.state IN ('FINALISED', 'ARCHIVED')
           AND (NEW.state IS NULL OR NEW.state NOT IN ('FINALISED', 'ARCHIVED')) THEN
            RAISE EXCEPTION 'EVENT_STATE_SEALED';
        END IF;
        -- Archived: entered only from FINALISED, so the sealed pair is reachable only through a removal.
        IF NEW.state = 'ARCHIVED' AND OLD.state IS DISTINCT FROM 'FINALISED' THEN
            RAISE EXCEPTION 'EVENT_ARCHIVED_FROM_FINALISED_ONLY';
        END IF;
        -- FINALISED is written by a removal of a RECONCILED event, inside its own transaction, and by nothing else.
        -- The way back from ARCHIVED is exempt: ARCHIVED is only ever entered from FINALISED (the rule above).
        IF NEW.state = 'FINALISED' AND OLD.state IS DISTINCT FROM 'ARCHIVED'
           AND (OLD.state IS DISTINCT FROM 'RECONCILED'
                OR current_setting('kbs.erase_confirm', true) IS DISTINCT FROM ('ERASE EVENT ' || NEW.id)) THEN
            RAISE EXCEPTION 'EVENT_FINALISED_BY_REMOVAL_ONLY';
        END IF;
        -- Reconciled: back to CLOSED (the undo) or on to FINALISED (the removal, guarded just above), nowhere else.
        IF OLD.state = 'RECONCILED' AND (NEW.state IS NULL OR NEW.state NOT IN ('CLOSED', 'FINALISED')) THEN
            RAISE EXCEPTION 'EVENT_RECONCILED_CLOSED_ONLY';
        END IF;
    END IF;
    -- A removed event stays closed to bookings, whenever its state moves or it is being removed.
    IF NEW.removed IS TRUE AND (state_changed OR OLD.removed IS NOT TRUE) AND NOT closed_family THEN
        RAISE EXCEPTION 'EVENT_REMOVED_STAYS_CLOSED';
    END IF;
    -- An event whose data a commit removed -- or is removing right now -- stays closed too: asked only when it
    -- would open. A running commit's ledger row was committed before its transaction began, so it is visible here.
    IF state_changed AND NOT closed_family THEN
        IF EXISTS (SELECT 1 FROM public.event_erasure_run r
                    WHERE r.event_id = NEW.id AND r.mode = 'commit' AND r.status IN ('succeeded', 'running')) THEN
            RAISE EXCEPTION 'EVENT_DATA_REMOVED_STAYS_CLOSED';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

COMMENT ON FUNCTION public.trigger_event_state_guards() IS
    'V0147: refuses a new event born FINALISED or ARCHIVED, and state/removed changes that would unseal a '
    'FINALISED/ARCHIVED event (EVENT_STATE_SEALED), enter ARCHIVED other than from FINALISED '
    '(EVENT_ARCHIVED_FROM_FINALISED_ONLY), write FINALISED outside a removal of a RECONCILED event '
    '(EVENT_FINALISED_BY_REMOVAL_ONLY), move a RECONCILED event '
    'anywhere but CLOSED or FINALISED (EVENT_RECONCILED_CLOSED_ONLY), take a removed event out of '
    'the closed family (EVENT_REMOVED_STAYS_CLOSED) or reopen an event a removal erased or is erasing (a succeeded or '
    'running commit: EVENT_DATA_REMOVED_STAYS_CLOSED). No application override: a DBA disables the trigger by hand.';

-- The trigger, guarded by a catalogue lookup: a no-op, taking no lock, when the file was applied by hand first.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                    WHERE tgrelid = 'public.event'::regclass AND tgname = 'event_state_guards' AND NOT tgisinternal) THEN
        CREATE TRIGGER event_state_guards
            BEFORE INSERT OR UPDATE OF state, removed ON public.event
            FOR EACH ROW EXECUTE FUNCTION public.trigger_event_state_guards();
    END IF;
END $$;

-- Ownership of the function this migration created, as V0109 section 7 does for its trigger functions:
-- migrations run as the server's connect role, so hand it to the owner every other role reaches the schema
-- through -- warning rather than failing, because a tidy-up must never abort the boot chain.
DO $$
DECLARE
    schema_owner name;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO schema_owner FROM pg_class c WHERE c.oid = 'public.event'::regclass;
    IF schema_owner IS NOT NULL
       AND (SELECT pg_get_userbyid(p.proowner) FROM pg_proc p
             WHERE p.oid = 'public.trigger_event_state_guards()'::regprocedure) <> schema_owner THEN
        BEGIN
            EXECUTE format('ALTER FUNCTION public.trigger_event_state_guards() OWNER TO %I', schema_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0147: could not realign the owner of trigger_event_state_guards() (%)', SQLERRM;
        END;
    END IF;
END $$;
