-- V0059: resolve booking-mail recipients for members without their own email.
--
-- Person.email = NULL on an account member now officially means "reach this
-- person via their account owner" (see the aggregate repo's
-- docs/member-email-owner-fallback-plan.md). The FO booking wizard's inline
-- "Another person" flow (kbs3-react d973c6914) creates such members, and they
-- also exist historically. Before this migration, any letter for a booking
-- whose document.person_email was NULL died on the recipient.email NOT NULL
-- constraint — aborting the enclosing transaction (BO booking confirm, the
-- system-letters batch, magic-link sends) — while cart/order letters were
-- silently never generated (trigger WHEN clause).
--
-- Changes, all in trigger_mail_auto_recipient() / generate_mails_on_booking:
--
-- 1. General fallback: the recipient address becomes
--        coalesce(nullif(person_email,''), frontend_account.username)
--    frontend_account.username is the authoritative account mailbox (it can
--    drift from the owner Person's email — 571 prod owner rows differ) and is
--    kept current by trigger_person_on_email_change_update_frontend_username.
--    nullif() also lets EMPTY-STRING emails (reachable via BO edits and the FO
--    returning-guest form) fall through instead of producing a mail that later
--    errors inside JavaMail on InternetAddress('').
--
-- 2. "order" letters keep their deliberate ACCOUNT-FIRST addressing from the
--    stage-1 guest-access arc (the order letter carries booking access — the
--    account login mailbox owns the booking), now written as the symmetric
--    coalesce so a missing account still falls back to person_email (guests).
--
-- 3. Magic-link mails resolve from magic_link.email FIRST: previously a mail
--    carrying both magic_link_id and document_id took the document branch and
--    could deliver the link to a different address than the one it was
--    requested for.
--
-- 4. If no address resolves at all (guest booking with no email), the
--    recipient insert is SKIPPED with a WARNING instead of raising 23502 and
--    rolling back the caller.
--
-- 5. generate_mails_on_booking now also fires for documents with a person but
--    no person_email (reachable via the account fallback), and its cart-letter
--    dedup gains a person_id leg — the r.email = NEW.person_email predicate is
--    never true when person_email is NULL, which re-triggered the cart letter
--    on every write.
--
-- Body below = live definition (staging dump, 2026-08-08, identical to
-- scripts/kbs-database-structure.sql) + the changes above.

CREATE OR REPLACE FUNCTION public.trigger_mail_auto_recipient() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
    ma mail_account%ROWTYPE;
    doc document%ROWTYPE;
    mlink magic_link%ROWTYPE;
    type letter_type%ROWTYPE;
    email recipient.email%type;
    account_email recipient.email%type;
BEGIN
    RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    IF (NOT EXISTS(select * from recipient where mail_id=NEW.id)) THEN
        IF (NEW.out) THEN -- outgoing email => sent to the person
            -- Magic-link mails first: the link's own address is authoritative even when
            -- the mail also carries a document_id (the document branch used to shadow it).
            select into mlink * from magic_link where id=NEW.magic_link_id;
            IF FOUND AND nullif(mlink.email, '') IS NOT NULL THEN
                INSERT INTO recipient (mail_id, email) values (NEW.id, mlink.email);
            ELSE
                select into doc * from document d where d.id=NEW.document_id;
                IF FOUND THEN -- email sent for a specific booking
                    -- The account login mailbox (NULL for guest bookings — no person row / no account).
                    account_email := (select fa.username from person p join frontend_account fa on fa.id=p.frontend_account_id where p.id=doc.person_id);
                    select into type lt.* from letter l join letter_type lt on lt.id=l.type_id where l.id=NEW.letter_id;
                    if (type.order) then
                        -- Order letters carry booking access: address the account that owns the
                        -- booking (deliberate since the stage-1 guest-access arc); guests fall
                        -- back to the email typed on the booking form.
                        email := coalesce(nullif(account_email, ''), nullif(doc.person_email, ''));
                    else
                        -- All other letters target the attendee: their own address first, the
                        -- account owner's when they have none (person_email NULL = "reach them
                        -- via their account owner").
                        email := coalesce(nullif(doc.person_email, ''), nullif(account_email, ''));
                    end if;
                    IF email IS NULL THEN
                        -- Truly unreachable (guest with no usable email): discard the mail
                        -- instead of violating recipient.email NOT NULL and rolling back the
                        -- enclosing transaction (booking confirm, system-letter batch, ...).
                        -- The mail row is deleted (not left recipient-less) so the mailers
                        -- never meet an address-less mail — KBS2's JavaMailSender indexes
                        -- getRecipients(TO)[0] unguarded. Same self-delete pattern as the
                        -- existing discard trigger on mail.
                        RAISE WARNING 'auto_recipient: no address resolvable for mail % (document %), mail discarded', NEW.id, doc.id;
                        DELETE FROM mail WHERE id = NEW.id;
                    ELSE
                        INSERT INTO recipient (mail_id, person_id, email) values (NEW.id, doc.person_id, email);
                    END IF;
                END IF;
            END IF;
        ELSE -- incoming email => sent to the mail_account
            SELECT INTO ma * FROM mail_account where id=NEW.account_id LIMIT 1;
            IF FOUND THEN
                INSERT INTO recipient (mail_id, name, email) values (NEW.id, ma.name, ma.email);
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END $$;

-- Cart-letter dedup: add the person_id leg (NULL-safe for members without an
-- email; guests have no person_id but always a person_email).
CREATE OR REPLACE FUNCTION public.trigger_document_generate_mails_on_booking() RETURNS trigger
    LANGUAGE plpgsql
    AS $_$
DECLARE
    lt letter%ROWTYPE;
    ml mail%ROWTYPE;
BEGIN
    RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    FOR lt IN EXECUTE 'select * from letter l join letter_type t on l.type_id=t.id, document d where d.id=$1 and (t.cart or t."order") and (l.event_id=d.event_id or l.event_id is null and l.organization_id=d.organization_id) and case when d.in_person then l.applicable_to_in_person else l.applicable_to_online end order by case when l.event_id=d.event_id then 0 else l.id end' USING NEW.id
        LOOP
            SELECT INTO ml * FROM mail m JOIN document d ON m.document_id=d.id join letter_type t on lt.type_id=t.id WHERE letter_id=lt.id AND (t.order and d.id=NEW.id OR t.cart and d.cart_id=NEW.cart_id AND EXISTS(SELECT * FROM recipient r WHERE r.mail_id=m.id AND (r.email=NEW.person_email OR r.person_id=NEW.person_id)));
            IF NOT FOUND THEN
                update document set trigger_send_letter_id=lt.id where id=NEW.id;
            END IF;
        END LOOP;
    RETURN NEW;
END $_$;

-- Fire the booking letters for members reachable via the account fallback too
-- (person_id set, person_email NULL). A person without any account resolves to
-- no address in auto_recipient and is skipped there with a warning.
DROP TRIGGER IF EXISTS generate_mails_on_booking ON public.document;
CREATE TRIGGER generate_mails_on_booking AFTER INSERT ON public.document FOR EACH ROW WHEN (((new.event_id IS NOT NULL) AND ((new.person_email IS NOT NULL) OR (new.person_id IS NOT NULL)))) EXECUTE FUNCTION public.trigger_document_generate_mails_on_booking();
