-- V0171: erase every date of birth. A booking keeps only a minor's age at its event (GDPR minimisation).
--
-- WHY. Pricing, carers and allocation need a child's age on the first day of the event, and of an adult only
-- that they are 18 or over. Nothing needs a date of birth. KBS3 now asks the age at the event on each booking
-- and stores it on the booking only (document.person_age, minors only); account and family-member profiles
-- store no age at all (decided with David; the plan "Replace date of birth with age at the event").
--
-- WHAT, in order (the runner wraps every pending script in one transaction; no BEGIN/COMMIT here):
--   0. Refuse while a rate still prices or matches on an age of 18 or over (rate.age1/2/3_max, rate.max_age).
--      Step 3 makes every adult's age null, and compute_rate_unit_price / rate_matches_document only reach a
--      band through a non-null person_age: such a rate would silently stop applying to adults the next time
--      their booking is priced. On 2026-11-06 the only one was rate 60700 (age2_max = 50; event starting
--      2026-10-09), probably a data-entry error. Fix the rate first; the error names it.
--      rate.min_age = 18 (143 rates) is not a band: no SQL, Java or React price calculator reads it.
--   1. history.changes: drop "age" from every AddDocumentEvent element whose age is 18 or over. Until now KBS3
--      sent adults' ages, and HistoryRecorder serialises the AddDocumentEvent it replays. Removing the key is
--      exactly what AddDocumentEventSerialCodec writes for a null age (NULL_VALUE_IGNORED), so a replay of the
--      rewritten history rebuilds the booking with no age, like the row itself after step 3. The array is
--      re-serialised through jsonb, as erase_past_event() (V0077) does for `request`. No trigger fires on an
--      UPDATE of history.changes. It runs FIRST, before steps 2 to 6 lock document and person: it is a scan of
--      the whole history table (prefiltered by LIKE before any JSON is parsed), the one step whose cost grows
--      with production's history. history is emptied on staging and kbs_local: rehearsed on a synthetic one.
--      KBS2-era history (plain text, not a JSON array) is not touched; whether it quotes ages is not measurable.
--   2. Backfill document.person_age where it is null, the event has started (start_date < today), and the date
--      of birth makes the person under 18 at the event's start: whole calendar years on the event's first day
--      (age(start_date, birthdate)), which is what the front office asks for. V0080's 365-day division
--      ((epoch(start) - epoch(birthdate)) / 31536000) overstates the age in the days before a birthday; on
--      kbs_local both give the same 187 ages. The date is read from the person after the account_person_id
--      indirection as V0080 does, falling back to the row's own date when the linked-to row has none. Ages
--      below 0 (a date of birth after the event's first day: 236 rows on kbs_local) are excluded explicitly,
--      since age() gives 0, not a negative number, for less than a year; they stay null.
--      187 bookings on kbs_local, none of them for an event still to come (4 for events started, not ended).
--      A booking whose event has not started is left alone: it could still be re-priced, and a child age on a
--      booking priced as an adult would change its balance then.
--   3. Null person_age >= 18 (1,794 bookings on kbs_local, among them typos of 352, 1982 and 1991), and set the
--      5 negative ages (-1: babies born after the event's first day, from KBS2's own date-of-birth arithmetic)
--      to 0, the same price band. Null already means "adult" everywhere: the price bands,
--      rate_matches_document, the allocation's if_child (`doc.person_age < 18`), person_isAdult. One trigger
--      reads it differently: trigger_allocation_rule_apply_rule() marks lines for re-allocation on
--      `person_age is not null` for an if_child rule. That now means "minors only", which is what if_child says.
--      Steps 2 and 3 run with two triggers on document DISABLED, re-enabled below in the same transaction:
--        * defer_compute_prices: it fires on UPDATE OF person_age and would re-price every booking touched at
--          commit. Past bookings keep the prices that were agreed and paid.
--        * record_changes_for_notification: one sys_log row per booking, which KBS2's NotificationActor turns
--          into one query and one broadcast each. Nobody is looking at these ages being cleaned.
--      Nothing else on document fires: every other UPDATE trigger names columns these statements do not set.
--   4. volunteering_application: age (V0072) from date_of_birth where it is null, in whole years at the
--      application's created_at (the declared age is a snapshot at application time), then date_of_birth =
--      NULL and trigger date_of_birth_never_stored keeps it empty. The column is NOT dropped yet: the staging
--      database is shared by every deployed branch, and the back office still on the staging branch selects
--      it. A later migration drops it, with person.birthdate, once nothing deployed reads either.
--   5. person.minor replaces the date of birth. THREE states (decided with Bruno, 2026-11-18): true = under 18,
--      false = 18 or over (no age question when booking), null = unknown (asked at the next booking, and the
--      answer is saved on the person). No age and no date is stored on the person. Added nullable, no default,
--      and filled BEFORE the dates are erased:
--        * account owners: false (an account is an adult's; the sign-up now asks it). Trigger
--          person_owner_is_adult holds that for every later write, KBS2's and the legacy screens' included.
--          On staging 34 owners had a date under 18, 21 of them in the current year: date-picker slips;
--        * a linked copy (a member standing for someone with an account of their own): false, like the account
--          holder it stands for (1,541 of the 1,543 linked copies on kbs_local point at an owner row);
--        * every other person with a date of birth: under 18 today (date > today - 18 years). The date is read
--          from the linked-to row first, as V0080 does, then from the row itself;
--        * anyone else: null. A removed person: null (nothing will ask them again).
--      On staging (dates kept by the anonymiser to the month): 676 members true, 528 false, 7,307 null.
--      Then person.birthdate = NULL everywhere (1,904 rows on kbs_local). The column itself STAYS: the KBS2
--      sign-in still deployed selects it (system.script, field group frontend_account). KBS2's dob-to-age branch
--      stops selecting it; a later migration drops the column once that branch is live. record_changes_for_notification on person writes one sys_log row per
--      person whose date is erased (accepted: about 1,900 rows once; it has no leg for minor).
--      on_details_change_update_upcoming_bookings is DISABLED for both statements: it fires on every person
--      update and would copy the person's current details onto their upcoming bookings, overwriting what a
--      booking captured, for a change that is not about those details.
--   6. What every later write is held to, whoever makes it (KBS2 forms and back office, KBS3 change sets; the
--      KBS3 server's own rule covers only the booking submit):
--      * birthdate_never_stored, BEFORE INSERT OR UPDATE OF birthdate on person: NEW.birthdate := NULL. The
--        KBS2 still deployed asks a date of birth and writes it (FrontendRequest.sync() writes any field sent;
--        RegisterPersonSlide, Person.js copyForServer). Those writes still succeed and store nothing, but the
--        date first sets person.minor (under 18: true, otherwise false; not on a removed person; always false
--        on an owner), so what an older KBS2 is told is not lost.
--      * person_owner_is_adult, BEFORE INSERT OR UPDATE OF owner, minor on person: an owner's minor is false.
--        Postgres picks UPDATE OF triggers from the statement's SET list, not from what an earlier trigger
--        changed, so an UPDATE of birthdate alone does not fire it: birthdate_never_stored keeps an owner false
--        itself.
--      * date_of_birth_never_stored, BEFORE INSERT OR UPDATE OF date_of_birth on volunteering_application:
--        NEW.date_of_birth := NULL (the KBS2 import of volunteering applications still sends one).
--      * person_age_minors_only, BEFORE INSERT OR UPDATE OF person_age on document: an age of 18 or over is
--        stored as null, a negative one as 0. It never refuses: KBS2 computes ages in the browser and wrote -1
--        for babies (5 bookings, 2019-2025) and staff typed years as ages (352, 1982, 1991); refusing would fail
--        a KBS2 booking or edit. An adult's age typed in KBS2 is simply not kept (the cosmetic loss the plan
--        accepts). The cost: a future adult age band could never match, which step 0 already rules out.
--      * trigger_document_auto_person_details() loses its date-of-birth step (dead once every date is null).
--        Its body is V0080's; kbs_local's live body was compared with V0080's before this was written.
--   7. anonymise_person() (V0153, frozen; restated by V0164 to know account_settings): `minor` is person.minor
--      instead of a reading of birthdate (null when unknown), and the person update clears it with the rest
--      (minor = NULL; an owner's comes back false through person_owner_is_adult). Only those two lines differ
--      from V0164's body. EXECUTE is revoked from PUBLIC again, as V0153 does (succession rules).
--   8. Comments on document.person_age, person.birthdate, person.minor and
--      volunteering_application.date_of_birth.
--
-- LOCKS. ALTER TABLE ... DISABLE/ENABLE TRIGGER takes SHARE ROW EXCLUSIVE on document and on person until the
-- migration commits (CREATE TRIGGER takes the same lock): booking and person writes, KBS2's included, wait for
-- the rest of the run. ADD COLUMN takes ACCESS EXCLUSIVE on person, so from step 5 to the commit even reads of
-- person wait (every sign-in): it is placed after the slow step 1, and adding a nullable column with no default
-- rewrites nothing. Rehearsed 2026-11-06 on a copy of kbs_local (294k bookings, 60k persons)
-- with a synthetic history of 3.0M rows (1.3 GB), in postgres:17 just restarted: step 1 1.9 s, step 2 0.23 s,
-- step 3 0.22 s, step 5 0.06 s; the earlier draft of this script with two others in one transaction 2.5 s.
-- Step 1 scales with the size of history (about 1.5 s per GB scanned here). This three-state version rehearsed
-- 2026-11-18 on a pre-V0155 copy of kbs_local (1,904 dates of birth, history nearly empty) after V0155-V0165:
-- 1.8 s in all; members 672 true, 1,788 false (linked copies first, then the date), 6,009 null; every owner
-- and every linked copy false; each trigger exercised (an owner's date by INSERT and by UPDATE), and
-- anonymise_person() run once (rolled back). The runner's lock_timeout is 5s: a write transaction on
-- document or person held open longer than that aborts the whole run, and the server waits on the migration.
-- Its statement_timeout (4min) bounds step 1.
--
-- NOT REVERSIBLE. The dates of birth and the adults' ages are gone from the live database. Backups and
-- snapshots keep them until they rotate out. Not measurable on staging (emptied there): whether production
-- history or mail rows quote a date of birth in free text.
--
-- ANONYMISER (aggregate repo, scripts/gdpr-anonymise/): document.person_age is classified 'kept';
-- person.birthdate keeps its rewrite (a no-op once production has run this); every reference to
-- volunteering_application.date_of_birth is guarded on the catalog, because the anonymiser runs at
-- production's schema version. The anonymiser disables user triggers on person and document while it runs.
--
-- DOMAIN MODEL. Person.minor is FIELD 2255 in DomainModelSnapshot.json and in KBS2's system.script (branch
-- dob-to-age), so the two stay in step. VolunteeringApplication.dateOfBirth and Person.birthdate stay in the
-- model while their columns exist (always empty).


-- 0. No rate band may sit at 18 or over --------------------------------------------------------------------

DO $pre$
DECLARE bad text;
BEGIN
    SELECT string_agg(format('rate %s (event %s: age1_max %s, age2_max %s, age3_max %s, max_age %s)',
                             r.id, coalesce(r.event_id::text, '-'), coalesce(r.age1_max::text, '-'),
                             coalesce(r.age2_max::text, '-'), coalesce(r.age3_max::text, '-'),
                             coalesce(r.max_age::text, '-')), '; ' ORDER BY r.id)
      INTO bad
      FROM public.rate r
     WHERE r.age1_max >= 18 OR r.age2_max >= 18 OR r.age3_max >= 18 OR r.max_age >= 18;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION '[V0171] rates with an age band of 18 or over: %', bad
              USING HINT = 'V0171 makes every adult''s person_age null, so such a band would stop applying to adults. '
                           'Correct the rate (band maximum 17 or below), then restart the server.';
    END IF;
END $pre$;


-- 1. History: no adult age in a recorded AddDocumentEvent ---------------------------------------------------

UPDATE public.history h
   SET changes = (SELECT jsonb_agg(CASE WHEN e->>'$codec' = 'AddDocumentEvent'
                                         AND jsonb_typeof(e->'age') = 'number'
                                         AND (e->>'age')::numeric >= 18
                                        THEN e - 'age'
                                        ELSE e END ORDER BY o)::text
                    FROM jsonb_array_elements(h.changes::jsonb) WITH ORDINALITY AS a(e, o))
 WHERE h.changes LIKE '%"AddDocumentEvent"%'
   AND h.changes LIKE '%"age"%'
   AND pg_input_is_valid(h.changes, 'jsonb')
   AND jsonb_typeof(h.changes::jsonb) = 'array'
   AND EXISTS (SELECT 1 FROM jsonb_array_elements(h.changes::jsonb) e
                WHERE e->>'$codec' = 'AddDocumentEvent'
                  AND jsonb_typeof(e->'age') = 'number'
                  AND (e->>'age')::numeric >= 18);


-- 2 and 3. Bookings: minors' ages from the date of birth; no adult ages -----------------------------------

ALTER TABLE public.document DISABLE TRIGGER defer_compute_prices;
ALTER TABLE public.document DISABLE TRIGGER record_changes_for_notification;

UPDATE public.document d
   SET person_age = x.age
  FROM (SELECT d2.id,
               extract(year FROM age(e.start_date, coalesce(pa.birthdate, p.birthdate)))::integer AS age
          FROM public.document d2
          JOIN public.person p ON p.id = d2.person_id
          JOIN public.person pa ON pa.id = coalesce(p.account_person_id, p.id)
          JOIN public.event e ON e.id = d2.event_id
         WHERE d2.person_age IS NULL
           AND coalesce(pa.birthdate, p.birthdate) <= e.start_date
           AND e.start_date < current_date) x
 WHERE d.id = x.id
   AND x.age BETWEEN 0 AND 17;

UPDATE public.document
   SET person_age = CASE WHEN person_age < 0 THEN 0 END
 WHERE person_age >= 18 OR person_age < 0;

ALTER TABLE public.document ENABLE TRIGGER defer_compute_prices;
ALTER TABLE public.document ENABLE TRIGGER record_changes_for_notification;


-- 4. Volunteering: keep the age, empty the date of birth ----------------------------------------------------

DO $va$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'volunteering_application'
                  AND column_name = 'date_of_birth') THEN
        EXECUTE $q$
            UPDATE public.volunteering_application
               SET age = extract(year FROM age(coalesce(created_at::date, current_date), date_of_birth))::integer
             WHERE age IS NULL
               AND date_of_birth IS NOT NULL
               AND date_of_birth <= coalesce(created_at::date, current_date)
        $q$;
    END IF;
END $va$;

DO $vb$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'volunteering_application'
                  AND column_name = 'date_of_birth') THEN
        EXECUTE 'UPDATE public.volunteering_application SET date_of_birth = NULL WHERE date_of_birth IS NOT NULL';

        EXECUTE $f$
            CREATE OR REPLACE FUNCTION public.trigger_volunteering_application_date_of_birth_never_stored()
                RETURNS trigger LANGUAGE plpgsql AS $b$
            BEGIN
                -- V0171: no date of birth is kept; the age (V0072) is. The column stays only until nothing
                -- deployed selects it.
                NEW.date_of_birth := NULL;
                RETURN NEW;
            END
            $b$
        $f$;
        EXECUTE 'DROP TRIGGER IF EXISTS date_of_birth_never_stored ON public.volunteering_application';
        EXECUTE $t$
            CREATE TRIGGER date_of_birth_never_stored
                BEFORE INSERT OR UPDATE OF date_of_birth ON public.volunteering_application
                FOR EACH ROW
                WHEN (NEW.date_of_birth IS NOT NULL)
                EXECUTE FUNCTION public.trigger_volunteering_application_date_of_birth_never_stored()
        $t$;
        EXECUTE $c$
            COMMENT ON COLUMN public.volunteering_application.date_of_birth IS
                'Always null since V0171 (GDPR minimisation): trigger date_of_birth_never_stored nulls every write; '
                'age replaces it. Kept only while deployed code still selects it; drop it then.'
        $c$;
    END IF;
END $vb$;

COMMENT ON COLUMN public.volunteering_application.age IS
    'Applicant age in whole years, as declared on the application form (V0072). V0171 emptied date_of_birth '
    'after copying its age, at the application date, into the rows that had none.';


-- 5. Persons: an "Under 18" flag instead of a date of birth -------------------------------------------------

ALTER TABLE public.person ADD COLUMN IF NOT EXISTS minor boolean;

ALTER TABLE public.person DISABLE TRIGGER on_details_change_update_upcoming_bookings;

-- Every row is given its value. It runs before the dates are erased below (the runner applies this script once);
-- on a database where the dates are already gone, a date-derived flag can only come out null or false.
UPDATE public.person q
   SET minor = CASE
                   WHEN q.owner THEN false
                   WHEN q.removed THEN NULL
                   WHEN q.account_person_id IS NOT NULL THEN false
                   WHEN x.birthdate IS NOT NULL THEN x.birthdate > current_date - interval '18 years'
                   ELSE NULL
               END
  FROM (SELECT p.id,
               coalesce((SELECT pa.birthdate FROM public.person pa WHERE pa.id = p.account_person_id), p.birthdate)
                   AS birthdate
          FROM public.person p) x
 WHERE x.id = q.id
   AND q.minor IS DISTINCT FROM CASE
                   WHEN q.owner THEN false
                   WHEN q.removed THEN NULL
                   WHEN q.account_person_id IS NOT NULL THEN false
                   WHEN x.birthdate IS NOT NULL THEN x.birthdate > current_date - interval '18 years'
                   ELSE NULL
               END;

UPDATE public.person SET birthdate = NULL WHERE birthdate IS NOT NULL;

ALTER TABLE public.person ENABLE TRIGGER on_details_change_update_upcoming_bookings;


-- 6. ... and none stored again, nor any adult age -----------------------------------------------------------

CREATE OR REPLACE FUNCTION public.trigger_person_birthdate_never_stored() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- V0171: dates of birth are not kept. An older KBS2 still sends one; the write succeeds and stores
    -- nothing. What it says first is kept as person.minor (under 18: true, otherwise false), as the step-5
    -- backfill does; not on a removed person. An owner stays false HERE, not only through
    -- person_owner_is_adult: an UPDATE that sets birthdate alone (KBS2 KMC's own-profile save sends only what
    -- changed) does not fire that trigger, whose UPDATE OF list Postgres reads from the statement, not from NEW.
    IF coalesce(NEW.owner, false) THEN
        NEW.minor := false;
    ELSIF NOT coalesce(NEW.removed, false) THEN
        NEW.minor := NEW.birthdate > current_date - interval '18 years';
    END IF;
    NEW.birthdate := NULL;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS birthdate_never_stored ON public.person;
CREATE TRIGGER birthdate_never_stored
    BEFORE INSERT OR UPDATE OF birthdate ON public.person
    FOR EACH ROW
    WHEN (NEW.birthdate IS NOT NULL)
    EXECUTE FUNCTION public.trigger_person_birthdate_never_stored();

CREATE OR REPLACE FUNCTION public.trigger_person_owner_is_adult() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- V0171: an account is an adult's (the sign-up asks it). Whoever writes the row - the KBS3 server, an older
    -- KBS2, the legacy screens - an owner's "Under 18" flag is false.
    NEW.minor := false;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS person_owner_is_adult ON public.person;
CREATE TRIGGER person_owner_is_adult
    BEFORE INSERT OR UPDATE OF owner, minor ON public.person
    FOR EACH ROW
    WHEN (NEW.owner AND NEW.minor IS DISTINCT FROM false)
    EXECUTE FUNCTION public.trigger_person_owner_is_adult();

CREATE OR REPLACE FUNCTION public.trigger_document_person_age_minors_only() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    -- V0171: a booking keeps a minor's age only. 18 or over is stored as null (= adult); a negative age (a baby
    -- born after the event's first day, as KBS2 computes it) as 0, the same price band. Never refused: the
    -- write may be a KBS2 booking.
    IF NEW.person_age < 0 THEN
        NEW.person_age := 0;
    ELSE
        NEW.person_age := NULL;
    END IF;
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS person_age_minors_only ON public.document;
CREATE TRIGGER person_age_minors_only
    BEFORE INSERT OR UPDATE OF person_age ON public.document
    FOR EACH ROW
    WHEN (NEW.person_age >= 18 OR NEW.person_age < 0)
    EXECUTE FUNCTION public.trigger_document_person_age_minors_only();

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
    -- V0171: no age from a date of birth any more (none is stored). person_age is the age declared for this
    -- booking, minors only.
    return new;
END
$$;


-- 7. anonymise_person(): `minor` from person.minor ----------------------------------------------------------
-- V0164's body (V0153's plus the account_settings key), with the two `minor` lines changed (marked V0171).

CREATE OR REPLACE FUNCTION public.anonymise_person(p_person_id integer,
                                                   p_candidate_document_ids integer[] DEFAULT '{}'::integer[],
                                                   p_member_ids integer[] DEFAULT '{}'::integer[],
                                                   p_lenient boolean DEFAULT false)
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
                         ap_member_docs;

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
           q.minor, -- V0171: the "Under 18" flag (null when unknown); no date of birth any more
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

    -- A preview (lenient) drops a tick that is no longer a candidate — the operator's ticks follow what the last
    -- preview offered, and unticking a member can take candidates away; a commit refuses it.
    IF p_lenient THEN
        v_ticked := ARRAY(SELECT DISTINCT x FROM unnest(v_ticked) AS x
                           WHERE EXISTS (SELECT 1 FROM ap_candidates c WHERE c.id = x AND c.action IN ('full', 'contact', 'carer'))
                           ORDER BY x);
    END IF;
    SELECT string_agg(x::text, ', ') INTO v_text
      FROM unnest(v_ticked) AS x
     WHERE x IS NULL OR NOT EXISTS (SELECT 1 FROM ap_candidates c WHERE c.id = x);
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
    INSERT INTO ap_share_lines
    SELECT dl.id, dl.document_id FROM document_line dl
     WHERE dl.share_mate_owner_person_id IN (SELECT id FROM ap_persons)
       AND NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = dl.document_id AND t.scope = 'full');
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
    ON CONFLICT DO NOTHING;

    -- Somebody else's bookings (a family member's, one they paid for or cared on) carrying one of their addresses:
    -- that address goes, nothing else.
    CREATE TEMP TABLE ap_email_docs AS
    SELECT d.id FROM document d
     WHERE lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses)
       AND NOT EXISTS (SELECT 1 FROM ap_docs t WHERE t.id = d.id AND t.scope IN ('full', 'contact'));
    ALTER TABLE ap_email_docs ADD PRIMARY KEY (id);
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
                       AND nullif(va.photo, '') IS NOT NULL)::text);

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

    -- person — first, so the upcoming-bookings trigger copies the anonymised details (the document step below
    -- anonymises those bookings again anyway).
    UPDATE person q
       SET first_name = v_first, last_name = '#' || q.id, name = NULL, lay_name = NULL,
           email = NULL, phone = NULL, street = NULL, post_code = NULL, latitude = NULL, longitude = NULL,
           passport = NULL, birthdate = NULL, minor = NULL, carer1_name = NULL, carer1_id = NULL, carer2_name = NULL, carer2_id = NULL,
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
       AND (   cm.person_id IN (SELECT id FROM ap_persons)
            OR cm.conversation_id IN (SELECT cv.id FROM conversation cv
                                       WHERE cv.viewer_person_id IN (SELECT id FROM ap_persons)
                                          OR cv.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full')));
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN QUERY VALUES ('updated', 'chat_message', n::text);

    UPDATE conversation cv SET title = NULL
     WHERE cv.title IS NOT NULL
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
       AND (li.person_id IN (SELECT id FROM ap_persons) OR li.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full'));
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
         WHERE psr.person_id IN (SELECT id FROM ap_persons)
            OR lower(trim(psr.email)) IN (SELECT address FROM ap_addresses)
            OR psr.document_id IN (SELECT id FROM ap_docs WHERE scope = 'full')
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
     WHERE va.person_id IN (SELECT id FROM ap_persons)
        OR (va.person_id IS NULL AND lower(trim(va.email)) IN (SELECT address FROM ap_addresses));
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

    SELECT count(*) INTO n FROM document d WHERE lower(trim(d.person_email)) IN (SELECT address FROM ap_addresses);
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

REVOKE ALL ON FUNCTION public.anonymise_person(integer, integer[], integer[], boolean) FROM PUBLIC;


-- 8. What the columns now mean -------------------------------------------------------------------------------

COMMENT ON COLUMN public.document.person_age IS
    'Age in whole years on the first day of the event, declared for this booking. Minors only: null means 18 '
    'or over (V0171 cleared every adult age; trigger person_age_minors_only nulls any later one). Read by '
    'pricing (rate age bands), allocation (if_child), carers.';

COMMENT ON COLUMN public.person.birthdate IS
    'Always null since V0171 (GDPR minimisation): trigger birthdate_never_stored nulls every write; person.minor '
    'replaces it. Kept only while a deployed KBS2 selects it on sign-in; drop it, with that trigger, once '
    'KBS2''s dob-to-age branch (which no longer selects it) is live.';

COMMENT ON COLUMN public.person.minor IS
    'Under 18 (V0171, replaces birthdate). true = under 18, false = 18 or over (no age question when booking), '
    'null = unknown (asked at the next booking; the answer is saved here). No age or date is stored. Always '
    'false on an account owner (trigger person_owner_is_adult). Set by the booker (adding a member, the answer '
    'to a booking''s age question, "Now 18 or over") and by an older KBS2''s date of birth (trigger '
    'birthdate_never_stored).';
