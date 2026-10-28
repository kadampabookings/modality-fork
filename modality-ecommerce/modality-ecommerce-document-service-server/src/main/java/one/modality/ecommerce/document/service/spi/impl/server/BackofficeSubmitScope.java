package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Where a back-office booking submit may land: in the organization (and event) of every booking it names, the
 * caller must hold {@link #SUBMIT_BOOKINGS} (V0138).
 *
 * <p>Until 2026-10-22 nothing asked. {@link DocumentSubmitAuthorization} fences the FRONT office — whose bookings,
 * which kinds of change — and lets a verified back-office session through untouched, so any account able to
 * sign in to the back office could create, change or cancel any booking at any organization's event. The
 * back-office screens hid what a role did not grant, which is the browser deciding.
 *
 * <p><b>Placed from the database, never from the request.</b> An existing booking is placed where it is stored —
 * a line or an attendance through its booking — so a caller granted in organization A cannot act on B's booking
 * by naming A. EVERY new booking in the submit is placed where it will be created. Existing or new, a booking's
 * EVENT decides the organization (read from the database), and a booking organization that differs is required
 * as well — centre A's booking at centre B's event is judged at both, as the write rule judges a letter. A target
 * that cannot be placed — no such row, a booking with neither event nor organization — refuses: what cannot be
 * placed cannot be checked.
 *
 * <p><b>Payment events are refused outright</b>, not placed: no back-office screen sends one, and they name a
 * payment by id alone, so one could move or delete another centre's payment or record a "successful" one.
 *
 * <p>Observed first (the census line was {@code "WOULD REFUSE a back-office booking submit"}), enforced from
 * 2026-10-28 after the production census from the 22 Oct deploy logged none. Super administrators pass, as through
 * every RouteAccessGuard check.
 *
 * @author Claude Code
 */
final class BackofficeSubmitScope {

    /** On since 2026-10-28, after a clean census; false would only log ("WOULD REFUSE"). */
    static final boolean ENFORCING = true;

    static final String SUBMIT_BOOKINGS = "SubmitBookings";

    /** What a refused caller is told: that it was refused, and no more. */
    static final String REFUSED = "[BookingSubmitScopeError] This operation is not available to you";

    private BackofficeSubmitScope() {
    }

    /** Where a booking lives. Either id may be null; a booking with neither cannot be placed. */
    record Place(Object organizationId, Object eventId) {}

    /** The reads the decision needs. An interface so the check answers them without a database. */
    interface Lookup {
        /** The booking of each line, by line id. Missing ids are simply absent from the result. */
        Future<List<Object[]>> documentsOfLines(Set<Object> lineIds);

        /** The line of each attendance, by attendance id. */
        Future<List<Object[]>> linesOfAttendances(Set<Object> attendanceIds);

        /** Each booking's place, by booking id: {id, organizationId, eventId}. */
        Future<List<Object[]>> placesOfDocuments(Set<Object> documentIds);

        /** The organization an event belongs to, or null when there is no such event. */
        Future<Object> eventOrganization(Object eventId);

        /** Whether the caller holds SubmitBookings at this place (super administrators always do). */
        Future<Boolean> holds(Place place);
    }

    /** A booking's owner as stored or as written: either id may be null; with neither it cannot be placed. */
    record Owner(Object eventId, Object organizationId) {}

    /**
     * Why this submit would be refused, or null when the caller may make it.
     *
     * @param targets       the existing bookings, lines and attendances the submit names
     * @param newBookings   every booking the submit creates — ALL of them: a submit may carry several
     *                      AddDocumentEvents, and placing only the first would let a second land anywhere
     * @param paymentEvents whether the submit adds, changes or removes a payment
     */
    static Future<String> refusal(DocumentSubmitAuthorization.Targets targets, List<Owner> newBookings,
                                  boolean paymentEvents, Lookup lookup) {
        // Payments are the payment service's: no back-office screen sends a payment event, and through this endpoint
        // one could record a "successful" payment, or move or delete another centre's (Update/RemoveMoneyTransferEvent
        // name the payment by id alone). Refused outright rather than placed.
        if (paymentEvents)
            return Future.succeededFuture("carries a payment change (payments are made through the payment service)");
        return existingOwners(targets, lookup).compose(existing -> {
            if (existing == null)
                return Future.succeededFuture("names a booking, line or attendance that could not be placed");
            List<Owner> owners = new ArrayList<>(existing);
            owners.addAll(newBookings);
            if (owners.isEmpty())
                return Future.succeededFuture(null); // names no booking at all: nothing to place, nothing to write
            List<Future<List<Place>>> placed = new ArrayList<>();
            for (Owner owner : owners)
                placed.add(placesOf(owner, lookup));
            return Future.all(new ArrayList<>(placed)).compose(all -> {
                Set<Place> places = new LinkedHashSet<>();
                for (int i = 0; i < owners.size(); i++) {
                    List<Place> ofOwner = all.resultAt(i);
                    if (ofOwner == null)
                        return Future.succeededFuture("a booking could not be placed (event " + owners.get(i).eventId()
                                                      + ", organization " + owners.get(i).organizationId() + ")");
                    places.addAll(ofOwner);
                }
                List<Place> ordered = new ArrayList<>(places);
                List<Future<Boolean>> answers = new ArrayList<>();
                for (Place place : ordered)
                    answers.add(lookup.holds(place));
                return Future.all(new ArrayList<>(answers)).map(held -> {
                    List<String> missing = new ArrayList<>();
                    for (int i = 0; i < ordered.size(); i++)
                        if (!Boolean.TRUE.equals(held.resultAt(i)))
                            missing.add(describe(ordered.get(i)));
                    return missing.isEmpty() ? null : "no " + SUBMIT_BOOKINGS + " in " + String.join("; ", missing);
                });
            });
        });
    }

    /** The stored owners of the existing targets, or null when any of them cannot be read. */
    private static Future<List<Owner>> existingOwners(DocumentSubmitAuthorization.Targets targets, Lookup lookup) {
        Set<Object> documents = new LinkedHashSet<>(targets.documents());
        Future<Boolean> attendancesResolved = targets.attendances().isEmpty() ? Future.succeededFuture(true)
            : lookup.linesOfAttendances(targets.attendances())
                .map(pairs -> parentsOf(pairs, targets.attendances()))
                .compose(lines -> lines == null ? Future.succeededFuture(false)
                : lookup.documentsOfLines(lines).map(pairs -> addParents(pairs, lines, documents)));
        return attendancesResolved.compose(okAttendances -> {
            if (!okAttendances)
                return Future.succeededFuture(null);
            Future<Boolean> linesResolved = targets.lines().isEmpty() ? Future.succeededFuture(true)
                : lookup.documentsOfLines(targets.lines()).map(pairs -> addParents(pairs, targets.lines(), documents));
            return linesResolved.compose(okLines -> {
                if (!okLines)
                    return Future.succeededFuture(null);
                if (documents.isEmpty())
                    return Future.succeededFuture(List.of());
                return lookup.placesOfDocuments(documents).map(rows -> {
                    Set<Object> found = new LinkedHashSet<>();
                    List<Owner> owners = new ArrayList<>();
                    for (Object[] row : rows) {
                        found.add(String.valueOf(row[0]));
                        owners.add(new Owner(row[2], row[1]));
                    }
                    for (Object id : documents)
                        if (!found.contains(String.valueOf(id)))
                            return null; // no such booking
                    return owners;
                });
            });
        });
    }

    /** The parents named by {child, parent} pairs, or null when a child is missing or has none. Pure. */
    private static Set<Object> parentsOf(List<Object[]> pairs, Set<Object> children) {
        Set<Object> found = new LinkedHashSet<>(), parents = new LinkedHashSet<>();
        for (Object[] pair : pairs) {
            if (pair[1] == null)
                return null;
            found.add(String.valueOf(pair[0]));
            parents.add(String.valueOf(pair[1]));
        }
        for (Object child : children)
            if (!found.contains(String.valueOf(child)))
                return null;
        return parents;
    }

    /** Adds the parents named by the pairs to {@code into}; false when a child is missing or has none. */
    private static boolean addParents(List<Object[]> pairs, Set<Object> children, Set<Object> into) {
        Set<Object> parents = parentsOf(pairs, children);
        if (parents == null)
            return false;
        into.addAll(parents);
        return true;
    }

    /**
     * Where a booking lives — existing or new, judged the same way — or null when it cannot be placed. Its event
     * decides the organization (read from the database), and an organization written or stored on the booking that
     * differs is required as well: a booking of centre A at centre B's event is judged at both.
     */
    private static Future<List<Place>> placesOf(Owner owner, Lookup lookup) {
        String event = key(owner.eventId()), organization = key(owner.organizationId());
        if (event == null)
            return Future.succeededFuture(organization == null ? null : List.of(new Place(organization, null)));
        return lookup.eventOrganization(owner.eventId()).map(eventOrganizationId -> {
            String eventOrganization = key(eventOrganizationId);
            if (eventOrganization == null)
                return null; // no such event
            Place atEvent = new Place(eventOrganization, event);
            return organization == null || organization.equals(eventOrganization) ? List.of(atEvent)
                : List.of(atEvent, new Place(organization, null));
        });
    }

    private static String key(Object id) {
        return id == null ? null : String.valueOf(id);
    }

    private static String describe(Place place) {
        return "organization " + place.organizationId() + (place.eventId() == null ? "" : " event " + place.eventId());
    }

    // ── The database ──────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The production lookup: reads made as the server, and the grant asked of the caller's captured state.
     *
     * @param callerState the caller's state, captured on the caller's thread before any async step
     */
    static Lookup databaseLookup(Object callerState) {
        return new Lookup() {
            @Override
            public Future<List<Object[]>> documentsOfLines(Set<Object> lineIds) {
                return pairs("select document from DocumentLine where id in $1", lineIds, row -> row.getForeignEntityId("document"));
            }

            @Override
            public Future<List<Object[]>> linesOfAttendances(Set<Object> attendanceIds) {
                return pairs("select documentLine from Attendance where id in $1", attendanceIds, row -> row.getForeignEntityId("documentLine"));
            }

            @Override
            public Future<List<Object[]>> placesOfDocuments(Set<Object> documentIds) {
                return asServer(() -> store().<Entity>executeQuery("select organization,event from Document where id in $1", (Object) keysOf(documentIds))
                    .map(rows -> {
                        List<Object[]> result = new ArrayList<>();
                        for (Entity row : rows)
                            result.add(new Object[] { Entities.getPrimaryKey(row),
                                Entities.getPrimaryKey(row.getForeignEntityId("organization")),
                                Entities.getPrimaryKey(row.getForeignEntityId("event")) });
                        return result;
                    }));
            }

            @Override
            public Future<Object> eventOrganization(Object eventId) {
                return asServer(() -> store().<Entity>executeQuery("select organization from Event where id=$1", primaryKeyOf(eventId))
                    .map(rows -> rows.isEmpty() ? null : Entities.getPrimaryKey(rows.get(0).getForeignEntityId("organization"))));
            }

            @Override
            public Future<Boolean> holds(Place place) {
                return RouteAccessGuard.callerHoldsOperation(callerState, SUBMIT_BOOKINGS,
                    primaryKeyOf(place.organizationId()), primaryKeyOf(place.eventId()));
            }
        };
    }

    private static Future<List<Object[]>> pairs(String dql, Set<Object> ids, Function<Entity, Object> parent) {
        return asServer(() -> store().<Entity>executeQuery(dql, (Object) keysOf(ids)).map(rows -> {
            List<Object[]> result = new ArrayList<>();
            for (Entity row : rows)
                result.add(new Object[] { Entities.getPrimaryKey(row), Entities.getPrimaryKey(parent.apply(row)) });
            return result;
        }));
    }

    private static EntityStore store() {
        return EntityStore.create(DataSourceModelService.getDefaultDataSourceModel());
    }

    private static <T> T asServer(java.util.function.Supplier<T> read) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), read);
    }

    private static Object[] keysOf(Set<Object> ids) {
        return ids.stream().map(BackofficeSubmitScope::primaryKeyOf).toArray();
    }

    /** An id as a query parameter: a number (the driver refuses a string where the column is an integer). */
    static Object primaryKeyOf(Object id) {
        if (id instanceof String text) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException e) {
                try {
                    return Long.valueOf(text);
                } catch (NumberFormatException e2) {
                    return id;
                }
            }
        }
        return id;
    }
}
