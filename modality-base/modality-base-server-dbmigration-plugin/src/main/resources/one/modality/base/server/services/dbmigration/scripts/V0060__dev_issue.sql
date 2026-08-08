-- V0060: dev_issue — the engineering-side list of things to address.
--
-- NOT the existing `issue` table, which is the viewer-facing catalog
-- behind the streaming page's self-help tips ("no sound" → "unmute the
-- player"). This one is the dev team's list: bugs, improvements, UX
-- difficulties — hence `kind` rather than a table called `bug`, since
-- most of what support surfaces is not a defect.
--
-- GitHub stays the tracker (search, labels, and above all the linkage
-- from an issue to the commits and PRs that close it). `github_issue`
-- holds the number and is NULLABLE: a UX nit nobody files upstream is
-- tracked here alone, and the column is a reference, never the identity.
--
-- Status lives HERE and is maintained by hand, deliberately: fetching it
-- from GitHub would need a token, a cache and a failure mode, to answer
-- a question that changes a few times a week.
--
-- Personal data stays in this database. A GitHub issue carries the
-- context (event, session, KBS version, device) and a deep link back to
-- the conversation — never the viewer's name, email or booking.

CREATE TABLE IF NOT EXISTS public.dev_issue (
    id           serial PRIMARY KEY,
    -- 'bug' | 'improvement' | 'ux' | 'question'. Free-form on purpose:
    -- a CHECK constraint would need a migration every time support finds
    -- a shape we did not anticipate.
    kind         character varying(16) NOT NULL DEFAULT 'bug',
    title        text NOT NULL,
    -- 'reported' → 'confirmed' → 'fixed' | 'wontfix'. 'wontfix' does
    -- real work here: most improvement requests end there, and saying so
    -- is what stops the same one being re-triaged every festival.
    status       character varying(16) NOT NULL DEFAULT 'reported',
    -- The GitHub issue number, when there is one. No URL: the repository
    -- is a deployment detail, and a number survives a repo rename.
    github_issue integer,
    created_at   timestamp with time zone NOT NULL DEFAULT now(),
    resolved_at  timestamp with time zone
);

-- The working view is "what is still open, newest first".
CREATE INDEX IF NOT EXISTS dev_issue_status_created_idx
    ON public.dev_issue (status, created_at DESC);

-- ── The link from a support conversation ────────────────────────────────

-- The agent's flag: "this smells like something we must fix", raised
-- before anyone knows WHICH dev issue it is. That is the triage queue.
ALTER TABLE public.conversation
    ADD COLUMN IF NOT EXISTS needs_fix boolean NOT NULL DEFAULT false;

-- Set once triaged. MANY conversations point at ONE dev issue — that is
-- the whole reason the status lives on dev_issue and not here: marking
-- it fixed must not leave the same problem reading "open" on one
-- conversation and "fixed" on another.
ALTER TABLE public.conversation
    ADD COLUMN IF NOT EXISTS dev_issue_id integer REFERENCES public.dev_issue(id);

-- "Which conversations hit this?" — the list to go back to once it is
-- fixed. Partial: almost every conversation leaves it NULL.
CREATE INDEX IF NOT EXISTS conversation_dev_issue_idx
    ON public.conversation (dev_issue_id)
    WHERE dev_issue_id IS NOT NULL;

-- The triage queue, likewise a small slice of the table.
CREATE INDEX IF NOT EXISTS conversation_needs_fix_idx
    ON public.conversation (needs_fix)
    WHERE needs_fix;
