-- V0149: who verified a payment, and when -- for Finance › Payments and Finance › Statements.
--
-- WHY. A payment is `verified` once someone has matched it to the payment provider's or the bank's statement.
-- KBS2 records that as a tick and a `verifier` text, and only when a payout batch is ticked: `on_verified` stamps
-- the batch and its payments with `(select username from sys_sync limit 1)`, the KBS2 login of the session. Finance
-- › Payments and Finance › Statements (KBS3) tick payments and payouts through a server endpoint acting for the
-- signed-in person (Bruno and David, 2026-10-28). That endpoint's session has no KBS2 login of its own, and
-- `public.sys_sync` holds 147 leftover rows on staging, so the same expression would stamp an arbitrary login. And a
-- text is not enough: the accountant asked who ticked a payment AND when, and an event cannot be reconciled while
-- the answer is unknown.
--
-- 1. COLUMNS on money_transfer, both NULL until a tick:
--    - `verified_date` (timestamp, UTC like `date`): when the payment was ticked.
--    - `verifier_person_id` (FK to person, ON DELETE SET NULL): the KBS3 person who ticked it. NULL for a KBS2 tick,
--      whose person is unknown. SET NULL, not NO ACTION: V0068 counts 28 inbound person FKs and the ones without an
--      ON DELETE are what make a hard person merge abort; this one must not add to them. Erasing a staff member's
--      person nulls the link and leaves the `verifier` text, as KBS2 logins do today. A person merge must re-point
--      it: the merge (PersonMergeCascade, server side) asks the catalogue for every key pointing at person and
--      REFUSES to merge while one is missing from PersonReferences. So this script must ship in the SAME deploy as
--      the PersonReferences line of docs/design/payment-verification-endpoint-for-bruno.patch, or every person
--      merge is refused until it does.
--    - An index on `verifier_person_id`, PARTIAL (`IS NOT NULL`): the ON DELETE SET NULL runs
--      `UPDATE money_transfer SET verifier_person_id = NULL WHERE verifier_person_id = $1` for every person deleted,
--      and without an index that is a sequential scan of the whole table (542,448 rows, 78 MB on staging,
--      2026-10-28) per person -- an erasure of a thousand people would scan it a thousand times. Only KBS3 ticks fill
--      the column, so the partial index stays small; the planner proves `IS NOT NULL` from the FK's `= $1`.
--    Rows ticked before this script keep `verified_date` and `verifier_person_id` NULL: nothing recorded when they
--    were ticked, and nothing is made up here.
--
-- 2. `kbs_verifier_name()` -- the text a tick writes into `verifier` (varchar(45)):
--    - when the session acts for a KBS3 person (`kbs_audit_person_id()`, V0068), that person's first name and the
--      initial of the last name, "Fiona S.", cut to 45 characters; NULL when the person has no name or no longer
--      exists -- never a KBS2 login, which would be wrong. The name itself is `kbs_short_person_name(first, last)`,
--      shared with the stamp below so the two can never disagree;
--    - otherwise KBS2's own expression, unchanged: `(select username from sys_sync limit 1)`. A KBS2 session's
--      `sys_sync` is its own login, so KBS2 keeps writing exactly what it writes today.
--
-- 3. `on_verified` (KBS2's trigger on payout batches) is re-written from the function production and staging run
--    (`\sf` on staging, 2026-10-28; no migration had touched it). What it does is unchanged: a batch whose
--    `expected_amount` differs from its `amount` refuses the tick, with the same message (the KBS3 endpoint matches
--    it); a tick sets the batch's `expected_amount`, `read` and `verifier`, and leaves every payment in it verified,
--    read, not pending, with the batch's verifier; an untick unticks them. Two things change:
--    - the verifier is `kbs_verifier_name()`, computed once per batch, instead of `(select username from sys_sync
--      limit 1)` twice;
--    - only the payments that change are written, each once, and a split payment that is already read and not
--      pending gets the tick alone. The old form re-wrote all four columns on every payment, and listing `read` or
--      `pending` fires `cascade_children` on a split payment, which re-writes each child's `successful`;
--      `defer_compute_document_deposit` then re-arms a deposit recompute on every child's booking -- for a flag that
--      moves no money: 993 bookings for one unticked batch on staging (2,841 payments, 529 of them split, 1,250
--      children). V0148 removed the same cost from ticking a split payment. A split payment that is still unread
--      or pending when its batch is ticked has to be written with `read` / `pending`, so it still goes through
--      `cascade_children` -- on staging every split payment of every unticked batch since 2023 is unread -- and its
--      children's bookings are still re-armed. Removing that is a change to KBS2's deposit trigger, not this.
--
-- 4. `stamp_verification`, BEFORE UPDATE OF verified, keeps the three columns describing the same tick:
--    - a tick (false -> true): `verified_date` = now (UTC), `verifier_person_id` = the KBS3 person (NULL for KBS2,
--      and NULL rather than a foreign-key error for an id that names no person), and `verifier` =
--      `kbs_verifier_name()` when a KBS3 person is set. A KBS2 tick keeps whatever KBS2 or `on_verified` wrote.
--    - an untick (true -> false): all three cleared. KBS2 left `verifier` behind on an untick; a name on an
--      unverified payment reads as a verification that no longer holds, so it goes too (David, 2026-10-28).
--    - a RE-tick that changes the verifier (true -> true, `verifier` changed, `verified` in the SET list): stamped
--      again, as a tick. Without it the cascades below would leave a payment that was already verified with the new
--      tick's name but the old tick's person and date. `on_verified` re-ticks EVERY payment of a batch, including the
--      ones ticked on their own before (on staging today, 2 verified payments carry another name than their
--      verified payout's), and overwrites their `verifier`.
--    It holds no lock and checks no event: the KBS3 endpoint refuses an untick of a reconciled event's payment
--    (David's decision), and KBS2 does not pass through it. It runs once per payment of a batch, so it reads the
--    audit id once and the person once (id and name in one SELECT, skipped for a KBS2 session).
--
--    HOW IT REACHES BATCH PAYMENTS AND SPLIT CHILDREN. Through the existing cascades, each of which updates
--    `verified` on the rows below, so this BEFORE trigger fires on each of them in the same transaction -- same
--    `now()`, same session person, same name:
--    - a payout (`receipts_transfer`): `on_verified` (AFTER) sets `verified = true` and the batch's verifier on its
--      payments that are not verified or carry another name, or `verified = false` on an untick. Its own re-update
--      of the batch row (`expected_amount`, `read`, `verifier`) does not list `verified`, so it does not fire this
--      trigger again, and for a KBS3 session it writes the name this trigger already wrote.
--    - a split payment (`spread`): V0148's `cascade_children_verified` (AFTER) copies `verified` and `verifier` to
--      the children.
--    - a split payment inside a payout: both, in turn.
--
-- 5. OPERATIONS (V0131's and V0148's templates; idempotent on the code).
--    - VerifyPayments: ticking and unticking payments and payouts, on Finance › Payments and Finance › Statements
--      (David, 2026-10-28). Checked by the server endpoint that writes the tick. No grant route (an action, not a
--      page); not read-only; granted to nobody.
--    - RouteToPayments (`/payments*`) and RouteToStatements (`/statements*`) become read_only. V0131's rule
--      (2026-10-18): a route may be read-only only when every write on the pages it opens has an operation of its
--      own. These two pages' only write is the tick, gated by VerifyPayments on the server, so opening them grants
--      nothing but looking -- a read-only access may be given them. Both are granted to nobody today (staging,
--      2026-10-28), and no other back-office route starts with `/payments` or `/statements`.
--
-- LOCKING. Adding the columns takes ACCESS EXCLUSIVE on money_transfer, which every payment reads, and the foreign
-- key takes SHARE ROW EXCLUSIVE on person. So, the V0116 way: the columns, the index and the trigger go in ONE
-- block that waits 3s at most and becomes a WARNING on a busy table, leaving everything as it was. The functions
-- outside the block are harmless without it: `kbs_verifier_name()` needs no new column, and the stamp function is
-- only called by the trigger the block creates. Running this file again by hand at a quieter moment (as the table
-- owner: SET ROLE kbs) puts it right; every statement is idempotent.
--
-- ANONYMISER (scripts/gdpr-anonymise, same change): `verified_date` not-pii (when a payment was checked against a
-- statement); `verifier_person_id` kept (a staff person's id, no content -- the person row it names is anonymised
-- in place). `verifier` is already anonymised, and money_transfer's user triggers are disabled while it is
-- rewritten (20-anonymise.sql, section 4) -- this trigger fires only on `verified`, which the anonymiser never
-- writes, in any case.
--
-- DOMAIN MODEL: MoneyTransfer.verifiedDate and MoneyTransfer.verifierPerson (KBS2 system.script FIELD rows,
-- DomainModelSnapshot.json, DomainModel.json, entities.ts).

-- 2. The verifier's name -------------------------------------------------------------------------------------------

-- "First L.": blanks trimmed, a missing part left out, at most 45 characters (verifier's length), NULL when there is
-- no name at all.
CREATE OR REPLACE FUNCTION public.kbs_short_person_name(first_name text, last_name text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT nullif(rtrim(left(btrim(coalesce(nullif(btrim(first_name), ''), '') || ' '
                                   || coalesce(left(nullif(btrim(last_name), ''), 1) || '.', '')), 45)), '')
$$;

COMMENT ON FUNCTION public.kbs_short_person_name(text, text) IS
    'A person''s first name and last-name initial, "Fiona S.", as money_transfer.verifier records a KBS3 verifier. V0149.';

CREATE OR REPLACE FUNCTION public.kbs_verifier_name() RETURNS text
    LANGUAGE plpgsql
AS $$
DECLARE
    v_person integer := public.kbs_audit_person_id(); -- never raises (V0068)
BEGIN
    IF v_person IS NULL THEN
        -- KBS2's own expression, unchanged: a KBS2 session's sys_sync holds its login. Unqualified on purpose,
        -- as in KBS2's trigger, so the session's own sys_sync is the one read.
        RETURN (select username from sys_sync limit 1);
    END IF;
    RETURN (SELECT public.kbs_short_person_name(p.first_name, p.last_name) FROM person p WHERE p.id = v_person);
END $$;

COMMENT ON FUNCTION public.kbs_verifier_name() IS
    'The text a verified tick writes into money_transfer.verifier: the KBS3 person''s "First L." when the session '
    'acts for one (kbs_audit_person_id), otherwise KBS2''s (select username from sys_sync limit 1). V0149.';

-- 3. on_verified: the name from kbs_verifier_name(), and only the payments that change are written --------------
-- Production's and staging's version (KBS2's), for the record:
--   if (NEW.verified) then
--     if (NEW.expected_amount is not null and NEW.expected_amount<>NEW.amount) then
--       RAISE EXCEPTION 'Can''t mark a transfer with error as verified';
--     else update money_transfer set expected_amount = amount, read = true,
--            verifier = (select username from sys_sync limit 1) where id=NEW.id; end if;
--     update money_transfer set read = true, pending = false, verified = true,
--            verifier = (select username from sys_sync limit 1) where transfer_id=NEW.id;
--   else update money_transfer set verified = false where transfer_id=NEW.id; end if;

CREATE OR REPLACE FUNCTION public.trigger_money_transfer_on_verified() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_name text;
BEGIN
    IF NEW.verified THEN
        IF NEW.expected_amount IS NOT NULL AND NEW.expected_amount <> NEW.amount THEN
            -- Same text as KBS2's: the KBS3 endpoint recognises the refusal by it.
            RAISE EXCEPTION 'Can''t mark a transfer with error as verified';
        END IF;
        v_name := public.kbs_verifier_name();
        UPDATE money_transfer SET expected_amount = amount, read = true, verifier = v_name WHERE id = NEW.id;
        -- Only the payments that change, each written once. A split payment already read and not pending gets the
        -- tick alone: listing `read` or `pending` would fire cascade_children, which re-writes every child's
        -- `successful` and so re-arms a deposit recompute on every child's booking; the tick reaches the children
        -- through cascade_children_verified instead, which writes `verified` and `verifier` only.
        UPDATE money_transfer SET verified = true, verifier = v_name, read = true, pending = false
         WHERE transfer_id = NEW.id
           AND (NOT spread OR NOT read OR pending)
           AND (NOT verified OR verifier IS DISTINCT FROM v_name OR NOT read OR pending);
        UPDATE money_transfer SET verified = true, verifier = v_name
         WHERE transfer_id = NEW.id
           AND spread AND read AND NOT pending
           AND (NOT verified OR verifier IS DISTINCT FROM v_name);
    ELSE
        UPDATE money_transfer SET verified = false WHERE transfer_id = NEW.id AND verified;
    END IF;
    RETURN NEW;
END $$;

-- 4. The stamp ------------------------------------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.trigger_money_transfer_stamp_verification() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_audit  integer;
    v_person integer;
    v_name   text;
BEGIN
    IF NEW.verified THEN
        -- UTC whatever the session's TimeZone, as money_transfer.date is read.
        NEW.verified_date := now() AT TIME ZONE 'UTC';
        v_audit := public.kbs_audit_person_id(); -- once per row: it opens a subtransaction (V0068's EXCEPTION)
        IF v_audit IS NULL THEN
            -- A KBS2 session: no person, and the verifier is what KBS2 or on_verified wrote.
            NEW.verifier_person_id := NULL;
        ELSE
            -- Id and name in one read, the name as kbs_verifier_name() gives it. An id that names no person leaves
            -- both NULL, rather than a foreign-key error that would refuse the tick.
            SELECT p.id, public.kbs_short_person_name(p.first_name, p.last_name)
              INTO v_person, v_name
              FROM person p
             WHERE p.id = v_audit;
            NEW.verifier_person_id := v_person;
            NEW.verifier := v_name;
        END IF;
    ELSE
        NEW.verified_date := NULL;
        NEW.verifier_person_id := NULL;
        NEW.verifier := NULL;
    END IF;
    RETURN NEW;
END $$;

-- 1 and 4. Columns, index and trigger, in one block (see LOCKING) --------------------------------------------------

DO $do$
DECLARE
    v_lock_timeout text := current_setting('lock_timeout');
BEGIN
    SET LOCAL lock_timeout = '3s';

    ALTER TABLE public.money_transfer
        ADD COLUMN IF NOT EXISTS verified_date timestamp without time zone,
        ADD COLUMN IF NOT EXISTS verifier_person_id integer
            CONSTRAINT money_transfer_verifier_person_id_fkey REFERENCES public.person (id) ON DELETE SET NULL;

    COMMENT ON COLUMN public.money_transfer.verified_date IS
        'When the transfer was ticked verified (UTC), set by the stamp_verification trigger; cleared on an untick. '
        'NULL on rows ticked before V0149.';
    COMMENT ON COLUMN public.money_transfer.verifier_person_id IS
        'The KBS3 person who ticked the transfer verified (kbs_audit_person_id), set by the stamp_verification '
        'trigger; NULL for a KBS2 tick, whose login is in verifier. Re-point it when merging persons.';

    CREATE INDEX IF NOT EXISTS money_transfer_verifier_person_id_idx
        ON public.money_transfer (verifier_person_id) WHERE verifier_person_id IS NOT NULL;

    CREATE OR REPLACE TRIGGER stamp_verification
        BEFORE UPDATE OF verified ON public.money_transfer
        FOR EACH ROW WHEN (old.verified IS DISTINCT FROM new.verified
                           OR (new.verified AND old.verifier IS DISTINCT FROM new.verifier))
        EXECUTE FUNCTION public.trigger_money_transfer_stamp_verification();

    PERFORM set_config('lock_timeout', v_lock_timeout, true);
    RAISE NOTICE 'V0149: money_transfer.verified_date and verifier_person_id added, stamp_verification installed';
EXCEPTION
    WHEN lock_not_available THEN
        RAISE WARNING 'V0149: money_transfer or person was busy, so NEITHER verified_date NOR verifier_person_id NOR the stamp_verification trigger were installed. Finance › Payments and Statements fail to load until this is done. Run this script again by hand at a quieter moment';
    WHEN insufficient_privilege THEN
        RAISE WARNING 'V0149: not allowed to alter money_transfer, so NEITHER verified_date NOR verifier_person_id NOR the stamp_verification trigger were installed. Run this script by hand as the table owner (SET ROLE kbs)';
END $do$;

-- 5. Operations ----------------------------------------------------------------------------------------------------

INSERT INTO operation (operation_code, name, backend, frontend, public, guest, read_only)
SELECT 'VerifyPayments', 'Verify payments against statements', true, false, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'VerifyPayments');

UPDATE operation SET read_only = true
 WHERE operation_code IN ('RouteToPayments', 'RouteToStatements') AND NOT read_only;
