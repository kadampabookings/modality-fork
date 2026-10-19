package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.async.Future;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.Attendance;
import one.modality.base.shared.entities.DocumentLine;
import one.modality.ecommerce.document.service.events.AbstractAttendancesEvent;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;
import one.modality.ecommerce.document.service.events.AbstractDocumentLineEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Who may submit which booking changes — the write-side counterpart of {@link DocumentAccess}.
 *
 * <p><b>The hole (found 2026-10-19).</b> {@code submitDocumentChanges} replayed whatever events arrived against
 * whatever booking ids they named. Reading a booking was limited to its owner; changing one was not limited at
 * all. Any client — a member with a modified client, an anonymous connection — could cancel or confirm anybody's
 * booking by its sequential id, make their own free ({@code PriceDocumentLineEvent}, a 100% discount: "a
 * back-office action — nothing in the front office offers it", but nothing enforced that), or record a payment
 * on it ({@code AddMoneyTransferEvent}, a server-side write the client-write deny on money_transfer never sees).
 *
 * <p><b>Two questions, both decided here before anything is queued or written:</b>
 * <ol>
 *   <li><b>Whose bookings.</b> Every EXISTING booking, line and attendance the events name must be one the
 *       caller may access — by the same rule as reading ({@link DocumentAccess}): a member's account, a guest's
 *       own email; a verified back office and the server itself, anything. A new booking has no owner yet and is
 *       anybody's to create — which is how a guest books. A line or attendance is judged by ITS booking, not by
 *       the booking id written beside it in the event, since the two need not agree.</li>
 *   <li><b>Which kinds of change.</b> A caller who is neither the back office nor the server may submit only the
 *       events the front office's booking flows make ({@link #FRONT_OFFICE_EVENTS}). Prices, payments,
 *       confirmation, arrival, verification marks, allocation and the multiple-booking operations are the back
 *       office's. (Which member of staff may do which is the next step: operation codes in the booking's
 *       organization.)</li>
 * </ol>
 *
 * <p><b>Observing first</b>, like every rule in this arc: until {@link #ENFORCING} is flipped, a submit that would
 * be refused is logged and let through, so a front-office flow the allowlist missed shows in the census instead of
 * breaking bookings.
 *
 * @author Claude Code
 */
final class DocumentSubmitAuthorization {

    private DocumentSubmitAuthorization() {
    }

    /** False while observing. Flip after a clean census — and add a log filter for {@link #REFUSED} the same day. */
    static final boolean ENFORCING = false;

    /** Told to the caller when refusing; deliberately says nothing about which booking or event was at fault. */
    static final String REFUSED = "[NotAuthorizedError] This booking change is not allowed";

    /**
     * The events the front office's booking flows submit — counted 2026-10-19 from the React front office through
     * the shared WorkingBooking (bookItem, bookScheduledItems, createDocumentEvent, removeAttendances, addRequest,
     * applyFacilityFeeRate, cancelDocument, setCarersInfo, setShareMateInfo, setShareOwnerQuantity), plus
     * RemoveDocumentLineEvent, which the shared document-events.ts emits when an edit undoes a line it added —
     * missed by a scan of WorkingBooking, caught by production's history (44 member submits, last 2026-10-13).
     * Simple class names, matched exactly.
     */
    static final Set<String> FRONT_OFFICE_EVENTS = Set.of(
        "AddDocumentEvent", "AddDocumentLineEvent", "AddAttendancesEvent", "RemoveAttendancesEvent", "RemoveDocumentLineEvent",
        "AddRequestEvent", "ApplyFacilityFeeEvent", "CancelDocumentEvent", "EditCarersInfoEvent",
        "EditShareMateInfoDocumentLineEvent", "EditShareOwnerInfoDocumentLineEvent"
    );

    /** The existing rows a submit names, by kind. New rows (this submit creates them) are not listed. */
    record Targets(Set<Object> documents, Set<Object> lines, Set<Object> attendances) {
        boolean isEmpty() {
            return documents.isEmpty() && lines.isEmpty() && attendances.isEmpty();
        }
    }

    /** Which of these ids the caller may access, per entity. An interface so the check needs no database. */
    interface OwnershipLookup {
        Future<Set<Object>> accessible(String entityName, Collection<Object> ids);
    }

    // ── The decision ──────────────────────────────────────────────────────────────────────────────────────

    /** The event kinds this caller may not submit — empty when all may run. Pure. */
    static List<String> forbiddenKinds(List<String> eventKinds, boolean backofficeOrServer) {
        List<String> forbidden = new ArrayList<>();
        if (!backofficeOrServer)
            for (String kind : eventKinds)
                if (!FRONT_OFFICE_EVENTS.contains(kind) && !forbidden.contains(kind))
                    forbidden.add(kind);
        return forbidden;
    }

    /**
     * Why this submit must not run, or a future of null when it may.
     *
     * @param backofficeOrServer a verified back office, or the server acting on nobody's behalf (SystemUserId)
     * @param readsNothing       a caller with neither an account nor a guest email: may create, may not touch
     */
    static Future<String> refusal(List<String> eventKinds, Targets targets, boolean backofficeOrServer,
                                  boolean readsNothing, OwnershipLookup lookup) {
        List<String> forbidden = forbiddenKinds(eventKinds, backofficeOrServer);
        if (!forbidden.isEmpty())
            return Future.succeededFuture("the front office may not submit " + String.join(", ", forbidden));
        if (backofficeOrServer || targets.isEmpty())
            return Future.succeededFuture(null);
        if (readsNothing)
            return Future.succeededFuture("no account or guest email, yet it names existing bookings " + describe(targets));
        List<Future<String>> answers = List.of(
            notAccessible("Document", targets.documents(), lookup),
            notAccessible("DocumentLine", targets.lines(), lookup),
            notAccessible("Attendance", targets.attendances(), lookup));
        return Future.all(new ArrayList<>(answers)).map(all -> {
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < answers.size(); i++) {
                String m = all.resultAt(i);
                if (m != null)
                    missing.add(m);
            }
            return missing.isEmpty() ? null : "not the caller's: " + String.join("; ", missing);
        });
    }

    private static Future<String> notAccessible(String entityName, Set<Object> ids, OwnershipLookup lookup) {
        if (ids.isEmpty())
            return Future.succeededFuture(null);
        return lookup.accessible(entityName, ids).map(accessible -> {
            List<String> refused = new ArrayList<>();
            for (Object id : ids)
                if (!accessible.contains(String.valueOf(id)))
                    refused.add(String.valueOf(id));
            return refused.isEmpty() ? null : entityName + " " + String.join(",", refused);
        });
    }

    private static String describe(Targets targets) {
        return "(documents " + targets.documents() + ", lines " + targets.lines() + ", attendances " + targets.attendances() + ")";
    }

    // ── Reading a submit ──────────────────────────────────────────────────────────────────────────────────

    /** The event kinds, by simple class name, in submission order. */
    static List<String> eventKinds(AbstractDocumentEvent[] events) {
        List<String> kinds = new ArrayList<>();
        for (AbstractDocumentEvent event : events)
            kinds.add(event.getClass().getSimpleName());
        return kinds;
    }

    /**
     * The existing rows the events name, read from the replayed entities (DocumentSubmitRequest.create replays them
     * on the caller's thread). A row this submit inserts is new and is not a target: nobody owns it yet.
     */
    static Targets existingTargets(AbstractDocumentEvent[] events) {
        Set<Object> documents = new LinkedHashSet<>(), lines = new LinkedHashSet<>(), attendances = new LinkedHashSet<>();
        for (AbstractDocumentEvent event : events) {
            if (event.getDocument() != null && !event.getDocument().isNew())
                documents.add(String.valueOf(Entities.getPrimaryKey(event.getDocument())));
            if (event instanceof AbstractDocumentLineEvent lineEvent) {
                DocumentLine line = lineEvent.getDocumentLine();
                if (line != null && !line.isNew())
                    lines.add(String.valueOf(Entities.getPrimaryKey(line)));
            }
            if (event instanceof AbstractAttendancesEvent attendancesEvent && attendancesEvent.getAttendances() != null)
                for (Attendance attendance : attendancesEvent.getAttendances())
                    if (attendance != null && !attendance.isNew())
                        attendances.add(String.valueOf(Entities.getPrimaryKey(attendance)));
        }
        return new Targets(documents, lines, attendances);
    }

    /**
     * The production lookup: which of these rows the caller's {@link DocumentAccess} reaches, with the same
     * predicate the read side uses, run as the server so the read inventory does not record it as a client read.
     */
    static OwnershipLookup databaseLookup(DocumentAccess access) {
        return (entityName, ids) -> {
            String path = switch (entityName) {
                case "Document" -> "";
                case "DocumentLine" -> "document.";
                default -> "documentLine.document.";
            };
            String dql = "select id from " + entityName + " where id in $1 and "
                         + access.clause(2, path + "person", path + "person_email");
            Object[] keys = ids.stream().map(DocumentSubmitAuthorization::primaryKeyOf).toArray();
            return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), () ->
                EntityStore.create(DataSourceModelService.getDefaultDataSourceModel())
                    .<Entity>executeQuery(dql, keys, access.parameter())
                    .map(rows -> {
                        Set<Object> accessible = new LinkedHashSet<>();
                        for (Entity row : rows)
                            accessible.add(String.valueOf(Entities.getPrimaryKey(row)));
                        return accessible;
                    }));
        };
    }

    /** An id as a query parameter: a number (the driver refuses a string where the column is an integer). */
    private static Object primaryKeyOf(Object id) {
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
