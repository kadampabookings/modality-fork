-- V0120: an invite token can be revoked, so a link sent to the wrong person can be stopped.
--
-- WHY. Until invitations were emailed, a wrong recipient took a deliberate act: the booker copied the
-- link and pasted it somewhere. Now one mistyped character in an address field posts a working bearer
-- token for a free bed to a stranger, and nothing takes it back. V0119's emailed tokens are capped at a
-- fortnight, which bounds the damage and does not end it: whoever holds the link can take the bed for
-- those two weeks, and the booker can only watch. "Ask the registration team" is not a remedy a booking
-- form should rely on.
--
-- WHAT IT IS NOT. Not a deletion. The row stays, because it is also the record of what was sent -- which
-- V0119 added and the cart now shows -- and a booker who revoked an invitation is precisely the booker
-- who wants to see that it happened. Clearing the record would answer the wrong question.
--
-- WHAT A REVOKED LINK SAYS. The same as a lapsed one: `resolve` stops finding it, and `isExpired` counts
-- it, so its holder is told the invitation no longer works and nothing more. That is deliberate. The
-- holder may be a stranger who received it by mistake, and the fact that somebody deliberately withdrew
-- it is the booker's business, not theirs.
--
-- NULL means live, which is the default for every row that exists -- so nothing changes for any token
-- already issued.
--
-- LOCKING. ADD COLUMN with no default is metadata only, and this table is read only while resolving an
-- invite. The timeout is kept for the reason V0116 gives: a lock wait is a better failure than a queue
-- of them.

DO $do$
BEGIN
    SET LOCAL lock_timeout = '3s';

    ALTER TABLE public.mate_invite_token
        ADD COLUMN IF NOT EXISTS revoked_date timestamp with time zone;

    COMMENT ON COLUMN public.mate_invite_token.revoked_date IS
        'When the room booker stopped this link working; NULL while it is live. The row is kept rather '
        'than deleted: it is also the record that an invitation was sent. A revoked link reads to its '
        'holder exactly like an expired one -- see MateInviteTokenStore.resolve.';
END
$do$;
