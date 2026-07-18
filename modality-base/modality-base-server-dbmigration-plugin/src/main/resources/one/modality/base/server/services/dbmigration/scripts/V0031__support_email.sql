-- V0031: event_type.support_email + organization.support_email — the "who do I email for help?"
-- address shown to bookers on the booking forms.
--
-- Replaces a hardcoded address. The London public talks form carried
-- "please contact talks@meditateinlondon.org for support" as an i18n string duplicated across all
-- seven language files, so (a) it was data living in translations, where a translator could
-- plausibly rewrite it, and (b) every other centre's bookers were told to email London. Making it a
-- field fixes both: the address becomes data, and it is per-centre.
--
-- Two levels, resolved event type first, then organization — the same shape as
-- registration_mail_account (V0017). The existing address argues for both: talks@… is a PROGRAMME
-- inbox, not a centre one, so an event type needs to be able to override the centre's general
-- address while the rest of that centre's events keep it.
--
-- Distinct from registration_mail_account despite the overlap, and deliberately not reusing it.
-- That is a mail_account FK — the identity we send letters FROM, with SMTP credentials behind it.
-- This is a display address bookers write TO. They are often the same string today, but conflating
-- them means a centre cannot change where replies go without also changing what it sends as.
--
-- Also deliberately NOT reusing organization.email, which already exists as a column. It is a
-- legacy KBS2 field that no KBS3 code declares or reads, so its contents are unverified across
-- every organization in the database; adopting it would publish whatever is in there to bookers at
-- every centre in one deploy. A new field is null everywhere until someone sets it — which is the
-- point: the note self-hides when unresolved, so a centre that has not configured one shows no
-- support line rather than a wrong address.

ALTER TABLE event_type
    ADD COLUMN IF NOT EXISTS support_email varchar(255);

ALTER TABLE organization
    ADD COLUMN IF NOT EXISTS support_email varchar(255);

COMMENT ON COLUMN event_type.support_email IS
    'Address shown to bookers on this event type''s booking forms for help with their booking. '
    'Overrides organization.support_email. NULL = defer to the organization.';

COMMENT ON COLUMN organization.support_email IS
    'Address shown to bookers on this organization''s booking forms for help with their booking, '
    'unless the event type overrides it. NULL = show no support note at all.';
