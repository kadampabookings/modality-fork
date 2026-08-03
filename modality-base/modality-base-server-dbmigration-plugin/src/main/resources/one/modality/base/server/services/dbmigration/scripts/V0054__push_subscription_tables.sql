-- V0054: bring the Web Push subscription tables under migration control.
--
-- push_subscription / push_subscription_recipient were created by hand on staging and
-- production (plus two loose scripts in the aggregate repo's scripts/ folder for the
-- vapid_public_key and unsubscribed_at columns), so fresh databases built from the
-- migration chain don't have them at all. This migration makes every environment
-- converge on the same shape, idempotently:
--
--   * CREATE TABLE IF NOT EXISTS with the full current shape (no-op on staging/prod);
--   * ADD COLUMN IF NOT EXISTS for the two hand-applied columns (no-op where the
--     loose scripts already ran);
--   * the send-path indexes, including the two new ones introduced for the letter
--     web-push variant (V0055):
--       - a UNIQUE index on push_subscription.endpoint. The PushSubscription entity
--         Javadoc and the FO subscribe hook (use-push-subscription.ts) both assume
--         one row per endpoint is enforced server-side, but the constraint never
--         actually existed — concurrent subscribes could duplicate rows and every
--         duplicate would receive every push twice. Existing duplicates are folded
--         into the newest row first.
--       - a partial (context, lower(email)) index backing live_push_endpoints()
--         (V0055), which matches recipients by context + case-insensitive email.
--
-- The production backfill of vapid_public_key (stamping the literal prod key on
-- pre-column rows) remains a MANUAL step — see the loose script
-- scripts/migrate-push-subscription-vapid-key.sql in the aggregate repo — because it
-- needs the environment's actual key, which a bundled migration cannot know.

-- 1. Tables (full current shape; skipped entirely where they already exist) ---------

CREATE SEQUENCE IF NOT EXISTS public.push_subscription_id_seq
    AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE IF NOT EXISTS public.push_subscription (
    id integer NOT NULL DEFAULT nextval('public.push_subscription_id_seq'),
    endpoint text NOT NULL,
    p256dh_key character varying(128) NOT NULL,
    auth_key character varying(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    last_seen_at timestamp with time zone DEFAULT now() NOT NULL,
    lang character varying(8),
    timezone character varying(64),
    display_mode character varying(16),
    app_timestamp character varying(32),
    user_agent text,
    vapid_public_key text,
    CONSTRAINT push_subscription_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE public.push_subscription_id_seq OWNED BY public.push_subscription.id;

CREATE SEQUENCE IF NOT EXISTS public.push_subscription_recipient_id_seq
    AS integer START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1;

CREATE TABLE IF NOT EXISTS public.push_subscription_recipient (
    id integer NOT NULL DEFAULT nextval('public.push_subscription_recipient_id_seq'),
    subscription_id integer NOT NULL REFERENCES public.push_subscription(id) ON DELETE CASCADE,
    context character varying(32) NOT NULL,
    person_id integer REFERENCES public.person(id) ON DELETE CASCADE,
    email character varying(255),
    document_id integer REFERENCES public.document(id) ON DELETE CASCADE,
    event_id integer REFERENCES public.event(id) ON DELETE CASCADE,
    organization_id integer REFERENCES public.organization(id) ON DELETE CASCADE,
    user_agent text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    unsubscribed_at timestamp without time zone,
    CONSTRAINT push_subscription_recipient_pkey PRIMARY KEY (id)
);

ALTER SEQUENCE public.push_subscription_recipient_id_seq OWNED BY public.push_subscription_recipient.id;

-- 2. Hand-applied columns (no-op where the loose scripts already ran) ---------------

ALTER TABLE public.push_subscription
    ADD COLUMN IF NOT EXISTS vapid_public_key text;

ALTER TABLE public.push_subscription_recipient
    ADD COLUMN IF NOT EXISTS unsubscribed_at timestamp without time zone;

-- 3. Existing send-path indexes (as on staging/prod today) --------------------------

CREATE INDEX IF NOT EXISTS push_subscription_recipient_email_idx
    ON public.push_subscription_recipient USING btree (email) WHERE (email IS NOT NULL);
CREATE INDEX IF NOT EXISTS push_subscription_recipient_event_id_idx
    ON public.push_subscription_recipient USING btree (event_id) WHERE (event_id IS NOT NULL);
CREATE INDEX IF NOT EXISTS push_subscription_recipient_organization_id_idx
    ON public.push_subscription_recipient USING btree (organization_id) WHERE (organization_id IS NOT NULL);
CREATE INDEX IF NOT EXISTS push_subscription_recipient_person_id_idx
    ON public.push_subscription_recipient USING btree (person_id) WHERE (person_id IS NOT NULL);
CREATE INDEX IF NOT EXISTS push_subscription_recipient_subscription_id_idx
    ON public.push_subscription_recipient USING btree (subscription_id);

-- 4. One row per endpoint (fold duplicates into the newest row, then enforce) -------

-- Recipients of a doomed older duplicate are re-pointed at the surviving row rather
-- than lost with the ON DELETE CASCADE (same device, so same delivery target).
UPDATE public.push_subscription_recipient psr
   SET subscription_id = keeper.id
  FROM public.push_subscription doomed
  JOIN public.push_subscription keeper
    ON keeper.endpoint = doomed.endpoint AND keeper.id > doomed.id
   AND NOT EXISTS (SELECT 1 FROM public.push_subscription newer
                    WHERE newer.endpoint = doomed.endpoint AND newer.id > keeper.id)
 WHERE psr.subscription_id = doomed.id;

DELETE FROM public.push_subscription doomed
 USING public.push_subscription newer
 WHERE doomed.endpoint = newer.endpoint AND doomed.id < newer.id;

CREATE UNIQUE INDEX IF NOT EXISTS push_subscription_endpoint_uniq
    ON public.push_subscription (endpoint);

-- 5. Send-path index for live_push_endpoints() (V0055) ------------------------------

CREATE INDEX IF NOT EXISTS push_subscription_recipient_context_live_idx
    ON public.push_subscription_recipient (context, lower(email))
    WHERE unsubscribed_at IS NULL;
