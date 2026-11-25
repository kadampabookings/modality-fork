-- letter_preview(letter, document): a booking's letter composed exactly as the send composes it -- the language
-- from letter_mail_lang(), subject and body from letter_compose_email() with the brackets expanded (V0160) --
-- for the back office's "Send a letter" preview (modality/service/document/previewLetter), in a transaction that
-- is READ ONLY by the time anything is composed.
--
-- WHY READ ONLY, AND WHY HERE. Composing a letter runs SQL that is data: interpret_brackets() EXECUTEs each
-- bracket_pattern's `replacement` and `condition`. They are lookups today (135 on staging, none writing), but
-- that is a fact about the rows, not a property of the code, and until 2026-11-25 any back-office client could
-- write those rows (now closed in ClientWriteSecrets). A preview is a read: it must not be able to queue a mail,
-- mint a token or change a row, whatever a pattern says. The server's query path runs in autocommit, with no
-- read-only transaction of its own, so the fence is set by the function itself, first thing:
-- set_config('transaction_read_only', 'on', true) switches the statement's transaction to read only -- allowed at
-- any point, unlike the other direction -- and every write reached afterwards, nested functions included, fails
-- with "cannot execute ... in a read-only transaction". Rehearsed on a local postgres:17.
--
-- Returns no row when the letter or the document does not exist, and a row of NULLs when the letter has no text to
-- send in the booker's language nor in English (letter_compose_email's contract).
--
-- No anonymiser or erasure-manifest change: a function, no table or column.

CREATE OR REPLACE FUNCTION public.letter_preview(p_letter_id integer, p_document_id integer,
                                                 OUT subject text, OUT content text)
RETURNS SETOF record
LANGUAGE plpgsql VOLATILE AS $$
BEGIN
    PERFORM set_config('transaction_read_only', 'on', true);
    RETURN QUERY
        SELECT c.subject, c.content
          FROM letter l, document d,
               letter_compose_email(l, letter_mail_lang(l, d.person_lang, d.organization_id), d.id, NULL) c
         WHERE l.id = p_letter_id AND d.id = p_document_id;
END $$;

COMMENT ON FUNCTION public.letter_preview(integer, integer) IS
    'A booking''s letter as the send would compose it (letter_mail_lang + letter_compose_email), in a transaction '
    'switched to READ ONLY first: bracket patterns are SQL, and a preview must not be able to write (V0182).';
