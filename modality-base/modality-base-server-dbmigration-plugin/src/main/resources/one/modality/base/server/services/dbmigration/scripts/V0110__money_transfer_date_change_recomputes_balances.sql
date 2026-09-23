-- A money transfer that MOVES IN TIME must recompute the balances it moved across.
--
-- Every money_transfer carries the running balance of the two accounts it touches
-- (`from_money_account_balance`, `to_money_account_balance`), maintained by a trigger: an insert,
-- delete or edit flags the affected accounts and records how far back to go, and a deferred
-- constraint trigger replays each flagged account from that date at commit time. That is why the
-- accounting teams can read a statement straight off the transfer list.
--
-- The running balance is ordered by `date`, so the date is one of the inputs. The trigger
-- declaration says so -- it fires on `UPDATE OF date, from_money_account_id, to_money_account_id,
-- amount, pending, successful`. The trigger BODY then tested every one of those columns except
-- `date`, so a date edit woke the trigger up and was thrown away.
--
-- It was not always like that. The function this repository was seeded with scheduled the replay
-- for ANY update of those columns:
--
--     IF (THIS.parent_id is null) THEN                                 -- V0001__base.sql
--
-- Somewhere after that it gained a test for which columns actually changed, so that an edit to a
-- comment or a verification flag would not replay an account for nothing -- and the test was
-- written without `date`:
--
--     IF (THIS.parent_id is null and (TG_OP <> 'UPDATE'                -- what prod runs today
--         OR NEW.from_money_account_id is distinct from OLD.from_money_account_id
--         or NEW.to_money_account_id is distinct from OLD.to_money_account_id
--         or NEW.amount is distinct from OLD.amount
--         or (not NEW.pending and NEW.successful) <> (not OLD.pending and OLD.successful))) THEN
--
-- So there are two defects, and the second hides behind the first:
--
--   1. A date-only edit does nothing at all. No account is flagged, no replay is scheduled, and
--      every balance between the transfer's old and new position keeps its old value.
--
--   2. Even when something else on the row changes too -- an amount corrected at the same time as
--      the date -- the replay starts at the transfer's NEW date. Moving a transfer FORWARD in time
--      leaves the rows between the old and the new date untouched, because the replay window opens
--      after them. The window has to start at the EARLIER of the two dates.
--
-- Fixed here: `date` joins the change test, and the replay starts at LEAST(new date, old date).
--
-- A third, latent defect is fixed with them. The trigger raised the account's "replay me" flag
-- before it wrote down the date to replay from. Raising the flag is what schedules the replay, and
-- the replay reads that date out of a temporary table -- so the write has to come first. Deferred
-- to commit as it normally is, the replay ran long after both statements and never noticed; forced
-- to run earlier (SET CONSTRAINTS ALL IMMEDIATE) it died on a table that did not exist yet:
--
--     ERROR: relation "money_account_trigger_info" does not exist
--
-- The two statements are swapped below. In normal deferred operation this changes nothing.
--
-- ── Reproduced before, verified after ───────────────────────────────────────────────────────────
--
-- On a throwaway cluster loaded from scripts/kbs-database-structure.sql -- four transfers between
-- one pair of accounts, the February one moved to May:
--
--            before            after
--   Mar 10   to_bal 180        to_bal 130   <- rows the moved transfer no longer sits behind
--   Apr 10   to_bal 250        to_bal 200
--   May 10   to_bal 150        to_bal 250   <- the moved transfer, now last
--
-- The same edit made backwards (April moved to February 1st) was wrong in the mirror image. A
-- randomised run of 1,000 single-edit transactions -- insert, delete, amount, pending, successful
-- and date, each compared against a full replay -- diverged only ever on a date edit, and not once
-- after this change. `scripts/verify-money-balance-triggers.sql` is that check, kept.
--
-- ── What this does NOT do ───────────────────────────────────────────────────────────────────────
--
-- It does not repair balances that are already wrong; it only stops new ones appearing. The repair
-- the accounting teams ask for by hand --
--
--   select compute_money_account_balances(id, :from_date) from money_account ma
--    where exists (select * from money_transfer mf where mf.date >= :from_date
--                    and (ma.id = mf.from_money_account_id or ma.id = mf.to_money_account_id));
--
-- -- is still needed once, and is in `scripts/repair-money-account-balances.sql`. It belongs there
-- rather than here: replaying whole accounts holds locks on money_transfer for as long as it
-- takes, which is not something to do in the middle of a deploy.
--
-- It also touches neither `compute_money_account_balances` nor
-- `deferred_compute_money_account_balances`, deliberately. Both differ between what prod runs and
-- what the V0001 baseline creates (the baseline has no standalone compute function at all -- its
-- logic is inlined), and the only copy of the prod versions available here is
-- scripts/kbs-database-structure.sql, a July dump that CLAUDE.md marks as not authoritative.
-- Replacing the function that writes every money balance in the system from a three-month-old copy
-- could silently revert a hand-patch nobody remembers making. The defect is entirely in the
-- function below, which IS the same broken shape in both, so fixing only it converges them.


CREATE OR REPLACE FUNCTION public.trigger_money_transfer_defer_compute_money_account_balances() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
	THIS money_transfer%ROWTYPE;
	PREV money_transfer%ROWTYPE;
	trigger_date timestamp;
BEGIN

	IF (TG_OP = 'DELETE') THEN THIS := OLD; ELSE THIS := NEW; END IF;

	IF (TG_OP = 'INSERT') THEN PREV := NEW; ELSE PREV := OLD; END IF;

	-- RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, THIS.id;

	-- Balances are a running total ordered by date, so a transfer that moved in time invalidates
	-- every balance between where it was and where it now is. The replay therefore starts at the
	-- earlier of the two dates -- the new one when the transfer moved back, the old one when it
	-- moved forward. For an insert or a delete the two are the same date.
	trigger_date := LEAST(THIS.date, PREV.date);

	IF (THIS.parent_id is null and (TG_OP <> 'UPDATE' OR NEW.date is distinct from OLD.date or NEW.from_money_account_id is distinct from OLD.from_money_account_id or NEW.to_money_account_id is distinct from OLD.to_money_account_id or NEW.amount is distinct from OLD.amount or (not NEW.pending and NEW.successful) <> (not OLD.pending and OLD.successful))) THEN

		-- Record where to replay from BEFORE raising the flag that schedules the replay.
		create temporary table if not exists money_account_trigger_info (money_account_id int, date timestamp) ON COMMIT DROP;

		insert into money_account_trigger_info (money_account_id) select id from money_account where (id=THIS.from_money_account_id or id=THIS.to_money_account_id or id=PREV.from_money_account_id or id=PREV.to_money_account_id) and not exists(select * from money_account_trigger_info where money_account_id = id);

		update money_account_trigger_info set date = trigger_date from money_account as ma where money_account_id=id and (date is null or date > trigger_date) and (id=THIS.from_money_account_id or id=THIS.to_money_account_id or id=PREV.from_money_account_id or id=PREV.to_money_account_id);

		update money_account set trigger_defer_compute_balances = true where (id=THIS.from_money_account_id or id=THIS.to_money_account_id or id=PREV.from_money_account_id or id=PREV.to_money_account_id) and trigger_defer_compute_balances = false;

  	END IF;

	RETURN NEW;

END $$;
