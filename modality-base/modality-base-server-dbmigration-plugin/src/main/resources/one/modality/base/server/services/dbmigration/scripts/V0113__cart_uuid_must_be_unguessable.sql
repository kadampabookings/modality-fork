-- cart.uuid is a BEARER TOKEN. `loadCart` takes it and returns the whole cart -- names, emails,
-- phones, addresses, payment records -- with no authentication and no session. The uuid is the
-- sole access control, so its unguessability IS the access control.
--
-- On 2026-09-24 that turned out to be false for 13,682 production carts. They carried a uuid of
-- the form '.' + unix seconds in hex + microseconds in hex + '.':
--
--     .688659cc9c948.   ->   2025-07-27 16:54:36.641352 UTC
--
-- A clock reading, with no secret material at all. Anyone able to approximate when a booking was
-- entered could read that guest's booking: a million candidates per second of uncertainty, against
-- an endpoint with no rate limiting -- and entry times are often knowable, since a confirmation
-- email carries its own timestamp. They were minted by the KBS plugin (a third-party tool used for
-- guest-stay bookings, last used 2025-07-27), and 6,561 of them were attached to events that had
-- not yet ended. All were rotated to gen_random_uuid() the same day.
--
-- Rotation fixes the rows that existed. This fixes the ones that do not exist yet: a generator that
-- mints a weak token now fails loudly instead of being discovered six years later.
--
-- TWO SHAPES ARE PERMITTED, and both are strong:
--
--   * a v4 uuid -- 122 bits of CSPRNG. What the live generator produces (Java's UUID.randomUUID,
--     and V0109's new_cart_id() via gen_random_uuid()).
--   * 'anon-' + 40 lowercase hex -- what the GDPR anonymiser writes on non-production databases:
--     `left(anon.token('cart', id), 45)`, i.e. HMAC-SHA256 keyed with anon.key(), truncated to
--     160 bits. Keyed, so deterministic per id only to someone holding the key.
--
-- Deliberately NOT 'anon-%', which would accept 'anon-' followed by anything at all.
--
-- >>> COUPLING: this constraint and anon.token()'s output shape are now joined. If the anonymiser's
-- >>> token format changes, `scripts/gdpr-anonymise/20-anonymise.sql` starts failing on staging and
-- >>> the error will point here, nowhere near the line that caused it. Change the two together.
--
-- NOT VALID is deliberate. It enforces on every future INSERT and UPDATE, which is the whole point,
-- while skipping the scan of existing rows -- and every existing row was rotated on the day this was
-- written, so there is nothing to find. VALIDATE CONSTRAINT would seq-scan 244k rows with a regex
-- per row while this migration holds ACCESS EXCLUSIVE at boot, which is how a boot migration dies on
-- a lock timeout. Validate later, by hand, at a quiet moment, if the formal guarantee is ever wanted.

ALTER TABLE cart ADD CONSTRAINT cart_uuid_unguessable_chk
    CHECK (uuid ~ '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
        OR uuid ~ '^anon-[0-9a-f]{40}$') NOT VALID;

COMMENT ON CONSTRAINT cart_uuid_unguessable_chk ON cart IS
    'cart.uuid is the bearer token for loadCart, so it must be unguessable: a v4 uuid in production, or the anonymiser''s keyed anon- token elsewhere. 13,682 carts held a bare timestamp until 2026-09-24 (V0112).';
