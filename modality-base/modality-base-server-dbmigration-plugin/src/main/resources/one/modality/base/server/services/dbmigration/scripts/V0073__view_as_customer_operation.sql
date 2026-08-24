-- V0073: seed the ViewAsCustomer back-office operation.
--
-- Gates the "View as customer" button, which asks the server for a one-time,
-- read-only pass into a customer's front office. It replaces the previous
-- practice of copying the stored password hash out of the back office and
-- typing it into the login box -- see ModalityPasswordAuthenticationGateway
-- .isTypedPasswordCorrect(), whose hash-accepting branch is removed in the
-- same change.
--
-- Grants nothing by itself. Nobody can open a support view until an admin
-- attaches this operation to a role through the Operations & Roles UI, which
-- is the intended default: the capability should be given deliberately, to
-- named people, rather than being implied by back-office access.
--
-- The server re-checks the grant on every request
-- (ModalityMagicLinkAuthenticationGateway.hasViewAsCustomerPermission); the
-- client's copy only decides whether the button is drawn.
--
-- public=false, unlike the RouteTo* operations seeded by V0020: those are
-- navigation entries every signed-in user may reach, whereas this one confers
-- access to someone else's personal data and must never be granted implicitly.
--
-- Idempotent: the WHERE NOT EXISTS guard makes re-runs a no-op, and the id is
-- allocated from max(id)+1 because the operation table has no sequence-backed
-- default and every environment has a different max.

INSERT INTO public.operation (id, operation_code, i18n_code, name, backend, frontend, public, read_only)
SELECT (SELECT coalesce(max(id), 0) + 1 FROM public.operation),
       'ViewAsCustomer', 'ViewAsCustomer', 'View as customer', true, false, false, true
WHERE NOT EXISTS (
    SELECT 1 FROM public.operation WHERE operation_code = 'ViewAsCustomer'
);
