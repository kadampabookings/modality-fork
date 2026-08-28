-- Admin-authored identification note for a back-office device (see docs/design/bo-signing-key-plan.md).
-- Written by the super-admin in the approval screen (e.g. "Bruno on the KI computer at MKMC") - a
-- durable, trustworthy, human-readable label, unlike the forgeable client-supplied hostname/os_user.
-- KEPT for the life of the device (NOT erased on decision like the raw enrolment context); it is the
-- curated replacement for the context we throw away. Covered by the whole-table 'truncated' anonymise
-- classification, so it drops from anonymised copies.
--
-- bo_device (V0078) is already owned by public.person's owner, and the migration role is a member of
-- that role (V0078's ALTER OWNER proved it), so this ALTER runs fine; ADD COLUMN keeps the owner.

ALTER TABLE public.bo_device ADD COLUMN IF NOT EXISTS admin_note varchar(512);
