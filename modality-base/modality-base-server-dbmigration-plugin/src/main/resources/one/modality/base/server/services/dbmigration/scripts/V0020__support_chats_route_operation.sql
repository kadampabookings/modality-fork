-- V0020: seed the RouteToSupportChats backoffice operation.
--
-- Grants nothing by itself — admins attach the '/support-chats' route to
-- roles through the back-office Operations & Roles UI (authorization_rule
-- 'grant route:/support-chats'), same as every other RouteTo* operation.
-- public=true only makes the operation code visible/grantable, mirroring
-- the existing route operations (RouteToHome, RouteToKitchen).
--
-- Idempotent: the WHERE NOT EXISTS guard makes re-runs a no-op, and the
-- id is allocated from max(id)+1 because the operation table has no
-- sequence-backed default and every environment has a different max.

INSERT INTO public.operation (id, operation_code, i18n_code, name, backend, frontend, public, read_only)
SELECT (SELECT coalesce(max(id), 0) + 1 FROM public.operation),
       'RouteToSupportChats', 'SupportChats', 'Support chats', true, false, true, false
WHERE NOT EXISTS (
    SELECT 1 FROM public.operation WHERE operation_code = 'RouteToSupportChats'
);
