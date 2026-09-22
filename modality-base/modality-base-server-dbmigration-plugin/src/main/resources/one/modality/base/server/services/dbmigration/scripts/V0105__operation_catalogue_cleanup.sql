-- Retire the operations no client checks any more, and drop a prefix that now misleads.
--
-- The `operation` table accumulated a client at a time. KBSX has been removed from the aggregate
-- altogether; the Java/GWT front office is gone; and the Java/GWT back office is now reached only
-- by super-administrators, who hold `grant operation:*` and `grant route:*` and so never consult
-- an individual row. What is left checking these codes is the React back office, and it checks 46
-- of the 115.
--
-- The remainder is not harmless clutter. The role editor listed every row, alphabetically, with
-- nothing to say which page or button each one opened, and `ToggleMarkNotMultipleBooking` beside
-- `RouteToEventLetters` is most of why defining a role meant guessing.
--
-- ## The KBSX prefix is a label, not a client
--
-- Twenty rows are named "KBSX - something", and it is tempting to delete by that name. Thirteen of
-- them carry operation codes the React back office depends on today: RouteToAuthorizations,
-- RouteToOperations, RouteToOrganizations, RouteToMonitor, RouteToLetters, RouteToStatistics,
-- RouteToUsers, RouteToMoneyFlows, RouteToPayments, RouteToStatements, RouteToIncome,
-- RouteToFilters and RouteToRoomsGraphic. Deleting by name would have made /authorizations,
-- /operations, /organizations, /monitor, /letters, /statistics and /users ungrantable to every
-- non-super-administrator, in one statement, silently. The prefix is dropped instead: the code is
-- what identifies an operation, and the name is what an administrator reads. Checked for
-- collisions first -- no stripped name matches another row's.
--
-- ## What the delete deliberately spares
--
-- Each condition below is a way this could have gone wrong, so each is enforced rather than
-- assumed:
--
--  * `frontend` -- one table serves both apps. RouteToOrders, RouteToMembers and the rest of the
--    booking site's route grants live here too, and a back-office-shaped cleanup must not touch
--    them. Rows serving both apps are spared for the same reason.
--  * `public` / `guest` -- these are granted automatically to every caller. See
--    ModalityAuthorizationServerServiceProvider: the logged-out push selects `... and public`, the
--    logged-in one `... and (public or guest)`. Removing one changes what anonymous visitors hold,
--    which is not a tidy-up.
--  * `authorization_role_operation` -- an operation a role still holds, EITHER directly or
--    through a granted operation group. The group case is not hypothetical: "Catering Manager"
--    holds the "Kitchen" group rather than RouteToKitchen itself, and a guard that only read
--    `operation_id` would have deleted a live grant without reporting it. Four back-office rows
--    are in this position and stay: RouteToBookings, RouteToEventRoomSetup,
--    RouteToStatisticsTranslation and ToggleCancelDocumentLine. Each means a role was given
--    something that opens nothing in the React back office -- an access gap worth investigating,
--    not data worth deleting. RouteToStatisticsTranslation was the clearest of them: it grants
--    /statistics-translation while the React page was guarded by the shared /statistics, so
--    "Education assistant" could not open the one page its operation exists for. That guard is
--    split per page in the same change as this script, which fixes it -- and is why the code is
--    in the keep list below rather than resting on the role that happens to hold it today.
--  * `authorization_assignment` -- the older table that granted an operation to a person directly.
--    It is DEPRECATED and no longer part of the authorization framework, and it has no entry in
--    DomainModel.json, so no DQL client can even see it. The guard is here for a purely mechanical
--    reason: the foreign key still exists and is NO ACTION, so deleting a row it references would
--    abort this script, and a boot migration that aborts takes the server down with it. Two rows
--    are referenced only from here -- RouteToHome (BackOffice) and Logout -- and are spared for
--    that reason alone. When the deprecated table is dropped, they can go with it.
--
-- The keep list is the React back office's capability manifest, which is derived from the
-- dashboard tile tree and the router's requireRoute() guards. Its counterpart in the UI
-- (useCapabilityCatalogue) reports in both directions, so a code the app starts checking without a
-- row here shows up as "not grantable" rather than failing quietly. Ten such codes exist today
-- and are added by V0106 -- they are in this keep list so the two scripts cannot fight.

-- 1. Drop the prefix of a client that no longer exists.
UPDATE operation
   SET name = btrim(substring(name from 8))
 WHERE name LIKE 'KBSX - %';

-- 2. Remove the back-office-only operations nothing checks and nobody holds.
DELETE FROM operation o
 WHERE o.backend
   AND NOT o.frontend
   AND NOT o.public
   AND NOT o.guest
   AND NOT EXISTS (SELECT 1 FROM authorization_role_operation r WHERE r.operation_id = o.id)
   AND NOT EXISTS (SELECT 1 FROM authorization_role_operation r
                    WHERE o.group_id IS NOT NULL AND r.operation_group_id = o.group_id)
   AND NOT EXISTS (SELECT 1 FROM authorization_assignment a WHERE a.operation_id = o.id)
   AND o.operation_code NOT IN (
    'EditLetterContent',
    'EditLetterProperties',
    'EditPassTemplate',
    'RouteToAdmin',
    'RouteToAuthorizations',
    'RouteToCreateEvent',
    'RouteToCustomers',
    'RouteToEventLetters',
    'RouteToEventPricing',
    'RouteToEventSetup',
    'RouteToFilters',
    'RouteToFinancesAndStats',
    'RouteToGlobalSetup',
    'RouteToHousehold',
    'RouteToIncome',
    'RouteToKitchen',
    'RouteToLabelEditor',
    'RouteToLetters',
    'RouteToMedias',
    'RouteToMoneyFlows',
    'RouteToMonitor',
    'RouteToOperations',
    'RouteToOrganizations',
    'RouteToPayments',
    'RouteToPolicies',
    'RouteToProgram',
    'RouteToPushNotifications',
    'RouteToRecurringEvents',
    'RouteToRegistration',
    'RouteToResidents',
    'RouteToRoomSetup',
    'RouteToRoomsGraphic',
    'RouteToStatements',
    'RouteToStatistics',
    'RouteToStatisticsTranslation',
    'RouteToSuperAdmin',
    'RouteToSupportChats',
    'RouteToTester',
    'RouteToUsers',
    'RouteToVolunteering',
    'RouteToVolunteeringSchedule',
    'ShowBookingEditor',
    'ShowNewBookingEditor',
    'ToggleCancelDocument',
    'ToggleConfirmDocument',
    'ToggleMarkDocumentAsRead',
    'ToggleMarkDocumentAsWillPay',
    'ViewAsBackOfficeUser',
    'ViewAsCustomer'
   );
