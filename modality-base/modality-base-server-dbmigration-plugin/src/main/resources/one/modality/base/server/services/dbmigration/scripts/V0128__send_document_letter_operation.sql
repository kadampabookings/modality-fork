-- Seeds the operation that gates sending a booking's letter, so the act can be granted to somebody
-- rather than falling out of whoever may write a document column.
--
-- WHY: a letter is composed and addressed by the database. `send_letter` fires on
-- `UPDATE OF document.trigger_send_letter_id`, inserts a `mail` carrying the document and no
-- recipient, and `trigger_mail_auto_recipient` then derives the address. Measured 2026-10-16: every
-- letter type whose body holds `[yourCart]` -- Confirmation, Cart, Cancellation (24h/72h/96h),
-- No-deposit (1h/3h/48h, 2, 3) -- has `letter_type.payer = false` and `letter_type."order" = false`,
-- so all of them take the branch where `document.person_email` beats the account login. That column
-- is client-writable, and the cart link the letter carries is a BEARER TOKEN: a guest session for
-- the booking, payment included. So a back-office caller could point a booking at their own address
-- and fire the trigger, against any booking by sequential id.
--
-- Neither obvious fix works. Making the account login win would change the recipient of 23,609
-- documents (8% of 292,744) whose attendee address legitimately differs from the account's -- a
-- member booking for a spouse or child. And no rule over the column's VALUE can tell a staff
-- typo-fix from a redirect. What closes it is structural, and this row is its first half: the send
-- becomes a named act, separately granted, scoped to the booking's owner and logged
-- (SendDocumentLetterEndpoint), after which the firing column is denied to clients.
--
-- NO grant_route, deliberately, following V0106's three in-page actions: this gates a control, not a
-- page. The back office reaches letters from screens that already have their own route grants; what
-- this adds is permission to make one be SENT.
--
-- GRANTED TO NOBODY by this script, which is the point of having it. Super administrators pass it
-- the way they pass every operation (the `operation:*` wildcard, a branch of its own in the grant
-- provider, not a row here), so the people who run the system keep working and everybody else must
-- be given it deliberately. Decide the holders before the client half ships, not after.
--
-- Idempotent on the code, like every operation seed since V0106: re-running adds nothing, and a code
-- that somehow already exists is left exactly as it is rather than being rewritten underneath
-- whoever created it.
--
-- No anonymiser change: `operation` is classified ('operation','*','not-pii','reference') and this
-- adds no column -- re-read rather than assumed, since a '*' row silently admits new ones.

INSERT INTO operation (operation_code, name, backend, frontend, public, guest)
SELECT v.code, v.name, true, false, false, false
  FROM (VALUES ('SendDocumentLetter', 'Send a booking letter')) AS v(code, name)
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = v.code);
