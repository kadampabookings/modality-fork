-- V0116: a money account can be preferred for online bookings.
--
-- WHY. The managers want to be able to take online bookings through a different payment gateway from
-- in-person ones. The gateway follows the money account (money_account.gateway_company_id, and the
-- gateway_parameter rows of that account), so what is needed is a way to steer a booking's payment to an
-- account by the booking's channel.
--
-- WHO PICKS THE ACCOUNT. Not the Java code: a payment is inserted with to_money_account_id NULL and
-- autoset_money_transfer_to_account fills it in. That function now reads the booking's channel from
-- document.in_person:
--   * a plain payment: its own document;
--   * a spread payment (one payment across several bookings): online only if EVERY child booking is
--     online, otherwise in-person as soon as one child is. (Only the parent row carries an account: the
--     autoset_accounts trigger fires only for rows without a parent, so the children have none.)
-- and ranks first the accounts whose preferred_for_online matches it: TRUE for an online booking, FALSE
-- for an in-person one.
--
-- A PREFERENCE, NOT A FILTER -- hence the name, and why it is not the applicable_to_in_person /
-- applicable_to_online pair of rate and letter, which do exclude. The channel ranks first in the ORDER BY,
-- ahead of money_flow_priority and the event preference, but removes no candidate: if no open account
-- matches the channel, the payment still lands on another open account instead of failing with 'No open
-- money account found for this payment'.
--
-- NO BEHAVIOUR CHANGE ON ITS OWN. The flag defaults to FALSE, so every account ranks the same for either
-- channel and the old order decides as before. Ticking it on one account sends online bookings there and
-- keeps in-person bookings off it while another account can take them.
--
-- The payment-methods preview (ServerPaymentServiceProvider.getPaymentMethods) orders by the same flag,
-- so the gateway the booker is shown is the one the trigger then picks.
--
-- LOCKING. ADD COLUMN with a constant default is metadata only, but it still takes ACCESS EXCLUSIVE on
-- money_account, which every payment reads. So, the V0114 way: wait 3s at most, and on a busy table
-- become a WARNING. The column and the function go in ONE block, so a failure leaves both as they were
-- (the old function never references a column that does not exist). Running this file again by hand at a
-- quieter moment puts it right.

DO $do$
BEGIN
    SET LOCAL lock_timeout = '3s';

    ALTER TABLE public.money_account
        ADD COLUMN IF NOT EXISTS preferred_for_online boolean DEFAULT false NOT NULL;

    COMMENT ON COLUMN public.money_account.preferred_for_online IS
        'Payments for online bookings (document.in_person = false) prefer accounts where this is true, and '
        'payments for in-person bookings prefer accounts where it is false. A preference, not a filter. '
        'See autoset_money_transfer_to_account.';

    -- Identical to the function this replaces (V0001, and what prod and staging run), except for the
    -- lines marked V0116.
    EXECUTE $fn$
CREATE OR REPLACE FUNCTION public.autoset_money_transfer_to_account(mt public.money_transfer, update_money_transfer boolean) RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE
	ma_id int4;
	eventid int4;
	organizationid int4;
	inperson boolean; -- V0116
BEGIN

		IF (not mt.spread) THEN
			SELECT d.event_id, d.organization_id, d.in_person INTO eventid, organizationid, inperson from document d where d.id=mt.document_id; -- V0116: + in_person
		ELSE
			SELECT d.event_id, d.organization_id INTO eventid, organizationid from money_transfer mtc join document d on d.id=mtc.document_id where mtc.parent_id=mt.id limit 1;
			-- V0116: online only if every child booking is online; in-person as soon as one is
			SELECT bool_or(d.in_person) INTO inperson from money_transfer mtc join document d on d.id=mtc.document_id where mtc.parent_id=mt.id;
		END IF;
		inperson := coalesce(inperson, true); -- V0116: no document -> in-person, the column's default
		-- select MoneyAccount ma
      	-- where type.internal=?thisMoneyTransfer.(payment=false or refund=document.expenditure)
			-- and type.customer=?thisMoneyTransfer.(payment=true and document.expenditure=false and refund=true)
			-- and type.supplier=?thisMoneyTransfer.(payment=true and document.expenditure=true and refund=false)
			-- and exists(select MethodSupport ms where ms.moneyAccountType=ma.type and ms.method=?thisMoneyTransfer.method)
			-- and exists(select MoneyFlow mf where mf.fromMoneyAccount=?thisMoneyTransfer.fromMoneyAccount and mf.toMoneyAccount=ma and (mf.method=null or mf.method=?thisMoneyTransfer.method))
		SELECT INTO ma_id ma.id FROM money_account as ma
			JOIN money_account_type as mat on ma.type_id=mat.id
			--LEFT JOIN money_account_priority as map on map.account_id=ma.id and username=(select username from sys_sync limit 1)
			LEFT JOIN money_flow as mf on mf.from_money_account_id=mt.from_money_account_id and mf.to_money_account_id=ma.id
			LEFT JOIN money_flow_priority as mfp on mfp.flow_id=mf.id and username=(select username from sys_sync limit 1)
			--, document as d -- Removed as was too much time consuming, and d.expenditure is always false
			WHERE
				ma.organization_id = organizationid
				AND not ma.closed
				--AND (not mt.spread and d.id=mt.document_id or mt.spread and (exists(select * from money_transfer c where c.parent_id=mt.id and c.document_id=d.id)))
				AND mat.internal = (mt.payment=false OR mt.refund=false) -- was: AND mat.internal = (mt.payment=false OR mt.refund=d.expenditure)
				AND mat.customer = (mt.payment=true AND true AND mt.refund=true) -- was: AND mat.customer = (mt.payment=true AND d.expenditure=false AND mt.refund=true)
				-- aways false: AND mat.supplier = (mt.payment=true AND d.expenditure=true AND mt.refund=false)
				AND EXISTS(SELECT * FROM method_support as ms WHERE ms.money_account_type_id=mat.id AND ms.method_id=mt.method_id)
				AND EXISTS(SELECT * FROM money_flow as mf WHERE mf.from_money_account_id=mt.from_money_account_id AND mf.to_money_account_id=ma.id AND (mf.method_id is null OR mf.method_id=mt.method_id) AND (mt.amount >=0 AND mf.positive_amounts OR mt.amount < 0 and mf.negative_amounts))
			ORDER BY CASE WHEN ma.preferred_for_online = not inperson THEN 0 ELSE 1 END, -- V0116
				mfp.ord NULLS LAST, CASE WHEN ma.event_id=eventid THEN 0 ELSE 1 END, ma.id
			LIMIT 1;
		IF NOT FOUND and mt.method_id<>6 THEN -- 6 is Contra method used for transfers and don't need a money account associated
			RAISE EXCEPTION 'No open money account found for this payment';
		END IF;
		IF (update_money_transfer and (ma_id is null and mt.to_money_account_id is not null or ma_id is not null and (mt.to_money_account_id is null or mt.to_money_account_id<>ma_id))) THEN
			update money_transfer set to_money_account_id = ma_id where id=mt.id;
		END IF;

		RETURN ma_id;
END;
$$
$fn$;

    RAISE NOTICE 'V0116: money_account.preferred_for_online added, autoset_money_transfer_to_account updated';
EXCEPTION
    WHEN lock_not_available THEN
        RAISE WARNING 'V0116: money_account was busy, so NEITHER preferred_for_online NOR the new autoset_money_transfer_to_account were installed. Payments keep their old routing, and the payment-methods preview fails over to card only until this is done. Run this script again by hand at a quieter moment';
    WHEN insufficient_privilege THEN
        RAISE WARNING 'V0116: not allowed to alter money_account, so NEITHER preferred_for_online NOR the new autoset_money_transfer_to_account were installed. Run this script by hand as the table owner (SET ROLE kbs)';
END $do$;
