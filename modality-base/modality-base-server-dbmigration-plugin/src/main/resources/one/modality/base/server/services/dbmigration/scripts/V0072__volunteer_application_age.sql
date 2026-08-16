-- The public volunteer application form now asks for the applicant's age in
-- whole years instead of their date of birth. The exact birth date was only
-- ever used to derive an age (the 18+ eligibility check and the age shown on
-- the coordinator screens), so storing the age directly is the smaller piece
-- of personal data to hold.
--
-- date_of_birth is deliberately KEPT and untouched: existing applications and
-- KBS2-imported candidates still carry it, and it remains editable from the
-- back office. Readers prefer age when it is set and fall back to computing
-- the age from date_of_birth otherwise.
--
-- The matching FIELD row was hand-added to DomainModelSnapshot.json (the runtime
-- model), not taken from KBS2. Add it to the KBS2 HSQL metadata as well, or the
-- next model re-import will drop it and DQL will stop resolving 'age'.
ALTER TABLE volunteering_application
    ADD COLUMN IF NOT EXISTS age integer;

COMMENT ON COLUMN volunteering_application.age IS
    'Applicant age in whole years, as declared on the application form. '
    'Null for legacy/imported rows — derive from date_of_birth in that case.';
