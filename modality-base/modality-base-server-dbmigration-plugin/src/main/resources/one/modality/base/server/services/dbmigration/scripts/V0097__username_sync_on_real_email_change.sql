-- V0097: sync the account login only when the owner's email actually CHANGES.
--
-- trigger_person_on_email_change_update_frontend_username (V0062) runs AFTER UPDATE OF email, and a
-- trigger with a column list fires whenever that column is in the SET list — not only when its value
-- changes. So merely RE-WRITING an owner's unchanged email copied it onto frontend_account.username
-- again. Three consequences, the first of which is the reason for this migration:
--
--  * TAKEOVER BY RE-SAVE. A client could insert a person flagged owner, with its own address, into
--    somebody else's account (or move one there), then write that same address back: no value
--    "changed", yet the trigger rewrote the victim's login to the attacker's address, and "forgot
--    password" did the rest. A rule that judges the VALUE written cannot see this; only the trigger
--    can decline to act on a non-change.
--  * DRIFT SILENTLY RESET. Any save that sends every field (the back office's customer form did)
--    re-copied a drifted owner's person.email onto their login, changing how they sign in without
--    anyone meaning to.
--  * A write on every such save, and the frontend_account trigger chain behind it (V0080), for nothing.
--
-- What still syncs, unchanged: an owner's email changed to a DIFFERENT value — which is how both KBS2
-- booking forms let an owner change their login ("modifying your email address will also modify your
-- login"). KBS3 clients cannot make that change directly any more (the client write guard refuses it:
-- OwnerLoginWritePolicy); the KBS3 email-change flow writes the username itself.
--
-- Idempotent: CREATE OR REPLACE of the function the existing trigger calls; the trigger is untouched.
CREATE OR REPLACE FUNCTION public.trigger_person_on_email_change_update_frontend_username() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.owner IS TRUE AND NEW.email IS NOT NULL AND NEW.email IS DISTINCT FROM OLD.email THEN
        UPDATE frontend_account SET username = NEW.email WHERE id = NEW.frontend_account_id;
    END IF;
    RETURN NEW;
END $$;
