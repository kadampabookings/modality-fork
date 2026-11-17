-- V0166: operation.explicit_only — an operation that a wildcard grant (operation:*) does not cover.
--
-- WHY. A super administrator is pushed "grant operation:*", and until now that covered every operation but the RouteTo*
-- ones. The Data Protection Officer's operation (DataProtection, V0163) must be refused to a super administrator who
-- does not hold the role (David, 2026-11-06 and 2026-11-17: strictly DPO). The framework could not say so, so the
-- erasure endpoints asked the role tables directly (EventErasureAccess) and the back office asked a dedicated
-- endpoint instead of its pushed grants — a second copy of the grant rules, which every other client-side check
-- would get wrong for a super administrator.
--
-- WHAT. A flag on the operation. The grant push names every flagged operation ("explicit operation:<code>"), and the
-- rule engine — the server's (isAuthorized) and the back office's (permissions store) alike — no longer lets a
-- WILDCARD grant cover it. Only a grant naming the operation itself does. A wildcard REVOKE still applies: refusing
-- is never weakened. Plan: docs/security/admin-tiers-and-explicit-operations-plan.md (aggregate repo), Phase 1.
--
-- DataProtection is the only flagged operation. Nobody loses anything a role grants them; a super administrator loses
-- the DataProtection that EventErasureAccess already refused them.
--
-- The DDL is one guarded ALTER with a constant default: a metadata-only change, a brief lock on a small table, safe
-- to re-run. The anonymiser classifies `operation` as a whole table ('*', not-pii, reference); a boolean flag on an
-- operation says nothing about a person, so that holds.

ALTER TABLE public.operation ADD COLUMN IF NOT EXISTS explicit_only boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN public.operation.explicit_only IS
    'Not covered by a wildcard grant (operation:*): only a grant naming this operation authorizes it (V0166).';

UPDATE public.operation SET explicit_only = true WHERE operation_code = 'DataProtection' AND NOT explicit_only;
