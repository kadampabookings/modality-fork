-- Revokes the booking-access verification codes that could have been handed out by
-- IssueBookingAccessMagicLinkCredentials, an authentication-gateway entry point that has
-- now been removed.
--
-- That entry point took an email and a requested path from the caller, minted a
-- BOOKING_ACCESS magic link for them, and returned the link's 6-digit verification code in
-- the reply. Nothing on that bus address was authenticated, so anyone could name anyone
-- else's address and be handed a working sign-in code for it, redeemable as that person.
-- No email was sent, so the account holder was never told.
--
-- Identifying the tainted rows by what the LEGITIMATE minter writes, not by what our own
-- client happened to send. Those are different sets, and the difference matters:
--
--   * The only legitimate minter is ServerDocumentServiceProvider ->
--     ModalityGuestAuthenticationGateway.registerBookingAccessMagicLink, which has written
--     exactly '/order/<documentPk>' since the feature was introduced (662c7771c, unchanged
--     by e460b7904).
--   * The removed endpoint let the CALLER choose the path. Our deleted React wrapper sent
--     the plural '/orders/<id>', but nothing required that — anyone exploiting the hole
--     would more likely have sent '/', '/home', '' or nothing at all (NULL). Filtering on
--     the plural shape alone would therefore have missed precisely the rows an attacker
--     created, while catching only the ones our own client made.
--
-- So: revoke every BOOKING_ACCESS row whose path is not the one shape the server itself
-- writes. By construction that invalidates nothing a guest legitimately holds.
--
-- Revoking by nulling verification_code, not by deleting the row or backdating
-- creation_date: redemption looks the code up with `where verification_code = $1`, so NULL
-- can never match anything a user types (and the 6-digit gate in
-- MagicLinkService.loadMagicLinkFromTokenOrVerificationCode means a null or empty input
-- never reaches that branch at all), while email/creation_date/requested_path survive for
-- audit. The token column is left alone — the endpoint returned only the code, never the
-- token, so the token was not disclosed and the emailed magic link still works.
--
-- Worth running BEFORE this migration, to see what is about to be revoked and to confirm
-- no unexpected legitimate path shape exists in production:
--     SELECT requested_path, count(*), min(creation_date), max(creation_date)
--     FROM magic_link WHERE link_type = 'BOOKING_ACCESS'
--     GROUP BY 1 ORDER BY 2 DESC LIMIT 50;
UPDATE magic_link
SET verification_code = NULL
WHERE link_type = 'BOOKING_ACCESS'
  AND verification_code IS NOT NULL
  AND (requested_path IS NULL OR requested_path !~ '^/order/[0-9]+$');
