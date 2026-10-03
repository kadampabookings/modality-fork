-- V0119: an invite token records that it was emailed, to which roommate slot, and in which message.
--
-- WHY. A room booker who says "they'll book themselves" is about to have an invitation sent on their
-- behalf, and today that leaves no trace at all. mate_invite_token holds the room line, the event, who
-- created it, when it expires and whether it was used -- nothing about whether anybody was written to.
-- So the cart cannot tell the booker what was sent, nothing can chase or re-send it, and the booking's
-- own letter could not describe it even if the timing allowed (it does not: generate_mails_on_booking is
-- AFTER INSERT ON document, so that letter is composed inside the submit that creates the booking,
-- before any invitation exists). Every surface that could reassure the booker is blocked on this.
--
-- WHAT IS DELIBERATELY NOT STORED HERE: the roommate's name, and their email address.
--
-- Both are already in the database exactly once, and writing them again would mean two places to erase,
-- two to keep in step, and a second copy for an erasure request to miss.
--   * The NAME is on the room line this token already points at: share_owner_mate1_name ..
--     share_owner_mate7_name, classified 'anonymised' in scripts/gdpr-anonymise/10-anon-helpers.sql with
--     the reason that a room-mate name is often nowhere else in the database. invited_mate_slot says
--     WHICH of those seven, so the name is read, never copied.
--   * The ADDRESS is in recipient.email, written once when the mail is enqueued and classified
--     'truncated' there. invite_mail_id reaches it through the message it was actually sent in, which
--     also makes the delivery state readable -- whether it was transmitted, and any error.
-- The result is that this migration introduces no new class of personal data, which for a third party
-- who never signed in, may never book, and did not choose to be entered into anybody's booking form, is
-- the whole point.
--
-- NULL invited_date IS MEANINGFUL, not missing. A token minted for the cart's "copy roommate's invite
-- link" was handed over by the booker themselves and was never emailed to anyone. One column therefore
-- tells the two routes apart: NULL = the booker took the link, set = we wrote to somebody.
--
-- LIFETIME comes from the row it hangs on. owner_document_line_id is ON DELETE CASCADE, so erasing or
-- deleting the booking takes this record with it; and the token already carries expires_date (event end
-- + 2 days). Nothing deletes expired tokens today, which is now a privacy matter as well as
-- housekeeping -- a sweep belongs with the invitation work, not in this file, which adds columns only.
--
-- THE INDEX. The cart's question is "what invitations exist for this booking's room?", i.e. a lookup by
-- owner_document_line_id, and the table carries no index on it today -- only the primary key and the two
-- on token_hash, because every read so far has arrived holding a token. Small table, so this is cheap
-- now and will not be later.
--
-- LOCKING. ADD COLUMN with no default is metadata only, and this table is read only while resolving an
-- invite, so it is nothing like money_account in V0116. The timeout is kept all the same, for the same
-- reason that file gives: a lock wait is a better failure than a queue of them.

DO $do$
BEGIN
    SET LOCAL lock_timeout = '3s';

    ALTER TABLE public.mate_invite_token
        ADD COLUMN IF NOT EXISTS invited_mate_slot smallint,
        ADD COLUMN IF NOT EXISTS invited_date timestamp with time zone,
        ADD COLUMN IF NOT EXISTS invite_mail_id integer;

    -- Seven, because that is how many name slots a room line has (share_owner_mate1_name ..
    -- share_owner_mate7_name). A slot outside that range names nobody, so it is a bug worth refusing at
    -- the point it is written rather than a row the cart silently cannot resolve.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'mate_invite_token_invited_mate_slot_check') THEN
        ALTER TABLE public.mate_invite_token
            ADD CONSTRAINT mate_invite_token_invited_mate_slot_check
            CHECK (invited_mate_slot IS NULL OR (invited_mate_slot BETWEEN 1 AND 7));
    END IF;

    -- SET NULL, not CASCADE: if the message is ever purged, the fact that an invitation was sent, and to
    -- which slot, is still true and still worth showing. Losing the record with the mail would be the
    -- booking quietly forgetting it invited somebody.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'mate_invite_token_invite_mail_id_fkey') THEN
        ALTER TABLE public.mate_invite_token
            ADD CONSTRAINT mate_invite_token_invite_mail_id_fkey
            FOREIGN KEY (invite_mail_id) REFERENCES public.mail(id) ON DELETE SET NULL;
    END IF;

    CREATE INDEX IF NOT EXISTS mate_invite_token_owner_document_line_idx
        ON public.mate_invite_token (owner_document_line_id);

    COMMENT ON COLUMN public.mate_invite_token.invited_mate_slot IS
        'Which of the owner room line''s seven roommate name slots (share_owner_mate1_name ..) this '
        'invitation was for. The name itself is read from that line and never copied here.';
    COMMENT ON COLUMN public.mate_invite_token.invited_date IS
        'When the invitation was emailed. NULL means the token was minted and handed over by the booker '
        'themselves (the cart''s copy-link), not written to anybody.';
    COMMENT ON COLUMN public.mate_invite_token.invite_mail_id IS
        'The message it was sent in, which is also where the recipient address lives (recipient.email). '
        'Kept out of this table on purpose: see the header of V0119.';
END
$do$;
