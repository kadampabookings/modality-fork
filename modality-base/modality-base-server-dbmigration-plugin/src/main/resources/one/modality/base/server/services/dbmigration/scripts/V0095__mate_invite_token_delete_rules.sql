-- Room-share invite tokens must not block the deletion of what they point at.
--
-- V0087 created mate_invite_token with four foreign keys and no delete rule on any of them, so
-- Postgres refuses to delete a document_line an invite was ever minted for:
--
--   ERROR: update or delete on table "document_line" violates foreign key constraint
--          "mate_invite_token_owner_document_line_id_fkey" on table "mate_invite_token"
--
-- which is what deleting the test bookings of a TESTING event runs into. The same block applies in
-- production to any path that deletes an accommodation line — a booker changing the room they had
-- already invited someone to would hit it too, so this is a live bug and not only a housekeeping one.
--
-- The rules below are the ones this schema already uses elsewhere:
--   * a child row that is meaningless without its parent cascades
--     (attendance_document_line_id_fkey, document_line_document_id_fkey);
--   * a nullable pointer to something that may go away is cleared
--     (document_event_id_fkey, document_person_id_fkey, person_frontend_account_id_fkey).
--
-- So: the owned line and the event are what the token IS (both NOT NULL — it cannot outlive them),
-- while the consuming line and the minting account are audit pointers the row survives without.
-- Clearing creator_account_id rather than blocking also keeps an account erasure from being held up
-- by a spent invite token.

-- Drop whatever V0087's foreign keys were named, rather than trusting the generated names: a
-- missed one would silently keep blocking, since adding the right rule beside it changes nothing.
DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'mate_invite_token'::regclass
          AND contype = 'f'
    LOOP
        EXECUTE format('ALTER TABLE mate_invite_token DROP CONSTRAINT %I', fk.conname);
    END LOOP;
END $$;

ALTER TABLE mate_invite_token
    ADD CONSTRAINT mate_invite_token_owner_document_line_id_fkey
        FOREIGN KEY (owner_document_line_id) REFERENCES document_line (id) ON DELETE CASCADE,
    ADD CONSTRAINT mate_invite_token_event_id_fkey
        FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE,
    ADD CONSTRAINT mate_invite_token_used_by_document_line_id_fkey
        FOREIGN KEY (used_by_document_line_id) REFERENCES document_line (id) ON DELETE SET NULL,
    ADD CONSTRAINT mate_invite_token_creator_account_id_fkey
        FOREIGN KEY (creator_account_id) REFERENCES frontend_account (id) ON DELETE SET NULL;
