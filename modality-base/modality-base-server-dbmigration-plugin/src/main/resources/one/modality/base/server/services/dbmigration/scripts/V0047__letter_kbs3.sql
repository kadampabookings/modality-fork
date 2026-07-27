-- V0047: letter.kbs3 — the KBS2→KBS3 mail-transmission handover flag.
--
-- Both mailers drain the same shared mail table, so during the migration each mail
-- must have exactly ONE transmitter. This column is that partition:
--   * KBS3's MailerJob (drainScope=flagged) drains mails whose letter is flagged,
--     plus letterless mails (magic links etc. — KBS3-originated anyway);
--   * KBS2's MailerActor drains the exact complement
--     (letter is not null and not letter..kbs3).
-- Letters migrate one at a time with a plain UPDATE — per-centre-per-letter
-- granularity, verifiable individually, instantly reversible, no redeploys and no
-- double-send window. Once every letter is flagged: KBS2 mailerEnabled=false,
-- KBS3 drainScope=all, and a later cleanup migration drops this column.

ALTER TABLE letter
    ADD COLUMN IF NOT EXISTS kbs3 boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN letter.kbs3 IS
    'Mails of this letter are transmitted by the KBS3 mailer (KBS2→KBS3 handover partition; default false = KBS2 transmits)';
