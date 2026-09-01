-- V0080: capture the resolved email on the booking, not NULL.
--
-- Person.email = NULL on an account member means "reach this person via their
-- account owner" (V0059, and the aggregate repo's
-- docs/member-email-owner-fallback-plan.md). V0059 resolved that at READ time,
-- inside trigger_mail_auto_recipient(), which fixed letters but left
-- document.person_email itself NULL. Every other consumer then had to know
-- about the convention, and most did not:
--
--   * BO registration list — the Email column renders blank
--     (kbs3-react backoffice/src/features/registration/components/booking-columns.tsx)
--   * BO search — `lower(person_email) like $1`, so these bookings cannot be
--     found by email AT ALL (use-registration-bookings.ts)
--   * KBS2 back office — grid conditions in system.script gate on
--     `person_email != null`, so these bookings drop out of them
--   * DUPLICATE cart letters — trigger_document_generate_mails_on_booking
--     skips a cart letter when the same cart already has one for the same
--     recipient, keyed on `r.email = NEW.person_email OR r.person_id =
--     NEW.person_id`. With person_email NULL the email leg is never true and
--     the person_id leg compares two DIFFERENT members, so every email-less
--     member in a cart generates another cart letter — all resolving to the
--     same account mailbox. A family of three currently sends the owner three
--     identical cart letters. Verified against the predicate: NULL => no
--     match; resolved address => match. V0080 makes this cohort behave like
--     families that already share a copied address, which dedup correctly.
--   * KBS2 back office — PersonalDetailsView shows blank, and several grid
--     conditions in system.script gate on `person_email != null`
--   * KBS2 payment redirect — the gateway form carried the literal string
--     "undefined", which SystemPay rejects with error 15 and the transaction
--     is definitively lost (kbs2 278986f1 works around this client-side)
--
-- Display sites can be patched one by one; SEARCH cannot — it needs the value
-- to BE there. And the document.person_* columns are conceptually a
-- CAPTURE of the personal data as it stood when the booking was submitted, so
-- the resolution belongs in that capture, exactly like every other
-- person_* column the same trigger denormalizes.
--
-- person.email STAYS NULL. That is the sentinel meaning "no mailbox of their
-- own", and it is what makes the address re-resolve when the owner changes it.
-- What this migration changes is only the document MIRROR of it.
--
-- Resolution:
--     coalesce(nullif(person.email,''), nullif(frontend_account.username,''))
-- NOTE it is NOT identical to V0059's read-time resolution, and deliberately so.
-- V0059 resolves from the booking person's OWN account
-- (person p where p.id = doc.person_id). The triggers below resolve from the
-- person AFTER the existing account_person_id indirection, because that is what
-- every other person_* column in the same trigger already does — including
-- person_email today. accountPerson is a CROSS-account link (1438 prod rows
-- point at a person on another account; see
-- scripts/clear-pointless-account-person-links.sql), so for a linked booking
-- both the captured name/address and now the captured mailbox belong to the
-- linked-to account. That is pre-existing behaviour for every other field, but
-- it means a stored value can differ from what V0059 would have resolved, and
-- V0059 prefers the stored one.
-- frontend_account.username is the authoritative account mailbox (it drifts
-- from the owner Person's email — 571 prod owner rows differ) and is kept
-- current by trigger_person_on_email_change_update_frontend_username. nullif()
-- also lets EMPTY-STRING emails fall through instead of being captured as ''.
--
-- Three parts, all DDL:
--   1. trigger_document_auto_person_details()  — resolve at booking capture.
--   2. trigger_person_on_details_change_update_upcoming_bookings() — resolve
--      when the person is later edited, or part 1's work is undone.
--   3. NEW on_username_change_update_upcoming_bookings on frontend_account —
--      neither existing trigger fires on an ACCOUNT change, so without this
--      the captured address goes stale the moment an owner changes their login.
--
-- The BACKFILL of the 84 documents currently holding NULL is deliberately NOT
-- here — it is scripts/v0080-backfill-document-person-email.sql, run by hand.
-- Every pending migration runs in ONE transaction under SET LOCAL
-- lock_timeout='5s' / statement_timeout='4min', so a single document row locked
-- by a live booking write would abort the whole batch and strand /health on
-- "WAITING: db-migration" (this has bitten us before). A data backfill must not
-- be able to fail a deploy, and it also wants to be verified on its own.
--
-- V0059's read-time coalesce is deliberately LEFT IN PLACE: it still covers
-- guest bookings (no person row) and costs nothing once the stored value is
-- correct.
--
-- KNOWN COST — email-keyed matching gets AMBIGUOUS, not just non-empty.
-- Several members of one account now share the owner's address, so anything
-- keying on person_email alone can conflate different people:
--   * volunteering KBS2 import (use-kbs2-import.ts buildImportedEmails) marks
--     `alreadyImported` from a Set of addresses — import a mother and her two
--     children are silently skipped. Today NULL makes them all import instead.
--     That consumer needs a person_id/documentId leg; it is NOT fixed here.
--   * the unauthenticated guest booking-access form
--     (ModalityGuestAuthenticationGateway, `lower(d.person_email) = lower($1)`)
--     mails a cart magic link for every matching document. Bookings that
--     matched nobody now match the account username — intended under "reach
--     them via the account owner", but it is an auth surface and it applies
--     retroactively once the backfill runs.
-- This ambiguity already exists for every family that used the old /members
-- dialog (it REQUIRED an email, so parents used their own); V0080 widens it.
--
-- Bodies below = live definition + the changes above. VERIFIED against PROD
-- 2026-09-01 (select prosrc from pg_proc where proname in
-- ('trigger_document_auto_person_details',
--  'trigger_person_on_details_change_update_upcoming_bookings')): both bodies
-- matched byte-for-byte, so CREATE OR REPLACE reverts nothing. Re-check if this
-- migration sits unapplied for long — the two functions have never been touched
-- by a migration, so an out-of-band edit would leave no trace to compare against.
--
-- Trigger safety for the document UPDATEs in part 3 (and in the backfill
-- script): every letter and pricing trigger on document is
-- AFTER UPDATE OF <specific columns> and person_email is in none of those
-- lists, and generate_mails_on_booking is AFTER INSERT only — so no letter is
-- generated and nothing is repriced. What DOES fire is
-- record_changes_for_notification_document(), which has an explicit
-- person_email leg writing one sys_log row per changed booking; KBS2's
-- NotificationActor then issues one query per row and broadcasts to connected
-- back-office clients. At 84 rows that is negligible, but it is why the
-- backfill runs at a chosen moment rather than during a rolling deploy.


-- 1. Capture the resolved address when the booking is submitted -------------

CREATE OR REPLACE FUNCTION public.trigger_document_auto_person_details() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
declare
    p person;
BEGIN
    RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    select into p * from person where id = new.person_id;
    if (p.account_person_id is not null) then
        select into p * from person where id = p.account_person_id;
    end if;
    new.person_organization_id = p.organization_id;
    new.person_language_id = p.language_id;
    new.person_country_name = p.country_name;
    new.person_country_geonameid = p.country_geonameid;
    new.person_country_id = p.country_id;
    new.person_post_code = p.post_code;
    new.person_city_name = p.city_name;
    new.person_city_geonameid = p.city_geonameid;
    new.person_city_latitude = p.city_latitude;
    new.person_city_longitude = p.city_longitude;
    new.person_city_timezone = p.city_timezone;
    new.person_street = p.street;
    new.person_latitude = p.latitude;
    new.person_longitude = p.longitude;
    new.person_name = p.name;
    new.person_first_name = p.first_name;
    new.person_last_name = p.last_name;
    new.person_lay_name = p.lay_name;
    new.person_male = p.male;
    new.person_ordained = p.ordained;
    -- V0080: a member with no email of their own is reached via the account
    -- mailbox; capture that address rather than NULL.
    new.person_email = coalesce(
        nullif(p.email, ''),
        (select nullif(fa.username, '') from frontend_account fa where fa.id = p.frontend_account_id));
    new.person_phone = p.phone;
    if (p.birthdate is not null and new.person_age is null) then
        select (extract(epoch from e.start_date)::bigint - extract(epoch from p.birthdate)::bigint) / 31536000
        into new.person_age
        from event e
        where e.id = new.event_id;
    end if;
    return new;
END
$$;


-- 2. Keep it resolved when the person is edited ------------------------------

CREATE OR REPLACE FUNCTION public.trigger_person_on_details_change_update_upcoming_bookings() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN

    update document du
    set person_first_name      = pa.first_name,
        person_last_name       = pa.last_name,
        person_male            = pa.male,
        person_ordained        = pa.ordained,
        person_lay_name        = pa.lay_name,
        person_phone           = pa.phone,
        -- V0080: same resolution as the capture trigger, so re-syncing an
        -- upcoming booking cannot put the NULL back.
        person_email           = coalesce(
                                     nullif(pa.email, ''),
                                     (select nullif(fa.username, '') from frontend_account fa where fa.id = pa.frontend_account_id)),
        person_organization_id = pa.organization_id,
        person_street          = pa.street,
        person_post_code       = pa.post_code,
        person_city_name       = pa.city_name,
        person_country_id      = pa.country_id
    from document d
             join person p on p.id = d.person_id
             join person pa on pa.id = coalesce(p.account_person_id, p.id)
             join event e on e.id = d.event_id
    where du.id = d.id
      and d.person_id = NEW.id
      and e.end_date > now();

    return NEW;
END
$$;


-- 3. Keep it resolved when the ACCOUNT mailbox changes ------------------------
-- Chain: an owner changing their email updates person.email, which
-- trigger_person_on_email_change_update_frontend_username copies onto
-- frontend_account.username, which now reaches the members' bookings here.
-- Part 2 only covers d.person_id = NEW.id, i.e. the owner's OWN bookings.

CREATE OR REPLACE FUNCTION public.trigger_frontend_account_on_username_change_update_bookings() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN

    update document du
    set person_email = NEW.username
    from document d
             join person p on p.id = d.person_id
             join person pa on pa.id = coalesce(p.account_person_id, p.id)
             join event e on e.id = d.event_id
    where du.id = d.id
      and pa.frontend_account_id = NEW.id
      -- only bookings relying on the fallback; a person with their own email
      -- is unaffected by an account login change
      and nullif(pa.email, '') is null
      and e.end_date > now()
      -- no-op when the captured address is already right: avoids a row lock,
      -- a dead tuple and two index writes per booking on every username edit
      and du.person_email is distinct from NEW.username;

    return NEW;
END
$$;

DROP TRIGGER IF EXISTS on_username_change_update_upcoming_bookings ON public.frontend_account;

-- The WHEN guard requires a non-blank NEW.username on purpose: a bulk write
-- that NULLs a username (a known hazard of
-- trigger_person_on_email_change_update_frontend_username firing from the
-- lowest-id person of an account) must not blank the captured addresses. The
-- bookings then keep the last known good address.
CREATE TRIGGER on_username_change_update_upcoming_bookings
    AFTER UPDATE OF username ON public.frontend_account
    FOR EACH ROW
    WHEN (new.username IS DISTINCT FROM old.username AND nullif(new.username, '') IS NOT NULL)
    EXECUTE FUNCTION public.trigger_frontend_account_on_username_change_update_bookings();

