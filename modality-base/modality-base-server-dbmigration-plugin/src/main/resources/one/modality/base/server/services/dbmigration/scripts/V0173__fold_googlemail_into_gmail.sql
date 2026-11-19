-- V0173: an address written as @googlemail.com is stored as @gmail.com.
--
-- WHY. @googlemail.com and @gmail.com are one Gmail mailbox (Google used googlemail in Germany and the UK for
-- years; both still deliver to the same inbox). People type whichever they remember, and each spelling opened its
-- own account: on prod (2026-11-19) 383 logins were googlemail ones, 35 of them beside a separate gmail account,
-- each holding part of one person's bookings.
--
-- HOW. The server folds the domain of every address a caller types before it finds or creates an account
-- (EmailAddresses.canonical, in modality-crm-server-authn-gateway-shared). These triggers are the backstop for
-- whatever stores an address without coming through there — a client-written member email, KBS2, a hand-run
-- script — so that no new googlemail login or person email reaches the tables.
--
-- WHAT IS FOLDED. Only the domain, case-insensitively, trailing blanks included; the rest of the value is kept as
-- written (this does not lower-case or trim anything else — that is the application's business, and doing it here
-- would rewrite the six pairs of logins on prod that differ only by case). Gmail also ignores dots and +tags in
-- the local part; folding those would merge addresses other providers keep apart, and is not done.
--
-- NOT DONE HERE: the existing googlemail rows. Renaming them collides with the 35 gmail accounts on
-- UNIQUE (corporation_id, username), and merging two accounts is a reviewed, human-run step:
-- scripts/fold-googlemail-accounts.sql in the aggregate repo. Until it has run, a googlemail account holder whose
-- person.email is written gets it folded, and the V0097 trigger then renames their login — which, for one of the
-- 35 with a gmail twin, fails on the unique constraint. Run the script right after this deploys.
--
-- DDL: one function and two BEFORE row triggers; no table rewrite. Anonymiser: no column added. Its run disables
-- user triggers on person and frontend_account, and writes @kbs-staging.invalid, which these leave alone anyway.
-- Safe to re-run.

CREATE OR REPLACE FUNCTION public.canonical_email_domain(address text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT regexp_replace(address, '@googlemail\.com\s*$', '@gmail.com', 'i')
$$;

COMMENT ON FUNCTION public.canonical_email_domain(text) IS
    'Folds @googlemail.com into @gmail.com (the same mailbox). Keep in step with EmailAddresses.canonical.';

CREATE OR REPLACE FUNCTION public.fold_googlemail_username() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.username := public.canonical_email_domain(NEW.username);
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION public.fold_googlemail_person_email() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    NEW.email := public.canonical_email_domain(NEW.email);
    RETURN NEW;
END
$$;

DROP TRIGGER IF EXISTS fold_googlemail_username ON public.frontend_account;
CREATE TRIGGER fold_googlemail_username
    BEFORE INSERT OR UPDATE OF username ON public.frontend_account
    FOR EACH ROW
    WHEN (NEW.username ILIKE '%@googlemail.com%')
    EXECUTE FUNCTION public.fold_googlemail_username();

DROP TRIGGER IF EXISTS fold_googlemail_person_email ON public.person;
CREATE TRIGGER fold_googlemail_person_email
    BEFORE INSERT OR UPDATE OF email ON public.person
    FOR EACH ROW
    WHEN (NEW.email ILIKE '%@googlemail.com%')
    EXECUTE FUNCTION public.fold_googlemail_person_email();
