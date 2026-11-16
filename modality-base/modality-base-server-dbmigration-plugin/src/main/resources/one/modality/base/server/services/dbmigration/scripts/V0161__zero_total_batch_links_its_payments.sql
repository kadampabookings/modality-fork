-- A daily batch whose payments add up to zero must still take those payments in.
--
-- A money flow with an `auto_transfer_time` batches the day's receipts of one account into a single
-- transfer to another (the "daily batch" on the Statements screen). Two pieces of code do it:
--
--   1. trigger_money_flow_transfer_ready_daily_batch (set off nightly by KBS2's AutomaticLettersActor
--      raising money_flow.trigger_transfer) finds the OLDEST receipt not yet in a batch
--      (`transfer_id is null`) and inserts the batch at that day's auto_transfer_time. It inserts no
--      amount, so the batch starts at the column default, 0.
--   2. compute_money_account_balances, replaying the account at commit, reaches the batch, sets its
--      amount to the running balance, and links the day's receipts to it (`transfer_id`).
--
-- Step 2 did both only when the amount had to change:
--
--     IF (mt.receipts_transfer and mt.amount<>balance) THEN
--
-- On a day whose receipts net to zero -- a payment and its refund, both on the same day -- the
-- balance is 0 and the batch is already 0, so the block was skipped. The batch was created and
-- nothing was linked to it. The receipts kept showing on the Statements screen with "Flat batches"
-- unticked, which is how the accounts team noticed.
--
-- And it did not stay one wrong batch. Step 1 always starts from the oldest unlinked receipt, so the
-- next night it found the same day again and made another empty batch dated that day, then another
-- the night after. Staging (a production copy) has 533 zero batches with nothing linked, across 24
-- accounts from 2015 to November 2026; account 115 has 60 copies of its 2025-03-16 23:59:59 batch, one
-- per night for two months.
--
-- ── The fix ─────────────────────────────────────────────────────────────────────────────────────
--
-- The amount-change path is unchanged. A batch whose amount is already right but which has nothing
-- linked to it now links the receipts in its window that NO batch holds yet (`transfer_id is null`).
-- The restriction is what keeps duplicates quiet: on a day with several empty copies, the first copy
-- the replay reaches takes the receipts, and the later copies find nothing left to take -- rather than
-- each copy taking them from the one before, on every replay.
--
-- The extra test costs an index lookup on money_transfer_transfer_id_idx per batch the replay passes,
-- and only a batch with nothing linked goes on to the update.
--
-- ── What this does NOT do ───────────────────────────────────────────────────────────────────────
--
-- It does not repair existing empty batches or delete the duplicates; it stops new ones. An existing
-- empty batch takes its receipts the next time a replay passes it (any new transfer on or before its
-- date on that account), not before. The duplicate copies stay empty and need deleting by hand.
--
-- The body below is what production runs: the staging copy of compute_money_account_balances (restored
-- from production) was compared with scripts/kbs-database-structure.sql on 2026-11-16 and the bodies
-- were identical. Only the block marked V0161 differs. A database built from the V0001 baseline has no
-- standalone compute function (its replay is inlined in deferred_compute_money_account_balances), so
-- there this creates the function without changing how balances are replayed.

CREATE OR REPLACE FUNCTION public.compute_money_account_balances(money_account_id integer, from_date timestamp without time zone) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
    ma money_account%ROWTYPE;
	mt money_transfer%ROWTYPE;
	pt money_transfer%ROWTYPE; -- previous money transfer
	balance int;
BEGIN

    RAISE NOTICE 'Updating balances for money_account % from date %', money_account_id, from_date;

	SELECT INTO ma * FROM money_account WHERE id=money_account_id;

    RAISE NOTICE 'STEP 1';

	-- find the last money_transfer before the triggered date and start from that balance
	SELECT INTO mt * FROM money_transfer WHERE date < from_date and date > from_date - interval '1 day' AND not pending and successful AND (from_money_account_id=money_account_id OR to_money_account_id=money_account_id) ORDER BY date DESC,id DESC LIMIT 1;

	IF NOT FOUND THEN
    	RAISE NOTICE 'STEP 1b';
		SELECT INTO mt * FROM money_transfer WHERE date < from_date and date > from_date - interval '1 month' AND not pending and successful AND (from_money_account_id=money_account_id OR to_money_account_id=money_account_id) ORDER BY date DESC,id DESC LIMIT 1;
		IF NOT FOUND THEN
	    	RAISE NOTICE 'STEP 1c';
			SELECT INTO mt * FROM money_transfer WHERE date < from_date AND not pending and successful AND (from_money_account_id=money_account_id OR to_money_account_id=money_account_id) ORDER BY date DESC,id DESC LIMIT 1;
		END IF;
	END IF;

    RAISE NOTICE 'STEP 2';

	IF NOT FOUND THEN
		balance := 0;
	ELSIF mt.from_money_account_id=money_account_id THEN
		balance := COALESCE(mt.from_money_account_balance, 0);
	ELSE
		balance := COALESCE(mt.to_money_account_balance, 0);
	END IF;

    RAISE NOTICE 'BEFORE LOOP';

	-- update all money_transfer balances after that date
	FOR mt IN SELECT * FROM money_transfer WHERE date >= from_date AND (from_money_account_id=money_account_id OR to_money_account_id=money_account_id) ORDER BY date, id
	LOOP
		RAISE NOTICE 'Updating money_transfer %', mt.id;
		IF mt.from_money_account_id=money_account_id THEN
			IF not mt.pending and mt.successful THEN
				IF (mt.receipts_transfer and mt.amount<>balance) THEN
					update money_transfer set amount = balance WHERE id=mt.id;
					pt := null;
					select into pt * from money_transfer where receipts_transfer=true and from_money_account_id=mt.from_money_account_id and date < mt.date order by date desc limit 1;
					update money_transfer set transfer_id=mt.id where parent_id is null and receipts_transfer=false and to_money_account_id=mt.from_money_account_id and method_id=mt.method_id and date <= mt.date and (pt is null or date > pt.date);
					update money_transfer set transfer_id=null where transfer_id=mt.id and date > mt.date;
					mt.amount := balance;
				-- V0161: a batch whose amount is already right (a zero-total day) but which nothing is
				-- linked to takes in the receipts of its window that no batch holds yet.
				ELSIF (mt.receipts_transfer and not exists (select 1 from money_transfer c where c.transfer_id=mt.id)) THEN
					pt := null;
					select into pt * from money_transfer where receipts_transfer=true and from_money_account_id=mt.from_money_account_id and date < mt.date order by date desc limit 1;
					update money_transfer set transfer_id=mt.id where transfer_id is null and parent_id is null and receipts_transfer=false and to_money_account_id=mt.from_money_account_id and method_id=mt.method_id and date <= mt.date and (pt is null or date > pt.date);
				END IF;
				balance := balance - mt.amount;
			END IF;
			update money_transfer set from_money_account_balance = balance WHERE id=mt.id and (from_money_account_balance is null or from_money_account_balance <> balance);
			IF ma.closed and FOUND THEN
				RAISE EXCEPTION '''%'' money account is closed', ma.name;
			END IF;
		ELSE
			IF not mt.pending and mt.successful THEN
				balance := balance + mt.amount;
			END IF;
			update money_transfer set to_money_account_balance = balance WHERE id=mt.id and (to_money_account_balance is null or to_money_account_balance <> balance);
			IF ma.closed and FOUND THEN
				RAISE EXCEPTION '''%'' money account is closed', ma.name;
			END IF;
		END IF;
	END LOOP;

    RAISE NOTICE 'AFTER LOOP';

	return money_account_id;

END $$;
