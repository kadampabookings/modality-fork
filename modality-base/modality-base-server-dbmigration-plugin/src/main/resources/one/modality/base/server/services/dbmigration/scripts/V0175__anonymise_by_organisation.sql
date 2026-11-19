-- V0175: anonymise_person() and anonymise_booker() erase by ORGANISATION — what a Data Protection Officer's
-- organisations hold, plus the shared account; another organisation's records are kept, unlinked.
--
-- WHY. Since 18 November a DPO holds DataProtection per organisation (V0168, the authorization framework), but erasing
-- a customer still reached every organisation's bookings: any organisation's DPO erased anybody, everywhere. On
-- staging (19 Nov 2026) 8,571 of 36,176 customers with bookings booked with two organisations or more (up to six).
-- David's rule (19 Nov): a DPO erases their organisations' part now — bookings of organisations where they do not
-- hold DataProtection are KEPT AS THEY ARE AND UNLINKED from the erased person — or the whole person once every other
-- organisation's DPO has approved (person_erasure_approval, V0176), which is this function with NULL, as before.
--
-- WHAT. Both functions take one more, last argument, p_organization_ids integer[] DEFAULT NULL: the organisations
-- whose records the run erases. The back office computes it from the framework (EventErasureAccess, fresh grants);
-- it is never sent by the browser. NULL = every organisation: the function behaves exactly as V0164's / V0163's
-- body (rehearsed side by side on the same fixture: same grid but for the rows below, same rows written).
-- The organisation of a booking is its event's, else its own (document.organization_id); a booking with neither is
-- in no organisation, so not covered. A volunteering application's is its own column.
--
-- anonymise_person(), with a list:
--   * every booking of an organisation not in the list leaves the scope as "kept": no rewrite reaches it, its lines,
--     its mails and their recipients, its history, payments, queued KBS2 requests, conversations, list items or push
--     registrations; it never blocks the run (not over, balance, queued request or mail: those refusals apply to the
--     covered bookings only — David, 19 Nov);
--   * its id links to the erased persons are cut FIRST, before the person row is rewritten (the person trigger
--     on_details_change_update_upcoming_bookings would otherwise write the pseudonym into a kept upcoming booking):
--     document.person_id / payer_id / person_carer1_id / person_carer2_id; the room-owner id on lines of another
--     organisation's bookings; history.user_person_id on another organisation's bookings' history, and the text
--     'person #<id>' on a kept booking's history; recipient.person_id of their mails; a kept conversation's viewer and
--     message authors (their participant and reaction rows, nothing but the link, are deleted); list items and push
--     registrations of another organisation's bookings. Removing a payer moves the booking out of the payer's cart
--     (trigger payer_cart, V0109; 2 bookings with a payer on staging): the only column of a kept booking that changes
--     beyond the links;
--   * the same trigger re-copies the current details of every person whose row the run writes (a member's address
--     cleared, the account's rows unlinked) onto their bookings of events not over: other organisations' such
--     bookings are photographed before the first write, put back before the checks, and proven unchanged;
--   * candidates (bookings without an account matched by address, name or phone) of another organisation are neither
--     touched nor offered: a tick on one is refused ([PersonAnonymiseCandidateError]; a preview drops it);
--   * volunteering applications of another organisation are kept, unlinked (person_id NULL);
--   * the person row, the account, its logins, sign-in factors, sessions, magic links, subscriptions and everything
--     not tied to a booking are erased as before: one organisation's DPO erases the account every organisation's
--     bookings hang on (the rule; the plan's question 4).
-- anonymise_booker(), with a list: the seed booking must be in a covered organisation ([DpoNotYourOrganizationError]);
-- only covered bookings enter the set; another organisation's booking matched as theirs (address and name), or whose
-- carer slot points at one of theirs, is kept, its carer-booking id cut; the address is never "theirs" while another
-- organisation's booking or application still carries it (booker/address then says 'shared').
--
-- GRID, new rows (both functions; 0 / '' when the run covers every organisation):
--   scope/organisations              the covered ids, ascending, comma-separated, or 'all'
--   organisations/touched            the organisations of the OTHER rows the run changes (other people's bookings
--                                    with their address, room lines naming them, cart-mates' history, bookings
--                                    they are only contact, payer or carer of), ascending: the back office asks
--                                    them too
--   kept/document_other_organisation how many of their bookings are kept
--   kept/organisations               the organisations of the kept bookings and applications, ascending
--   kept/document                    the kept bookings' ids — the runner reads it for its reply and NEVER stores it
--   kept/candidate_other_organisation, kept/volunteering_application_other_organisation    counts
--   kept/document_mentions_other_organisation (person only)    other people's bookings of other organisations that
--                                    carry their address or name them on a room line: left as they are
--   updated/document_detached, updated/volunteering_application_detached, and (person only) updated/history_detached,
--   document_line_detached, recipient_detached, conversation_detached, list_item_detached,
--   push_subscription_recipient_detached    rows whose link was cut
--   updated/document_kept_restored (person only)    other organisations' bookings put back after the person
--                                    trigger re-copied a member's details onto them (see below)
-- PROOFS (raise = the caller rolls back): the "personal data survives" checks exempt the kept rows; new ones fail
-- the run if any row of another organisation still points at an erased person (person) or booking (booker), or if
-- one of its bookings a person trigger can reach differs from its photograph (person).
-- ap_applications (person, both modes): every application the run deletes or keeps, with its organisation — read
-- by the back office inside the run's transaction, as ab_applications for a booker.
--
-- GENERATED IN PART. Both bodies were copied from V0164 (anonymise_person) and V0163 (anonymise_booker) by a one-off
-- script that asserted each substitution to match exactly once; every change is marked "V0175". Diff them to check.
--
-- SUCCESSION (V0153's rules). The old signatures are DROPPED here, so no overload is left (a 4-argument call resolves
-- to the new function through the default); EXECUTE is revoked from PUBLIC again; the comments are restated. A later
-- migration restating either function starts from THIS body.
-- V0171 (erase birthdates, not shipped) also restates anonymise_person(), from V0164, adding person.minor (in
-- ap_members, and cleared in the person rewrite) — a column production does not have, so V0175 is generated from
-- V0164 and does not depend on V0171 (David, 19 Nov). Whichever of the two ships second restates the function from
-- the other's body: V0175 ships first, so V0171 must be renumbered above V0176 and its anonymise_person()
-- regenerated on THIS body (with its two minor lines added back).
--
-- ANONYMISER / ERASURE MANIFEST. No table or column is added: nothing to classify. The person routine's reasons in
-- scripts/gdpr-erasure/manifest.tsv now say "in the DPO's organisations".
--
-- No BEGIN/COMMIT: the runner wraps every pending script in one transaction.

-- ===== anonymise_person() ======================================================================================

DROP FUNCTION IF EXISTS public.anonymise_person(integer, integer[], integer[], boolean);

CREATE OR REPLACE FUNCTION public.anonymise_person(p_person_id integer,
                                                   p_candidate_document_ids integer[] DEFAULT '{}'::integer[],
                                                   p_member_ids integer[] DEFAULT '{}'::integer[],
                                                   p_lenient boolean DEFAULT false,
                                                   p_organization_ids integer[] DEFAULT NULL)
RETURNS TABLE(step text, metric text, value text)
LANGUAGE plpgsql
SET timezone TO 'Europe/London'
AS $anon$
DECLARE
    v_tag_prefix constant text := '[personal data removed (GDPR)';
    v_tag         varchar(255);
    v_confirm     text := nullif(current_setting('kbs.erase_confirm', true), '');
    v_first       constant varchar(45) := 'Anonymised';
    v_last        varchar(45);
    v_email       varchar(127);
    v_ticked      integer[] := coalesce(p_candidate_document_ids, '{}'::integer[]);
    v_members     integer[] := coalesce(p_member_ids, '{}'::integer[]);
    v_names       text[];
    v_row_names   text[];
    v_unique_names text[];
    v_family_names text[];
    v_addresses   text[];
    v_removed_same bigint;
    v_shared_phones bigint;
    p             record;
    v_account_id  integer;
    v_text        text;
    v_mail_rows_before   bigint;
    v_mail_queued_before bigint;
    n             bigint;
    bad           text := '';
    -- V0175: the organisations this run covers, sorted, no NULL; NULL = every organisation (the function as it was)
    v_orgs        integer[] := CASE WHEN p_organization_ids IS NOT NULL
                                    THEN ARRAY(SELECT DISTINCT o FROM unnest(p_organization_ids) AS o WHERE o IS NOT NULL ORDER BY o) END;
    v_kept_candidates bigint := 0;
    n2            bigint;
    v_id_rx       text;
BEGIN
    v_tag := v_tag_prefix || ' on ' || current_date || ']';

    -- ================= INTERLOCKS =================
    IF p_person_id IS NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseNotFoundError] the person id is NULL';
    END IF;
    IF current_setting('transaction_isolation') NOT IN ('repeatable read', 'serializable') THEN
        RAISE EXCEPTION 'anonymise_person must run in a REPEATABLE READ transaction — start with: BEGIN ISOLATION LEVEL REPEATABLE READ;';
    END IF;
    IF transaction_timestamp() = statement_timestamp() THEN
        RAISE EXCEPTION 'anonymise_person refuses to run as a single autocommit statement — it would commit with no review. BEGIN ISOLATION LEVEL REPEATABLE READ; SET LOCAL kbs.erase_confirm = ...; SELECT * FROM anonymise_person(...); then COMMIT or ROLLBACK, each statement sent separately';
    END IF;
    IF v_confirm IS DISTINCT FROM ('ANONYMISE PERSON ' || p_person_id) THEN
        RAISE EXCEPTION 'anonymise_person: missing statement of intent — run  SET LOCAL kbs.erase_confirm = ''ANONYMISE PERSON %'';  in this transaction first (found: %)',
                        p_person_id, coalesce(v_confirm, '<unset>');
    END IF;

    -- ================= THE PERSON =================
    SELECT * INTO p FROM person WHERE id = p_person_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION '[PersonAnonymiseNotFoundError] person % does not exist', p_person_id;
    END IF;
    IF p.anonymised_date IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseAlreadyDoneError] person % was anonymised on %', p_person_id, p.anonymised_date::date;
    END IF;
    IF p.account_person_id IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseLinkedCopyError] person % is a linked copy of person %: anonymise person % instead',
                        p_person_id, p.account_person_id, p.account_person_id;
    END IF;

    -- Deferred triggers fired at COMMIT read this session temp table; a safety net, as in V0077.
    PERFORM set_transaction_parameters(true);

    v_last  := '#' || p_person_id;
    v_email := 'anonymised-' || p_person_id || '@invalid';
    -- Their own account, only when they own it: a member row sits on somebody else's account.
    v_account_id := CASE WHEN p.owner THEN p.frontend_account_id END;

    -- Every foreign key into person and frontend_account must be one this version handles (or knows to be an id
    -- that may stay). A key added later makes this refuse rather than leave data behind.
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_text
      FROM (SELECT format('%s:%s.%s', rc.relname, c.relname, a.attname) AS k
              FROM pg_constraint con
              JOIN pg_class c  ON c.oid = con.conrelid
              JOIN pg_class rc ON rc.oid = con.confrelid
              JOIN pg_namespace ns ON ns.oid = rc.relnamespace AND ns.nspname = 'public'
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
             WHERE con.contype = 'f' AND rc.relname IN ('person', 'frontend_account')) fk
     WHERE k <> ALL (ARRAY[
        -- handled: rewritten, revoked or deleted below
        'person:document.person_id', 'person:document.payer_id', 'person:document.person_carer1_id',
        'person:document.person_carer2_id', 'person:person.account_person_id', 'person:person.carer1_id',
        'person:person.carer2_id', 'person:auth_session.person_id', 'person:recipient.person_id',
        'person:chat_message.person_id', 'person:conversation.viewer_person_id', 'person:invitation.invitee_id',
        'person:invitation.created_alias_person_id', 'person:list_item.person_id', 'person:error.user_person_id',
        'person:push_subscription_recipient.person_id', 'person:subscription.person_id',
        'person:volunteering_application.person_id',
        -- refusals: a person holding any of these is staff
        'person:authorization_super_admin.super_admin_id', 'person:authorization_organization_admin.admin_id',
        'person:authorization_organization_user_access.user_id', 'person:authorization_management.manager_id',
        'person:authorization_management.user_id', 'person:role_attribution.person_id', 'person:teacher.person_id',
        -- ids only, no text about the person: kept as links to a row that no longer identifies anybody
        'person:history.user_person_id', 'person:money_transfer.verifier_person_id', 'person:invitation.inviter_id',
        'person:list_item.payer_id', 'person:list.created_by_id', 'person:pass_template.created_by',
        'person:conversation.assignee_id', 'person:conversation_participant.person_id',
        'person:chat_message_reaction.person_id', 'person:chat_presence.person_id', 'person:activity_state.owner_id',
        'person:driver.person_id', 'person:support_report.person_id', 'person:session_user.user_id',
        -- the account's own: deleted by cascade, or set null
        'frontend_account:person.frontend_account_id', 'frontend_account:mate_invite_token.creator_account_id',
        'frontend_account:webauthn_credential.frontend_account_id', 'frontend_account:totp_credential.frontend_account_id',
        'frontend_account:totp_backup_code.frontend_account_id', 'frontend_account:second_factor_reset.frontend_account_id',
        'frontend_account:account_sign_in_restriction.frontend_account_id',
        'frontend_account:account_settings.account_id']); -- V0164: UI flags, deleted with their account by cascade (V0158)
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseUnknownReferenceError] foreign keys this version does not handle: % — ship a new version of anonymise_person() first', v_text;
    END IF;

    -- ================= SCOPE =================
    DROP TABLE IF EXISTS ap_members, ap_persons, ap_core_addresses, ap_addresses, ap_names, ap_phones, ap_candidates, ap_docs,
                         ap_email_docs, ap_events, ap_share_lines, ap_hist_docs, ap_hist, ap_recipients, ap_sender_mails, ap_mails, ap_namesakes, ap_carer_docs,
                         ap_member_docs, ap_kept, ap_mentions, ap_owner_lines, ap_kept_recipients, ap_kept_applications,
                         ap_kept_conversations, ap_applications, ap_kept_snapshot;

    -- The human: the row, and its linked copies on other accounts.
    CREATE TEMP TABLE ap_persons (id integer PRIMARY KEY);
    INSERT INTO ap_persons VALUES (p_person_id);
    INSERT INTO ap_persons SELECT q.id FROM person q WHERE q.account_person_id = p_person_id ON CONFLICT DO NOTHING;

    -- The other members of their own account (see the header): linked copies of people with an account of their
    -- own, and members without one — anonymised with them when ticked, with their own linked copies.
    CREATE TEMP TABLE ap_members (id integer PRIMARY KEY, linked boolean, ticked boolean, minor boolean, bookings bigint,
                                 shares_address boolean NOT NULL DEFAULT false);
    INSERT INTO ap_members
    SELECT q.id, q.account_person_id IS NOT NULL, q.id = ANY (v_members),
           coalesce(q.birthdate > current_date - interval '18 years', false),
           (SELECT count(*) FROM document d WHERE d.person_id = q.id)
      FROM person q
     WHERE q.frontend_account_id = v_account_id AND q.id <> p_person_id AND NOT q.removed;
    SELECT string_agg(x::text, ', ') INTO v_text
      FROM unnest(v_members) AS x
     WHERE x IS NULL OR NOT EXISTS (SELECT 1 FROM ap_members m WHERE m.id = x AND NOT m.linked);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseMemberError] persons % are not members of the account of person % without an account of their own', v_text, p_person_id;
    END IF;
    INSERT INTO ap_persons SELECT m.id FROM ap_members m WHERE m.ticked ON CONFLICT DO NOTHING;
    INSERT INTO ap_persons SELECT q.id FROM person q JOIN ap_members m ON m.ticked AND q.account_person_id = m.id
    ON CONFLICT DO NOTHING;
    ANALYZE ap_persons; -- temp tables are never auto-analysed: without it the planner guesses, and can scan

    -- Addresses that are certainly theirs: the persons' emails and their own account's login.
    CREATE TEMP TABLE ap_core_addresses (address text PRIMARY KEY);
    INSERT INTO ap_core_addresses
    SELECT DISTINCT a FROM (
        SELECT lower(trim(q.email)) AS a FROM person q JOIN ap_persons s ON s.id = q.id
        UNION SELECT lower(trim(fa.username)) FROM frontend_account fa WHERE fa.id = v_account_id
    ) t WHERE position('@' IN coalesce(a, '')) > 1;

    -- Members typed with one of those addresses (the owner's, as their own): it goes from their row (below).
    UPDATE ap_members m SET shares_address = true
      FROM person q WHERE q.id = m.id AND NOT m.linked AND NOT m.ticked
       AND lower(trim(q.email)) IN (SELECT address FROM ap_core_addresses);

    -- Their names, as public.abc_names() spells them (lower case, no accents, words sorted). Two words at least:
    -- a single word matches too many people to be a match.
    -- from_row: the name of one of their person rows. Only those make a booking with their address theirs without a
    -- tick (below).
    CREATE TEMP TABLE ap_names (abc text PRIMARY KEY, from_row boolean NOT NULL);
    INSERT INTO ap_names
    SELECT DISTINCT nm, true FROM (
        SELECT public.abc_names(q.first_name || ' ' || q.last_name) AS nm FROM person q JOIN ap_persons s ON s.id = q.id
    ) t WHERE position(' ' IN trim(coalesce(nm, ''))) > 0;

    -- Removed rows of the same human: a soft-deleted duplicate (merging customers on the customers screen removes the
    -- row and keeps every field) with one of their addresses AND one of their names. On kbs_local in November 2026,
    -- 983 removed rows held a live customer's address, 292 of them under the same name. Anonymised with them — left
    -- alone, a 'removed' row would keep their name, phone and street, and its bookings with them.
    INSERT INTO ap_persons
    SELECT q.id FROM person q
     WHERE q.removed AND q.anonymised_date IS NULL
       AND lower(trim(q.email)) IN (SELECT address FROM ap_core_addresses)
       AND q.abc_names IN (SELECT abc FROM ap_names WHERE from_row)
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS v_removed_same = ROW_COUNT;
    ANALYZE ap_persons;

    -- A name on one of their own bookings (an old name, a typo) counts too — unless somebody else bears it: an own
    -- booking often carries a family member's name (booked under the owner's person row), and that member, unticked,
    -- must not lose their bookings as if they were the requester's.
    INSERT INTO ap_names
    SELECT DISTINCT d.person_abc_names, false FROM document d JOIN ap_persons s ON s.id = d.person_id
     WHERE position(' ' IN trim(coalesce(d.person_abc_names, ''))) > 0
       AND NOT EXISTS (SELECT 1 FROM person x WHERE x.abc_names = d.person_abc_names AND NOT x.removed
                         AND NOT EXISTS (SELECT 1 FROM ap_persons s2 WHERE s2.id = x.id))
    ON CONFLICT DO NOTHING;
    SELECT array_agg(abc), array_agg(abc) FILTER (WHERE from_row) INTO v_names, v_row_names FROM ap_names;
    v_names := coalesce(v_names, '{}'::text[]);
    v_row_names := coalesce(v_row_names, '{}'::text[]);
    -- The names of their rows nobody else bears (no live person row outside theirs — an unticked member counts as
    -- somebody else): only those are cleared on bookings and rows that nothing else ties to them. A name known only
    -- from a booking is never certain enough for that (a child booked under the parent's row has no row of their
    -- own to show), so carers, room lines and history match on the row names (v_row_names) alone.
    SELECT array_agg(a.abc) INTO v_unique_names FROM ap_names a
     WHERE a.from_row AND NOT EXISTS (SELECT 1 FROM person x WHERE x.abc_names = a.abc AND NOT x.removed
                         AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = x.id));
    v_unique_names := coalesce(v_unique_names, '{}'::text[]);

    -- Addresses that are probably theirs (their bookings' snapshot, accounts they owned before a move), KEPT ONLY
    -- WHEN NOTHING SAYS OTHERWISE: an inferred address is dropped as soon as it also belongs to somebody else — a
    -- person row (removed or not) or another account holds it, a volunteering application of another person uses
    -- it, or a booking carries it under a name that is not one of theirs (a member with no email books under the
    -- account owner's address; staff may have typed a relative's or the reception's). Only these and the core
    -- addresses drive the steps that need no tick.
    CREATE TEMP TABLE ap_addresses (address text PRIMARY KEY);
    INSERT INTO ap_addresses SELECT address FROM ap_core_addresses;
    INSERT INTO ap_addresses
    SELECT DISTINCT a FROM (
        SELECT lower(trim(d.person_email)) AS a FROM document d JOIN ap_persons s ON s.id = d.person_id
        UNION SELECT lower(trim(m.from_account_username)) FROM person_account_move m JOIN ap_persons s ON s.id = m.person_id
         WHERE m.was_owner
    ) t
     WHERE position('@' IN coalesce(a, '')) > 1
       AND NOT EXISTS (SELECT 1 FROM person x WHERE lower(trim(x.email)) = t.a
                          AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = x.id)
                          -- a removed duplicate under one of their names is them (joined below), not somebody else
                          AND NOT (x.removed AND x.anonymised_date IS NULL AND x.abc_names = ANY (v_row_names)))
       AND NOT EXISTS (SELECT 1 FROM frontend_account fa WHERE lower(trim(fa.username)) = t.a
                          AND fa.id IS DISTINCT FROM v_account_id)
       AND NOT EXISTS (SELECT 1 FROM volunteering_application va WHERE lower(trim(va.email)) = t.a
                          AND va.person_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = va.person_id))
       AND NOT EXISTS (SELECT 1 FROM document d WHERE lower(trim(d.person_email)) = t.a
                          AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = d.person_id)
                          -- a booking without a name is not known to be theirs: it contradicts too
                          AND NOT coalesce(d.person_abc_names = ANY (v_names), false))
    ON CONFLICT DO NOTHING;
    ANALYZE ap_addresses;

    -- A removed duplicate under one of those older addresses and one of their names: the same human too.
    INSERT INTO ap_persons
    SELECT q.id FROM person q
     WHERE q.removed AND q.anonymised_date IS NULL
       AND lower(trim(q.email)) IN (SELECT address FROM ap_addresses)
       AND q.abc_names = ANY (v_row_names)
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS n = ROW_COUNT;
    v_removed_same := v_removed_same + n;
    ANALYZE ap_persons;

    SELECT array_agg(address) INTO v_addresses FROM ap_addresses;
    v_addresses := coalesce(v_addresses, '{}'::text[]);

    -- Their phone numbers, as the last 9 digits (so +44 7700… and 07700… meet).
    CREATE TEMP TABLE ap_phones (phone text PRIMARY KEY);
    INSERT INTO ap_phones
    SELECT DISTINCT right(dg, 9) FROM (
        SELECT regexp_replace(q.phone, '\D', '', 'g') AS dg FROM person q JOIN ap_persons s ON s.id = q.id
        UNION SELECT regexp_replace(d.person_phone, '\D', '', 'g') FROM document d JOIN ap_persons s ON s.id = d.person_id
    ) t WHERE length(coalesce(dg, '')) >= 9;

    -- A phone on more than 5 bookings made without an account is a shared number — a centre's, a family's, an
    -- organiser's — not evidence that a booking is theirs. On production (November 2026) 2,019 phones were, between
    -- them carrying 22,755 of 88,587 such bookings: matched on, they would bury the real candidates. Set aside.
    ALTER TABLE ap_phones ADD COLUMN guest_bookings bigint;
    UPDATE ap_phones ph SET guest_bookings = c.n
      FROM (SELECT right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) AS phone, count(*) AS n
              FROM document d
             WHERE d.person_id IS NULL AND length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
               AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ap_phones)
             GROUP BY 1) c
     WHERE c.phone = ph.phone;
    DELETE FROM ap_phones WHERE guest_bookings > 5;
    GET DIAGNOSTICS v_shared_phones = ROW_COUNT;

    -- ================= REFUSALS (before any write) =================
    SELECT string_agg(DISTINCT what, ', ') INTO v_text FROM (
        SELECT 'super administrator' AS what FROM authorization_super_admin x JOIN ap_persons s ON s.id = x.super_admin_id
        UNION ALL SELECT 'organization administrator' FROM authorization_organization_admin x JOIN ap_persons s ON s.id = x.admin_id
        UNION ALL SELECT 'organization access' FROM authorization_organization_user_access x JOIN ap_persons s ON s.id = x.user_id
        UNION ALL SELECT 'management' FROM authorization_management x JOIN ap_persons s ON s.id IN (x.manager_id, x.user_id)
        UNION ALL SELECT 'role' FROM role_attribution x JOIN ap_persons s ON s.id = x.person_id
        UNION ALL SELECT 'teacher' FROM teacher x JOIN ap_persons s ON s.id = x.person_id
        UNION ALL SELECT 'back-office account' FROM frontend_account fa WHERE fa.id = v_account_id AND fa.backoffice
    ) t;
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseStaffError] person % holds staff rights or roles (%): remove them first', p_person_id, v_text;
    END IF;

    SELECT string_agg(DISTINCT who, ', ') INTO v_text FROM (
        SELECT 'person ' || x.id AS who FROM person x JOIN ap_core_addresses a ON a.address = lower(trim(x.email))
         WHERE NOT x.removed AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = x.id)
           -- A member of their own account typed with their address is not a duplicate: it loses the address below
           AND NOT EXISTS (SELECT 1 FROM ap_members m WHERE m.id = x.id AND NOT m.linked)
        UNION ALL
        SELECT 'account ' || fa.id FROM frontend_account fa JOIN ap_core_addresses a ON a.address = lower(trim(fa.username))
         WHERE fa.id IS DISTINCT FROM v_account_id
        UNION ALL
        SELECT 'volunteering application ' || va.id FROM volunteering_application va
          JOIN ap_core_addresses a ON a.address = lower(trim(va.email))
         WHERE va.person_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = va.person_id)
    ) t;
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseDuplicatesError] the email address of person % is also used by % : merge or correct them first', p_person_id, v_text;
    END IF;

    -- Bookings made without an account that match them.
    CREATE TEMP TABLE ap_candidates AS
    SELECT d.id, d.ref, d.event_id,
           (lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses))             AS by_email,
           coalesce(d.person_abc_names = ANY (v_names), false)                            AS by_name,
           coalesce(d.person_abc_names = ANY (v_row_names), false)                        AS by_row_name,
           (length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
            AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ap_phones)) AS by_phone
      FROM document d
     WHERE d.person_id IS NULL
       AND (   lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses)
            OR d.person_abc_names = ANY (v_names)
            OR (length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
                AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ap_phones)));
    ALTER TABLE ap_candidates ADD COLUMN action text, ADD COLUMN carer_match boolean NOT NULL DEFAULT false;
    -- email + the name of one of their rows = the same human; email + a name only from their bookings' snapshots =
    -- perhaps them (ticked, in full; unticked, the address still goes — see ap_email_docs); their email alone = their
    -- contact details on somebody else's booking (the address is certainly theirs, so that needs no tick); name
    -- (with or without phone) = perhaps them, under another address; phone alone = perhaps a shared phone.
    UPDATE ap_candidates SET action = CASE WHEN by_email AND by_row_name THEN 'auto'
                                           WHEN by_email AND by_name THEN 'full'
                                           WHEN by_email THEN 'auto-contact'
                                           WHEN by_name THEN 'full'
                                           ELSE 'contact' END;
    ALTER TABLE ap_candidates ADD PRIMARY KEY (id);

    -- Carer names are typed: on the data this was rehearsed on, not one of 5,374 links the carer's person row. So a
    -- booking naming them as carer is found by the name — narrowed first to bookings that carry one, since
    -- public.abc_names() is plpgsql (see the room-sharing step). A name match is THEIRS without a tick only when
    -- something else ties the booking to them (it is theirs, a member's, one they paid for, or carries their address)
    -- or nobody else bears that name; otherwise somebody else's carer slot would be wiped on a namesake's account —
    -- a carer is who answers for a child. Those are candidates (action 'carer': ticked, that slot alone is cleared).
    CREATE TEMP TABLE ap_carer_docs AS
    SELECT c.id, c.n1 = ANY (v_row_names) OR c.n2 = ANY (v_row_names) AS by_name,
           c.n1 = ANY (v_unique_names) OR c.n2 = ANY (v_unique_names) AS unique_name
      FROM (SELECT x.id, public.abc_names(x.n1) AS n1, public.abc_names(x.n2) AS n2
              FROM (SELECT d.id, d.person_carer1_name AS n1, d.person_carer2_name AS n2 FROM document d
                     WHERE d.person_carer1_name IS NOT NULL OR d.person_carer2_name IS NOT NULL
                    OFFSET 0) x
            OFFSET 0) c
     WHERE c.n1 = ANY (v_row_names) OR c.n2 = ANY (v_row_names);
    ALTER TABLE ap_carer_docs ADD COLUMN tied boolean;
    UPDATE ap_carer_docs cd SET tied = cd.unique_name
        OR EXISTS (SELECT 1 FROM document d WHERE d.id = cd.id
                     AND (   d.person_id IN (SELECT s.id FROM ap_persons s) OR d.person_id IN (SELECT m.id FROM ap_members m)
                          OR d.payer_id IN (SELECT s.id FROM ap_persons s)
                          OR d.person_carer1_id IN (SELECT s.id FROM ap_persons s)
                          OR d.person_carer2_id IN (SELECT s.id FROM ap_persons s)
                          OR lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses)))
        OR EXISTS (SELECT 1 FROM ap_candidates c WHERE c.id = cd.id AND c.action IN ('auto', 'auto-contact'));
    -- A booking that is already a candidate on its own (name, phone) carries the carer match along; the others
    -- become 'carer' candidates.
    UPDATE ap_candidates c SET carer_match = true FROM ap_carer_docs cd WHERE cd.id = c.id AND NOT cd.tied;
    INSERT INTO ap_candidates (id, ref, event_id, by_email, by_name, by_row_name, by_phone, action, carer_match)
    SELECT d.id, d.ref, d.event_id, false, true, false, false, 'carer', true
      FROM ap_carer_docs cd JOIN document d ON d.id = cd.id
     WHERE NOT cd.tied
    ON CONFLICT (id) DO NOTHING;

    -- V0175: a candidate booking of an organisation this run does not cover is never touched and cannot be ticked
    -- (its tick is refused like any other non-candidate's); it is only counted, as kept/candidate_other_organisation.
    ALTER TABLE ap_candidates ADD COLUMN covered boolean NOT NULL DEFAULT true;
    IF v_orgs IS NOT NULL THEN
        UPDATE ap_candidates c SET covered = false WHERE NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = c.id) = ANY (v_orgs), false);
    END IF;

    -- A preview (lenient) drops a tick that is no longer a candidate — the operator's ticks follow what the last
    -- preview offered, and unticking a member can take candidates away; a commit refuses it.
    IF p_lenient THEN
        v_ticked := ARRAY(SELECT DISTINCT x FROM unnest(v_ticked) AS x
                           WHERE EXISTS (SELECT 1 FROM ap_candidates c WHERE c.id = x AND c.action IN ('full', 'contact', 'carer') AND c.covered)
                           ORDER BY x);
    END IF;
    SELECT string_agg(x::text, ', ') INTO v_text
      FROM unnest(v_ticked) AS x
     WHERE x IS NULL OR NOT EXISTS (SELECT 1 FROM ap_candidates c WHERE c.id = x AND c.covered);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseCandidateError] bookings % are not candidates of person %', v_text, p_person_id;
    END IF;

    -- The bookings, each in one scope (the first that applies: what happens to the booking's person_* snapshot),
    -- and two flags that hold whatever the scope: paid = they paid for it (it counts for the refusals and its
    -- payments' comments are tagged), cared = it names them as carer (that slot is cleared). A child's booking made
    -- under the parent's address, paid by the parent who is also its carer, is 'contact', paid and cared at once.
    CREATE TEMP TABLE ap_docs (id integer PRIMARY KEY, scope text NOT NULL,
                               paid boolean NOT NULL DEFAULT false, cared boolean NOT NULL DEFAULT false);
    INSERT INTO ap_docs SELECT d.id, 'full' FROM document d JOIN ap_persons s ON s.id = d.person_id;
    INSERT INTO ap_docs SELECT c.id, 'full' FROM ap_candidates c
     WHERE c.action = 'auto' OR (c.action = 'full' AND c.id = ANY (v_ticked)) ON CONFLICT DO NOTHING;
    INSERT INTO ap_docs SELECT c.id, 'contact' FROM ap_candidates c
     WHERE c.action = 'auto-contact' OR (c.action = 'contact' AND c.id = ANY (v_ticked)) ON CONFLICT DO NOTHING;
    INSERT INTO ap_docs (id, scope, paid) SELECT DISTINCT d.id, 'payer', true FROM document d JOIN ap_persons s ON s.id = d.payer_id
    ON CONFLICT (id) DO UPDATE SET paid = true;
    INSERT INTO ap_docs (id, scope, cared)
    SELECT DISTINCT d.id, 'carer', true FROM document d JOIN ap_persons s ON s.id IN (d.person_carer1_id, d.person_carer2_id)
    ON CONFLICT (id) DO UPDATE SET cared = true;
    -- ... and by the typed name (ap_carer_docs): tied to them, or ticked.
    INSERT INTO ap_docs (id, scope, cared)
    SELECT cd.id, 'carer', true FROM ap_carer_docs cd
     WHERE cd.tied OR cd.id = ANY (v_ticked)
    ON CONFLICT (id) DO UPDATE SET cared = true;
    ANALYZE ap_docs;

    -- ================= V0175: OTHER ORGANISATIONS' BOOKINGS =================
    -- A booking of an organisation this run does not cover (the event's organisation, else the booking's own; none =
    -- not covered) leaves ap_docs, which every step below reads: it is KEPT as it is, never blocks the run, and only
    -- its id links to the persons erased here are cut (THE ANONYMISATION, first step). ap_mentions: other people's
    -- bookings of those organisations that carry one of their addresses or name them on a room line, kept too.
    CREATE TEMP TABLE ap_kept (id integer PRIMARY KEY, scope text NOT NULL, paid boolean NOT NULL, cared boolean NOT NULL,
                               organization_id integer);
    CREATE TEMP TABLE ap_mentions (id integer PRIMARY KEY);
    IF v_orgs IS NOT NULL THEN
        INSERT INTO ap_kept
        SELECT t.id, t.scope, t.paid, t.cared, (SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = t.id) FROM ap_docs t WHERE NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = t.id) = ANY (v_orgs), false);
        DELETE FROM ap_docs t WHERE t.id IN (SELECT k.id FROM ap_kept k);
        SELECT count(*) INTO v_kept_candidates FROM ap_candidates c
         WHERE NOT c.covered AND c.action IN ('full', 'contact', 'carer') AND NOT EXISTS (SELECT 1 FROM ap_kept k WHERE k.id = c.id);
        DELETE FROM ap_candidates c WHERE NOT c.covered;
        ANALYZE ap_docs;
        ANALYZE ap_kept;
    END IF;

    -- Not over, then money owed (anonymise_person_blocking(): the preview's warnings read the same rules).
    SELECT string_agg(DISTINCT coalesce(b.ref::text, 'id ' || b.document_id), ', ') INTO v_text
      FROM public.anonymise_person_blocking(ARRAY(SELECT t.id FROM ap_docs t WHERE t.scope = 'full' OR t.paid)) b
     WHERE b.kind = 'active';
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseActiveBookingsError] person % has bookings that are not over (refs %): cancel them, or wait until they end', p_person_id, v_text;
    END IF;

    SELECT string_agg(DISTINCT coalesce(b.ref::text, 'id ' || b.document_id), ', ') INTO v_text
      FROM public.anonymise_person_blocking(ARRAY(SELECT t.id FROM ap_docs t WHERE t.scope = 'full' OR t.paid)) b
     WHERE b.kind = 'balance';
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseBalanceError] person % has bookings with money still owed either way (refs %): settle them first', p_person_id, v_text;
    END IF;

    SELECT count(*) INTO n FROM enqueued_request er JOIN ap_docs t ON t.id = er.document_id AND t.scope IN ('full', 'contact')
     WHERE er.execution_date IS NULL;
    IF n > 0 THEN
        RAISE EXCEPTION '[PersonAnonymiseQueuedPayloadsError] % un-executed KBS2 requests still hold bookings of person %', n, p_person_id;
    END IF;

    -- The mails to or about them: of a 'full'/'contact' booking, to one of their addresses or rows, or from one.
    -- Their recipient rows and the mails they sent are found ONCE (recipient and mail are the biggest tables after
    -- history: 1.1M recipients on production) and reused by the rewrite and the checks; REPEATABLE READ means a
    -- second scan could only find these same rows. The person_id arm alone can use its index.
    CREATE TEMP TABLE ap_recipients (id integer PRIMARY KEY, mail_id integer);
    INSERT INTO ap_recipients
    SELECT r.id, r.mail_id FROM recipient r WHERE r.person_id IN (SELECT id FROM ap_persons)
    UNION SELECT r.id, r.mail_id FROM recipient r WHERE lower(trim(r.email)) IN (SELECT address FROM ap_addresses);
    ANALYZE ap_recipients;
    CREATE TEMP TABLE ap_sender_mails (id integer PRIMARY KEY);
    INSERT INTO ap_sender_mails SELECT m.id FROM mail m WHERE lower(trim(m.from_email)) IN (SELECT address FROM ap_addresses);
    -- V0175: a mail of another organisation's booking is kept whole (subject, content, sender, recipients); a
    -- recipient row of it naming them by id loses that link only (THE ANONYMISATION, first step). A mail tied to no
    -- booking follows the person, as before.
    CREATE TEMP TABLE ap_kept_recipients (id integer PRIMARY KEY);
    IF v_orgs IS NOT NULL THEN
        INSERT INTO ap_kept_recipients
        SELECT r.id FROM ap_recipients r JOIN mail m ON m.id = r.mail_id
         WHERE m.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = m.document_id) = ANY (v_orgs), false);
        DELETE FROM ap_recipients r WHERE r.id IN (SELECT k.id FROM ap_kept_recipients k);
        DELETE FROM ap_sender_mails sm USING mail m
         WHERE m.id = sm.id AND m.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = m.document_id) = ANY (v_orgs), false);
        ANALYZE ap_recipients;
    END IF;
    CREATE TEMP TABLE ap_mails (id integer PRIMARY KEY);
    INSERT INTO ap_mails
    SELECT m.id FROM mail m JOIN ap_docs t ON t.id = m.document_id AND t.scope IN ('full', 'contact')
    UNION SELECT r.mail_id FROM ap_recipients r WHERE r.mail_id IS NOT NULL
    UNION SELECT sm.id FROM ap_sender_mails sm;
    ANALYZE ap_mails;

    -- Still waiting to be sent: rewriting it would send the tag. Older unsent rows are past every mailer's drain
    -- window (7 days) and are rewritten with the rest.
    SELECT count(*) INTO n FROM mail m JOIN ap_mails a ON a.id = m.id
     WHERE NOT m.transmitted AND m.date > now() - interval '30 days';
    IF n > 0 THEN
        RAISE EXCEPTION '[PersonAnonymiseQueuedMailError] % mails to or about person % are still waiting to be sent: try again once they have gone', n, p_person_id;
    END IF;

    -- Events of their bookings, and other people's lines naming them as room owner or mate in those events.
    CREATE TEMP TABLE ap_events AS
    SELECT DISTINCT d.event_id FROM document d JOIN ap_docs t ON t.id = d.id AND t.scope = 'full' WHERE d.event_id IS NOT NULL;
    ANALYZE ap_events;

    -- Two passes, each narrowed BEFORE any name is normalised: public.abc_names() is plpgsql, and called on the
    -- eight name columns of every booking line it took minutes (1.4M lines). First the lines naming them as room
    -- owner by id; then, among the lines of their events' bookings that carry a room name at all, those whose name
    -- is theirs — each present name normalised once.
    CREATE TEMP TABLE ap_share_lines (id integer PRIMARY KEY, document_id integer NOT NULL);
    -- V0175: the lines naming them as room owner by id are found once, each with whether its booking is in an
    -- organisation this run covers: a line of another organisation's booking is kept, only its id link is cut.
    CREATE TEMP TABLE ap_owner_lines (id integer PRIMARY KEY, document_id integer NOT NULL, covered boolean NOT NULL);
    INSERT INTO ap_owner_lines
    SELECT dl.id, dl.document_id, v_orgs IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = dl.document_id) = ANY (v_orgs), false)
      FROM document_line dl WHERE dl.share_mate_owner_person_id IN (SELECT id FROM ap_persons);
    INSERT INTO ap_share_lines
    SELECT o.id, o.document_id FROM ap_owner_lines o
     WHERE o.covered AND NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = o.document_id AND t.scope = 'full');
    INSERT INTO ap_mentions
    SELECT DISTINCT o.document_id FROM ap_owner_lines o
     WHERE NOT o.covered AND NOT EXISTS (SELECT 1 FROM ap_kept k WHERE k.id = o.document_id);
    INSERT INTO ap_share_lines
    SELECT DISTINCT c.id, c.document_id
      FROM (SELECT dl.id, dl.document_id,
                   ARRAY[dl.share_mate_owner_name, dl.share_owner_mate1_name, dl.share_owner_mate2_name,
                         dl.share_owner_mate3_name, dl.share_owner_mate4_name, dl.share_owner_mate5_name,
                         dl.share_owner_mate6_name, dl.share_owner_mate7_name] AS names
              FROM ap_events e
              JOIN document d ON d.event_id = e.event_id
              JOIN document_line dl ON dl.document_id = d.id
             WHERE NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = d.id AND t.scope = 'full')
               AND (dl.share_mate_owner_name IS NOT NULL OR dl.share_owner_mate1_name IS NOT NULL
                    OR dl.share_owner_mate2_name IS NOT NULL OR dl.share_owner_mate3_name IS NOT NULL
                    OR dl.share_owner_mate4_name IS NOT NULL OR dl.share_owner_mate5_name IS NOT NULL
                    OR dl.share_owner_mate6_name IS NOT NULL OR dl.share_owner_mate7_name IS NOT NULL)
            OFFSET 0) c -- OFFSET 0: a fence, so the planner cannot push the name test below into this scan
      CROSS JOIN LATERAL unnest(c.names) AS n(name)
     WHERE n.name IS NOT NULL AND public.abc_names(n.name) = ANY (v_row_names)
       AND (v_orgs IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = c.document_id) = ANY (v_orgs), false)) -- V0175 (an event has one organisation: a safety net)
    ON CONFLICT DO NOTHING;

    -- Somebody else's bookings (a family member's, one they paid for or cared on) carrying one of their addresses:
    -- that address goes, nothing else.
    CREATE TEMP TABLE ap_email_docs AS
    SELECT d.id FROM document d
     WHERE lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses)
       AND NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = d.id AND t.scope IN ('full', 'contact'));
    ALTER TABLE ap_email_docs ADD PRIMARY KEY (id);
    -- V0175: another organisation's booking carrying their address is that organisation's record: kept as it is.
    IF v_orgs IS NOT NULL THEN
        WITH gone AS (DELETE FROM ap_email_docs ed WHERE NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = ed.id) = ANY (v_orgs), false) RETURNING ed.id)
        INSERT INTO ap_mentions SELECT g.id FROM gone g WHERE NOT EXISTS (SELECT 1 FROM ap_kept k WHERE k.id = g.id)
        ON CONFLICT DO NOTHING;
    END IF;
    ANALYZE ap_email_docs;
    ANALYZE ap_share_lines;

    -- The history rows to look at — every booking in scope, those carrying their address, and the other bookings
    -- whose lines name them — with each one's changes redacted ONCE, here.
    -- One table of booking ids first, then history by its document_id index: an OR of three INs on history
    -- (2.8M rows on production) reads all of it.
    -- cared: the booking's carer slot naming them goes, so its history's carer names go too (and only then).
    CREATE TEMP TABLE ap_hist_docs (id integer PRIMARY KEY, scope text NOT NULL, cared boolean NOT NULL);
    INSERT INTO ap_hist_docs SELECT t.id, t.scope, t.cared FROM ap_docs t;
    INSERT INTO ap_hist_docs SELECT ed.id, 'other', false FROM ap_email_docs ed ON CONFLICT DO NOTHING;
    INSERT INTO ap_hist_docs SELECT DISTINCT sl.document_id, 'other', false FROM ap_share_lines sl ON CONFLICT DO NOTHING;
    -- The other bookings of their bookings' carts: a guest signed in by a cart or magic link acts on the whole cart,
    -- and history.username records them as 'guest:<their address>' (HistoryRecorder, since 2026-10-19).
    INSERT INTO ap_hist_docs
    SELECT DISTINCT d2.id, 'other', false
      FROM ap_docs t JOIN document d ON d.id = t.id AND d.cart_id IS NOT NULL
      JOIN document d2 ON d2.cart_id = d.cart_id
     WHERE t.scope IN ('full', 'contact')
       AND (v_orgs IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = d2.id) = ANY (v_orgs), false)) -- V0175: another organisation's booking in the cart is kept
    ON CONFLICT DO NOTHING;
    ANALYZE ap_hist_docs;
    CREATE TEMP TABLE ap_hist AS
    SELECT h.id, t.scope,
           public.anonymise_person_changes(h.changes, t.scope, v_row_names, v_addresses, v_first,
                                           '#' || coalesce(hd.person_id, p_person_id), v_tag, t.cared) AS new_changes,
           public.anonymise_person_comment(h.comment, v_row_names, t.cared) AS new_comment,
           -- who acted: a guest is recorded as 'guest:' + their address (cut to 64), an SSO login as the address
           CASE WHEN h.username LIKE 'guest:%'
                     AND lower(substr(h.username, 7)) IN (SELECT left(a.address, 58) FROM ap_addresses a)
                THEN 'guest:' || v_email
                WHEN lower(trim(h.username)) IN (SELECT a.address FROM ap_addresses a)
                THEN v_email::text
                ELSE h.username END AS new_username
      FROM ap_hist_docs t
      JOIN history h ON h.document_id = t.id
      LEFT JOIN document hd ON hd.id = t.id;
    ALTER TABLE ap_hist ADD PRIMARY KEY (id);
    ANALYZE ap_hist;

    -- Other customers bearing one of their names: person rows not removed, not theirs, not a member of their
    -- account, not a linked copy (the person it copies is listed instead). NOT anonymised: nothing but the name
    -- links them, and names collide — on kbs_local in November 2026 a third of 58,000 customers shared theirs with
    -- somebody, up to 20 at once. Listed so staff can spot the same human under another email (a customer anonymised
    -- that day had a second family account, all four names alike, 110 bookings) and merge it first or anonymise it
    -- on its own. family_names = the other people on that customer's account who bear a name from this account
    -- (theirs or a member's): a family registered twice scores high, and is listed first.
    SELECT array_agg(DISTINCT q.abc_names) INTO v_family_names FROM person q
     WHERE (q.id IN (SELECT s.id FROM ap_persons s) OR q.id IN (SELECT m.id FROM ap_members m))
       AND position(' ' IN trim(coalesce(q.abc_names, ''))) > 0;
    v_family_names := coalesce(v_family_names, '{}'::text[]);
    CREATE TEMP TABLE ap_namesakes AS
    SELECT x.id, x.frontend_account_id AS account_id, x.owner,
           (SELECT count(*) FROM document d WHERE d.person_id = x.id) AS bookings,
           (SELECT count(*) FROM person y
             WHERE y.frontend_account_id = x.frontend_account_id AND y.id <> x.id AND NOT y.removed
               AND y.abc_names = ANY (v_family_names)) AS family_names
      FROM person x
     WHERE x.abc_names = ANY (v_names) AND NOT x.removed AND x.account_person_id IS NULL
       AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = x.id)
       AND NOT EXISTS (SELECT 1 FROM ap_members m WHERE m.id = x.id);

    -- What ticking each member (without an account of their own) would bring into the refusals: their bookings and
    -- their copies', those they paid for, and the bookings made without an account under their name with their own
    -- address or one of the requester's (ticked, those become theirs in full), and their removed duplicates'. A hint
    -- shown before the tick, not the rule: the preview re-run with the tick has the last word.
    CREATE TEMP TABLE ap_member_docs AS
    WITH mp AS (SELECT m.id AS member_id, q.id AS person_id, q.abc_names, lower(trim(q.email)) AS email
                  FROM ap_members m JOIN person mm ON mm.id = m.id
                  JOIN person q ON q.id = m.id OR q.account_person_id = m.id
                                -- their removed duplicates, which ticking them brings in too
                                OR (q.removed AND q.anonymised_date IS NULL AND q.abc_names = mm.abc_names
                                    AND lower(trim(q.email)) = lower(trim(mm.email)))
                 WHERE NOT m.linked)
    SELECT mp.member_id, d.id AS doc_id FROM mp JOIN document d ON d.person_id = mp.person_id
    UNION
    SELECT mp.member_id, d.id FROM mp JOIN document d ON d.payer_id = mp.person_id
    UNION
    SELECT mp.member_id, d.id FROM mp JOIN document d
        ON d.person_id IS NULL AND d.person_abc_names = mp.abc_names
       AND (lower(trim(d.person_email)) = mp.email OR lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses));
    -- V0175: ticked, a member's bookings of another organisation would be kept: they would not stop the run
    IF v_orgs IS NOT NULL THEN
        DELETE FROM ap_member_docs md WHERE NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = md.doc_id) = ANY (v_orgs), false);
    END IF;

    -- V0175: volunteering applications of an organisation this run does not cover (an application's organisation is
    -- its own column; none = not covered) are kept, unlinked from them; so are conversations about another
    -- organisation's booking, with every message in them (THE ANONYMISATION, first step).
    -- ap_applications: every application the run deletes or keeps (theirs, their removed duplicates', or made without
    -- an account under one of their addresses), in both modes, with its organisation. The back office reads it inside
    -- the run's transaction (the organisations involved), as it reads ab_applications for a booker.
    CREATE TEMP TABLE ap_applications (id integer PRIMARY KEY, organization_id integer);
    INSERT INTO ap_applications
    SELECT va.id, va.organization_id FROM volunteering_application va
     WHERE va.person_id IN (SELECT id FROM ap_persons)
        OR (va.person_id IS NULL AND lower(trim(va.email)) IN (SELECT address FROM ap_addresses));
    CREATE TEMP TABLE ap_kept_applications (id integer PRIMARY KEY, organization_id integer);
    CREATE TEMP TABLE ap_kept_conversations (id integer PRIMARY KEY);
    IF v_orgs IS NOT NULL THEN
        INSERT INTO ap_kept_applications
        SELECT a.id, a.organization_id FROM ap_applications a
         WHERE NOT coalesce(a.organization_id = ANY (v_orgs), false);
        INSERT INTO ap_kept_conversations
        SELECT cv.id FROM conversation cv
         WHERE cv.document_id IS NOT NULL
           AND (   cv.viewer_person_id IN (SELECT id FROM ap_persons)
                OR cv.document_id IN (SELECT k.id FROM ap_kept k)
                OR EXISTS (SELECT 1 FROM chat_message cm WHERE cm.conversation_id = cv.id AND cm.person_id IN (SELECT id FROM ap_persons))
                OR EXISTS (SELECT 1 FROM conversation_participant cp WHERE cp.conversation_id = cv.id
                             AND cp.person_id IN (SELECT id FROM ap_persons)))
           AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = cv.document_id) = ANY (v_orgs), false);
    END IF;

    -- ================= THE GRID: where, who, what is in scope =================
    SELECT count(*) INTO v_mail_rows_before FROM mail;
    SELECT count(*) INTO v_mail_queued_before FROM mail WHERE NOT transmitted;

    RETURN QUERY VALUES
        ('where',  'database',  current_database()::text),
        ('where',  'run_at',    now()::text),
        ('where',  'tag',       v_tag::text),
        ('person', 'id',        p_person_id::text),
        ('person', 'linked_copies', (SELECT count(*) FROM person q WHERE q.account_person_id = p_person_id)::text),
        ('person', 'own_account',   (v_account_id IS NOT NULL)::text),
        ('person', 'addresses',     (SELECT count(*) FROM ap_addresses)::text),
        -- V0175: the organisations the run was told to cover ('all': every organisation)
        ('scope',  'organisations', coalesce(array_to_string(v_orgs, ','), 'all')),
        -- V0175: the organisations of the OTHER rows the run changes, outside the subject's own bookings: other people's
        -- bookings carrying their address, room lines naming them, the other bookings of their carts (history), and
        -- the bookings they are only the contact, payer or carer of. The back office asks these organisations too.
        ('organisations', 'touched', (SELECT coalesce(string_agg(x.o::text, ',' ORDER BY x.o), '')
                      FROM (SELECT DISTINCT (SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = y.id) AS o
                              FROM (SELECT ed.id FROM ap_email_docs ed
                                    UNION SELECT sl.document_id FROM ap_share_lines sl
                                    UNION SELECT hd.id FROM ap_hist_docs hd WHERE hd.scope = 'other'
                                    UNION SELECT t.id FROM ap_docs t WHERE t.scope IN ('contact', 'payer', 'carer')) y) x
                     WHERE x.o IS NOT NULL)),
        ('scope',  'own_bookings',  (SELECT count(*) FROM document d JOIN ap_persons s ON s.id = d.person_id)::text),
        ('scope',  'guest_bookings_matched', (SELECT count(*) FROM ap_candidates WHERE action = 'auto')::text),
        ('scope',  'bookings_with_their_address',
                   ((SELECT count(*) FROM ap_candidates WHERE action = 'auto-contact') + (SELECT count(*) FROM ap_email_docs))::text),
        ('scope',  'candidates',             (SELECT count(*) FROM ap_candidates WHERE action IN ('full', 'contact', 'carer'))::text),
        ('scope',  'removed_duplicates',     v_removed_same::text),
        ('scope',  'candidates_ticked',      cardinality(v_ticked)::text),
        ('scope',  'shared_phones_ignored',  v_shared_phones::text),
        ('scope',  'account_members',        (SELECT count(*) FROM ap_members)::text),
        ('scope',  'account_members_ticked', (SELECT count(*) FROM ap_members WHERE ticked)::text),
        ('scope',  'member_bookings_with_their_address',
                   (SELECT count(*) FROM ap_email_docs ed JOIN document d ON d.id = ed.id
                      JOIN ap_members m ON m.id = d.person_id AND NOT m.ticked)::text),
        ('scope',  'namesakes',              (SELECT count(*) FROM ap_namesakes)::text),
        ('scope',  'paid_for_others',        (SELECT count(*) FROM ap_docs WHERE paid AND scope <> 'full')::text),
        ('scope',  'as_carer',               (SELECT count(*) FROM ap_docs WHERE cared AND scope <> 'full')::text),
        ('scope',  'named_on_room_lines',    (SELECT count(*) FROM ap_share_lines)::text),
        ('scope',  'mails',                  (SELECT count(*) FROM ap_mails)::text),
        ('scope',  'volunteering_photos_outside_db',
                   (SELECT count(*) FROM volunteering_application va
                     WHERE (va.person_id IN (SELECT id FROM ap_persons)
                            OR (va.person_id IS NULL AND lower(trim(va.email)) IN (SELECT address FROM ap_addresses)))
                       AND nullif(va.photo, '') IS NOT NULL
                       AND (v_orgs IS NULL OR coalesce(va.organization_id = ANY (v_orgs), false)))::text),
        -- V0175: what organisations this run does not cover keep (0 and '' when it covers every organisation). The
        -- runner reads kept/document for its reply and never stores it: it would tie '#id' to named bookings.
        ('kept',   'document_other_organisation', (SELECT count(*) FROM ap_kept)::text),
        ('kept',   'organisations',
                   (SELECT coalesce(string_agg(x.o::text, ',' ORDER BY x.o), '')
                      FROM (SELECT k.organization_id AS o FROM ap_kept k
                            UNION SELECT ka.organization_id FROM ap_kept_applications ka) x WHERE x.o IS NOT NULL)),
        ('kept',   'document', (SELECT coalesce(string_agg(k.id::text, ',' ORDER BY k.id), '') FROM ap_kept k)),
        ('kept',   'candidate_other_organisation', v_kept_candidates::text),
        ('kept',   'document_mentions_other_organisation', (SELECT count(*) FROM ap_mentions)::text),
        ('kept',   'volunteering_application_other_organisation', (SELECT count(*) FROM ap_kept_applications)::text);

    -- One row per candidate, for the screen: booking id, ref, event, and why it matched. Never the booking's
    -- names or addresses.
    RETURN QUERY
    SELECT 'candidate'::text, c.id::text,
           jsonb_build_object('documentId', c.id, 'ref', c.ref, 'eventId', c.event_id, 'eventName', e.name,
                              'eventStart', to_char(e.start_date, 'YYYY-MM-DD'),
                              'match', concat_ws('+', CASE WHEN c.by_email THEN 'email' END,
                                                      CASE WHEN c.by_name THEN 'name' END,
                                                      CASE WHEN c.by_phone THEN 'phone' END),
                              'action', c.action, 'ticked', c.id = ANY (v_ticked), 'carerMatch', c.carer_match,
                              -- what would stop the run if it were ticked ('contact' leaves the booking's own
                              -- refusals alone): staff see it here, not after typing the phrase
                              'blockedBy', CASE WHEN c.action = 'full' THEN public.anonymise_person_blockers(ARRAY[c.id]) END)::text
      FROM ap_candidates c LEFT JOIN event e ON e.id = c.event_id
     WHERE c.action IN ('full', 'contact', 'carer')
     ORDER BY e.start_date DESC NULLS LAST, c.id;

    -- One row per member of their account, for the screen: ids, flags and counts — the names travel in the
    -- endpoint's reply, never in this grid (which the run ledger keeps).
    RETURN QUERY
    SELECT 'member'::text, m.id::text,
           jsonb_build_object('personId', m.id, 'linked', m.linked, 'ticked', m.ticked, 'minor', m.minor,
                              'bookings', m.bookings, 'sharesAddress', m.shares_address,
                              -- ticked, their bookings join the run's refusals (ap_member_docs): what would stop
                              -- it, as on a candidate
                              'blockedBy', CASE WHEN NOT m.linked THEN public.anonymise_person_blockers(ARRAY(
                                  SELECT md.doc_id FROM ap_member_docs md WHERE md.member_id = m.id)) END)::text
      FROM ap_members m ORDER BY m.id;

    -- The ticks this run ran with (a preview drops stale ones): the very sets a commit of this preview must send.
    RETURN QUERY VALUES ('ticks', 'candidates', array_to_string(v_ticked, ',')),
                        ('ticks', 'members',    array_to_string(v_members, ','));

    -- The other customers bearing one of their names, most likely the same human first: ids and counts only (the
    -- names travel in the endpoint's reply), the first 50 — scope/namesakes says how many there are in all.
    RETURN QUERY
    SELECT 'namesake'::text, n.id::text,
           jsonb_build_object('personId', n.id, 'accountId', n.account_id, 'owner', n.owner, 'bookings', n.bookings,
                              'familyNames', n.family_names)::text
      FROM ap_namesakes n ORDER BY n.family_names DESC, n.bookings DESC, n.id LIMIT 50;

    -- ================= THE ANONYMISATION =================

    -- ================= V0175: UNLINK WHAT OTHER ORGANISATIONS KEEP — FIRST =================
    -- Every id link from a kept row to the persons erased below is cut before anything else is written: the person
    -- trigger on_details_change_update_upcoming_bookings copies a person's details onto their bookings of events not
    -- over, so fired by the person rewrite below it would write the pseudonym into a kept booking still pointing at
    -- them (V0170 met the same trigger). Nothing else of a kept row changes, with one exception: a booking whose payer
    -- goes leaves the payer's cart (trigger payer_cart, V0109), which writes one history row; that row's text, like
    -- any earlier 'person #id' of theirs on a kept booking's history, loses the id.
    --
    -- The same trigger also reaches other organisations' bookings the run never links to: those of the persons whose
    -- row it writes but does not erase (a member's address cleared, the account's rows unlinked), re-copying their
    -- current details over what the booking captured. So every booking of another organisation that the trigger can
    -- reach (an event not over, a person on it) is photographed here, put back as it was before the checks, and
    -- proven unchanged. Chosen over disabling the trigger: no DDL and no table lock inside a DPO's run.
    CREATE TEMP TABLE ap_kept_snapshot AS
    SELECT d.id, d.person_first_name, d.person_last_name, d.person_male, d.person_ordained, d.person_lay_name, d.person_phone, d.person_email, d.person_organization_id, d.person_street, d.person_post_code, d.person_city_name, d.person_country_id, d.person_abc_names FROM document d WHERE false;
    IF v_orgs IS NOT NULL THEN
        INSERT INTO ap_kept_snapshot
        SELECT d.id, d.person_first_name, d.person_last_name, d.person_male, d.person_ordained, d.person_lay_name, d.person_phone, d.person_email, d.person_organization_id, d.person_street, d.person_post_code, d.person_city_name, d.person_country_id, d.person_abc_names
          FROM event e JOIN document d ON d.event_id = e.id
         WHERE e.end_date > now() AND d.person_id IS NOT NULL
           AND NOT coalesce(coalesce(e.organization_id, d.organization_id) = ANY (v_orgs), false);
        ALTER TABLE ap_kept_snapshot ADD PRIMARY KEY (id);
    END IF;

    IF v_orgs IS NOT NULL THEN
        UPDATE document d
           SET person_id        = CASE WHEN d.person_id        IN (SELECT id FROM ap_persons) THEN NULL ELSE d.person_id END,
               payer_id         = CASE WHEN d.payer_id         IN (SELECT id FROM ap_persons) THEN NULL ELSE d.payer_id END,
               person_carer1_id = CASE WHEN d.person_carer1_id IN (SELECT id FROM ap_persons) THEN NULL ELSE d.person_carer1_id END,
               person_carer2_id = CASE WHEN d.person_carer2_id IN (SELECT id FROM ap_persons) THEN NULL ELSE d.person_carer2_id END
          FROM ap_kept k
         WHERE k.id = d.id
           AND (   d.person_id IN (SELECT id FROM ap_persons) OR d.payer_id IN (SELECT id FROM ap_persons)
                OR d.person_carer1_id IN (SELECT id FROM ap_persons) OR d.person_carer2_id IN (SELECT id FROM ap_persons));
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'document_detached', n::text);

    IF v_orgs IS NOT NULL THEN
        -- them as the actor on another organisation's bookings' history (by history_user_person_id_idx)
        UPDATE history h SET user_person_id = NULL
         WHERE h.user_person_id IN (SELECT id FROM ap_persons) AND h.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = h.document_id) = ANY (v_orgs), false);
        GET DIAGNOSTICS n = ROW_COUNT;
        v_id_rx := 'person #(' || (SELECT string_agg(s.id::text, '|') FROM ap_persons s) || ')\M';
        UPDATE history h SET comment = regexp_replace(h.comment, v_id_rx, 'person #(erased)', 'g')
         WHERE h.document_id IN (SELECT k.id FROM ap_kept k) AND h.comment ~ v_id_rx;
        GET DIAGNOSTICS n2 = ROW_COUNT;
        n := n + n2;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'history_detached', n::text);

    IF v_orgs IS NOT NULL THEN
        UPDATE document_line dl SET share_mate_owner_person_id = NULL
          FROM ap_owner_lines o
         WHERE o.id = dl.id AND NOT o.covered AND dl.share_mate_owner_person_id IN (SELECT id FROM ap_persons);
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'document_line_detached', n::text);

    IF v_orgs IS NOT NULL THEN
        UPDATE recipient r SET person_id = NULL
          FROM ap_kept_recipients k
         WHERE k.id = r.id AND r.person_id IN (SELECT id FROM ap_persons);
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'recipient_detached', n::text);

    -- A kept conversation: the viewer and author links go; their participant and reaction rows (nothing but the link)
    -- are deleted.
    IF v_orgs IS NOT NULL THEN
        UPDATE conversation cv SET viewer_person_id = NULL
         WHERE cv.id IN (SELECT kc.id FROM ap_kept_conversations kc) AND cv.viewer_person_id IN (SELECT id FROM ap_persons);
        GET DIAGNOSTICS n = ROW_COUNT;
        UPDATE chat_message cm SET person_id = NULL
         WHERE cm.conversation_id IN (SELECT kc.id FROM ap_kept_conversations kc) AND cm.person_id IN (SELECT id FROM ap_persons);
        GET DIAGNOSTICS n2 = ROW_COUNT;
        n := n + n2;
        DELETE FROM chat_message_reaction cr USING chat_message cm
         WHERE cm.id = cr.message_id AND cm.conversation_id IN (SELECT kc.id FROM ap_kept_conversations kc)
           AND cr.person_id IN (SELECT id FROM ap_persons);
        GET DIAGNOSTICS n2 = ROW_COUNT;
        n := n + n2;
        DELETE FROM conversation_participant cp
         WHERE cp.conversation_id IN (SELECT kc.id FROM ap_kept_conversations kc) AND cp.person_id IN (SELECT id FROM ap_persons);
        GET DIAGNOSTICS n2 = ROW_COUNT;
        n := n + n2;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'conversation_detached', n::text);

    IF v_orgs IS NOT NULL THEN
        UPDATE list_item li
           SET person_id = CASE WHEN li.person_id IN (SELECT id FROM ap_persons) THEN NULL ELSE li.person_id END,
               payer_id  = CASE WHEN li.payer_id  IN (SELECT id FROM ap_persons) THEN NULL ELSE li.payer_id END
         WHERE (li.person_id IN (SELECT id FROM ap_persons) OR li.payer_id IN (SELECT id FROM ap_persons))
           AND li.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = li.document_id) = ANY (v_orgs), false);
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'list_item_detached', n::text);

    IF v_orgs IS NOT NULL THEN
        UPDATE push_subscription_recipient psr SET person_id = NULL
         WHERE psr.person_id IN (SELECT id FROM ap_persons)
           AND psr.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = psr.document_id) = ANY (v_orgs), false);
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'push_subscription_recipient_detached', n::text);

    IF v_orgs IS NOT NULL THEN
        UPDATE volunteering_application va SET person_id = NULL
          FROM ap_kept_applications ka
         WHERE ka.id = va.id AND va.person_id IN (SELECT id FROM ap_persons);
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'volunteering_application_detached', n::text);

    -- person — first, so the upcoming-bookings trigger copies the anonymised details (the document step below
    -- anonymises those bookings again anyway).
    UPDATE person q
       SET first_name = v_first, last_name = '#' || q.id, name = NULL, lay_name = NULL,
           email = NULL, phone = NULL, street = NULL, post_code = NULL, latitude = NULL, longitude = NULL,
           passport = NULL, birthdate = NULL, carer1_name = NULL, carer1_id = NULL, carer2_name = NULL, carer2_id = NULL,
           removed = true, emailing_list = false, anonymised_date = now()
      FROM ap_persons s WHERE s.id = q.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person', n::text);

    -- Linked members are other people's copies on the deleted account: off the account (below), and removed.
    UPDATE person q SET removed = true FROM ap_members m WHERE m.id = q.id AND m.linked;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'member_copies_removed', n::text);

    -- Unticked members typed with their address: the address is theirs, not the member's. Its triggers re-sync the
    -- member's upcoming bookings; the account unlink below re-syncs them again without the account's login, and
    -- the document step clears the address from every booking that still carries it.
    UPDATE person q SET email = NULL FROM ap_members m WHERE m.id = q.id AND m.shares_address;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'member_address_cleared', n::text);

    -- Removed rows typed with one of their addresses under another name (a removed member, mostly — 692 on kbs_local
    -- sat on a live owner's account): the address goes, the rest is somebody else's.
    UPDATE person q SET email = NULL
     WHERE q.removed AND lower(trim(q.email)) IN (SELECT address FROM ap_core_addresses)
       AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = q.id);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'removed_row_address_cleared', n::text);

    -- Other people's rows naming them as carer.
    UPDATE person q
       SET carer1_name = CASE WHEN q.carer1_id IN (SELECT id FROM ap_persons) THEN NULL ELSE q.carer1_name END,
           carer1_id   = CASE WHEN q.carer1_id IN (SELECT id FROM ap_persons) THEN NULL ELSE q.carer1_id END,
           carer2_name = CASE WHEN q.carer2_id IN (SELECT id FROM ap_persons) THEN NULL ELSE q.carer2_name END,
           carer2_id   = CASE WHEN q.carer2_id IN (SELECT id FROM ap_persons) THEN NULL ELSE q.carer2_id END
     WHERE (q.carer1_id IN (SELECT id FROM ap_persons) OR q.carer2_id IN (SELECT id FROM ap_persons))
       AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = q.id);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_as_carer_by_id', n::text);

    -- ... and by the typed name (narrowed first: abc_names() is plpgsql) — on their account's rows, or when nobody
    -- else bears the name: on anybody else's row a namesake may be the carer (see ap_carer_docs).
    UPDATE person q
       SET carer1_name = CASE WHEN public.abc_names(q.carer1_name) = ANY (CASE WHEN q.frontend_account_id = v_account_id
                                                                              THEN v_row_names ELSE v_unique_names END)
                              THEN NULL ELSE q.carer1_name END,
           carer2_name = CASE WHEN public.abc_names(q.carer2_name) = ANY (CASE WHEN q.frontend_account_id = v_account_id
                                                                              THEN v_row_names ELSE v_unique_names END)
                              THEN NULL ELSE q.carer2_name END
     WHERE q.id IN (SELECT c.id FROM (SELECT x.id, x.carer1_name AS n1, x.carer2_name AS n2 FROM person x
                                       WHERE x.carer1_name IS NOT NULL OR x.carer2_name IS NOT NULL OFFSET 0) c
                     WHERE public.abc_names(c.n1) = ANY (v_row_names) OR public.abc_names(c.n2) = ANY (v_row_names))
       AND NOT EXISTS (SELECT 1 FROM ap_persons s WHERE s.id = q.id);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_as_carer_by_name', n::text);

    -- Sessions: the person row is never deleted, so its cascade never fires. Revoke them.
    UPDATE auth_session a
       SET revoked = now(), revoked_reason = 'gdpr-anonymised'
     WHERE a.revoked IS NULL
       AND (a.person_id IN (SELECT id FROM ap_persons) OR (v_account_id IS NOT NULL AND a.account_id = v_account_id));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'auth_session_revoked', n::text);

    -- Their own account: unlink (audit_account_move records it), then delete — credentials go by cascade.
    IF v_account_id IS NOT NULL THEN
        UPDATE person q SET frontend_account_id = NULL WHERE q.frontend_account_id = v_account_id;
        DELETE FROM frontend_account fa WHERE fa.id = v_account_id;
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('deleted', 'frontend_account', n::text);

    -- Backups taken by maintenance scripts, not by migrations, so a database may lack them: reached through EXECUTE.
    -- They hold logins (their address) and names; no foreign key leads to them, so the reference guard cannot.
    IF to_regclass('public.frontend_account_dissolved_backup') IS NOT NULL THEN
        EXECUTE 'DELETE FROM public.frontend_account_dissolved_backup b
                  WHERE lower(trim(b.username)) IN (SELECT address FROM ap_addresses)';
        GET DIAGNOSTICS n = ROW_COUNT;
        RETURN QUERY VALUES ('deleted', 'frontend_account_dissolved_backup', n::text);
    END IF;
    IF to_regclass('public.member_email_nullify_backup') IS NOT NULL THEN
        EXECUTE 'DELETE FROM public.member_email_nullify_backup b
                  WHERE b.person_id IN (SELECT id FROM ap_persons) OR b.frontend_account_id = $1
                     OR lower(trim(b.email)) IN (SELECT address FROM ap_addresses)' USING v_account_id;
        GET DIAGNOSTICS n = ROW_COUNT;
        RETURN QUERY VALUES ('deleted', 'member_email_nullify_backup', n::text);
    END IF;
    IF to_regclass('public.spare_owner_row_backup') IS NOT NULL THEN
        EXECUTE 'DELETE FROM public.spare_owner_row_backup b
                  WHERE b.person_id IN (SELECT id FROM ap_persons) OR b.surviving_owner_id IN (SELECT id FROM ap_persons)
                     OR b.frontend_account_id = $1' USING v_account_id;
        GET DIAGNOSTICS n = ROW_COUNT;
        RETURN QUERY VALUES ('deleted', 'spare_owner_row_backup', n::text);
    END IF;

    -- Account moves and link changes: their rows' login and any note, and THEIR login wherever it appears — the
    -- unlink above wrote it into the rows of the account's removed members too (those rows' note is the run's own
    -- audit note, kept).
    UPDATE person_account_move m
       SET from_account_username = CASE WHEN m.from_account_username IS NULL THEN NULL
                                        WHEN m.person_id IN (SELECT id FROM ap_persons)
                                          OR lower(trim(m.from_account_username)) IN (SELECT address FROM ap_addresses)
                                        THEN v_email ELSE m.from_account_username END,
           note = CASE WHEN m.person_id IN (SELECT id FROM ap_persons) AND m.note IS NOT NULL AND m.moved_at < now()
                            AND m.note NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE m.note END
     WHERE (m.person_id IN (SELECT id FROM ap_persons) OR lower(trim(m.from_account_username)) IN (SELECT address FROM ap_addresses))
       AND (m.from_account_username IS DISTINCT FROM v_email
            OR (m.person_id IN (SELECT id FROM ap_persons) AND m.note IS NOT NULL AND m.moved_at < now()
                AND m.note NOT LIKE v_tag_prefix || '%'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_account_move', n::text);

    UPDATE person_link_change c
       SET note = v_tag
      FROM ap_persons s
     WHERE s.id = c.person_id AND c.note IS NOT NULL AND c.changed_at < now() AND c.note NOT LIKE v_tag_prefix || '%';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_link_change', n::text);

    -- Magic links of their addresses. cart.magic_link_id has no ON DELETE action: clear it first.
    UPDATE cart c SET magic_link_id = NULL
     WHERE c.magic_link_id IN (SELECT ml.id FROM magic_link ml
                                WHERE lower(trim(ml.email)) IN (SELECT address FROM ap_addresses)
                                   OR lower(trim(ml.old_email)) IN (SELECT address FROM ap_addresses));
    DELETE FROM magic_link ml
     WHERE lower(trim(ml.email)) IN (SELECT address FROM ap_addresses)
        OR lower(trim(ml.old_email)) IN (SELECT address FROM ap_addresses);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'magic_link', n::text);

    -- Bookings.
    UPDATE document d
       SET person_first_name = v_first, person_last_name = '#' || coalesce(d.person_id, p_person_id),
           person_name = NULL, person_lay_name = NULL,
           person_email = NULL, person_phone = NULL, person_street = NULL, person_post_code = NULL,
           person_latitude = NULL, person_longitude = NULL, person_passport = NULL,
           person_carer1_name = NULL, person_carer2_name = NULL,
           inet_ip = NULL, inet_geo_latitude = NULL, inet_geo_longitude = NULL,
           special_needs = CASE WHEN d.special_needs IS NOT NULL AND d.special_needs NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.special_needs END,
           comment       = CASE WHEN d.comment       IS NOT NULL AND d.comment       NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.comment END,
           request       = CASE WHEN d.request       IS NOT NULL AND d.request       NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.request END
      FROM ap_docs t WHERE t.id = d.id AND t.scope = 'full';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_full', n::text);

    UPDATE document d
       SET person_email = NULL, person_phone = NULL, person_street = NULL, person_post_code = NULL,
           person_latitude = NULL, person_longitude = NULL
      FROM ap_docs t WHERE t.id = d.id AND t.scope = 'contact';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_contact', n::text);

    UPDATE document d
       SET person_carer1_name = CASE WHEN d.person_carer1_id IN (SELECT id FROM ap_persons)
                                       OR public.abc_names(d.person_carer1_name) = ANY (v_row_names)
                                     THEN NULL ELSE d.person_carer1_name END,
           person_carer2_name = CASE WHEN d.person_carer2_id IN (SELECT id FROM ap_persons)
                                       OR public.abc_names(d.person_carer2_name) = ANY (v_row_names)
                                     THEN NULL ELSE d.person_carer2_name END
      FROM ap_docs t WHERE t.id = d.id AND t.cared AND t.scope <> 'full';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_carer', n::text);

    UPDATE document d SET person_email = NULL
      FROM ap_email_docs ed WHERE ed.id = d.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_their_address', n::text);

    -- The bearer key of their bookings' carts: links in old emails stop opening them — but only a cart holding no
    -- one else's booking. A cart is often a family's (12% of carts held several people's bookings when this was
    -- written): rotating it would cut the others' links too, while it opens nothing of theirs that still identifies
    -- them. Those carts are counted instead.
    UPDATE cart c SET uuid = gen_random_uuid()::text
     WHERE c.id IN (SELECT d.cart_id FROM document d JOIN ap_docs t ON t.id = d.id AND t.scope = 'full')
       AND NOT EXISTS (SELECT 1 FROM document o WHERE o.cart_id = c.id
                         AND NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = o.id AND t.scope = 'full'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'cart_uuid', n::text);
    RETURN QUERY VALUES ('kept', 'cart_shared_with_others',
        (SELECT count(DISTINCT d.cart_id) FROM document d JOIN ap_docs t ON t.id = d.id AND t.scope = 'full'
          WHERE d.cart_id IS NOT NULL
            AND EXISTS (SELECT 1 FROM document o WHERE o.cart_id = d.cart_id
                          AND NOT EXISTS (SELECT 1 FROM ap_docs t2 WHERE t2.id = o.id AND t2.scope = 'full')))::text);

    UPDATE document_line dl SET comment = v_tag
      FROM ap_docs t
     WHERE t.id = dl.document_id AND t.scope = 'full' AND dl.comment IS NOT NULL AND dl.comment NOT LIKE v_tag_prefix || '%';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line_comment', n::text);

    UPDATE document_line dl
       SET share_mate_owner_name = CASE WHEN dl.share_mate_owner_person_id IN (SELECT id FROM ap_persons)
                                          OR public.abc_names(dl.share_mate_owner_name) = ANY (v_row_names)
                                        THEN NULL ELSE dl.share_mate_owner_name END,
           share_owner_mate1_name = CASE WHEN public.abc_names(dl.share_owner_mate1_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate1_name END,
           share_owner_mate2_name = CASE WHEN public.abc_names(dl.share_owner_mate2_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate2_name END,
           share_owner_mate3_name = CASE WHEN public.abc_names(dl.share_owner_mate3_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate3_name END,
           share_owner_mate4_name = CASE WHEN public.abc_names(dl.share_owner_mate4_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate4_name END,
           share_owner_mate5_name = CASE WHEN public.abc_names(dl.share_owner_mate5_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate5_name END,
           share_owner_mate6_name = CASE WHEN public.abc_names(dl.share_owner_mate6_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate6_name END,
           share_owner_mate7_name = CASE WHEN public.abc_names(dl.share_owner_mate7_name) = ANY (v_row_names) THEN NULL ELSE dl.share_owner_mate7_name END
      FROM ap_share_lines sl WHERE sl.id = dl.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line_room_names', n::text);

    -- Mails: subject and content of every mail to or about them, letters included; never transmitted or read.
    UPDATE mail m SET subject = v_tag, content = v_tag
      FROM ap_mails a
     WHERE a.id = m.id AND (m.subject NOT LIKE v_tag_prefix || '%' OR m.content NOT LIKE v_tag_prefix || '%');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'mail', n::text);

    UPDATE mail m SET from_name = NULL, from_email = NULL
      FROM ap_sender_mails sm WHERE sm.id = m.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'mail_sender', n::text);

    UPDATE recipient r SET name = NULL, email = v_email
      FROM ap_recipients ar WHERE ar.id = r.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'recipient', n::text);

    -- History: request, the comment quoting a mail we tagged, and changes redacted in place.
    UPDATE history h
       SET request = CASE WHEN hs.scope = 'full' AND h.request IS NOT NULL AND h.request NOT LIKE v_tag_prefix || '%'
                          THEN v_tag ELSE h.request END,
           comment = CASE WHEN h.comment IS NOT NULL AND h.comment NOT LIKE v_tag_prefix || '%'
                               AND h.mail_id IN (SELECT id FROM ap_mails)
                          THEN v_tag ELSE hs.new_comment END,
           changes = hs.new_changes,
           username = hs.new_username
      FROM ap_hist hs
     WHERE hs.id = h.id
       AND (   (hs.scope = 'full' AND h.request IS NOT NULL AND h.request NOT LIKE v_tag_prefix || '%')
            OR (h.comment IS NOT NULL AND h.comment NOT LIKE v_tag_prefix || '%' AND h.mail_id IN (SELECT id FROM ap_mails))
            OR hs.new_comment IS DISTINCT FROM h.comment
            OR hs.new_username IS DISTINCT FROM h.username
            -- Only a change in MEANING is written: re-serialising an unchanged array would rewrite every row
            OR (h.changes IS NOT NULL AND hs.new_changes IS DISTINCT FROM h.changes
                AND (   hs.new_changes IS NULL
                     OR NOT pg_input_is_valid(h.changes, 'jsonb')
                     OR hs.new_changes::jsonb IS DISTINCT FROM h.changes::jsonb)));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'history', n::text);

    -- Payments: the comment and what the gateway sent back; the money and the reference stay.
    UPDATE money_transfer mt
       SET comment = CASE WHEN mt.comment IS NOT NULL AND mt.comment NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE mt.comment END,
           gateway_response = NULL, gateway_generate_payment_url_response = NULL
      FROM ap_docs t
     WHERE t.id = mt.document_id AND (t.scope IN ('full', 'contact') OR t.paid)
       AND (   (mt.comment IS NOT NULL AND mt.comment NOT LIKE v_tag_prefix || '%')
            OR mt.gateway_response IS NOT NULL OR mt.gateway_generate_payment_url_response IS NOT NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'money_transfer', n::text);

    -- Chat: their messages anywhere, and every text message and the title of their conversations.
    UPDATE chat_message cm SET content = v_tag
     WHERE cm.kind = 'text' AND cm.content NOT LIKE v_tag_prefix || '%'
       AND cm.conversation_id NOT IN (SELECT kc.id FROM ap_kept_conversations kc) -- V0175
       AND (   cm.person_id IN (SELECT id FROM ap_persons)
            OR cm.conversation_id IN (SELECT cv.id FROM conversation cv
                                       WHERE cv.viewer_person_id IN (SELECT id FROM ap_persons)
                                          OR cv.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full')));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'chat_message', n::text);

    UPDATE conversation cv SET title = NULL
     WHERE cv.title IS NOT NULL
       AND cv.id NOT IN (SELECT kc.id FROM ap_kept_conversations kc) -- V0175
       AND (cv.viewer_person_id IN (SELECT id FROM ap_persons) OR cv.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'conversation', n::text);

    -- Invitations: the alias somebody gave them (as invitee). An inviter's own invitations name other people.
    UPDATE invitation i SET alias_first_name = v_first, alias_last_name = v_last
     WHERE (i.invitee_id IN (SELECT id FROM ap_persons) OR i.created_alias_person_id IN (SELECT id FROM ap_persons))
       AND (i.alias_first_name IS DISTINCT FROM v_first OR i.alias_last_name IS DISTINCT FROM v_last);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'invitation', n::text);

    UPDATE list_item li SET comment = v_tag
     WHERE li.comment IS NOT NULL AND li.comment NOT LIKE v_tag_prefix || '%'
       AND (li.person_id IN (SELECT id FROM ap_persons) OR li.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full'))
       AND (v_orgs IS NULL OR li.document_id IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = li.document_id) = ANY (v_orgs), false)); -- V0175
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'list_item', n::text);

    UPDATE error er SET message = v_tag, user_agent = NULL, route = NULL
     WHERE er.user_person_id IN (SELECT id FROM ap_persons)
       AND (er.message IS NULL OR er.message NOT LIKE v_tag_prefix || '%' OR er.user_agent IS NOT NULL OR er.route IS NOT NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'error', n::text);

    UPDATE enqueued_request er
       SET request_string = v_tag,
           reply_string = CASE WHEN er.reply_string IS NOT NULL THEN v_tag END
      FROM ap_docs t
     WHERE t.id = er.document_id AND t.scope IN ('full', 'contact') AND er.execution_date IS NOT NULL
       AND (er.request_string NOT LIKE v_tag_prefix || '%' OR (er.reply_string IS NOT NULL AND er.reply_string NOT LIKE v_tag_prefix || '%'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'enqueued_request', n::text);

    -- Deleted outright: nothing points at them that must survive.
    WITH gone AS (
        DELETE FROM push_subscription_recipient psr
         WHERE (   psr.person_id IN (SELECT id FROM ap_persons)
                OR lower(trim(psr.email)) IN (SELECT address FROM ap_addresses)
                OR psr.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full'))
           -- V0175: one made for another organisation's booking is kept (unlinked above)
           AND (v_orgs IS NULL OR psr.document_id IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = psr.document_id) = ANY (v_orgs), false))
        RETURNING psr.id, psr.subscription_id)
    -- The CTE's deletes are invisible below: a subscription goes only when every recipient it has is one of `gone`
    -- (a device shared by a family keeps the others' registrations).
    DELETE FROM push_subscription ps
     WHERE ps.id IN (SELECT subscription_id FROM gone)
       AND NOT EXISTS (SELECT 1 FROM push_subscription_recipient o
                        WHERE o.subscription_id = ps.id AND o.id NOT IN (SELECT id FROM gone));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'push_subscription', n::text);

    DELETE FROM subscription su WHERE su.person_id IN (SELECT id FROM ap_persons);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'subscription', n::text);

    -- Volunteering applications hold health, criminal-record and immigration answers: deleted, their timeline,
    -- communications, date proposals and assignments with them (ON DELETE CASCADE).
    DELETE FROM volunteering_application va
     WHERE (   va.person_id IN (SELECT id FROM ap_persons)
            OR (va.person_id IS NULL AND lower(trim(va.email)) IN (SELECT address FROM ap_addresses)))
       AND va.id NOT IN (SELECT ka.id FROM ap_kept_applications ka); -- V0175: another organisation's, kept
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'volunteering_application', n::text);

    -- Mate invitations to their rooms.
    DELETE FROM mate_invite_token mit
     WHERE mit.owner_document_line_id IN (SELECT dl.id FROM document_line dl JOIN ap_docs t ON t.id = dl.document_id AND t.scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'mate_invite_token', n::text);

    -- One marker per booking whose data was removed. username is never 'online': no trigger sends anything.
    INSERT INTO history (document_id, comment, username, user_person_id)
    SELECT t.id, 'Personal data removed (GDPR erasure request)', 'gdpr-erasure', public.kbs_audit_person_id()
      FROM ap_docs t WHERE t.scope IN ('full', 'contact');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('inserted', 'history_marker', n::text);

    -- The run ledger. Once they are anonymised, no run may still tie their id to who it listed: the ticks (a booking
    -- ticked for its contact details keeps the other person's name), a refusal's text naming the person or account
    -- sharing their address, a grid listing candidates, members or namesakes (the runner stores none of those any
    -- more; rows written before it did are cleaned here too). Other people's runs whose refusal names them lose
    -- that text as well. Counts, keys and dates stay.
    UPDATE person_erasure_run r
       SET candidate_document_ids = '{}', member_ids = '{}', error = NULL,
           grid = CASE WHEN jsonb_typeof(r.grid) = 'array'
                       THEN (SELECT coalesce(jsonb_agg(g.e ORDER BY g.o), '[]'::jsonb)
                               FROM jsonb_array_elements(r.grid) WITH ORDINALITY AS g(e, o)
                              WHERE g.e ->> 'step' NOT IN ('candidate', 'member', 'namesake', 'ticks'))
                       ELSE r.grid END
     WHERE r.person_id IN (SELECT id FROM ap_persons);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_erasure_run_own', n::text);
    UPDATE person_erasure_run r
       SET error = CASE WHEN EXISTS (SELECT 1 FROM ap_persons s WHERE r.error ~ ('\mperson ' || s.id || '\M'))
                          OR (v_account_id IS NOT NULL AND r.error ~ ('\maccount ' || v_account_id || '\M'))
                        THEN NULL ELSE r.error END,
           -- a preview of somebody else that ticked their bookings, or them as a member
           candidate_document_ids = CASE WHEN r.candidate_document_ids && ARRAY(SELECT t.id FROM ap_docs t)
                                         THEN '{}'::integer[] ELSE r.candidate_document_ids END,
           member_ids = CASE WHEN r.member_ids && ARRAY(SELECT s.id FROM ap_persons s)
                             THEN '{}'::integer[] ELSE r.member_ids END
     WHERE r.person_id NOT IN (SELECT id FROM ap_persons)
       AND (   (r.error IS NOT NULL
                AND (   EXISTS (SELECT 1 FROM ap_persons s WHERE r.error ~ ('\mperson ' || s.id || '\M'))
                     OR (v_account_id IS NOT NULL AND r.error ~ ('\maccount ' || v_account_id || '\M'))))
            OR r.candidate_document_ids && ARRAY(SELECT t.id FROM ap_docs t)
            OR r.member_ids && ARRAY(SELECT s.id FROM ap_persons s));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_erasure_run_others', n::text);

    -- ================= V0175: OTHER ORGANISATIONS' BOOKINGS PUT BACK =================
    -- What the person trigger re-copied onto them (see the photograph at UNLINK). The names first, then the derived
    -- person_abc_names on its own: trigger set_person_abc_names recomputes it from the names.
    IF v_orgs IS NOT NULL THEN
        UPDATE document d
           SET person_first_name = s.person_first_name,
               person_last_name = s.person_last_name,
               person_male = s.person_male,
               person_ordained = s.person_ordained,
               person_lay_name = s.person_lay_name,
               person_phone = s.person_phone,
               person_email = s.person_email,
               person_organization_id = s.person_organization_id,
               person_street = s.person_street,
               person_post_code = s.person_post_code,
               person_city_name = s.person_city_name,
               person_country_id = s.person_country_id
          FROM ap_kept_snapshot s
         WHERE s.id = d.id AND (d.person_first_name, d.person_last_name, d.person_male, d.person_ordained, d.person_lay_name, d.person_phone, d.person_email, d.person_organization_id, d.person_street, d.person_post_code, d.person_city_name, d.person_country_id, d.person_abc_names) IS DISTINCT FROM (s.person_first_name, s.person_last_name, s.person_male, s.person_ordained, s.person_lay_name, s.person_phone, s.person_email, s.person_organization_id, s.person_street, s.person_post_code, s.person_city_name, s.person_country_id, s.person_abc_names);
        GET DIAGNOSTICS n = ROW_COUNT;
        UPDATE document d SET person_abc_names = s.person_abc_names
          FROM ap_kept_snapshot s
         WHERE s.id = d.id AND d.person_abc_names IS DISTINCT FROM s.person_abc_names;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'document_kept_restored', n::text);

    -- ================= CHECKS (raise = the caller rolls back) =================
    SELECT count(*) INTO n FROM person q JOIN ap_persons s ON s.id = q.id
     WHERE q.email IS NOT NULL OR q.phone IS NOT NULL OR q.street IS NOT NULL OR q.anonymised_date IS NULL;
    IF n > 0 THEN bad := bad || format(E'\n  person rows still identifying: %s', n); END IF;

    -- Live duplicates were refused; members' and removed rows' copies of the address were cleared
    SELECT count(*) INTO n FROM person q WHERE lower(trim(q.email)) IN (SELECT address FROM ap_core_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  person rows still holding their address: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ap_hist hs ON hs.id = h.id JOIN ap_hist_docs t ON t.id = h.document_id
     WHERE public.anonymise_person_comment(h.comment, v_row_names, t.cared) IS DISTINCT FROM h.comment;
    IF n > 0 THEN bad := bad || format(E'\n  history comments still naming them: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ap_hist hs ON hs.id = h.id
     WHERE lower(trim(h.username)) IN (SELECT a.address FROM ap_addresses a)
        OR (h.username LIKE 'guest:%' AND lower(substr(h.username, 7)) IN (SELECT left(a.address, 58) FROM ap_addresses a));
    IF n > 0 THEN bad := bad || format(E'\n  history rows still naming them as the user: %s', n); END IF;

    IF to_regclass('public.member_email_nullify_backup') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM public.member_email_nullify_backup b
                  WHERE lower(trim(b.email)) IN (SELECT address FROM ap_addresses) OR b.frontend_account_id = $1'
           INTO n USING v_account_id;
        IF n > 0 THEN bad := bad || format(E'\n  backup rows still holding their address: %s', n); END IF;
    END IF;

    SELECT count(*) INTO n FROM frontend_account fa
     WHERE fa.id = v_account_id OR lower(trim(fa.username)) IN (SELECT address FROM ap_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  accounts left: %s', n); END IF;

    SELECT count(*) INTO n FROM document d JOIN ap_docs t ON t.id = d.id AND t.scope IN ('full', 'contact')
     WHERE d.person_email IS NOT NULL OR d.person_phone IS NOT NULL OR d.person_street IS NOT NULL
        OR (t.scope = 'full' AND (d.person_first_name IS DISTINCT FROM v_first OR d.person_last_name IS DISTINCT FROM '#' || coalesce(d.person_id, p_person_id)
                                  OR d.inet_ip IS NOT NULL OR d.person_passport IS NOT NULL));
    IF n > 0 THEN bad := bad || format(E'\n  bookings still identifying: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ap_hist hs ON hs.id = h.id
     WHERE h.changes IS NOT NULL AND EXISTS (SELECT 1 FROM ap_addresses a WHERE position(a.address IN lower(h.changes)) > 0);
    IF n > 0 THEN bad := bad || format(E'\n  history.changes still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ap_hist hs ON hs.id = h.id AND hs.scope = 'full'
     WHERE pg_input_is_valid(h.changes, 'jsonb') AND jsonb_typeof(h.changes::jsonb) = 'array'
       AND EXISTS (SELECT 1 FROM jsonb_array_elements(h.changes::jsonb) e
                    WHERE e ->> '$codec' = 'AddDocumentEvent'
                      -- a name the event never carried is no name left (a booking of an existing person sends
                      -- none: the replay reads it from the document row, anonymised and checked above)
                      AND (   (e ? 'firstName' AND e ->> 'firstName' IS DISTINCT FROM v_first)
                           OR (e ? 'lastName' AND coalesce(e ->> 'lastName', '') NOT LIKE '#%')));
    IF n > 0 THEN bad := bad || format(E'\n  history.changes still holding a name: %s', n); END IF;

    SELECT count(*) INTO n FROM document d WHERE lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses)
       AND (v_orgs IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = d.id) = ANY (v_orgs), false)); -- V0175: another organisation's bookings are kept as they are
    IF n > 0 THEN bad := bad || format(E'\n  bookings still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM person_account_move m WHERE lower(trim(m.from_account_username)) IN (SELECT address FROM ap_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  account moves still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM document d JOIN ap_docs t ON t.id = d.id AND t.cared
     WHERE public.abc_names(d.person_carer1_name) = ANY (v_row_names) OR public.abc_names(d.person_carer2_name) = ANY (v_row_names);
    IF n > 0 THEN bad := bad || format(E'\n  bookings still naming them as carer: %s', n); END IF;

    SELECT count(*) INTO n FROM recipient r JOIN ap_recipients ar ON ar.id = r.id
     WHERE lower(trim(r.email)) IN (SELECT address FROM ap_addresses) OR r.name IS NOT NULL;
    IF n > 0 THEN bad := bad || format(E'\n  recipients still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM mail m JOIN ap_mails a ON a.id = m.id
     WHERE m.subject NOT LIKE v_tag_prefix || '%' OR m.content NOT LIKE v_tag_prefix || '%';
    IF n > 0 THEN bad := bad || format(E'\n  mails not rewritten: %s', n); END IF;

    SELECT count(*) INTO n FROM magic_link ml
     WHERE lower(trim(ml.email)) IN (SELECT address FROM ap_addresses) OR lower(trim(ml.old_email)) IN (SELECT address FROM ap_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  magic links left: %s', n); END IF;

    SELECT count(*) INTO n FROM auth_session a
     WHERE a.revoked IS NULL AND (a.person_id IN (SELECT id FROM ap_persons) OR (v_account_id IS NOT NULL AND a.account_id = v_account_id));
    IF n > 0 THEN bad := bad || format(E'\n  live sessions left: %s', n); END IF;

    -- V0175: no row another organisation keeps still points at a person erased here — kept and named, it would tell
    -- who '#id' was.
    IF v_orgs IS NOT NULL THEN
        SELECT count(*) INTO n FROM (
                  SELECT d.id FROM document d JOIN ap_persons s ON s.id = d.person_id
            UNION SELECT d.id FROM document d JOIN ap_persons s ON s.id = d.payer_id
            UNION SELECT d.id FROM document d JOIN ap_persons s ON s.id = d.person_carer1_id
            UNION SELECT d.id FROM document d JOIN ap_persons s ON s.id = d.person_carer2_id) x
         WHERE NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = x.id) = ANY (v_orgs), false);
        IF n > 0 THEN bad := bad || format(E'\n  kept bookings still linked to them: %s', n); END IF;

        SELECT count(*) INTO n FROM document_line dl JOIN ap_owner_lines o ON o.id = dl.id AND NOT o.covered
         WHERE dl.share_mate_owner_person_id IN (SELECT id FROM ap_persons);
        IF n > 0 THEN bad := bad || format(E'\n  kept room lines still linked to them: %s', n); END IF;

        -- two counts, each on its own index (history_user_person_id_idx, history_document_id_idx): an OR of the two
        -- would read the whole history table
        SELECT count(*) INTO n FROM history h
         WHERE h.user_person_id IN (SELECT id FROM ap_persons) AND h.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = h.document_id) = ANY (v_orgs), false);
        SELECT count(*) INTO n2 FROM history h
         WHERE h.document_id IN (SELECT k.id FROM ap_kept k) AND h.comment ~ v_id_rx;
        IF n + n2 > 0 THEN bad := bad || format(E'\n  kept bookings'' history still linked to them: %s', n + n2); END IF;

        SELECT count(*) INTO n FROM document d JOIN ap_kept_snapshot s ON s.id = d.id
         WHERE (d.person_first_name, d.person_last_name, d.person_male, d.person_ordained, d.person_lay_name, d.person_phone, d.person_email, d.person_organization_id, d.person_street, d.person_post_code, d.person_city_name, d.person_country_id, d.person_abc_names) IS DISTINCT FROM (s.person_first_name, s.person_last_name, s.person_male, s.person_ordained, s.person_lay_name, s.person_phone, s.person_email, s.person_organization_id, s.person_street, s.person_post_code, s.person_city_name, s.person_country_id, s.person_abc_names);
        IF n > 0 THEN bad := bad || format(E'\n  other organisations'' bookings rewritten by the run: %s', n); END IF;

        SELECT count(*) INTO n FROM recipient r JOIN mail m ON m.id = r.mail_id
         WHERE r.person_id IN (SELECT id FROM ap_persons) AND m.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = m.document_id) = ANY (v_orgs), false);
        IF n > 0 THEN bad := bad || format(E'\n  kept mails'' recipients still linked to them: %s', n); END IF;

        SELECT count(*) INTO n FROM conversation cv
         WHERE cv.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = cv.document_id) = ANY (v_orgs), false)
           AND (   cv.viewer_person_id IN (SELECT id FROM ap_persons)
                OR EXISTS (SELECT 1 FROM chat_message cm WHERE cm.conversation_id = cv.id AND cm.person_id IN (SELECT id FROM ap_persons))
                OR EXISTS (SELECT 1 FROM conversation_participant cp WHERE cp.conversation_id = cv.id
                             AND cp.person_id IN (SELECT id FROM ap_persons))
                OR EXISTS (SELECT 1 FROM chat_message cm JOIN chat_message_reaction cr ON cr.message_id = cm.id
                            WHERE cm.conversation_id = cv.id AND cr.person_id IN (SELECT id FROM ap_persons)));
        IF n > 0 THEN bad := bad || format(E'\n  kept conversations still linked to them: %s', n); END IF;

        SELECT count(*) INTO n FROM list_item li
         WHERE (li.person_id IN (SELECT id FROM ap_persons) OR li.payer_id IN (SELECT id FROM ap_persons))
           AND li.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = li.document_id) = ANY (v_orgs), false);
        IF n > 0 THEN bad := bad || format(E'\n  kept list items still linked to them: %s', n); END IF;

        SELECT count(*) INTO n FROM push_subscription_recipient psr
         WHERE psr.person_id IN (SELECT id FROM ap_persons) AND psr.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = psr.document_id) = ANY (v_orgs), false);
        IF n > 0 THEN bad := bad || format(E'\n  kept push registrations still linked to them: %s', n); END IF;

        SELECT count(*) INTO n FROM volunteering_application va WHERE va.person_id IN (SELECT id FROM ap_persons);
        IF n > 0 THEN bad := bad || format(E'\n  volunteering applications still linked to them: %s', n); END IF;
    END IF;

    IF bad <> '' THEN
        RAISE EXCEPTION 'Personal data survives the anonymisation of person % [db=%]:% — ROLLBACK and investigate', p_person_id, current_database(), bad;
    END IF;

    SELECT count(*) INTO n FROM mail;
    IF n <> v_mail_rows_before THEN
        RAISE EXCEPTION 'The mail table gained or lost rows (% now, % before) — a trigger fired: ROLLBACK', n, v_mail_rows_before;
    END IF;
    SELECT count(*) INTO n FROM mail WHERE NOT transmitted;
    IF n <> v_mail_queued_before THEN
        RAISE EXCEPTION 'The unsent mail queue changed (% now, % before) — ROLLBACK', n, v_mail_queued_before;
    END IF;

    RETURN QUERY VALUES ('status', 'result', 'anonymisation OK — uncommitted; COMMIT to apply, ROLLBACK for a free dry run');
END
$anon$;

REVOKE ALL ON FUNCTION public.anonymise_person(integer, integer[], integer[], boolean, integer[]) FROM PUBLIC;

COMMENT ON FUNCTION public.anonymise_person(integer, integer[], integer[], boolean, integer[]) IS
'GDPR erasure of one customer (V0153; V0175: by organisation): removes everything that identifies the person (and its linked copies) from their account, bookings, history, mails, payments and chat, keeping amounts, dates and gateway references. p_organization_ids = the organisations whose records it erases (NULL: every organisation); another organisation''s bookings and volunteering applications are kept as they are and unlinked from the person. Requires an explicit REPEATABLE READ transaction and SET LOCAL kbs.erase_confirm = ''ANONYMISE PERSON <id>''. Never commits — the caller reads the grid, then COMMIT or ROLLBACK. Docs: aggregate repo, docs/operations/gdpr-person-erasure-reference.md.';

-- ===== anonymise_booker() ======================================================================================

DROP FUNCTION IF EXISTS public.anonymise_booker(integer, integer[], boolean);

CREATE OR REPLACE FUNCTION public.anonymise_booker(p_document_id integer,
                                                   p_candidate_document_ids integer[] DEFAULT '{}'::integer[],
                                                   p_lenient boolean DEFAULT false,
                                                   p_organization_ids integer[] DEFAULT NULL)
RETURNS TABLE(step text, metric text, value text)
LANGUAGE plpgsql
SET timezone TO 'Europe/London'
AS $anon$
DECLARE
    v_tag_prefix constant text := '[personal data removed (GDPR)';
    v_tag         varchar(255);
    v_confirm     text := nullif(current_setting('kbs.erase_confirm', true), '');
    v_first       constant varchar(45) := 'Anonymised';
    v_last        varchar(45);
    v_email       varchar(127);
    v_ticked      integer[] := coalesce(p_candidate_document_ids, '{}'::integer[]);
    b             record;
    v_address     text;
    v_name        text;
    v_names       text[];
    v_owned       boolean := false;
    v_other_applications bigint := 0;
    v_theirs      boolean := false;
    v_address_state text;
    v_addresses   text[];
    v_shared_phones bigint;
    v_text        text;
    v_mail_rows_before   bigint;
    v_mail_queued_before bigint;
    n             bigint;
    bad           text := '';
    -- V0175: the organisations this run covers, sorted, no NULL; NULL = every organisation (the function as it was)
    v_orgs        integer[] := CASE WHEN p_organization_ids IS NOT NULL
                                    THEN ARRAY(SELECT DISTINCT o FROM unnest(p_organization_ids) AS o WHERE o IS NOT NULL ORDER BY o) END;
    v_kept_candidates bigint := 0;
    v_kept_address boolean := false;
BEGIN
    v_tag := v_tag_prefix || ' on ' || current_date || ']';

    -- ================= INTERLOCKS =================
    IF p_document_id IS NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseNotFoundError] the booking id is NULL';
    END IF;
    IF current_setting('transaction_isolation') NOT IN ('repeatable read', 'serializable') THEN
        RAISE EXCEPTION 'anonymise_booker must run in a REPEATABLE READ transaction — start with: BEGIN ISOLATION LEVEL REPEATABLE READ;';
    END IF;
    IF transaction_timestamp() = statement_timestamp() THEN
        RAISE EXCEPTION 'anonymise_booker refuses to run as a single autocommit statement — it would commit with no review. BEGIN ISOLATION LEVEL REPEATABLE READ; SET LOCAL kbs.erase_confirm = ...; SELECT * FROM anonymise_booker(...); then COMMIT or ROLLBACK, each statement sent separately';
    END IF;
    IF v_confirm IS DISTINCT FROM ('ANONYMISE BOOKER ' || p_document_id) THEN
        RAISE EXCEPTION 'anonymise_booker: missing statement of intent — run  SET LOCAL kbs.erase_confirm = ''ANONYMISE BOOKER %'';  in this transaction first (found: %)',
                        p_document_id, coalesce(v_confirm, '<unset>');
    END IF;

    -- ================= THE SEED BOOKING =================
    SELECT d.id, d.ref, d.person_id, d.person_first_name, d.person_last_name, d.person_email, d.person_abc_names,
           d.person_phone
      INTO b FROM document d WHERE d.id = p_document_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION '[PersonAnonymiseNotFoundError] booking % does not exist', p_document_id;
    END IF;
    -- The pseudonym AND no address: a name anybody can type is no proof the erasure happened.
    IF b.person_first_name = 'Anonymised' AND b.person_last_name LIKE '#%' AND b.person_email IS NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseAlreadyDoneError] the booker of booking % was anonymised already', p_document_id;
    END IF;
    IF b.person_id IS NOT NULL THEN
        -- a linked copy stands for the person it copies: that is the one Anonymise customer accepts
        SELECT coalesce(q.account_person_id, q.id) INTO n FROM person q WHERE q.id = b.person_id;
        RAISE EXCEPTION '[PersonAnonymiseBookerHasAccountError] booking % belongs to person %: anonymise that customer instead',
                        p_document_id, coalesce(n, b.person_id);
    END IF;
    -- V0175: the seed is always anonymised in full, so it must be in an organisation the run covers.
    IF v_orgs IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = p_document_id) = ANY (v_orgs), false) THEN
        RAISE EXCEPTION '[DpoNotYourOrganizationError] booking % belongs to an organisation this run does not cover', p_document_id;
    END IF;

    -- Deferred triggers fired at COMMIT read this session temp table; a safety net, as in V0077.
    PERFORM set_transaction_parameters(true);

    -- The pseudonym of every booking anonymised in full: all of them are bookings without an account, so it never
    -- depends on document.person_id ('#' || NULL would be NULL, and jsonb_set(..., NULL) a null array element).
    v_last  := '#B' || p_document_id;
    v_email := 'anonymised-b' || p_document_id || '@invalid';
    v_address := CASE WHEN position('@' IN coalesce(trim(b.person_email), '')) > 1 THEN lower(trim(b.person_email)) END;
    -- Two words at least, as V0153: a single word matches too many people to be a match.
    v_name := CASE WHEN position(' ' IN trim(coalesce(b.person_abc_names, ''))) > 0 THEN b.person_abc_names END;
    v_names := CASE WHEN v_name IS NOT NULL THEN ARRAY[v_name] ELSE '{}'::text[] END;

    -- Every foreign key into document must be one this version handles (or knows to be an id that may stay).
    SELECT string_agg(k, ', ' ORDER BY k) INTO v_text
      FROM (SELECT format('%s.%s', c.relname, a.attname) AS k
              FROM pg_constraint con
              JOIN pg_class c ON c.oid = con.conrelid
              JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
             WHERE con.contype = 'f' AND con.confrelid = 'public.document'::regclass) fk
     WHERE k <> ALL (ARRAY[
        -- handled: rewritten or deleted below
        'history.document_id', 'mail.document_id', 'money_transfer.document_id', 'document_line.document_id',
        'conversation.document_id', 'list_item.document_id', 'enqueued_request.document_id',
        'push_subscription_recipient.document_id', 'volunteering_application.document_id',
        'document.person_carer1_document_id', 'document.person_carer2_document_id',
        -- ids only, no text about the booker
        'document.group_document_id']);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseUnknownReferenceError] foreign keys this version does not handle: % — ship a new version of anonymise_booker() first', v_text;
    END IF;

    -- ================= A CUSTOMER RECORD OF THE SAME PERSON =================
    -- Their name AND their address on a person row (or the account it hangs off), or on a booking of a customer
    -- bearing their name: that is a customer, whom Anonymise customer erases with this booking. 'person <id>' each,
    -- the form V0153's ledger scrub looks for.
    IF v_address IS NOT NULL AND v_name IS NOT NULL THEN
        v_text := public.anonymise_booker_customers(v_name, ARRAY[v_address]);
        IF v_text IS NOT NULL THEN
            RAISE EXCEPTION '[PersonAnonymiseBookerIsCustomerError] the booker of booking % has a customer record: % — anonymise that customer instead',
                            p_document_id, v_text;
        END IF;
    END IF;

    -- ================= THE ADDRESS =================
    DROP TABLE IF EXISTS ab_address_owners, ab_phones, ab_candidates, ab_addresses, ab_carer_docs, ab_docs,
                         ab_doc_addresses, ab_recipients, ab_sender_mails, ab_mails, ab_events, ab_share_lines,
                         ab_email_docs, ab_hist_docs, ab_hist, ab_namesakes, ab_linked_names, ab_applications,
                         ab_kept, ab_kept_applications;

    -- Who else holds A: listed (ids; the names travel in the endpoint's reply, never in this grid). An account
    -- without an owner row still makes A somebody else's (v_owned), with nobody to list.
    CREATE TEMP TABLE ab_address_owners (person_id integer PRIMARY KEY, via text NOT NULL);
    IF v_address IS NOT NULL THEN
        INSERT INTO ab_address_owners
        SELECT DISTINCT coalesce(q.account_person_id, q.id), 'person' FROM person q
         WHERE q.anonymised_date IS NULL AND lower(trim(q.email)) = v_address
        ON CONFLICT DO NOTHING;
        INSERT INTO ab_address_owners
        SELECT DISTINCT q.id, 'account' FROM frontend_account fa JOIN person q ON q.frontend_account_id = fa.id AND q.owner
         WHERE lower(trim(fa.username)) = v_address
        ON CONFLICT DO NOTHING;
        INSERT INTO ab_address_owners
        SELECT DISTINCT va.person_id, 'volunteering' FROM volunteering_application va
         WHERE va.person_id IS NOT NULL AND lower(trim(va.email)) = v_address
        ON CONFLICT DO NOTHING;
        INSERT INTO ab_address_owners
        SELECT DISTINCT coalesce(q.account_person_id, q.id), 'booking' FROM document d JOIN person q ON q.id = d.person_id
         WHERE lower(trim(d.person_email)) = v_address
           -- one carrying their name too is theirs, made under somebody's account: a candidate (below)
           AND NOT coalesce(d.person_abc_names = v_name, false)
        ON CONFLICT DO NOTHING;
        v_owned := EXISTS (SELECT 1 FROM ab_address_owners)
                   OR EXISTS (SELECT 1 FROM frontend_account fa WHERE lower(trim(fa.username)) = v_address);
        -- A volunteering application made without an account under A in another name is somebody else's: it is never
        -- touched, and A, which it still carries, is then never theirs.
        SELECT count(*) INTO v_other_applications FROM volunteering_application va
         WHERE va.person_id IS NULL AND lower(trim(va.email)) = v_address
           AND NOT coalesce(public.abc_names(va.first_name || ' ' || va.last_name) = v_name, false);
    END IF;

    -- ================= PHONES =================
    -- The seed's and the A+N bookings', as the last 9 digits (V0153). A phone on more than 5 OTHER bookings made
    -- without an account is a shared number (a centre's, a family's) and is set aside.
    CREATE TEMP TABLE ab_phones (phone text PRIMARY KEY, guest_bookings bigint);
    INSERT INTO ab_phones (phone)
    SELECT DISTINCT right(dg, 9) FROM (
        SELECT regexp_replace(b.person_phone, '\D', '', 'g') AS dg
        UNION
        SELECT regexp_replace(d.person_phone, '\D', '', 'g') FROM document d
         WHERE d.person_id IS NULL AND v_address IS NOT NULL AND v_name IS NOT NULL
           AND lower(trim(d.person_email)) = v_address AND d.person_abc_names = v_name
    ) t WHERE length(coalesce(dg, '')) >= 9;
    UPDATE ab_phones ph SET guest_bookings = c.n
      FROM (SELECT right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) AS phone, count(*) AS n
              FROM document d
             WHERE d.person_id IS NULL AND d.id <> p_document_id
               AND length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
               AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ab_phones)
               -- coalesce: a booking without an address must count, not vanish into NOT NULL
               AND NOT coalesce(v_address IS NOT NULL AND v_name IS NOT NULL
                                AND lower(trim(d.person_email)) = v_address AND d.person_abc_names = v_name, false)
             GROUP BY 1) c
     WHERE c.phone = ph.phone;
    DELETE FROM ab_phones WHERE guest_bookings > 5;
    GET DIAGNOSTICS v_shared_phones = ROW_COUNT;

    -- ================= CANDIDATES =================
    -- Other bookings made without an account that match them. by_email: A, under their name, or under any name
    -- while A is free (owned, A under another name is somebody else's booking, not a match).
    CREATE TEMP TABLE ab_candidates AS
    SELECT d.id, d.ref, d.event_id, d.person_id AS customer_id,
           coalesce(v_address IS NOT NULL AND lower(trim(d.person_email)) = v_address
                    AND (NOT v_owned OR d.person_abc_names = v_name), false) AS by_email,
           coalesce(d.person_abc_names = v_name, false) AS by_name,
           (length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
            AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ab_phones)) AS by_phone
      FROM document d
     WHERE d.id <> p_document_id
       AND (   (d.person_id IS NULL
                AND (   (v_address IS NOT NULL AND lower(trim(d.person_email)) = v_address)
                     OR d.person_abc_names = v_name
                     OR (length(regexp_replace(coalesce(d.person_phone, ''), '\D', '', 'g')) >= 9
                         AND right(regexp_replace(d.person_phone, '\D', '', 'g'), 9) IN (SELECT phone FROM ab_phones))))
            -- a customer's own booking made under their name and address (a parent's account booking for them, or
            -- for the same human under another name: the customer-record test below refuses a row bearing N)
            OR (d.person_id IS NOT NULL AND v_address IS NOT NULL AND v_name IS NOT NULL
                AND lower(trim(d.person_email)) = v_address AND d.person_abc_names = v_name));
    DELETE FROM ab_candidates WHERE NOT (by_email OR by_name OR by_phone);
    ALTER TABLE ab_candidates ADD COLUMN action text, ADD COLUMN carer_match boolean NOT NULL DEFAULT false;
    -- on a customer's account = perhaps them, in full if ticked (the booking stays on that account); email + name =
    -- them, in full, no tick; email alone = the same address under another name (staff tick: the
    -- address may be theirs, or a parent's typed on their booking); name = perhaps them under another address;
    -- phone alone = perhaps a shared phone.
    UPDATE ab_candidates SET action = CASE WHEN customer_id IS NOT NULL THEN 'full'
                                           WHEN by_email AND by_name THEN 'auto'
                                           WHEN by_email THEN 'contact'
                                           WHEN by_name THEN 'full'
                                           ELSE 'contact' END;
    ALTER TABLE ab_candidates ADD PRIMARY KEY (id);
    -- V0175: a candidate booking of an organisation this run does not cover is never touched and cannot be ticked;
    -- it is only counted (kept/candidate_other_organisation). And while A stays on another organisation's records (a
    -- booking, a volunteering application), it is never theirs here: the address-wide steps would leave it there.
    ALTER TABLE ab_candidates ADD COLUMN covered boolean NOT NULL DEFAULT true;
    IF v_orgs IS NOT NULL THEN
        UPDATE ab_candidates c SET covered = false WHERE NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = c.id) = ANY (v_orgs), false);
        v_kept_address := v_address IS NOT NULL
            AND (   EXISTS (SELECT 1 FROM document d WHERE lower(trim(d.person_email)) = v_address AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = d.id) = ANY (v_orgs), false))
                 OR EXISTS (SELECT 1 FROM volunteering_application va WHERE lower(trim(va.email)) = v_address AND NOT coalesce(va.organization_id = ANY (v_orgs), false)));
    END IF;

    -- A is theirs when it is free and every other booking carrying it is ticked (another name, or a customer's). Ticks only make it truer,
    -- and the candidates above do not depend on it: the same ticks on the same data give the same answer, so a
    -- commit with its preview's ticks reproduces the preview.
    v_theirs := v_address IS NOT NULL AND NOT v_owned AND v_other_applications = 0 AND NOT v_kept_address
                AND NOT EXISTS (SELECT 1 FROM ab_candidates c
                                 WHERE c.by_email AND c.action IN ('contact', 'full') AND NOT coalesce(c.id = ANY (v_ticked), false));
    v_address_state := CASE WHEN v_address IS NULL THEN 'none' WHEN v_owned THEN 'owned'
                            WHEN v_theirs THEN 'theirs' ELSE 'shared' END;
    CREATE TEMP TABLE ab_addresses (address text PRIMARY KEY);
    IF v_theirs THEN
        INSERT INTO ab_addresses VALUES (v_address);
    END IF;
    ANALYZE ab_addresses;
    -- Read only now: the address steps and the history redaction below see A exactly when it is theirs.
    SELECT coalesce(array_agg(address), '{}'::text[]) INTO v_addresses FROM ab_addresses;

    -- Carer names are typed (V0153). Narrowed first to bookings that carry one: public.abc_names() is plpgsql.
    -- Tied = the booking is theirs (the seed, A+N), or carries their address when it is theirs; otherwise a
    -- namesake may be the carer, and the booking is a candidate.
    CREATE TEMP TABLE ab_carer_docs AS
    SELECT c.id
      FROM (SELECT x.id, public.abc_names(x.n1) AS n1, public.abc_names(x.n2) AS n2
              FROM (SELECT d.id, d.person_carer1_name AS n1, d.person_carer2_name AS n2 FROM document d
                     WHERE (d.person_carer1_name IS NOT NULL OR d.person_carer2_name IS NOT NULL)
                       AND cardinality(v_names) > 0
                    OFFSET 0) x
            OFFSET 0) c
     WHERE c.n1 = ANY (v_names) OR c.n2 = ANY (v_names);
    ALTER TABLE ab_carer_docs ADD COLUMN tied boolean;
    UPDATE ab_carer_docs cd SET tied = cd.id = p_document_id
        OR EXISTS (SELECT 1 FROM ab_candidates c WHERE c.id = cd.id AND c.action = 'auto')
        OR EXISTS (SELECT 1 FROM document d WHERE d.id = cd.id AND lower(trim(d.person_email)) IN (SELECT address FROM ab_addresses));
    UPDATE ab_candidates c SET carer_match = true FROM ab_carer_docs cd WHERE cd.id = c.id AND NOT cd.tied;
    INSERT INTO ab_candidates (id, ref, event_id, by_email, by_name, by_phone, action, carer_match)
    SELECT d.id, d.ref, d.event_id, false, true, false, 'carer', true
      FROM ab_carer_docs cd JOIN document d ON d.id = cd.id
     WHERE NOT cd.tied
    ON CONFLICT (id) DO NOTHING;
    IF v_orgs IS NOT NULL THEN
        UPDATE ab_candidates c SET covered = false WHERE c.covered AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = c.id) = ANY (v_orgs), false); -- V0175
    END IF;

    -- A preview (lenient) drops a tick that is no longer a candidate; a commit refuses it.
    IF p_lenient THEN
        v_ticked := ARRAY(SELECT DISTINCT x FROM unnest(v_ticked) AS x
                           WHERE EXISTS (SELECT 1 FROM ab_candidates c WHERE c.id = x AND c.action IN ('full', 'contact', 'carer') AND c.covered)
                           ORDER BY x);
    END IF;
    SELECT string_agg(coalesce(x::text, 'NULL'), ', ') INTO v_text
      FROM unnest(v_ticked) AS x
     WHERE x IS NULL OR NOT EXISTS (SELECT 1 FROM ab_candidates c WHERE c.id = x AND c.action IN ('full', 'contact', 'carer') AND c.covered);
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseCandidateError] bookings % are not candidates of booking %', v_text, p_document_id;
    END IF;

    -- The bookings, each in one scope; cared = a carer slot of it names them (that slot is cleared).
    CREATE TEMP TABLE ab_docs (id integer PRIMARY KEY, scope text NOT NULL, cared boolean NOT NULL DEFAULT false);
    INSERT INTO ab_docs VALUES (p_document_id, 'full');
    INSERT INTO ab_docs SELECT c.id, 'full' FROM ab_candidates c
     WHERE c.action = 'auto' OR (c.action = 'full' AND c.id = ANY (v_ticked)) ON CONFLICT DO NOTHING;
    INSERT INTO ab_docs SELECT c.id, 'contact' FROM ab_candidates c
     WHERE c.action = 'contact' AND c.id = ANY (v_ticked) ON CONFLICT DO NOTHING;
    INSERT INTO ab_docs (id, scope, cared)
    SELECT cd.id, 'carer', true FROM ab_carer_docs cd WHERE cd.tied OR cd.id = ANY (v_ticked)
    ON CONFLICT (id) DO UPDATE SET cared = true;
    -- A carer slot pointing at one of their bookings by id is theirs, whatever name it shows.
    INSERT INTO ab_docs (id, scope, cared)
    SELECT d.id, 'carer', true FROM document d
     WHERE d.person_carer1_document_id = ANY (ARRAY(SELECT t.id FROM ab_docs t WHERE t.scope = 'full' AND (v_orgs IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = t.id) = ANY (v_orgs), false))))
        OR d.person_carer2_document_id = ANY (ARRAY(SELECT t.id FROM ab_docs t WHERE t.scope = 'full' AND (v_orgs IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = t.id) = ANY (v_orgs), false))))
    ON CONFLICT (id) DO UPDATE SET cared = true;
    ANALYZE ab_docs;

    -- ================= V0175: OTHER ORGANISATIONS' BOOKINGS =================
    -- A booking of an organisation this run does not cover (the event's organisation, else the booking's own; none =
    -- not covered) leaves ab_docs, which every step below reads: KEPT as it is, never blocking the run; only its id
    -- links to the bookings erased here are cut (THE ANONYMISATION, first step).
    CREATE TEMP TABLE ab_kept (id integer PRIMARY KEY, scope text NOT NULL, cared boolean NOT NULL, organization_id integer);
    IF v_orgs IS NOT NULL THEN
        INSERT INTO ab_kept SELECT t.id, t.scope, t.cared, (SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = t.id) FROM ab_docs t WHERE NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = t.id) = ANY (v_orgs), false);
        DELETE FROM ab_docs t WHERE t.id IN (SELECT k.id FROM ab_kept k);
        SELECT count(*) INTO v_kept_candidates FROM ab_candidates c
         WHERE NOT c.covered AND c.action IN ('full', 'contact', 'carer') AND NOT EXISTS (SELECT 1 FROM ab_kept k WHERE k.id = c.id);
        DELETE FROM ab_candidates c WHERE NOT c.covered;
        ANALYZE ab_docs;
    END IF;

    -- Each booking's own address, read before anything is written: it goes from that booking's own mails and
    -- history whatever A's state (those rows describe that booking).
    CREATE TEMP TABLE ab_doc_addresses (id integer NOT NULL, address text NOT NULL, PRIMARY KEY (id, address));
    INSERT INTO ab_doc_addresses
    SELECT d.id, lower(trim(d.person_email)) FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope IN ('full', 'contact')
     WHERE position('@' IN coalesce(trim(d.person_email), '')) > 1;
    -- ... and the addresses it was booked under before (changed since): its own history still shows them
    INSERT INTO ab_doc_addresses
    SELECT DISTINCT h.document_id, lower(trim(e ->> 'email'))
      FROM ab_docs t JOIN history h ON h.document_id = t.id
     CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN pg_input_is_valid(h.changes, 'jsonb')
                                                  THEN CASE WHEN jsonb_typeof(h.changes::jsonb) = 'array' THEN h.changes::jsonb END
                                             END) e
     WHERE t.scope IN ('full', 'contact') AND e ->> '$codec' = 'AddDocumentEvent'
       AND position('@' IN coalesce(trim(e ->> 'email'), '')) > 1
    ON CONFLICT DO NOTHING;
    ANALYZE ab_doc_addresses;

    -- The customer-record test again, for every booking anonymised in full under every address it carries or
    -- carried: a ticked name candidate made under the address of a customer bearing their name is that customer.
    IF v_name IS NOT NULL THEN
        v_text := public.anonymise_booker_customers(v_name, ARRAY(SELECT DISTINCT da.address FROM ab_doc_addresses da
                                                                    JOIN ab_docs t ON t.id = da.id AND t.scope = 'full'));
        IF v_text IS NOT NULL THEN
            RAISE EXCEPTION '[PersonAnonymiseBookerIsCustomerError] the booker of booking % has a customer record: % — anonymise that customer instead',
                            p_document_id, v_text;
        END IF;
    END IF;

    -- Volunteering applications made without an account that are theirs: under A in their name, or tied to one of
    -- their bookings. Deleted below; their photos (files outside the database) are listed for staff to delete.
    CREATE TEMP TABLE ab_applications AS
    SELECT va.id, nullif(va.photo, '') AS photo FROM volunteering_application va
     WHERE va.person_id IS NULL
       AND (   (v_address IS NOT NULL AND v_name IS NOT NULL AND lower(trim(va.email)) = v_address
                AND coalesce(public.abc_names(va.first_name || ' ' || va.last_name) = v_name, false))
            OR va.document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full'));
    -- V0175: theirs, but of an organisation this run does not cover: kept, and unlinked from the bookings erased here
    CREATE TEMP TABLE ab_kept_applications (id integer PRIMARY KEY, organization_id integer);
    IF v_orgs IS NOT NULL THEN
        INSERT INTO ab_kept_applications
        SELECT va.id, va.organization_id FROM volunteering_application va
         WHERE va.person_id IS NULL AND NOT coalesce(va.organization_id = ANY (v_orgs), false)
           AND (   (v_address IS NOT NULL AND v_name IS NOT NULL AND lower(trim(va.email)) = v_address
                    AND coalesce(public.abc_names(va.first_name || ' ' || va.last_name) = v_name, false))
                OR va.document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                OR va.document_id IN (SELECT k.id FROM ab_kept k WHERE k.scope = 'full'));
        DELETE FROM ab_applications a WHERE a.id IN (SELECT ka.id FROM ab_kept_applications ka);
    END IF;

    -- ================= REFUSALS (before any write) =================
    SELECT string_agg(DISTINCT coalesce(x.ref::text, 'id ' || x.document_id), ', ') INTO v_text
      FROM public.anonymise_person_blocking(ARRAY(SELECT t.id FROM ab_docs t WHERE t.scope = 'full')) x
     WHERE x.kind = 'active';
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseActiveBookingsError] the booker of booking % has bookings that are not over (refs %): cancel them, or wait until they end', p_document_id, v_text;
    END IF;

    SELECT string_agg(DISTINCT coalesce(x.ref::text, 'id ' || x.document_id), ', ') INTO v_text
      FROM public.anonymise_person_blocking(ARRAY(SELECT t.id FROM ab_docs t WHERE t.scope = 'full')) x
     WHERE x.kind = 'balance';
    IF v_text IS NOT NULL THEN
        RAISE EXCEPTION '[PersonAnonymiseBalanceError] the booker of booking % has bookings with money still owed either way (refs %): settle them first', p_document_id, v_text;
    END IF;

    SELECT count(*) INTO n FROM enqueued_request er JOIN ab_docs t ON t.id = er.document_id AND t.scope IN ('full', 'contact')
     WHERE er.execution_date IS NULL;
    IF n > 0 THEN
        RAISE EXCEPTION '[PersonAnonymiseQueuedPayloadsError] % un-executed KBS2 requests still hold bookings of the booker of booking %', n, p_document_id;
    END IF;

    -- The mails to or about them: of their bookings ('full', 'contact'), to or from their address (only when it is
    -- theirs: recipient and mail are the biggest tables after history, scanned once), and the recipients and
    -- senders of their bookings' own mails under that booking's own address.
    CREATE TEMP TABLE ab_recipients (id integer PRIMARY KEY, mail_id integer);
    CREATE TEMP TABLE ab_sender_mails (id integer PRIMARY KEY);
    IF v_theirs THEN
        INSERT INTO ab_recipients
        SELECT r.id, r.mail_id FROM recipient r WHERE lower(trim(r.email)) IN (SELECT address FROM ab_addresses);
        INSERT INTO ab_sender_mails
        SELECT m.id FROM mail m WHERE lower(trim(m.from_email)) IN (SELECT address FROM ab_addresses);
    END IF;
    INSERT INTO ab_recipients
    SELECT r.id, r.mail_id FROM ab_doc_addresses da JOIN mail m ON m.document_id = da.id JOIN recipient r ON r.mail_id = m.id
     WHERE lower(trim(r.email)) = da.address
    ON CONFLICT DO NOTHING;
    INSERT INTO ab_sender_mails
    SELECT m.id FROM ab_doc_addresses da JOIN mail m ON m.document_id = da.id
     WHERE lower(trim(m.from_email)) = da.address
    ON CONFLICT DO NOTHING;
    -- a recipient of their own bookings' mails bearing their name, under whatever address
    IF cardinality(v_names) > 0 THEN
        INSERT INTO ab_recipients
        SELECT r.id, r.mail_id FROM ab_docs t JOIN mail m ON m.document_id = t.id AND t.scope = 'full'
          JOIN recipient r ON r.mail_id = m.id
         WHERE r.name IS NOT NULL AND public.abc_names(r.name) = ANY (v_names)
        ON CONFLICT DO NOTHING;
    END IF;
    -- V0175: a mail of another organisation's booking is kept whole
    IF v_orgs IS NOT NULL THEN
        DELETE FROM ab_recipients r USING mail m
         WHERE m.id = r.mail_id AND m.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = m.document_id) = ANY (v_orgs), false);
        DELETE FROM ab_sender_mails sm USING mail m
         WHERE m.id = sm.id AND m.document_id IS NOT NULL AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = m.document_id) = ANY (v_orgs), false);
    END IF;
    ANALYZE ab_recipients;
    CREATE TEMP TABLE ab_mails (id integer PRIMARY KEY);
    INSERT INTO ab_mails
    SELECT m.id FROM mail m JOIN ab_docs t ON t.id = m.document_id AND t.scope IN ('full', 'contact')
    UNION SELECT r.mail_id FROM ab_recipients r WHERE r.mail_id IS NOT NULL
    UNION SELECT sm.id FROM ab_sender_mails sm;
    ANALYZE ab_mails;

    -- Still waiting to be sent: rewriting it would send the tag (V0153).
    SELECT count(*) INTO n FROM mail m JOIN ab_mails a ON a.id = m.id
     WHERE NOT m.transmitted AND m.date > now() - interval '30 days';
    IF n > 0 THEN
        RAISE EXCEPTION '[PersonAnonymiseQueuedMailError] % mails to or about the booker of booking % are still waiting to be sent: try again once they have gone', n, p_document_id;
    END IF;

    -- Events of their bookings, and other people's lines naming them as room owner or mate there: by id first (a
    -- mate's line pointing at one of their lines), then by name, each present name normalised once (V0153).
    CREATE TEMP TABLE ab_events AS
    SELECT DISTINCT d.event_id FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope = 'full' WHERE d.event_id IS NOT NULL;
    ANALYZE ab_events;
    CREATE TEMP TABLE ab_share_lines (id integer PRIMARY KEY, document_id integer NOT NULL, by_id boolean NOT NULL);
    INSERT INTO ab_share_lines
    SELECT dl.id, dl.document_id, true
      FROM ab_events e
      JOIN document d ON d.event_id = e.event_id
      JOIN document_line dl ON dl.document_id = d.id
     WHERE dl.share_mate_owner_document_line_id IN (SELECT ol.id FROM document_line ol JOIN ab_docs t ON t.id = ol.document_id AND t.scope = 'full')
       AND NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.id = d.id AND t.scope = 'full');
    IF cardinality(v_names) > 0 THEN
        INSERT INTO ab_share_lines
        SELECT DISTINCT c.id, c.document_id, false
          FROM (SELECT dl.id, dl.document_id,
                       ARRAY[dl.share_mate_owner_name, dl.share_owner_mate1_name, dl.share_owner_mate2_name,
                             dl.share_owner_mate3_name, dl.share_owner_mate4_name, dl.share_owner_mate5_name,
                             dl.share_owner_mate6_name, dl.share_owner_mate7_name] AS names
                  FROM ab_events e
                  JOIN document d ON d.event_id = e.event_id
                  JOIN document_line dl ON dl.document_id = d.id
                 WHERE NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.id = d.id AND t.scope = 'full')
                   AND (dl.share_mate_owner_name IS NOT NULL OR dl.share_owner_mate1_name IS NOT NULL
                        OR dl.share_owner_mate2_name IS NOT NULL OR dl.share_owner_mate3_name IS NOT NULL
                        OR dl.share_owner_mate4_name IS NOT NULL OR dl.share_owner_mate5_name IS NOT NULL
                        OR dl.share_owner_mate6_name IS NOT NULL OR dl.share_owner_mate7_name IS NOT NULL)
                OFFSET 0) c -- a fence: the name test below must not be pushed into this scan
          CROSS JOIN LATERAL unnest(c.names) AS nm(name)
         WHERE nm.name IS NOT NULL AND public.abc_names(nm.name) = ANY (v_names)
        ON CONFLICT DO NOTHING;
    END IF;
    ANALYZE ab_share_lines;

    -- Somebody else's bookings carrying their address (when it is theirs): that address goes, nothing else. Every
    -- booking without an account under it is in scope by then, so only a stray one can be here.
    CREATE TEMP TABLE ab_email_docs (id integer PRIMARY KEY);
    IF v_theirs THEN
        INSERT INTO ab_email_docs
        SELECT d.id FROM document d
         WHERE lower(trim(d.person_email)) IN (SELECT address FROM ab_addresses)
           AND NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.id = d.id AND t.scope IN ('full', 'contact'));
    END IF;

    -- The names a carer slot or a room-mate line shows where it points at one of their bookings BY ID: theirs, however
    -- spelled. The slot is cleared below; its booking's history is redacted with the same name (that booking only).
    CREATE TEMP TABLE ab_linked_names (document_id integer NOT NULL, abc text NOT NULL, PRIMARY KEY (document_id, abc));
    INSERT INTO ab_linked_names
    SELECT DISTINCT x.id, x.abc
      FROM (SELECT d.id, public.abc_names(CASE WHEN k.slot = 1 THEN d.person_carer1_name ELSE d.person_carer2_name END) AS abc
              FROM (SELECT d0.id, d0.person_carer1_name, d0.person_carer2_name, d0.person_carer1_document_id,
                           d0.person_carer2_document_id
                      FROM document d0
                     WHERE d0.person_carer1_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                        OR d0.person_carer2_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                    OFFSET 0) d
             CROSS JOIN (VALUES (1), (2)) AS k(slot)
             WHERE (k.slot = 1 AND d.person_carer1_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                    AND d.person_carer1_name IS NOT NULL)
                OR (k.slot = 2 AND d.person_carer2_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                    AND d.person_carer2_name IS NOT NULL)
            UNION
            SELECT sl.document_id, public.abc_names(dl.share_mate_owner_name)
              FROM ab_share_lines sl JOIN document_line dl ON dl.id = sl.id
             WHERE sl.by_id AND dl.share_mate_owner_name IS NOT NULL) x
     WHERE coalesce(x.abc, '') <> '';

    -- The history rows to look at, by booking id (history's document_id index), each redacted ONCE here (V0153).
    CREATE TEMP TABLE ab_hist_docs (id integer PRIMARY KEY, scope text NOT NULL, cared boolean NOT NULL);
    INSERT INTO ab_hist_docs SELECT t.id, t.scope, t.cared FROM ab_docs t;
    INSERT INTO ab_hist_docs SELECT ed.id, 'other', false FROM ab_email_docs ed ON CONFLICT DO NOTHING;
    INSERT INTO ab_hist_docs SELECT DISTINCT sl.document_id, 'other', false FROM ab_share_lines sl ON CONFLICT DO NOTHING;
    -- The other bookings of their bookings' carts: a guest signed in by a cart or magic link acts on the whole cart.
    INSERT INTO ab_hist_docs
    SELECT DISTINCT d2.id, 'other', false
      FROM ab_docs t JOIN document d ON d.id = t.id AND d.cart_id IS NOT NULL
      JOIN document d2 ON d2.cart_id = d.cart_id
     WHERE t.scope IN ('full', 'contact')
       AND (v_orgs IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = d2.id) = ANY (v_orgs), false)) -- V0175: another organisation's booking in the cart is kept
    ON CONFLICT DO NOTHING;
    ANALYZE ab_hist_docs;
    CREATE TEMP TABLE ab_hist AS
    SELECT h.id, t.scope, nm.names,
           public.anonymise_person_changes(h.changes, t.scope, nm.names, v_addresses, v_first, v_last, v_tag, t.cared) AS new_changes,
           public.anonymise_person_comment(h.comment, nm.names, t.cared) AS new_comment,
           -- who acted: a guest is 'guest:' + their address (cut to 64), an SSO login the address — their address
           -- anywhere, or one of the booking's own addresses on its own history
           CASE WHEN h.username LIKE 'guest:%'
                     AND (   lower(substr(h.username, 7)) IN (SELECT left(a.address, 58) FROM ab_addresses a)
                          OR lower(substr(h.username, 7)) IN (SELECT left(da.address, 58) FROM ab_doc_addresses da WHERE da.id = t.id))
                THEN 'guest:' || v_email
                WHEN lower(trim(h.username)) IN (SELECT a.address FROM ab_addresses a)
                  OR lower(trim(h.username)) IN (SELECT da.address FROM ab_doc_addresses da WHERE da.id = t.id)
                THEN v_email::text
                ELSE h.username END AS new_username
      FROM ab_hist_docs t
      JOIN history h ON h.document_id = t.id
      -- their name, and the names tied to them by id on that booking
      CROSS JOIN LATERAL (SELECT v_names || coalesce(ARRAY(SELECT ln.abc FROM ab_linked_names ln WHERE ln.document_id = t.id), '{}'::text[]) AS names) nm;
    ALTER TABLE ab_hist ADD PRIMARY KEY (id);
    ANALYZE ab_hist;

    -- A redaction must keep every change array valid for the replay: no element may have turned into null.
    SELECT count(*) INTO n FROM ab_hist hs JOIN history h ON h.id = hs.id
     WHERE hs.new_changes IS NOT NULL AND pg_input_is_valid(hs.new_changes, 'jsonb')
       AND jsonb_typeof(hs.new_changes::jsonb) = 'array'
       AND (SELECT count(*) FROM jsonb_array_elements(hs.new_changes::jsonb) e WHERE jsonb_typeof(e) = 'null')
         > (SELECT count(*) FROM jsonb_array_elements(CASE WHEN pg_input_is_valid(h.changes, 'jsonb') AND jsonb_typeof(h.changes::jsonb) = 'array'
                                                           THEN h.changes::jsonb ELSE '[]'::jsonb END) e
             WHERE jsonb_typeof(e) = 'null');
    IF n > 0 THEN
        RAISE EXCEPTION 'anonymise_booker would leave % history change arrays with a null element (booking %) — nothing written', n, p_document_id;
    END IF;

    -- Other customers bearing their name: listed, never touched (V0153).
    CREATE TEMP TABLE ab_namesakes AS
    SELECT x.id, x.frontend_account_id AS account_id, x.owner,
           (SELECT count(*) FROM document d WHERE d.person_id = x.id) AS bookings
      FROM person x
     WHERE cardinality(v_names) > 0 AND x.abc_names = ANY (v_names) AND NOT x.removed AND x.account_person_id IS NULL;

    -- ================= THE GRID =================
    SELECT count(*) INTO v_mail_rows_before FROM mail;
    SELECT count(*) INTO v_mail_queued_before FROM mail WHERE NOT transmitted;

    RETURN QUERY VALUES
        ('where',  'database',  current_database()::text),
        ('where',  'run_at',    now()::text),
        ('where',  'tag',       v_tag::text),
        ('booker', 'document_id',    p_document_id::text),
        ('booker', 'ref',            b.ref::text),
        ('booker', 'named',          (v_name IS NOT NULL)::text),
        ('booker', 'has_address',    (v_address IS NOT NULL)::text),
        ('booker', 'address',        v_address_state),
        ('booker', 'address_owners', (SELECT count(*) FROM ab_address_owners)::text),
        -- V0175: the organisations the run was told to cover ('all': every organisation)
        ('scope',  'organisations', coalesce(array_to_string(v_orgs, ','), 'all')),
        -- V0175: the organisations of the OTHER rows the run changes, outside the booker's own bookings
        ('organisations', 'touched', (SELECT coalesce(string_agg(x.o::text, ',' ORDER BY x.o), '')
                      FROM (SELECT DISTINCT (SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = y.id) AS o
                              FROM (SELECT ed.id FROM ab_email_docs ed
                                    UNION SELECT sl.document_id FROM ab_share_lines sl
                                    UNION SELECT hd.id FROM ab_hist_docs hd WHERE hd.scope = 'other'
                                    UNION SELECT t.id FROM ab_docs t WHERE t.scope IN ('contact', 'carer')) y) x
                     WHERE x.o IS NOT NULL)),
        -- which bookings (and how), and which applications: a commit must find the same ones, not only as many
        ('booker', 'scope_digest',   md5(coalesce((SELECT string_agg(t.id || ':' || t.scope || ':' || t.cared, ',' ORDER BY t.id) FROM ab_docs t), '')
                                         || '|' || coalesce((SELECT string_agg(c.id || ':' || c.action, ',' ORDER BY c.id) FROM ab_candidates c), '')
                                         || '|' || coalesce((SELECT string_agg(a.id::text, ',' ORDER BY a.id) FROM ab_applications a), '')
                                         -- V0175: for a run covering some organisations only, which, and what they keep
                                         || coalesce(CASE WHEN v_orgs IS NOT NULL THEN
                                                '|' || array_to_string(v_orgs, ',')
                                                || '|' || coalesce((SELECT string_agg(k.id || ':' || k.scope, ',' ORDER BY k.id) FROM ab_kept k), '')
                                                || '|' || coalesce((SELECT string_agg(ka.id::text, ',' ORDER BY ka.id) FROM ab_kept_applications ka), '')
                                            END, ''))),
        ('scope',  'guest_bookings_matched', (SELECT count(*) FROM ab_candidates WHERE action = 'auto')::text),
        ('scope',  'candidates',             (SELECT count(*) FROM ab_candidates WHERE action IN ('full', 'contact', 'carer'))::text),
        ('scope',  'candidates_ticked',      cardinality(v_ticked)::text),
        ('scope',  'address_candidates_unticked',
                   (SELECT count(*) FROM ab_candidates c
                     WHERE c.by_email AND c.action IN ('contact', 'full') AND NOT coalesce(c.id = ANY (v_ticked), false))::text),
        ('scope',  'other_applications_under_address', v_other_applications::text),
        ('scope',  'volunteering_applications', (SELECT count(*) FROM ab_applications)::text),
        ('scope',  'bookings_with_their_address', (SELECT count(*) FROM ab_email_docs)::text),
        ('scope',  'shared_phones_ignored',  v_shared_phones::text),
        ('scope',  'namesakes',              (SELECT count(*) FROM ab_namesakes)::text),
        ('scope',  'as_carer',               (SELECT count(*) FROM ab_docs WHERE cared AND scope <> 'full')::text),
        ('scope',  'named_on_room_lines',    (SELECT count(*) FROM ab_share_lines)::text),
        ('scope',  'mails',                  (SELECT count(*) FROM ab_mails)::text),
        ('scope',  'volunteering_photos_outside_db', (SELECT count(*) FROM ab_applications WHERE photo IS NOT NULL)::text),
        -- V0175: what organisations this run does not cover keep (0 and '' when it covers every organisation). The
        -- runner reads kept/document for its reply and never stores it.
        ('kept',   'document_other_organisation', (SELECT count(*) FROM ab_kept)::text),
        ('kept',   'organisations',
                   (SELECT coalesce(string_agg(x.o::text, ',' ORDER BY x.o), '')
                      FROM (SELECT k.organization_id AS o FROM ab_kept k
                            UNION SELECT ka.organization_id FROM ab_kept_applications ka) x WHERE x.o IS NOT NULL)),
        ('kept',   'document', (SELECT coalesce(string_agg(k.id::text, ',' ORDER BY k.id), '') FROM ab_kept k)),
        ('kept',   'candidate_other_organisation', v_kept_candidates::text),
        ('kept',   'volunteering_application_other_organisation', (SELECT count(*) FROM ab_kept_applications)::text);

    -- One row per candidate, for the screen: never the booking's names or addresses (V0153's shape).
    RETURN QUERY
    SELECT 'candidate'::text, c.id::text,
           jsonb_build_object('documentId', c.id, 'ref', c.ref, 'eventId', c.event_id, 'eventName', e.name,
                              'eventStart', to_char(e.start_date, 'YYYY-MM-DD'),
                              'match', concat_ws('+', CASE WHEN c.by_email THEN 'email' END,
                                                      CASE WHEN c.by_name THEN 'name' END,
                                                      CASE WHEN c.by_phone THEN 'phone' END),
                              'action', c.action, 'ticked', c.id = ANY (v_ticked), 'carerMatch', c.carer_match,
                              -- made under a customer's account (the id only; who it is travels in the reply)
                              'customerId', c.customer_id,
                              'blockedBy', CASE WHEN c.action = 'full' THEN public.anonymise_person_blockers(ARRAY[c.id]) END)::text
      FROM ab_candidates c LEFT JOIN event e ON e.id = c.event_id
     WHERE c.action IN ('full', 'contact', 'carer')
     ORDER BY e.start_date DESC NULLS LAST, c.id;

    -- The ticks this run ran with (a preview drops stale ones): the very set a commit of this preview must send.
    RETURN QUERY VALUES ('ticks', 'candidates', array_to_string(v_ticked, ',')),
                        ('ticks', 'members',    '');

    RETURN QUERY
    SELECT 'namesake'::text, x.id::text,
           jsonb_build_object('personId', x.id, 'accountId', x.account_id, 'owner', x.owner, 'bookings', x.bookings,
                              'familyNames', 0)::text
      FROM ab_namesakes x ORDER BY x.bookings DESC, x.id LIMIT 50;

    RETURN QUERY
    SELECT 'addressOwner'::text, o.person_id::text, jsonb_build_object('personId', o.person_id, 'via', o.via)::text
      FROM ab_address_owners o ORDER BY o.person_id LIMIT 20;

    -- The photo files of the applications deleted below: outside the database, so staff delete them, and once the
    -- row is gone nothing else leads to them. For the screen only (the runner never stores this step).
    RETURN QUERY
    SELECT 'photo'::text, a.id::text, a.photo::text FROM ab_applications a WHERE a.photo IS NOT NULL ORDER BY a.id;

    -- ================= THE ANONYMISATION =================

    -- ================= V0175: UNLINK WHAT OTHER ORGANISATIONS KEEP — FIRST =================
    -- A kept booking whose carer slot points at one of the bookings erased here loses that id (the name typed beside it
    -- is that organisation's record, and stays); a kept volunteering application tied to one of them likewise.
    IF v_orgs IS NOT NULL THEN
        UPDATE document d
           SET person_carer1_document_id = CASE WHEN d.person_carer1_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                                                THEN NULL ELSE d.person_carer1_document_id END,
               person_carer2_document_id = CASE WHEN d.person_carer2_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                                                THEN NULL ELSE d.person_carer2_document_id END
          FROM ab_kept k
         WHERE k.id = d.id
           AND (   d.person_carer1_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                OR d.person_carer2_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full'));
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'document_detached', n::text);

    IF v_orgs IS NOT NULL THEN
        UPDATE volunteering_application va SET document_id = NULL
          FROM ab_kept_applications ka
         WHERE ka.id = va.id AND va.document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full');
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('updated', 'volunteering_application_detached', n::text);

    -- Bookings.
    UPDATE document d
       SET person_first_name = v_first, person_last_name = v_last,
           person_name = NULL, person_lay_name = NULL,
           person_email = NULL, person_phone = NULL, person_street = NULL, person_post_code = NULL,
           person_latitude = NULL, person_longitude = NULL, person_passport = NULL,
           person_carer1_name = NULL, person_carer2_name = NULL,
           inet_ip = NULL, inet_geo_latitude = NULL, inet_geo_longitude = NULL,
           special_needs = CASE WHEN d.special_needs IS NOT NULL AND d.special_needs NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.special_needs END,
           comment       = CASE WHEN d.comment       IS NOT NULL AND d.comment       NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.comment END,
           request       = CASE WHEN d.request       IS NOT NULL AND d.request       NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE d.request END
      FROM ab_docs t WHERE t.id = d.id AND t.scope = 'full';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_full', n::text);

    UPDATE document d
       SET person_email = NULL, person_phone = NULL, person_street = NULL, person_post_code = NULL,
           person_latitude = NULL, person_longitude = NULL
      FROM ab_docs t WHERE t.id = d.id AND t.scope = 'contact';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_contact', n::text);

    UPDATE document d
       SET person_carer1_name = CASE WHEN d.person_carer1_document_id IN (SELECT t2.id FROM ab_docs t2 WHERE t2.scope = 'full')
                                       OR public.abc_names(d.person_carer1_name) = ANY (v_names)
                                     THEN NULL ELSE d.person_carer1_name END,
           person_carer2_name = CASE WHEN d.person_carer2_document_id IN (SELECT t2.id FROM ab_docs t2 WHERE t2.scope = 'full')
                                       OR public.abc_names(d.person_carer2_name) = ANY (v_names)
                                     THEN NULL ELSE d.person_carer2_name END
      FROM ab_docs t WHERE t.id = d.id AND t.cared AND t.scope <> 'full';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_carer', n::text);

    UPDATE document d SET person_email = NULL
      FROM ab_email_docs ed WHERE ed.id = d.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_their_address', n::text);

    -- The bearer key of their bookings' carts, unless the cart holds someone else's booking too (V0153).
    -- ... and its magic link, which a shared address keeps: the cart no longer opens through it
    UPDATE cart c SET uuid = gen_random_uuid()::text, magic_link_id = NULL
     WHERE c.id IN (SELECT d.cart_id FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope = 'full')
       AND NOT EXISTS (SELECT 1 FROM document o WHERE o.cart_id = c.id
                         AND NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.id = o.id AND t.scope = 'full'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'cart_uuid', n::text);
    RETURN QUERY VALUES ('kept', 'cart_shared_with_others',
        (SELECT count(DISTINCT d.cart_id) FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope = 'full'
          WHERE d.cart_id IS NOT NULL
            AND EXISTS (SELECT 1 FROM document o WHERE o.cart_id = d.cart_id
                          AND NOT EXISTS (SELECT 1 FROM ab_docs t2 WHERE t2.id = o.id AND t2.scope = 'full')))::text);

    UPDATE document_line dl SET comment = v_tag
      FROM ab_docs t
     WHERE t.id = dl.document_id AND t.scope = 'full' AND dl.comment IS NOT NULL AND dl.comment NOT LIKE v_tag_prefix || '%';
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line_comment', n::text);

    UPDATE document_line dl
       SET share_mate_owner_name = CASE WHEN sl.by_id OR public.abc_names(dl.share_mate_owner_name) = ANY (v_names)
                                        THEN NULL ELSE dl.share_mate_owner_name END,
           share_owner_mate1_name = CASE WHEN public.abc_names(dl.share_owner_mate1_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate1_name END,
           share_owner_mate2_name = CASE WHEN public.abc_names(dl.share_owner_mate2_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate2_name END,
           share_owner_mate3_name = CASE WHEN public.abc_names(dl.share_owner_mate3_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate3_name END,
           share_owner_mate4_name = CASE WHEN public.abc_names(dl.share_owner_mate4_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate4_name END,
           share_owner_mate5_name = CASE WHEN public.abc_names(dl.share_owner_mate5_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate5_name END,
           share_owner_mate6_name = CASE WHEN public.abc_names(dl.share_owner_mate6_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate6_name END,
           share_owner_mate7_name = CASE WHEN public.abc_names(dl.share_owner_mate7_name) = ANY (v_names) THEN NULL ELSE dl.share_owner_mate7_name END
      FROM ab_share_lines sl WHERE sl.id = dl.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'document_line_room_names', n::text);

    -- Magic links of their address (only when it is theirs). cart.magic_link_id has no ON DELETE action.
    IF v_theirs THEN
        UPDATE cart c SET magic_link_id = NULL
         WHERE c.magic_link_id IN (SELECT ml.id FROM magic_link ml
                                    WHERE lower(trim(ml.email)) IN (SELECT address FROM ab_addresses)
                                       OR lower(trim(ml.old_email)) IN (SELECT address FROM ab_addresses));
        DELETE FROM magic_link ml
         WHERE lower(trim(ml.email)) IN (SELECT address FROM ab_addresses)
            OR lower(trim(ml.old_email)) IN (SELECT address FROM ab_addresses);
        GET DIAGNOSTICS n = ROW_COUNT;
    ELSE
        n := 0;
    END IF;
    RETURN QUERY VALUES ('deleted', 'magic_link', n::text);

    -- Mails: subject and content of every mail to or about them, letters included; never transmitted or read.
    UPDATE mail m SET subject = v_tag, content = v_tag
      FROM ab_mails a
     WHERE a.id = m.id AND (m.subject NOT LIKE v_tag_prefix || '%' OR m.content NOT LIKE v_tag_prefix || '%');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'mail', n::text);

    UPDATE mail m SET from_name = NULL, from_email = NULL
      FROM ab_sender_mails sm WHERE sm.id = m.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'mail_sender', n::text);

    UPDATE recipient r SET name = NULL, email = v_email
      FROM ab_recipients ar WHERE ar.id = r.id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'recipient', n::text);

    -- History: request, the comment quoting a mail we tagged, changes redacted in place, the user (V0153).
    UPDATE history h
       SET request = CASE WHEN hs.scope = 'full' AND h.request IS NOT NULL AND h.request NOT LIKE v_tag_prefix || '%'
                          THEN v_tag ELSE h.request END,
           comment = CASE WHEN h.comment IS NOT NULL AND h.comment NOT LIKE v_tag_prefix || '%'
                               AND h.mail_id IN (SELECT id FROM ab_mails)
                          THEN v_tag ELSE hs.new_comment END,
           changes = hs.new_changes,
           username = hs.new_username
      FROM ab_hist hs
     WHERE hs.id = h.id
       AND (   (hs.scope = 'full' AND h.request IS NOT NULL AND h.request NOT LIKE v_tag_prefix || '%')
            OR (h.comment IS NOT NULL AND h.comment NOT LIKE v_tag_prefix || '%' AND h.mail_id IN (SELECT id FROM ab_mails))
            OR hs.new_comment IS DISTINCT FROM h.comment
            OR hs.new_username IS DISTINCT FROM h.username
            OR (h.changes IS NOT NULL AND hs.new_changes IS DISTINCT FROM h.changes
                AND (   hs.new_changes IS NULL
                     OR NOT pg_input_is_valid(h.changes, 'jsonb')
                     OR hs.new_changes::jsonb IS DISTINCT FROM h.changes::jsonb)));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'history', n::text);

    -- Payments: the comment and what the gateway sent back; the money and the reference stay.
    UPDATE money_transfer mt
       SET comment = CASE WHEN mt.comment IS NOT NULL AND mt.comment NOT LIKE v_tag_prefix || '%' THEN v_tag ELSE mt.comment END,
           gateway_response = NULL, gateway_generate_payment_url_response = NULL
      FROM ab_docs t
     WHERE t.id = mt.document_id AND t.scope IN ('full', 'contact')
       AND (   (mt.comment IS NOT NULL AND mt.comment NOT LIKE v_tag_prefix || '%')
            OR mt.gateway_response IS NOT NULL OR mt.gateway_generate_payment_url_response IS NOT NULL);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'money_transfer', n::text);

    -- Chat: every text message and the title of their bookings' conversations.
    UPDATE chat_message cm SET content = v_tag
     WHERE cm.kind = 'text' AND cm.content NOT LIKE v_tag_prefix || '%'
       AND cm.conversation_id IN (SELECT cv.id FROM conversation cv
                                   WHERE cv.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'chat_message', n::text);

    UPDATE conversation cv SET title = NULL
     WHERE cv.title IS NOT NULL AND cv.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'conversation', n::text);

    UPDATE list_item li SET comment = v_tag
     WHERE li.comment IS NOT NULL AND li.comment NOT LIKE v_tag_prefix || '%'
       AND li.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'list_item', n::text);

    UPDATE enqueued_request er
       SET request_string = v_tag,
           reply_string = CASE WHEN er.reply_string IS NOT NULL THEN v_tag END
      FROM ab_docs t
     WHERE t.id = er.document_id AND t.scope IN ('full', 'contact') AND er.execution_date IS NOT NULL
       AND (er.request_string NOT LIKE v_tag_prefix || '%' OR (er.reply_string IS NOT NULL AND er.reply_string NOT LIKE v_tag_prefix || '%'));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'enqueued_request', n::text);

    -- Deleted outright: push registrations of their address or their bookings (a device shared with others keeps
    -- theirs, V0153).
    WITH gone AS (
        DELETE FROM push_subscription_recipient psr
         WHERE (   lower(trim(psr.email)) IN (SELECT address FROM ab_addresses)
                OR psr.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full'))
           -- V0175: one made for another organisation's booking is kept
           AND (v_orgs IS NULL OR psr.document_id IS NULL OR coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = psr.document_id) = ANY (v_orgs), false))
        RETURNING psr.id, psr.subscription_id)
    DELETE FROM push_subscription ps
     WHERE ps.id IN (SELECT subscription_id FROM gone)
       AND NOT EXISTS (SELECT 1 FROM push_subscription_recipient o
                        WHERE o.subscription_id = ps.id AND o.id NOT IN (SELECT id FROM gone));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'push_subscription', n::text);

    -- Volunteering applications made without an account that are theirs (ab_applications): health,
    -- criminal-record and immigration answers, deleted with their timeline (ON DELETE CASCADE). The photo is a file
    -- outside the database: the grid counts it, staff delete it (reference doc).
    DELETE FROM volunteering_application va WHERE va.id IN (SELECT a.id FROM ab_applications a);
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'volunteering_application', n::text);

    DELETE FROM mate_invite_token mit
     WHERE mit.owner_document_line_id IN (SELECT dl.id FROM document_line dl JOIN ab_docs t ON t.id = dl.document_id AND t.scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('deleted', 'mate_invite_token', n::text);

    -- One marker per booking whose data was removed. username is never 'online': no trigger sends anything.
    INSERT INTO history (document_id, comment, username, user_person_id)
    SELECT t.id, 'Personal data removed (GDPR erasure request)', 'gdpr-erasure', public.kbs_audit_person_id()
      FROM ab_docs t WHERE t.scope IN ('full', 'contact');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('inserted', 'history_marker', n::text);

    -- The run ledger: no run may still tie these bookings to whom it listed. Runs started from one of them keep
    -- counts, keys and dates; any run's ticks touching them, and refusal texts naming one, go.
    UPDATE person_erasure_run r
       SET candidate_document_ids = '{}', member_ids = '{}', error = NULL,
           grid = CASE WHEN jsonb_typeof(r.grid) = 'array'
                       THEN (SELECT coalesce(jsonb_agg(g.e ORDER BY g.o), '[]'::jsonb)
                               FROM jsonb_array_elements(r.grid) WITH ORDINALITY AS g(e, o)
                              WHERE g.e ->> 'step' NOT IN ('candidate', 'member', 'namesake', 'addressOwner', 'ticks'))
                       ELSE r.grid END
     WHERE r.document_id IN (SELECT id FROM ab_docs WHERE scope = 'full');
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_erasure_run_own', n::text);
    UPDATE person_erasure_run r
       SET candidate_document_ids = CASE WHEN r.candidate_document_ids && ARRAY(SELECT t.id FROM ab_docs t)
                                         THEN '{}'::integer[] ELSE r.candidate_document_ids END,
           error = CASE WHEN EXISTS (SELECT 1 FROM ab_docs t WHERE t.scope = 'full' AND r.error ~ ('\mbooking ' || t.id || '\M'))
                        THEN NULL ELSE r.error END
     WHERE NOT EXISTS (SELECT 1 FROM ab_docs t WHERE t.scope = 'full' AND t.id = r.document_id)
       AND (   r.candidate_document_ids && ARRAY(SELECT t.id FROM ab_docs t)
            OR (r.error IS NOT NULL
                AND EXISTS (SELECT 1 FROM ab_docs t WHERE t.scope = 'full' AND r.error ~ ('\mbooking ' || t.id || '\M'))));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'person_erasure_run_others', n::text);

    -- ================= CHECKS (raise = the caller rolls back) =================
    SELECT count(*) INTO n FROM document d JOIN ab_docs t ON t.id = d.id AND t.scope IN ('full', 'contact')
     WHERE d.person_email IS NOT NULL OR d.person_phone IS NOT NULL OR d.person_street IS NOT NULL
        OR (t.scope = 'full' AND (d.person_first_name IS DISTINCT FROM v_first OR d.person_last_name IS NULL
                                  OR d.person_last_name IS DISTINCT FROM v_last
                                  OR d.inet_ip IS NOT NULL OR d.person_passport IS NOT NULL));
    IF n > 0 THEN bad := bad || format(E'\n  bookings still identifying: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id JOIN ab_hist_docs t ON t.id = h.document_id
     WHERE public.anonymise_person_comment(h.comment, hs.names, t.cared) IS DISTINCT FROM h.comment;
    IF n > 0 THEN bad := bad || format(E'\n  history comments still naming them: %s', n); END IF;

    -- Redacting again finds nothing more: no name of theirs (or tied to them by id), no address of theirs is left
    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id JOIN ab_hist_docs t ON t.id = h.document_id
     WHERE pg_input_is_valid(h.changes, 'jsonb')
       AND public.anonymise_person_changes(h.changes, t.scope, hs.names, v_addresses, v_first, v_last, v_tag, t.cared)::jsonb
           IS DISTINCT FROM h.changes::jsonb;
    IF n > 0 THEN bad := bad || format(E'\n  history.changes not fully redacted: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id
     WHERE lower(trim(h.username)) IN (SELECT a.address FROM ab_addresses a)
        OR (h.username LIKE 'guest:%' AND lower(substr(h.username, 7)) IN (SELECT left(a.address, 58) FROM ab_addresses a))
        OR lower(trim(h.username)) IN (SELECT da.address FROM ab_doc_addresses da WHERE da.id = h.document_id)
        OR (h.username LIKE 'guest:%' AND lower(substr(h.username, 7)) IN (SELECT left(da.address, 58) FROM ab_doc_addresses da WHERE da.id = h.document_id));
    IF n > 0 THEN bad := bad || format(E'\n  history rows still naming them as the user: %s', n); END IF;

    -- Their address anywhere in the rows looked at; a booking's own address in its own rows.
    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id
     WHERE h.changes IS NOT NULL
       AND (   EXISTS (SELECT 1 FROM ab_addresses a WHERE position(a.address IN lower(h.changes)) > 0)
            OR EXISTS (SELECT 1 FROM ab_doc_addresses da WHERE da.id = h.document_id AND position(da.address IN lower(h.changes)) > 0));
    IF n > 0 THEN bad := bad || format(E'\n  history.changes still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM history h JOIN ab_hist hs ON hs.id = h.id AND hs.scope = 'full'
     WHERE pg_input_is_valid(h.changes, 'jsonb') AND jsonb_typeof(h.changes::jsonb) = 'array'
       AND EXISTS (SELECT 1 FROM jsonb_array_elements(h.changes::jsonb) e
                    WHERE e ->> '$codec' = 'AddDocumentEvent'
                      AND (   (e ? 'firstName' AND e ->> 'firstName' IS DISTINCT FROM v_first)
                           OR (e ? 'lastName' AND coalesce(e ->> 'lastName', '') NOT LIKE '#%')));
    IF n > 0 THEN bad := bad || format(E'\n  history.changes still holding a name: %s', n); END IF;

    SELECT count(*) INTO n FROM document d WHERE lower(trim(d.person_email)) IN (SELECT address FROM ab_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  bookings still holding their address: %s', n); END IF;

    SELECT count(*) INTO n FROM document d JOIN ab_docs t ON t.id = d.id AND t.cared
     WHERE public.abc_names(d.person_carer1_name) = ANY (v_names) OR public.abc_names(d.person_carer2_name) = ANY (v_names)
        OR (d.person_carer1_name IS NOT NULL AND d.person_carer1_document_id IN (SELECT t2.id FROM ab_docs t2 WHERE t2.scope = 'full'))
        OR (d.person_carer2_name IS NOT NULL AND d.person_carer2_document_id IN (SELECT t2.id FROM ab_docs t2 WHERE t2.scope = 'full'));
    IF n > 0 THEN bad := bad || format(E'\n  bookings still naming them as carer: %s', n); END IF;

    SELECT count(*) INTO n FROM document_line dl JOIN ab_share_lines sl ON sl.id = dl.id
     WHERE (sl.by_id AND dl.share_mate_owner_name IS NOT NULL)
        OR public.abc_names(dl.share_mate_owner_name) = ANY (v_names);
    IF n > 0 THEN bad := bad || format(E'\n  room lines still naming them: %s', n); END IF;

    SELECT count(*) INTO n FROM recipient r JOIN ab_recipients ar ON ar.id = r.id
     WHERE r.name IS NOT NULL OR r.email IS DISTINCT FROM v_email;
    IF n > 0 THEN bad := bad || format(E'\n  recipients still holding an address: %s', n); END IF;

    SELECT count(*) INTO n FROM volunteering_application va WHERE va.id IN (SELECT a.id FROM ab_applications a);
    IF n > 0 THEN bad := bad || format(E'\n  volunteering applications left: %s', n); END IF;

    SELECT count(*) INTO n FROM mail m JOIN ab_sender_mails sm ON sm.id = m.id
     WHERE m.from_email IS NOT NULL OR m.from_name IS NOT NULL;
    IF n > 0 THEN bad := bad || format(E'\n  mails still naming them as the sender: %s', n); END IF;

    SELECT count(*) INTO n FROM mail m JOIN ab_mails a ON a.id = m.id
     WHERE m.subject NOT LIKE v_tag_prefix || '%' OR m.content NOT LIKE v_tag_prefix || '%';
    IF n > 0 THEN bad := bad || format(E'\n  mails not rewritten: %s', n); END IF;

    SELECT count(*) INTO n FROM magic_link ml
     WHERE lower(trim(ml.email)) IN (SELECT address FROM ab_addresses) OR lower(trim(ml.old_email)) IN (SELECT address FROM ab_addresses);
    IF n > 0 THEN bad := bad || format(E'\n  magic links left: %s', n); END IF;

    -- V0175: no row another organisation keeps still points at a booking erased here.
    IF v_orgs IS NOT NULL THEN
        SELECT count(*) INTO n FROM document d
         WHERE (   d.person_carer1_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full')
                OR d.person_carer2_document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full'))
           AND NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = d.id) = ANY (v_orgs), false);
        IF n > 0 THEN bad := bad || format(E'\n  kept bookings still linked to theirs: %s', n); END IF;

        SELECT count(*) INTO n FROM document_line dl
          JOIN document_line ol ON ol.id = dl.share_mate_owner_document_line_id
          JOIN ab_docs t ON t.id = ol.document_id AND t.scope = 'full'
         WHERE NOT coalesce((SELECT coalesce(oe.organization_id, od.organization_id) FROM document od LEFT JOIN event oe ON oe.id = od.event_id WHERE od.id = dl.document_id) = ANY (v_orgs), false);
        IF n > 0 THEN bad := bad || format(E'\n  kept room lines still linked to theirs: %s', n); END IF;

        SELECT count(*) INTO n FROM volunteering_application va
         WHERE va.document_id IN (SELECT t.id FROM ab_docs t WHERE t.scope = 'full') AND NOT coalesce(va.organization_id = ANY (v_orgs), false);
        IF n > 0 THEN bad := bad || format(E'\n  kept volunteering applications still linked to theirs: %s', n); END IF;
    END IF;

    IF bad <> '' THEN
        RAISE EXCEPTION 'Personal data survives the anonymisation of the booker of booking % [db=%]:% — ROLLBACK and investigate', p_document_id, current_database(), bad;
    END IF;

    SELECT count(*) INTO n FROM mail;
    IF n <> v_mail_rows_before THEN
        RAISE EXCEPTION 'The mail table gained or lost rows (% now, % before) — a trigger fired: ROLLBACK', n, v_mail_rows_before;
    END IF;
    SELECT count(*) INTO n FROM mail WHERE NOT transmitted;
    IF n <> v_mail_queued_before THEN
        RAISE EXCEPTION 'The unsent mail queue changed (% now, % before) — ROLLBACK', n, v_mail_queued_before;
    END IF;

    RETURN QUERY VALUES ('status', 'result', 'anonymisation OK — uncommitted; COMMIT to apply, ROLLBACK for a free dry run');
END
$anon$;

REVOKE ALL ON FUNCTION public.anonymise_booker(integer, integer[], boolean, integer[]) FROM PUBLIC;

COMMENT ON FUNCTION public.anonymise_booker(integer, integer[], boolean, integer[]) IS
'GDPR erasure of somebody who only ever booked without an account (V0163; V0175: by organisation), started from one of their bookings: removes everything that identifies them from their bookings, history, mails, payments and chat, keeping amounts, dates and gateway references. p_organization_ids = the organisations whose records it erases (NULL: every organisation; the seed booking must be in one); another organisation''s bookings are kept as they are and unlinked. Requires an explicit REPEATABLE READ transaction and SET LOCAL kbs.erase_confirm = ''ANONYMISE BOOKER <document id>''. Never commits — the caller reads the grid, then COMMIT or ROLLBACK. Docs: aggregate repo, docs/operations/gdpr-person-erasure-reference.md.';
