-- V0094: a person the account holder books for occasionally, without keeping them as a member.
--
-- WHY. A room booker sharing a twin with a friend who has no account can add that friend from the
-- cart and book their place (room-mate plan step 7c). Most such friends are one-off — someone at one
-- festival — but the account keeps them for good, so bookers collect members they never manage, and
-- each is a third-party record they did not ask to hold.
--
-- WHAT THIS IS. `occasional = true` means "booked for occasionally, not kept as a member":
-- the member PICKERS hide them (the booking wizard's member cards, "Booking for", the roommate
-- slot), while everything that already works on them goes on working — their booking, its price, the
-- room rules, the invite linking, their mails, the orders page, and the cart's own lookups, which is
-- what lets "Book their place" still be offered for them afterwards.
--
-- WHAT IT IS NOT. Not `removed`, which means DELETED (V0065 says so at length, and a removed row
-- that still holds live bookings is a trap): a removed person cannot be found by the flow that just
-- created them, cannot be claimed by the friend's own account, and is invisible to staff at the desk,
-- who would then create a duplicate. This row is a live member row in every respect but visibility.
--
-- VISIBLE SOMEWHERE. /members lists flagged people in their own group, so the booker can still see,
-- correct and keep or delete the details they entered for someone else. Hiding a third party's data
-- from the person who entered it, with no way back, is not a state to leave anyone in.
--
-- No index: nothing queries BY this flag. The member queries already select by account, and the flag
-- is read from rows they return.

ALTER TABLE public.person
    ADD COLUMN IF NOT EXISTS occasional boolean DEFAULT false NOT NULL;

COMMENT ON COLUMN public.person.occasional IS
    'True when the account holder books for this person occasionally, without keeping them as a '
    'member: the member pickers hide them and /members groups them apart, while everything else '
    'treats them as an ordinary member. Cleared when the booker keeps them. Not a soft delete — '
    'see removed.';
