-- V0139: event_type.two_carers_for_children — how many carers a child's booking must name.
--
-- A booking for an account member under 18 must name the carers attending with them. The booking
-- form used to ask for TWO carers for any child aged 0-15, at every event. That rule belongs to the
-- big international events (NKT Festivals); at a centre's course a child coming with one parent is
-- fine, and the form blocked exactly that booking.
--
-- Setting this flag keeps the two-carer rule for every event of the type: a child (0-15) must name
-- two different carers, a young adult (16-17) one. Left false, any member under 18 names one carer.
--
-- NOT NULL DEFAULT false, like event_type.no_terms_acceptance (V0032): a plain flag with no wider
-- scope to defer to. The default is the lenient rule, so only the festival types need setting.
--
-- The types that ask two carers today are the NKT Spring, Summer and Fall Festivals (35, 36, 37);
-- they are set here so festivals keep the two-carer rule from the moment the column exists.

ALTER TABLE event_type
    ADD COLUMN IF NOT EXISTS two_carers_for_children boolean DEFAULT false NOT NULL;

UPDATE event_type SET two_carers_for_children = true WHERE id IN (35, 36, 37);

COMMENT ON COLUMN event_type.two_carers_for_children IS
    'When true, a booking for a child (0-15) at an event of this type must name two different '
    'carers attending the event, as at NKT Festivals. When false, any member under 18 names one.';
