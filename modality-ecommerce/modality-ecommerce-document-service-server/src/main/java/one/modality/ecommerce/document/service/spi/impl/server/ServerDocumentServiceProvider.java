package one.modality.ecommerce.document.service.spi.impl.server;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.ReadOnlyAstArray;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Arrays;
import dev.webfx.platform.util.Numbers;
import dev.webfx.platform.util.Strings;
import dev.webfx.platform.util.collection.Collections;
import dev.webfx.stack.com.serial.SerialCodecManager;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityStore;
import dev.webfx.stack.orm.entity.EntityStoreQuery;
import dev.webfx.stack.orm.entity.UpdateStore;
import dev.webfx.stack.session.state.RestrictedPrincipalRegistry;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;
import one.modality.base.shared.entities.*;
import one.modality.ecommerce.document.service.GuestBookingAccessService;

import java.util.ServiceLoader;
import one.modality.base.shared.entities.triggers.Triggers;
import one.modality.ecommerce.document.service.*;
import one.modality.ecommerce.document.service.events.AbstractDocumentEvent;
import one.modality.ecommerce.document.service.events.book.*;
import one.modality.ecommerce.document.service.events.registration.MarkDocumentAsArrivedEvent;
import one.modality.ecommerce.document.service.events.registration.MarkDocumentAsCheckedOutEvent;
import one.modality.ecommerce.document.service.events.registration.documentline.AllocateDocumentLineEvent;
import one.modality.ecommerce.document.service.events.registration.documentline.CancelDocumentLineEvent;
import one.modality.ecommerce.document.service.events.registration.documentline.LinkMateToOwnerDocumentLineEvent;
import one.modality.ecommerce.document.service.events.registration.documentline.PriceDocumentLineEvent;
import one.modality.ecommerce.document.service.spi.DocumentServiceProvider;
import one.modality.ecommerce.history.server.HistoryRecorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Bruno Salmon
 */
public class ServerDocumentServiceProvider implements DocumentServiceProvider {


    /*------------------------------------------------------------------------------------------------------------------
    |                                                  LOAD                                                            |
    ------------------------------------------------------------------------------------------------------------------*/

    @Override
    public Future<DocumentAggregate> loadDocument(LoadDocumentArgument argument) {
        if (argument.historyPrimaryKey() == null) {
            return loadLatestDocumentsFromDatabase(argument, true)
                .map(Arrays::first);
        }
        return loadDocumentFromHistory(argument);
    }

    @Override
    public Future<DocumentAggregate[]> loadDocuments(LoadDocumentArgument argument) {
        return loadLatestDocumentsFromDatabase(argument, false);
    }

    private Future<DocumentAggregate[]> loadLatestDocumentsFromDatabase(LoadDocumentArgument argument, boolean limitTo1) {
        Object docPk = argument.documentPrimaryKey();
        boolean personProvided = argument.personPrimaryKey() != null;
        boolean accountProvided = argument.accountPrimaryKey() != null;

        if (!personProvided && !accountProvided)
            return loadBatchForDocumentPk(docPk, argument);

        // Two-round-trip approach:
        // Round 1 — fast indexed query on Document alone to resolve the PK(s).
        // Round 2 — loadBatchForDocumentPk() uses direct =$1 equality so the planner can
        //           satisfy every join with an index (document_line(document_id), etc.).
        //
        // A single-CTE approach was tried ("with td as (...) select ... from DocumentLine t, td
        // where t.document=$td.id") but PostgreSQL materialises the CTE as an opaque subquery.
        // The planner cannot push the join condition through parallel workers, so it falls back
        // to a full 1.26 M-row seq scan on document_line (~8 s).  Two round trips each hitting
        // only an index are far faster in practice.
        Object primaryKey = personProvided ? argument.personPrimaryKey() : argument.accountPrimaryKey();
        // %extra% adds "or id=$3" inside the parentheses when a known docPk must be included
        // alongside the latest doc for this person/event (e.g. the booking confirmation flow).
        String findDocsDql = "select id from Document where !cancelled and (%field%=$1 and event=$2%extra%) order by id desc%limit%"
                .replace("%field%", personProvided ? "person" : "person.frontendAccount")
                .replace("%extra%", docPk != null ? " or id=$3" : "")
                .replace("%limit%", limitTo1 ? " limit 1" : "");

        return resolveEventPk(argument.eventPrimaryKey()).compose(resolvedEventPk -> {
        Object eventPk = Numbers.toShortestNumber(resolvedEventPk);
        EntityStoreQuery findDocsQuery = docPk != null
                ? new EntityStoreQuery(findDocsDql, primaryKey, eventPk, docPk)
                : new EntityStoreQuery(findDocsDql, primaryKey, eventPk);

        return EntityStore.create()
                .executeQueryBatch(new EntityStoreQuery[]{findDocsQuery})
                .compose(entityLists -> {
                    @SuppressWarnings("unchecked")
                    List<Document> docs = (List<Document>) entityLists[0];
                    if (docs.isEmpty())
                        return Future.succeededFuture(new DocumentAggregate[0]);
                    // Pass null for argumentForAccessControl: round 1 already filtered by
                    // person/account, so the per-PK account check in loadBatchForDocumentPk
                    // is redundant and would incorrectly reject unauthenticated sessions that
                    // are allowed to book (e.g. noAccountBooking events).
                    if (docs.size() == 1)
                        return loadBatchForDocumentPk(docs.get(0).getPrimaryKey(), null);
                    // Multiple docs (loadDocuments path): load and flatten sequentially.
                    // Sequential is fine here — the per-doc batch is fast and the doc count is small.
                    Future<DocumentAggregate[]> combined = Future.succeededFuture(new DocumentAggregate[0]);
                    for (Document doc : docs) {
                        Object pk = doc.getPrimaryKey();
                        combined = combined.compose(prev ->
                            loadBatchForDocumentPk(pk, null).map(next -> {
                                DocumentAggregate[] merged = new DocumentAggregate[prev.length + next.length];
                                System.arraycopy(prev, 0, merged, 0, prev.length);
                                System.arraycopy(next, 0, merged, prev.length, next.length);
                                return merged;
                            })
                        );
                    }
                    return combined;
                });
        });
    }

    /**
     * Returns the 4 template EntityStoreQuery objects with {@code docPk} as the {@code $1}
     * parameter. For the CTE path {@code docPk} is {@code null} (the queries are rewritten
     * before execution); for the direct-PK path it holds the resolved document primary key.
     */
    private static EntityStoreQuery[] buildBatchQueries(Object docPk) {
        return new EntityStoreQuery[]{
            // 0 - Loading document
            new EntityStoreQuery("select creationDate,event,person,ref,inPerson,earlyBird,person_lang,person_firstName,person_lastName,person_email,person_age,person_facilityFee,request,person_carer1Name, person_carer1Document, person_carer2Name, person_carer2Document, arrived, checkedOut from Document where id=$1 order by id", docPk),
            // 1 - Loading document lines
            new EntityStoreQuery("select document,site,item,price_net,price_minDeposit,price_custom,price_discount" +
                                 ",share_owner,share_owner_quantity,share_owner_mate1Name,share_owner_mate2Name,share_owner_mate3Name,share_owner_mate4Name,share_owner_mate5Name,share_owner_mate6Name,share_owner_mate7Name" +
                                 ",share_mate,share_mate_ownerName,share_mate_ownerDocumentLine,share_mate_ownerPerson" +
                                 ",resourceConfiguration,pool,reserved,allocate,breakfastIncluded,cancelled,read" +
                                 " from DocumentLine where document=$1 and site!=null order by id", docPk),
            // 2 - Loading attendances
            new EntityStoreQuery("select documentLine,date,scheduledItem,videoAccessEnabled from Attendance where present and documentLine.document=$1 order by id", docPk),
            // 3 - Loading money transfers
            new EntityStoreQuery("select document,amount,pending,successful from MoneyTransfer where document=$1 order by id", docPk)
        };
    }

    /**
     * Executes the 4-query batch that loads a document aggregate by its direct primary key.
     * Called after the document PK has already been resolved (either passed in directly or
     * from a person/event lookup).
     */
    private Future<DocumentAggregate[]> loadBatchForDocumentPk(Object docPk, LoadDocumentArgument argumentForAccessControl) {
        EntityStoreQuery[] queries = buildBatchQueries(docPk);
        // Frontoffice access control: when loading by document PK only (no person/account
        // filter), restrict to documents accessible by the authenticated user.
        // Registered users: filtered by account via accountCanAccessPersonOrders().
        // Guest users (ModalityGuestPrincipal): filtered by person_email match.
        // Back-office callers bypass this check.
        if (argumentForAccessControl != null && docPk != null && !ThreadLocalStateHolder.isBackoffice()) {
            Object userId = ThreadLocalStateHolder.getUserId();
            Object userAccountId = getUserAccountId(userId);
            String guestEmail = getGuestEmail(userId);
            if (userAccountId == null && guestEmail == null) {
                return Future.failedFuture("Authentication required to access this document");
            }
            String docQuery = queries[0].getSelect();
            if (userAccountId != null) {
                // Registered user: filter by account-level access.
                docQuery = docQuery.replace(" order by ", " and accountCanAccessPersonOrders($2, person) order by ");
                queries[0] = new EntityStoreQuery(docQuery, docPk, userAccountId);
            } else {
                // Guest user: filter to documents whose person_email matches the guest's email.
                docQuery = docQuery.replace(" order by ", " and lower(person_email)=lower($2) order by ");
                queries[0] = new EntityStoreQuery(docQuery, docPk, guestEmail);
            }
        }
        return executeQueryBatchAndMap(queries);
    }

    /** Executes the 4-query batch and maps the results into {@link DocumentAggregate} instances. */
    private static Future<DocumentAggregate[]> executeQueryBatchAndMap(EntityStoreQuery[] queries) {
        return EntityStore.create()
            .executeQueryBatch(queries)
            .map(entityLists -> {
                Map<Document, List<AbstractDocumentEvent>> allDocumentEvents = new HashMap<>();
                // Creating initial document events (with AddDocumentEvent only)
                Collections.forEach((List<Document>) entityLists[0], document -> {
                    List<AbstractDocumentEvent> documentEvents = new ArrayList<>();
                    documentEvents.add(new AddDocumentEvent(document));
                    allDocumentEvents.put(document, documentEvents);
                    if (document.isPersonFacilityFee())
                        documentEvents.add(new ApplyFacilityFeeEvent(document, true));
                    if (!Strings.isBlank(document.getRequest()))
                        documentEvents.add(new AddRequestEvent(document, document.getRequest()));
                    if (!Strings.isBlank(document.getCarer1Name()) || !Strings.isBlank(document.getCarer2Name()) || document.getCarer1Document() != null || document.getCarer2Document() != null)
                        documentEvents.add(new EditCarersInfoEvent(document, document.getCarer1Name(), document.getCarer1Document(), document.getCarer2Name(), document.getCarer2Document()));
                    if (document.isArrived())
                        documentEvents.add(new MarkDocumentAsArrivedEvent(document, true));
                    if (document.isCheckedOut())
                        documentEvents.add(new MarkDocumentAsCheckedOutEvent(document, true));
                });
                // Aggregating document lines by adding AddDocumentLineEvent and PriceDocumentLineEvent for each document
                ((List<DocumentLine>) entityLists[1]).forEach(documentLine -> {
                    List<AbstractDocumentEvent> documentEvents = allDocumentEvents.get(documentLine.getDocument());
                    if (documentEvents == null) return; // document was filtered by access control
                    documentEvents.add(new AddDocumentLineEvent(documentLine, documentLine.isAllocate()));
                    documentEvents.add(new PriceDocumentLineEvent(documentLine));
                    // Rebuild the cancelled state so the client knows which lines are cancelled (e.g.
                    // in-person options cancelled when a booking switched to online). Without this the
                    // client treats them as active and recomputes them to £0; with it, the loaded
                    // price_net (the non-refundable charge) is used instead.
                    if (Boolean.TRUE.equals(documentLine.isCancelled()))
                        documentEvents.add(new CancelDocumentLineEvent(documentLine, true, Boolean.TRUE.equals(documentLine.isRead())));
                    if (documentLine.isShareOwner()) {
                        // Carry the persisted quantity (e.g. public-talk headcount) — the 2-arg
                        // constructor serializes quantity 0 and the client re-derives 1 + mates = 1.
                        Integer shareOwnerQuantity = documentLine.getShareOwnerQuantity();
                        documentEvents.add(new EditShareOwnerInfoDocumentLineEvent(documentLine, documentLine.getShareOwnerMatesNames(), shareOwnerQuantity != null ? shareOwnerQuantity : 0));
                    }
                    if (documentLine.isShareMate()) {
                        documentEvents.add(new EditShareMateInfoDocumentLineEvent(documentLine, documentLine.getShareMateOwnerName()));
                        DocumentLine ownerDocumentLine = documentLine.getShareMateOwnerDocumentLine();
                        Person ownerPerson = documentLine.getShareMateOwnerPerson();
                        if (ownerDocumentLine != null || ownerPerson != null) {
                            documentEvents.add(new LinkMateToOwnerDocumentLineEvent(documentLine, ownerDocumentLine, ownerPerson));
                        }
                    }
                    ResourceConfiguration resourceConfiguration = documentLine.getResourceConfiguration();
                    if (resourceConfiguration != null) {
                        // Synthesized events stay faithful to the row: the line's item and
                        // partition markers (reserved/pool) ride along so replay reconstructs
                        // them (they may differ from older events in the History stream).
                        documentEvents.add(new AllocateDocumentLineEvent(documentLine, resourceConfiguration,
                            documentLine.getItem(), documentLine.isReserved(), documentLine.getPool()));
                    }
                });
                // Aggregating attendances by adding AddAttendancesEvent for each document line
                ((List<Attendance>) entityLists[2]).stream().collect(Collectors.groupingBy(Attendance::getDocumentLine))
                    .forEach((documentLine, attendances) -> {
                        List<AbstractDocumentEvent> documentEvents = allDocumentEvents.get(documentLine.getDocument());
                        if (documentEvents == null) return; // document was filtered by access control
                        Attendance firstAttendance = Collections.first(attendances);
                        documentEvents.add(new AddAttendancesEvent(attendances.toArray(new Attendance[0]), firstAttendance != null && firstAttendance.isVideoAccessEnabled()));
                    });
                // Aggregating money transfers by Adding AddMoneyTransferEvent
                ((List<MoneyTransfer>) entityLists[3]).forEach(moneyTransfer -> {
                    List<AbstractDocumentEvent> documentEvents = allDocumentEvents.get(moneyTransfer.getDocument());
                    if (documentEvents == null) return; // document was filtered by access control
                    documentEvents.add(new AddMoneyTransferEvent(moneyTransfer));
                });
                return allDocumentEvents.values().stream()
                    .map(DocumentAggregate::new)
                    .toArray(DocumentAggregate[]::new);
            });
    }

    private Future<DocumentAggregate> loadDocumentFromHistory(LoadDocumentArgument argument) {
        // When loaded by docPk we don't need the event PK at all — execute directly.
        if (argument.documentPrimaryKey() != null) {
            return runLoadDocumentFromHistory(
                "select changes from History where document=$1 and id<=$2 order by id",
                new Object[]{argument.documentPrimaryKey(), argument.historyPrimaryKey()});
        }
        // When loaded by person+event, eventPrimaryKey() may be a slug — resolve first.
        return resolveEventPk(argument.eventPrimaryKey()).compose(resolvedEventPk -> runLoadDocumentFromHistory(
            "select changes from History where document=(select Document where person=$1 and event=$2 and !cancelled order by id desc limit 1) and id<=$3 order by id",
            new Object[]{argument.personPrimaryKey(), resolvedEventPk, argument.historyPrimaryKey()}));
    }

    private static Future<DocumentAggregate> runLoadDocumentFromHistory(String select, Object[] parameters) {
        return EntityStore.create()
            .<History>executeQuery(select, parameters)
            .map(historyList -> {
                if (historyList.isEmpty()) {
                    return null;
                }
                List<AbstractDocumentEvent> documentEvents = new ArrayList<>();
                historyList.forEach(history -> {
                    ReadOnlyAstArray astArray = AST.parseArray(history.getChanges(), "json");
                    if (astArray != null) { // This case may come from KBS2
                        AbstractDocumentEvent[] events = SerialCodecManager.decodeAstArrayToJavaArray(astArray, AbstractDocumentEvent.class);
                        documentEvents.addAll(Arrays.asList(events));
                    }
                });
                return new DocumentAggregate(documentEvents);
            });
    }

    // Resolves a raw event PK that may be either a numeric ID or a slug (URL-friendly
    // event identifier) to a value usable in DQL `where id=$1`/`event=$1` lookups.
    // Numeric Strings and Numbers pass through; non-numeric Strings are looked up via
    // Event.slug. A missing slug results in a failed Future.
    private static Future<Object> resolveEventPk(Object rawEventPk) {
        if (rawEventPk == null || rawEventPk instanceof Number) {
            return Future.succeededFuture(rawEventPk);
        }
        if (!(rawEventPk instanceof String)) {
            return Future.succeededFuture(rawEventPk);
        }
        String s = (String) rawEventPk;
        Long parsedId = Numbers.parseLong(s);
        if (parsedId != null) {
            return Future.succeededFuture(parsedId);
        }
        return EntityStore.create()
            .<Event>executeQuery("select id from Event where slug=$1", s)
            .compose(events -> events.isEmpty()
                ? Future.failedFuture(new IllegalArgumentException("No event found for slug: " + s))
                : Future.succeededFuture(events.get(0).getPrimaryKey()));
    }



    /*------------------------------------------------------------------------------------------------------------------
    |                                                 SUBMIT                                                           |
    ------------------------------------------------------------------------------------------------------------------*/

    @Override
    public Future<SubmitDocumentChangesResult> submitDocumentChanges(SubmitDocumentChangesArgument argument) {
        DocumentSubmitRequest request = DocumentSubmitRequest.create(argument);
        // A read-only session (a support member viewing a customer's front office) must not be able
        // to book, cancel or amend anything on that customer's behalf.
        //
        // The check has to be HERE, against the principal the request captured at creation, and not
        // at the SQL layer where writes are otherwise refused: by the time this flow reaches
        // submitChanges() it has been through an async database read, and — as the comment in
        // submitDocumentChangesNow() below records for the same reason — the thread-local no longer
        // holds the caller's session, so a check down there would see no principal and let the write
        // through.
        if (RestrictedPrincipalRegistry.isUserRestricted(request.userId()))
            return Future.failedFuture("[ReadOnlySessionError] This session is not allowed to modify data");
        if (request.document() == null)
            return Future.failedFuture("No document changes to submit");
        if (request.runId() == null) {
            String message = "No runId could be found for push notification";
            Console.log(message + " - " + argument.historyComment());
            return Future.failedFuture(message);
        }

        // Delegating the call to DocumentSubmitController, which will handle the complexity of the event queue (on big
        // events bookings openings). If no event queue is required, it will call back submitDocumentChangesNow()
        // straight away. Otherwise it will enqueue the submission for later processing but immediately return a result
        // with status ENQUEUED. At the booking opening time, the queue will process the enqueued submissions and call
        // submitDocumentChangesNow() for each of them, and communicate the final result to the clients using push
        // notification.
        return DocumentSubmitController.submitDocumentChanges(request);
    }

    static Future<SubmitDocumentChangesResult> submitDocumentChangesNow(DocumentSubmitRequest request) {
        // Use the userId captured at request-creation time (in DocumentSubmitRequest.create) rather than
        // re-reading ThreadLocalStateHolder here. When a booking is deferred through the event queue and
        // processed later by DocumentSubmitEventQueue, the Vert.x context has changed and the ThreadLocal
        // no longer holds the original caller's session — it would return null, incorrectly making every
        // queued registered-user booking look like a guest booking and generating a spurious magic link.
        Object userId = request.userId();
        boolean isGuestBooking = getUserAccountId(userId) == null;
        String clientOrigin = request.argument().clientOrigin();

        // Note: At this point, the document may be null, but in that case we at least have documentLine not null
        return HistoryRecorder.prepareDocumentHistoriesBeforeSubmit(request.argument().historyComment(), request.document(), request.documentLine(), userId)
            .compose(histories -> { // At this point, history.getDocument() is never null (resolved through DB reading)
                Document document = histories[0].getDocument();

                return submitChangesAndPrepareResult(request.updateStore(), document, request.backoffice())
                    .compose(result -> { // Completing the history recording (changes column with resolved primary keys)
                        if (result.status() == DocumentChangesStatus.APPROVED) {
                            // For guest bookings: record the magic link and link it to the cart. The
                            // link's bearer token is minted by MagicLinkService from a secure source —
                            // this caller neither supplies nor sees it.
                            // The bracket pattern [bookingUrl] no longer needs the token on the document —
                            // it now derives the cart URL from person.frontend_account_id check.
                            if (isGuestBooking && clientOrigin != null) {
                                registerBookingAccessMagicLink(
                                    result.documentPrimaryKey(),
                                    result.cartPrimaryKey(),
                                    document.getEmail(),
                                    document.getPersonLang(),
                                    clientOrigin
                                );
                            }
                            return HistoryRecorder.completeDocumentHistoriesAfterSubmit(histories, request.argument().documentEvents())
                                .map(ignoredVoid -> result);
                        }
                        return Future.succeededFuture(result); // submit refused by logic (e.g. sold-out)
                    });
            });
    }

    private static Future<SubmitDocumentChangesResult> submitChangesAndPrepareResult(UpdateStore updateStore, Document document, boolean backoffice) {
        // The transaction parameters drive the allocation semantics in
        // deferred_allocate_document_line(): a FRONT-office submit is subject to
        // the frontend gates (online/gender/partition/capacity — the client is
        // not trusted), while a BACK-office submit is authoritative (explicit
        // resource_configuration/reserved/pool honoured — the "back-office drop"
        // mode). The origin comes from the session state captured at request
        // time (see DocumentSubmitRequest), NOT from the argument.
        return updateStore.submitChanges(backoffice ? Triggers.backOfficeTransaction(updateStore) : Triggers.frontOfficeTransaction(updateStore))
            .compose(batch -> {
                Object documentPk = document.getPrimaryKey();
                Object documentRef = document.getRef();
                Object cartPk = null;
                String cartUuid = null;
                Cart cart = document.getCart();
                if (cart != null) {
                    cartPk = cart.getPrimaryKey();
                    cartUuid = cart.getUuid();
                }
                if (cartUuid == null || documentRef == null) {
                    return document.onExpressionLoaded("ref,cart.uuid")
                        .map(x -> SubmitDocumentChangesResult.createApprovedResult(
                            document.getPrimaryKey(),
                            document.getRef(),
                            document.getCart().getPrimaryKey(),
                            document.getCart().getUuid()
                        ));
                }
                return Future.succeededFuture(SubmitDocumentChangesResult.createApprovedResult(documentPk, documentRef, cartPk, cartUuid));
            })
            // Detecting sold-out exception from the database
            .recover(ex -> {
                String message = ex.getMessage();
                if (message != null) {
                    // If it's a double-booking exception
                    if (message.contains("DOUBLEBOOKING")) { // PLSQL trigger_document_auto_ref() code: raise exception 'DOUBLEBOOKING';
                        return Future.succeededFuture(SubmitDocumentChangesResult.createAlreadyBookedResult());
                    }
                    // If it's an event-on-hold exception
                    if (message.contains("EVENT_ON_HOLD")) { // PLSQL trigger_document_auto_ref() code: raise exception 'EVENT_ON_HOLD';
                        return Future.succeededFuture(SubmitDocumentChangesResult.createEventOnHoldResult());
                    }
                    // If it's a sold-out exception
                    if (message.contains("SoldOut".toUpperCase())) { // PLSQL deferred_allocate_document_line() code: RAISE EXCEPTION 'SOLDOUT ...';
                        Object sitePk = readSiteOrItemPrimaryKey(message, true);
                        Object itemPk = readSiteOrItemPrimaryKey(message, false);
                        return Future.succeededFuture(SubmitDocumentChangesResult.createSoldOutResult(sitePk, itemPk));
                    }
                }
                // If none of the above, it's a technical exception, so we return it as is
                return Future.failedFuture(ex);
            });
    }

    private static Object readSiteOrItemPrimaryKey(String message, boolean isSite) {
        // PLSQL deferred_allocate_document_line() code: RAISE EXCEPTION 'SOLDOUT site_id=%, item_id=% (no resource found)', NEW.site_id, NEW.item_id;
        String token = isSite ? "site_id=" : "item_id=";
        int index = message.indexOf(token);
        if (index >= 0) {
            int start = index + token.length();
            int end = start;
            while (end < message.length() && Character.isDigit(message.charAt(end))) {
                end++;
            }
            if (end > start) {
                return Integer.parseInt(message.substring(start, end));
            }
        }
        return null;
    }

    /**
     * Extracts the user's frontend account ID from the userId principal object.
     * Uses reflection to avoid a direct module dependency on modality.crm.shared.authn.
     */
    private static Object getUserAccountId(Object userId) {
        if (userId == null) return null;
        try {
            return userId.getClass().getMethod("getUserAccountId").invoke(userId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the email from a ModalityGuestPrincipal, or null for any other principal type.
     * Uses reflection to avoid a direct module dependency on modality.crm.shared.authn.
     */
    private static String getGuestEmail(Object userId) {
        if (userId == null) return null;
        try {
            // ModalityGuestPrincipal has getEmail(); ModalityUserPrincipal does not.
            // If the method exists and the principal has no getUserAccountId, it's a guest.
            if (userId.getClass().getMethod("getUserAccountId").invoke(userId) != null)
                return null; // registered user — not a guest
            return (String) userId.getClass().getMethod("getEmail").invoke(userId);
        } catch (NoSuchMethodException e) {
            // getEmail() exists but getUserAccountId() does not — pure guest principal
            try {
                return (String) userId.getClass().getMethod("getEmail").invoke(userId);
            } catch (Exception ignored) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Persists the magic_link record for a guest booking. Fire-and-forget — failures are
     * logged but do not affect the booking result already returned to the client.
     * The confirmation email was already queued by the DB trigger during the document INSERT.
     */
    private static void registerBookingAccessMagicLink(Object documentPk, Object cartPk, String personEmail, String personLang, String clientOrigin) {
        if (personEmail == null || clientOrigin == null) return;
        ServiceLoader<GuestBookingAccessService> loader = ServiceLoader.load(GuestBookingAccessService.class);
        for (GuestBookingAccessService service : loader) {
            service.registerBookingAccessMagicLink(
                documentPk, cartPk, personEmail, personLang, clientOrigin,
                DataSourceModelService.getDefaultDataSourceModel()
            ).onFailure(err -> Console.log("GuestBookingAccessService failed for document " + documentPk + ": " + err));
            break; // use first registered implementation
        }
    }

    @Override
    public Future<Boolean> leaveEventQueue(Object queueToken) {
        return Future.succeededFuture(DocumentSubmitController.leaveEventQueue(queueToken));
    }

    @Override
    public Future<SubmitDocumentChangesResult> fetchEventQueueResult(Object queueToken) {
        return Future.succeededFuture(DocumentSubmitController.fetchEventQueueResult(queueToken));
    }
}
