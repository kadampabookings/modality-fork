-- V0032: terms & conditions — centre-level defaults, and an opt-out for newcomer event types.
--
-- Three columns serving one change to how the booking form asks for T&C acceptance.
--
-- 1/2. organization.in_person_terms_label_id + online_terms_label_id
--
--   Same two fields Event already has, one level up. Today every event that wants terms must carry
--   its own labels, so an unset event shows the booker "I have read and accept the terms and
--   conditions" with nothing behind the words. A centre can now state its terms once and have every
--   event inherit them.
--
--   Resolution is PER FIELD, each falling back on its own: in-person terms resolve
--   event -> organization, and online terms resolve event -> organization, independently. An event
--   that overrides only its in-person terms therefore keeps the centre's online terms rather than
--   silently dropping them.
--
-- 3. event_type.no_terms_acceptance
--
--   Some event types are aimed at newcomers — public talks, intro classes — where putting a terms
--   checkbox in front of someone attending their first meditation class is friction that buys
--   nothing. Setting this hides the acceptance step for every event of that type.
--
--   This earns its place precisely BECAUSE of the two columns above: once a centre sets
--   organization-level terms, they cascade to all of its events, including the newcomer-facing
--   ones that previously showed no terms simply because nobody had configured any. Without an
--   explicit opt-out, adding centre terms would silently introduce a checkbox on those forms.
--
--   NOT NULL DEFAULT false, mirroring event.no_account_booking: this is a plain flag with no
--   wider scope to defer to, so "unset" and "false" mean the same thing and there is nothing for
--   a nullable column to express (unlike the item-policy flags of V0026/V0027).

ALTER TABLE organization
    ADD COLUMN IF NOT EXISTS in_person_terms_label_id integer REFERENCES label(id);

ALTER TABLE organization
    ADD COLUMN IF NOT EXISTS online_terms_label_id integer REFERENCES label(id);

ALTER TABLE event_type
    ADD COLUMN IF NOT EXISTS no_terms_acceptance boolean DEFAULT false NOT NULL;

COMMENT ON COLUMN organization.in_person_terms_label_id IS
    'Centre-wide in-person terms & conditions, used when the event sets none of its own.';

COMMENT ON COLUMN organization.online_terms_label_id IS
    'Centre-wide online terms & conditions, used when the event sets none of its own.';

COMMENT ON COLUMN event_type.no_terms_acceptance IS
    'When true, booking forms for this event type do not ask the booker to accept terms & '
    'conditions — for newcomer-facing types (public talks, intro classes). Terms configured at '
    'event or organization level are not shown.';
