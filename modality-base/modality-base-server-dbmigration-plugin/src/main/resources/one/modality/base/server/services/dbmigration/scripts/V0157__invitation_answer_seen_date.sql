-- Records when the person who SENT an invitation saw its answer, so the front office can show a dot
-- on "My profile" for an answer they have not seen yet ("Kiran accepted", "Kiran declined").
--
-- WHY on the invitation, per row: each answer is seen or not on its own, and the same on every
-- device. A per-browser marker would show an old answer as new on the next phone.
--
-- What counts as an answer is what the server writes, never the browser (V0157 goes with the
-- decline/cancel endpoints and ClientWriteSecrets closing `invitation` to clients):
--   accepted : pending = false, accepted = true,  usage_date set   (PersonLinkRules, unchanged)
--   declined : pending = false, accepted = false, usage_date set   (the new decline endpoint)
--   cancelled: pending = false, accepted = false, usage_date NULL  (the new cancel endpoint)
-- Declines made before this release went through a client write that set no date, so they read as
-- cancellations: 16 closed-unaccepted rows on staging (2026-11-07), 1 with a date. Nothing is made up
-- for them.
--
-- The backfill marks every answer that already exists as seen, at the time it was given: without it,
-- every member who ever invited anybody would get a dot on the first page load after the deploy, for
-- answers months old. A NULL therefore means "answered after V0157 and not seen yet" (or no answer).
--
-- Written by markInvitationAnswerSeen only, for the inviter, once the answer has been on screen for a
-- couple of seconds — what "seen" means is the front office's judgement, recorded here.
--
-- Anonymiser: classified ('invitation','answer_seen_date','kept','audit date stamp …') in
-- scripts/gdpr-anonymise/10-anon-helpers.sql, like the other date stamps.

alter table invitation add column answer_seen_date timestamp with time zone;

update invitation set answer_seen_date = usage_date
 where usage_date is not null and pending = false;

comment on column invitation.answer_seen_date is
    'When the inviter saw the answer (accepted or declined) on screen. NULL = not seen yet, or no answer. Backfilled to usage_date by V0157 for answers given before it.';
