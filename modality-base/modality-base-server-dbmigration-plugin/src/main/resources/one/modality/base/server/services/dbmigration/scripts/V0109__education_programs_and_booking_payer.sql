-- V0109: education programmes, and a payer on the booking.
--
-- WHY. The STTP (Special Teacher Training Programme) restarts in October 2026, and its
-- registrations are made by staff from the back office rather than through a public form. Two
-- things the schema could not express:
--
--   1. A centre keeps, per education programme (the three STTPs of NKT-IKBU, PF, PFE...), the
--      list of its CURRENT registrants -- people, not bookings -- and turns that list into
--      bookings on the event that is the programme's current series of classes (back office:
--      Education > Programs).
--   2. Somebody other than the attendee may pay: a centre's Administrative Director pays for its
--      Resident Teacher. Nothing recorded a payer -- not document, not cart, not money_transfer --
--      and a letter could only ever reach the attendee or their account owner (V0059).
--
-- WHAT.
--   * list.education_program: a programme is a `list` of that kind. A CHECK keeps it apart from
--     the three item kinds, so the Customers saved-list dialogs (which load person_list lists only,
--     and live-sync their selection into list_item rows) can never open a programme and rewrite
--     its registrants.
--   * list_item.payer_id: the registrant's standing payer in that programme (NULL = pays for
--     themselves).
--   * document.payer_id: who pays for this booking. Copied from the list item when a programme is
--     converted into bookings, editable per booking afterwards. The booking itself stays on
--     person_id, so the attendee's media access and their own letters are unchanged.
--   * The cart follows the payer. A new trigger moves a booking into the payer's cart for the
--     event whenever payer_id changes, so one payer and one event share one /pay-cart/ link that
--     pays every booking that payer covers -- and a payer is never shown the attendee's own
--     account cart. trigger_document_auto_ref's account-cart reuse now skips payer carts: without
--     that, a later booking made by the attendee's account for the same event would land in the
--     payer's cart, and therefore in the payer's payment link.
--   * letter_type.payer and a "Payment request" type: letters of such a type go to the booking's
--     payer (their own email, else their account login), or to the attendee when the booking has
--     no payer. Cart and order letters are unchanged and still go to the attendee.
--   * [payCartUrl] and [startLink:payCart]: the login-free /pay-cart/<uuid> page. The existing
--     [cartUrl] builds /<lang>/cart/<uuid>, a page scoped to the booking's own account, which would
--     send a payer to a sign-in screen.
--   * RouteToEducationPrograms: the Programs page's own operation, granted to nobody (V0108 rule:
--     access is not granted by implication).
--
-- CART TOKENS. Carts created from here on take their uuid from gen_random_uuid() (a CSPRNG, core
-- since PostgreSQL 13) instead of V0069's md5(random()). The uuid is the bearer token of a
-- login-free payment page, which this migration starts emailing to people other than the
-- attendee. Same 36-character shape, so nothing that reads it changes.
--
-- LOCKS. ADD COLUMN takes ACCESS EXCLUSIVE on document, letter_type, list and list_item, and the
-- two foreign keys take SHARE ROW EXCLUSIVE on person. All of it is catalogue-only (nullable
-- columns without default, booleans with a constant default) and held until the migration batch
-- commits. V0070 records `letter` failing to get its lock three times on prod, so every DDL
-- statement here is guarded by a catalogue lookup: the file can be applied by hand in a quiet
-- window first, and the boot then re-runs it as a no-op that takes no heavy lock.
--
-- TRIGGER SAFETY. Nothing here reads get_transaction_parameter(): plain client updates carry no
-- preamble (V0076). The payer trigger fires only when payer_id actually changes. No repricing
-- trigger lists payer_id or cart_id. Payments reference the document, never the cart
-- (money_transfer has no cart column), so moving a booking between carts carries nothing
-- financial.
--
-- BASED ON the latest bodies: trigger_document_auto_ref = V0069 (verbatim apart from the cart
-- block), trigger_mail_auto_recipient = V0059 (verbatim apart from the payer branch). NOT on
-- scripts/kbs-database-structure.sql, which predates both.
--
-- NOT HERE. PersonReferences.java (the person-merge registry) learns the two new person
-- references in the same change; without it every merge is refused. No backfill. The V0059
-- rewrite of trigger_document_generate_mails_on_booking lost the V0040/V0043/V0052 letter scoping
-- and suppression; that is a separate fix. Programme events should therefore carry no cart letter
-- until it lands: that letter is sent at booking creation, before the payer is written.

-- 1. Columns and constraints ----------------------------------------------------------------
--    Guarded by catalogue lookups rather than ADD COLUMN IF NOT EXISTS: IF NOT EXISTS still takes
--    ACCESS EXCLUSIVE before it finds the column there, while a guard takes no lock at all.
--    Foreign keys are NO ACTION, like list_item.person_id and document.person_carer1_id: SET NULL
--    would silently forget who pays (and fire the cart trigger during a person delete), whereas a
--    refusal is safe. The only hard delete of a person, the duplicate merge, re-points first.

DO $ddl$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.document'::regclass
                      AND attname = 'payer_id' AND NOT attisdropped) THEN
        ALTER TABLE public.document ADD COLUMN payer_id integer
            CONSTRAINT document_payer_id_fkey REFERENCES public.person (id);
        COMMENT ON COLUMN public.document.payer_id IS
            'Who pays for this booking when it is not its attendee (V0109). The booking stays on '
            'person_id; a change moves it into that payer''s cart for the event (trigger payer_cart), '
            'and letters of a letter_type.payer type are addressed to this person.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.letter_type'::regclass
                      AND attname = 'payer' AND NOT attisdropped) THEN
        ALTER TABLE public.letter_type ADD COLUMN payer boolean DEFAULT false NOT NULL;
        COMMENT ON COLUMN public.letter_type.payer IS
            'Letters of this type go to document.payer_id when the booking has one, else to the '
            'attendee (V0109).';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.list'::regclass
                      AND attname = 'education_program' AND NOT attisdropped) THEN
        ALTER TABLE public.list ADD COLUMN education_program boolean DEFAULT false NOT NULL;
        COMMENT ON COLUMN public.list.education_program IS
            'The list is an education programme: its items are the current registrants (persons), '
            'event_id is the programme''s current series of classes (V0109).';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.list'::regclass
                      AND conname = 'list_education_program_kind_chk') THEN
        ALTER TABLE public.list ADD CONSTRAINT list_education_program_kind_chk
            CHECK (NOT (education_program AND (person_list OR document_list OR label_list)));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = 'public.list_item'::regclass
                      AND attname = 'payer_id' AND NOT attisdropped) THEN
        ALTER TABLE public.list_item ADD COLUMN payer_id integer
            CONSTRAINT list_item_payer_id_fkey REFERENCES public.person (id);
        COMMENT ON COLUMN public.list_item.payer_id IS
            'Standing payer of this registrant in an education programme; copied onto the booking '
            'when the programme is converted into bookings (V0109). NULL = pays for themselves.';
    END IF;
END $ddl$;

-- 2. Reference rows ---------------------------------------------------------------------------

-- The letter type. Its id comes from the sequence; ord 27 places it after "Order letter (KBS3)"
-- (25). scope_override like every booking letter type: the narrowest-scoped letter wins.
INSERT INTO letter_type (name, ord, scope_override, payer)
SELECT 'Payment request', 27, true, true
 WHERE NOT EXISTS (SELECT 1 FROM letter_type t WHERE t.payer);

-- The payment-link brackets. interpret_brackets makes ONE pass ordered by ord, so a bracket that
-- another one inserts needs a higher ord than its inserter: startLink:payCart (100) inserts
-- [payCartUrl] (112) -- the same ladder as startLink:cart (100) -> cartUrl (110). The host is
-- fixed, like [bookingUrl] and [payOrderUrl]: /pay-cart/ exists only in the KBS3 front office, so
-- the event's own front-end host (legacy events) would produce a dead link.
INSERT INTO bracket_pattern (pattern, lang, replacement, condition, ord, description)
SELECT v.pattern, NULL, v.replacement, NULL, v.ord, v.description
  FROM (VALUES
        ('startLink:payCart',
         '''<a href="[payCartUrl]">''',
         100,
         'Opening link to the login-free payment page of the booking''s cart (V0109). Close with [endLink].'),
        ('payCartUrl',
         'select ''https://kadampabookings.org/pay-cart/'' || c.uuid from cart c where c.id=d.cart_id',
         112,
         'Login-free payment page of the booking''s cart: pays every booking in that cart, no sign-in (V0109).')
       ) AS v(pattern, replacement, ord, description)
 WHERE NOT EXISTS (SELECT 1 FROM bracket_pattern bp WHERE bp.pattern = v.pattern);

-- The Programs page's own operation. grant_route is the exact path the router checks, with no
-- trailing wildcard. public and guest are false: the authorization service hands those to every
-- caller, anonymous ones included. Nobody holds it until somebody ticks it in the role editor.
INSERT INTO operation (operation_code, name, grant_route, backend, frontend, public, guest)
SELECT 'RouteToEducationPrograms', 'Education programs', '/education-programs', true, false, false, false
 WHERE NOT EXISTS (SELECT 1 FROM operation o WHERE o.operation_code = 'RouteToEducationPrograms');

-- 3. Cart helpers -------------------------------------------------------------------------------
--    VOLATILE (the default) on purpose: each call takes a fresh snapshot, which is what lets the
--    advisory lock below serialise two conversions for the same payer under READ COMMITTED.
--    The document itself is excluded with IS DISTINCT FROM, never <>: a NULL id must not turn the
--    predicate NULL and silently disable reuse.

CREATE OR REPLACE FUNCTION public.new_cart_id() RETURNS integer
    LANGUAGE sql
AS $$
    INSERT INTO cart (uuid) VALUES (gen_random_uuid()::text) RETURNING id
$$;

COMMENT ON FUNCTION public.new_cart_id() IS
    'Creates a cart with a CSPRNG uuid (the bearer token of the login-free /pay-cart/ page) and returns its id (V0109).';

CREATE OR REPLACE FUNCTION public.document_payer_cart_id(p_event_id integer, p_payer_id integer, p_document_id integer)
    RETURNS integer
    LANGUAGE sql
AS $$
    SELECT d.cart_id
      FROM document d
     WHERE d.event_id = p_event_id
       AND d.payer_id = p_payer_id
       AND NOT d.cancelled
       AND d.cart_id IS NOT NULL
       AND d.id IS DISTINCT FROM p_document_id
     ORDER BY d.cart_id
     LIMIT 1
$$;

COMMENT ON FUNCTION public.document_payer_cart_id(integer, integer, integer) IS
    'The cart another live booking of that event paid by that payer already uses, or NULL (V0109).';

-- V0069's account rule, now never joining a payer's cart, and never answering with the booking's
-- own cart when a booking leaves its payer.
CREATE OR REPLACE FUNCTION public.document_account_cart_id(p_event_id integer, p_person_id integer, p_document_id integer)
    RETURNS integer
    LANGUAGE sql
AS $$
    SELECT d.cart_id
      FROM document d
               JOIN person p ON p.id = d.person_id
               JOIN person np ON np.id = p_person_id
     WHERE d.event_id = p_event_id
       AND p.frontend_account_id = np.frontend_account_id
       AND d.cart_id IS NOT NULL
       AND d.payer_id IS NULL
       AND d.id IS DISTINCT FROM p_document_id
     ORDER BY d.cart_id
     LIMIT 1
$$;

COMMENT ON FUNCTION public.document_account_cart_id(integer, integer, integer) IS
    'V0069''s account cart rule: the cart of another booking of that event on the same frontend '
    'account, payer carts excluded, or NULL (V0109).';

-- 4. trigger_document_auto_ref -- V0069 body, cart block changed --------------------------------

CREATE OR REPLACE FUNCTION public.trigger_document_auto_ref() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    -- Setting the booking ref
    select into new.ref count(*) + 1 from document where event_id = new.event_id;
    -- Setting the booking organization if not set (to be the same as event)
    if (new.organization_id is null) then
        select into new.organization_id e.organization_id from event e where e.id = new.event_id;
    end if;
    -- Switching the booking to online for KBS2 events that don't allow in-person bookings (ex: 2026 KMCF French Festival Online)
    if (select not kbs3 and not in_person_allowed from event where id = new.event_id) then
        new.in_person = false;
    end if;
    -- Setting the booking activity if not set (to be the same as event)
    if (new.activity_id is null) then
        select into new.activity_id e.activity_id from event e where e.id = new.event_id;
    end if;
    -- Booking rules the back office is trusted to override (frontend requests only)
    if (get_transaction_parameter() = false /* ie frontend request */) then
        -- Checking if the event is on hold
        if (select state = 'ON_HOLD' from event where id = new.event_id) then
            raise exception 'EVENT_ON_HOLD';
        end if;
        -- Checking if the event is closed (new frontoffice bookings are no longer accepted;
        -- backoffice bookings skip this whole block and remain possible)
        if (select state = 'CLOSED' from event where id = new.event_id) then
            raise exception 'EVENT_CLOSED';
        end if;
        -- Now raising an exception on double bookings (unless it is a tester)
        if (exists(select *
                   from document d
                            join person p on p.id = d.person_id
                            join frontend_account fa on fa.id = p.frontend_account_id
                   where d.event_id = new.event_id
                     and d.person_id = new.person_id
                     and not d.cancelled
                     and not fa.tester)) then
            raise exception 'DOUBLEBOOKING';
        end if;
    end if;
    -- Setting the booking cart if not set — for ALL bookings, front-office and back-office
    -- alike, because the document service dereferences document.cart when building its result.
    if (new.cart_id is null) then
        if (new.payer_id is not null) then
            -- V0109: a booking with a payer shares that payer's cart for the event. Inert today
            -- (AddDocumentEvent cannot carry a payer, which is set by a later update and handled
            -- by trigger_document_payer_cart); kept so an insert that does carry one follows the
            -- same rule.
            if (new.person_id is null) then
                raise exception 'PAYER_NEEDS_PERSON';
            end if;
            if (new.event_id is not null) then
                perform pg_advisory_xact_lock(new.event_id, new.payer_id);
            end if;
            new.cart_id := public.document_payer_cart_id(new.event_id, new.payer_id, new.id);
        else
            -- Reusing the cart of a booking made for that event with the same frontend account
            -- (V0109: never a payer's cart).
            new.cart_id := public.document_account_cart_id(new.event_id, new.person_id, new.id);
        end if;
        -- Otherwise creating a new booking cart (V0109: CSPRNG token)
        if (new.cart_id is null) then
            new.cart_id := public.new_cart_id();
        end if;
    end if;
    return new;
END
$$;

-- 5. The cart follows the payer -------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.trigger_document_payer_cart() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_actor integer;
BEGIN
    RAISE NOTICE 'Entering trigger %.%(%)', TG_RELNAME, TG_NAME, NEW.id;
    IF NEW.payer_id IS NOT NULL THEN
        -- A guest booking reaches its booking only through the magic link of its original cart
        -- (ServerDocumentServiceProvider), so it is never moved into somebody else's cart.
        IF NEW.person_id IS NULL THEN
            RAISE EXCEPTION 'PAYER_NEEDS_PERSON';
        END IF;
        -- Two conversions for the same payer running in parallel must end in ONE cart. The
        -- two-int key space does not overlap the migration runner's one-bigint lock.
        IF NEW.event_id IS NOT NULL THEN
            PERFORM pg_advisory_xact_lock(NEW.event_id, NEW.payer_id);
        END IF;
        NEW.cart_id := public.document_payer_cart_id(NEW.event_id, NEW.payer_id, NEW.id);
    ELSE
        -- Payer removed: back to V0069's account rule, which ignores payer carts and this booking.
        NEW.cart_id := public.document_account_cart_id(NEW.event_id, NEW.person_id, NEW.id);
    END IF;
    IF NEW.cart_id IS NULL THEN
        NEW.cart_id := public.new_cart_id();
    END IF;
    -- Audit, ids only: history.comment is KEPT by erase_past_event (V0077), so no names go in it.
    -- The actor is recorded like HistoryRecorder does (user_person_id for a person, a system name
    -- otherwise); kbs_audit_person_id() never raises (V0068), and the lookup drops a stale id
    -- rather than breaking history_user_person_id_fkey. username is never 'online', so the
    -- history triggers keyed on it do not fire.
    v_actor := (SELECT p.id FROM person p WHERE p.id = public.kbs_audit_person_id());
    INSERT INTO history (document_id, username, user_person_id, comment)
    VALUES (NEW.id,
            CASE WHEN v_actor IS NULL THEN 'system' END,
            v_actor,
            CASE WHEN OLD.payer_id IS NULL THEN 'Payer set to person #' || NEW.payer_id
                 WHEN NEW.payer_id IS NULL THEN 'Payer removed (was person #' || OLD.payer_id || ')'
                 ELSE 'Payer changed from person #' || OLD.payer_id || ' to #' || NEW.payer_id
            END
            || '; cart #' || coalesce(OLD.cart_id::text, 'none') || ' -> #' || NEW.cart_id);
    RETURN NEW;
END
$$;

-- BEFORE UPDATE OF payer_id, and only when the value really changes: an UPDATE that lists the
-- column with its current value (a change-set rewriting the whole row) moves nothing. It runs
-- before AFTER UPDATE send_letter, so a single statement setting payer_id and
-- trigger_send_letter_id composes the letter against the payer's cart and addresses the payer.
DO $ddl$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid = 'public.document'::regclass
                      AND tgname = 'payer_cart' AND NOT tgisinternal) THEN
        CREATE TRIGGER payer_cart BEFORE UPDATE OF payer_id ON public.document FOR EACH ROW
            WHEN (new.payer_id IS DISTINCT FROM old.payer_id)
            EXECUTE FUNCTION public.trigger_document_payer_cart();
    END IF;
END $ddl$;

-- 6. trigger_mail_auto_recipient -- V0059 body, payer branch added --------------------------------

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
    recipient_person_id recipient.person_id%type;
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
                    recipient_person_id := doc.person_id;
                    if (type.payer and doc.payer_id is not null) then
                        -- V0109: payment requests go to whoever pays for the booking: their own
                        -- address, else their account login. Read live, never captured. No
                        -- fallback to the attendee: a payer's cart can hold other attendees'
                        -- bookings, so an unreachable payer discards the mail (below) instead.
                        recipient_person_id := doc.payer_id;
                        email := (select coalesce(nullif(p.email, ''), nullif(fa.username, ''))
                                    from person p
                                             left join frontend_account fa on fa.id = p.frontend_account_id
                                   where p.id = doc.payer_id);
                    elsif (type.order) then
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
                        INSERT INTO recipient (mail_id, person_id, email) values (NEW.id, recipient_person_id, email);
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

-- 7. Ownership of the functions this migration created -------------------------------------------
--    Migrations run as the server's connect role, so anything they CREATE is owned by that role
--    instead of the schema owner every other role reaches the schema through (see V0064, V0070).
--    Realign, warning rather than failing: a tidy-up must never abort the boot chain.

DO $$
DECLARE
    schema_owner name;
    fn record;
BEGIN
    SELECT pg_get_userbyid(c.relowner) INTO schema_owner FROM pg_class c WHERE c.oid = 'public.document'::regclass;
    FOR fn IN
        SELECT p.oid::regprocedure AS signature
          FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public'
           AND p.proname IN ('new_cart_id', 'document_payer_cart_id', 'document_account_cart_id',
                             'trigger_document_payer_cart')
           AND pg_get_userbyid(p.proowner) <> schema_owner
    LOOP
        BEGIN
            EXECUTE format('ALTER FUNCTION %s OWNER TO %I', fn.signature, schema_owner);
        EXCEPTION WHEN OTHERS THEN
            RAISE WARNING 'V0109: could not realign the owner of % (%)', fn.signature, SQLERRM;
        END;
    END LOOP;
END $$;
