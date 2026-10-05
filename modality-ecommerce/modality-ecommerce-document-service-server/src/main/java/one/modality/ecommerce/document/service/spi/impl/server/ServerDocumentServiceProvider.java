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
import one.modality.ecommerce.document.service.events.book.EditShareOwnerInfoDocumentLineEvent;
import one.modality.ecommerce.document.service.events.registration.documentline.LinkMateToOwnerDocumentLineEvent;
import one.modality.base.shared.entities.DocumentLine;
import dev.webfx.stack.orm.entity.Entities;
import dev.webfx.stack.orm.entity.Entity;
import one.modality.ecommerce.document.service.events.registration.documentline.PriceDocumentLineEvent;
import one.modality.ecommerce.document.service.spi.DocumentServiceProvider;
import one.modality.ecommerce.document.service.util.SharingPlaceAvailability;
import one.modality.ecommerce.history.server.HistoryRecorder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
        // A roommate link names the booking line it points at, and the replay below would write whatever the
        // client sent — so a link is judged against the database BEFORE it can be queued (MateLinkRules).
        return validateMateLinks(request)
            // And the flag that decides a line's PRICE is judged here too, before the queue: a line may be
            // born a sharing place but never become one (refuseShareMateFlagAcquisition).
            .compose(ignored -> refuseShareMateFlagAcquisition(request))
            .compose(ignored -> DocumentSubmitController.submitDocumentChanges(request));
    }

    static Future<SubmitDocumentChangesResult> submitDocumentChangesNow(DocumentSubmitRequest request) {
        // A sharing place with no bed left is refused first. Here rather than before the queue: a booking
        // opening's burst is absorbed before any policy is loaded, the count is as fresh as it can be, and
        // by now the event is known even for a modification.
        return refuseSharingPlaceWithoutFreeBed(request).compose(soldOut -> soldOut != null
            ? Future.succeededFuture(soldOut)
            : submitDocumentChangesAfterChecks(request));
    }

    private static Future<SubmitDocumentChangesResult> submitDocumentChangesAfterChecks(DocumentSubmitRequest request) {
        // Use the userId captured at request-creation time (in DocumentSubmitRequest.create) rather than
        // re-reading ThreadLocalStateHolder here. When a booking is deferred through the event queue and
        // processed later by DocumentSubmitEventQueue, the Vert.x context has changed and the ThreadLocal
        // no longer holds the original caller's session — it would return null, incorrectly making every
        // queued registered-user booking look like a guest booking and generating a spurious magic link.
        Object userId = request.userId();
        // Whose booking may need a guest link: a guest's own, and any that staff enter in the back office — for
        // somebody who may well have no account. Which of them get one is decided from the database
        // (GuestBookingAccessService), never from this request. The backoffice flag, which the client can sway,
        // only decides whether to look: claiming it on a front-office account's own booking just costs a lookup.
        boolean mayNeedGuestLink = getUserAccountId(userId) == null || request.backoffice();

        // Note: At this point, the document may be null, but in that case we at least have documentLine not null
        return HistoryRecorder.prepareDocumentHistoriesBeforeSubmit(request.argument().historyComment(), request.document(), request.documentLine(), userId)
            .compose(histories -> { // At this point, history.getDocument() is never null (resolved through DB reading)
                Document document = histories[0].getDocument();

                return submitChangesAndPrepareResult(request.updateStore(), document, request.backoffice())
                    .compose(result -> { // Completing the history recording (changes column with resolved primary keys)
                        if (result.status() == DocumentChangesStatus.APPROVED) {
                            // Somebody without an account opens their booking from the letter's /cart/ button,
                            // through the cart's guest link. Whether the cart gets (or keeps) one, for which
                            // address, on which host, with which token: all decided and minted server-side —
                            // this caller supplies none of it and never sees the token.
                            if (mayNeedGuestLink)
                                syncCartAccessLink(result.cartPrimaryKey());
                            return HistoryRecorder.completeDocumentHistoriesAfterSubmit(histories, request.argument().documentEvents())
                                .compose(ignoredVoid -> consumeMateInviteTokenIfPresent(request, result))
                                .compose(r -> linkBookerMateIfPresent(request, r))
                                .compose(r -> reportRoomLineIfBooked(request, r));
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
    /**
     * Refuses a submit carrying a roommate link that fails {@link MateLinkRules}, before anything is
     * queued or written. Loads only what the rules judge: the submitting account's back-office flag and,
     * for each link, the mate line and the owner line it names. A submit with no link passes at once, so
     * every other booking flow is untouched.
     */
    private static Future<Void> validateMateLinks(DocumentSubmitRequest request) {
        List<LinkMateToOwnerDocumentLineEvent> links = new ArrayList<>();
        for (AbstractDocumentEvent documentEvent : request.argument().documentEvents())
            if (documentEvent instanceof LinkMateToOwnerDocumentLineEvent link)
                links.add(link);
        if (links.isEmpty())
            return Future.succeededFuture();

        // The account's own flag, read from the database — the session's backoffice flag is client-asserted.
        Object accountId = getUserAccountId(request.userId());
        Future<Boolean> backofficeAccount = accountId == null
            ? Future.succeededFuture(null)
            : EntityStore.create().<Entity>executeQuery("select backoffice from FrontendAccount where id=$1", accountId)
                .map(accounts -> accounts.isEmpty() ? null : Boolean.TRUE.equals(accounts.get(0).getBooleanFieldValue("backoffice")));
        return backofficeAccount.compose(isBackofficeAccount ->
            validateMateLinksFrom(links, 0, request.backoffice(), isBackofficeAccount, accountId));
    }

    /** Judges the links one after another; the first refusal fails the whole submit. */
    private static Future<Void> validateMateLinksFrom(List<LinkMateToOwnerDocumentLineEvent> links, int index,
                                                     boolean backofficeSession, Boolean backofficeAccount,
                                                     Object submitterAccountId) {
        if (index >= links.size())
            return Future.succeededFuture();
        LinkMateToOwnerDocumentLineEvent link = links.get(index);
        Object matePk = link.getDocumentLinePrimaryKey();
        Object ownerPk = link.getOwnerDocumentLine();
        boolean ownerPersonNamed = link.getOwnerPerson() != null;
        if (matePk == null)
            return refuseOrContinue(MateLinkRules.check(backofficeSession, backofficeAccount, null, ownerPk != null, null, ownerPersonNamed, submitterAccountId),
                links, index, backofficeSession, backofficeAccount, submitterAccountId);
        // document.person.frontendAccount is who OWNS the booking, which the front-office rule judges the
        // link by — the same path mint checks before it will issue a token for a room.
        return linesOwnedBy(submitterAccountId, matePk, ownerPk).compose(owned ->
            EntityStore.create().<DocumentLine>executeQuery(
                "select share_mate, share_owner, item.share_mate, document.event from DocumentLine where id=$1 or id=$2",
                matePk, ownerPk != null ? ownerPk : matePk)
            .compose(lines -> {
                MateLinkRules.LineFacts mate = null, owner = null;
                for (DocumentLine line : lines) {
                    Object linePk = line.getPrimaryKey();
                    MateLinkRules.LineFacts facts = lineFacts(line, isOwned(owned, linePk));
                    if (MateLinkRules.sameId(linePk, matePk))
                        mate = facts;
                    if (ownerPk != null && MateLinkRules.sameId(linePk, ownerPk))
                        owner = facts;
                }
                return refuseOrContinue(MateLinkRules.check(backofficeSession, backofficeAccount, mate, ownerPk != null, owner, ownerPersonNamed, submitterAccountId),
                    links, index, backofficeSession, backofficeAccount, submitterAccountId);
            }));
    }

    /**
     * Refuses a submit that turns a line it did not create into a sharing place — the flag that prices it
     * at nothing (room-mate plan, "Security and personal data" item 7).
     *
     * <p>The rule is {@link MateLinkRules#checkShareMateFlag}; this loads the facts it judges. Costs
     * nothing on ordinary traffic: a submit whose share-mate events all belong to lines it is adding —
     * which is every submit the booking form produces when a mate books — asks the database nothing.
     *
     * <p>Placed beside {@code validateMateLinks} rather than inside
     * {@code refuseSharingPlaceWithoutFreeBed} for two reasons: that guard answers "is there a bed",
     * whose answer is a SOLD_OUT result, while this answers "may you say this at all", whose answer is a
     * refusal; and like a link, a crafted flag should be refused before the submit is queued rather than
     * when the queue gets to it.
     */
    private static Future<Void> refuseShareMateFlagAcquisition(DocumentSubmitRequest request) {
        Set<String> addedLinePks = new HashSet<>();
        List<Object> flaggedLinePks = new ArrayList<>();
        // One pass, then the filter: an Add event may follow the Edit that flags its line, and a submit
        // whose order decided whether a line could be free would be a submit worth reordering.
        for (AbstractDocumentEvent documentEvent : request.argument().documentEvents()) {
            if (documentEvent instanceof AddDocumentLineEvent add)
                addedLinePks.add(String.valueOf(add.getDocumentLinePrimaryKey()));
            else if (documentEvent instanceof EditShareMateInfoDocumentLineEvent mate)
                flaggedLinePks.add(mate.getDocumentLinePrimaryKey());
        }
        flaggedLinePks.removeIf(linePk -> addedLinePks.contains(String.valueOf(linePk)));
        if (flaggedLinePks.isEmpty())
            return Future.succeededFuture();
        // Only now, on the path no ordinary submit takes: the account's own flag, from the database.
        Object accountId = getUserAccountId(request.userId());
        Future<Boolean> backofficeAccount = accountId == null
            ? Future.succeededFuture(null)
            : EntityStore.create().<Entity>executeQuery("select backoffice from FrontendAccount where id=$1", accountId)
                .map(accounts -> accounts.isEmpty() ? null : Boolean.TRUE.equals(accounts.get(0).getBooleanFieldValue("backoffice")));
        return backofficeAccount.compose(isBackofficeAccount ->
            refuseFlagAcquisitionFrom(request.backoffice(), isBackofficeAccount, flaggedLinePks, 0));
    }

    /** One line at a time, as the neighbours walk their lists: the list is empty on every ordinary submit. */
    private static Future<Void> refuseFlagAcquisitionFrom(boolean backofficeSession, Boolean backofficeAccount,
                                                         List<Object> linePks, int index) {
        if (index >= linePks.size())
            return Future.succeededFuture();
        Object linePk = linePks.get(index);
        return EntityStore.create().<DocumentLine>executeQuery(
                "select share_mate from DocumentLine where id=$1", linePk)
            .compose(lines -> {
                Boolean alreadySharing = lines.isEmpty() ? null : Boolean.TRUE.equals(lines.get(0).isShareMate());
                String refusal = MateLinkRules.checkShareMateFlag(backofficeSession, backofficeAccount,
                    false, alreadySharing);
                if (refusal != null) {
                    // The line and the caller stay in the log, where the message to the client does not put
                    // them: this is reached only by a crafted submit, so it is worth being able to see whose.
                    Console.log(refusal + " (line=" + linePk + ", alreadySharing=" + alreadySharing + ")");
                    return Future.failedFuture(refusal);
                }
                return refuseFlagAcquisitionFrom(backofficeSession, backofficeAccount, linePks, index + 1);
            });
    }

    private static Future<Void> refuseOrContinue(String refusal, List<LinkMateToOwnerDocumentLineEvent> links, int index,
                                                 boolean backofficeSession, Boolean backofficeAccount,
                                                 Object submitterAccountId) {
        if (refusal != null) {
            Console.log(refusal);
            return Future.failedFuture(refusal);
        }
        return validateMateLinksFrom(links, index + 1, backofficeSession, backofficeAccount, submitterAccountId);
    }

    /**
     * The facts {@link MateLinkRules} judges, from a line loaded with share_mate, share_owner,
     * item.share_mate and document.event. Ownership is not among them: it is a question about the
     * SUBMITTER, answered by {@link #linesOwnedBy} and passed in.
     */
    /**
     * Which of {@code lineIds} belong to a booking the account may act on, by the same
     * {@code accountCanAccessPersonOrders} predicate the orders page and the read inventory use — so a
     * room booked FOR a household member, whose person row has no frontendAccount of its own and reaches
     * the account through accountPerson, counts as theirs. Asked as a WHERE clause rather than selected,
     * which is how that function is used everywhere else; selecting it would mean projecting four levels
     * of foreign key and trusting an emission path nothing else exercises.
     *
     * <p>An empty set for a guest: with no account there is nothing to compare against, and the rule
     * refuses on that alone.
     */
    private static Future<List<Object>> linesOwnedBy(Object accountId, Object lineId1, Object lineId2) {
        if (accountId == null)
            return Future.succeededFuture(new ArrayList<>());
        return EntityStore.create().<DocumentLine>executeQuery(
                "select id from DocumentLine where (id=$1 or id=$2) and accountCanAccessPersonOrders($3, document.person)",
                lineId1, lineId2 != null ? lineId2 : lineId1, accountId)
            .map(lines -> {
                List<Object> owned = new ArrayList<>();
                for (DocumentLine line : lines)
                    owned.add(line.getPrimaryKey());
                return owned;
            });
    }

    /** Whether {@code owned} names this line — primary keys compared by value, as everywhere else here. */
    private static boolean isOwned(List<Object> owned, Object linePk) {
        for (Object ownedPk : owned)
            if (MateLinkRules.sameId(ownedPk, linePk))
                return true;
        return false;
    }

    private static MateLinkRules.LineFacts lineFacts(DocumentLine line, boolean owned) {
        Entity item = line.getForeignEntity("item");
        Entity document = line.getForeignEntity("document");
        boolean shareMate = Boolean.TRUE.equals(line.getBooleanFieldValue("share_mate"))
                            || item != null && Boolean.TRUE.equals(item.getBooleanFieldValue("share_mate"));
        Object documentPk = line.getForeignEntityId("document") == null ? null : Entities.getPrimaryKey(line.getForeignEntityId("document"));
        Object eventPk = document == null || document.getForeignEntityId("event") == null ? null : Entities.getPrimaryKey(document.getForeignEntityId("event"));
        return new MateLinkRules.LineFacts(shareMate, Boolean.TRUE.equals(line.getBooleanFieldValue("share_owner")), documentPk, eventPk, owned);
    }

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
     * Brings the cart's guest link in line with its bookings. Fire-and-forget — failures are
     * logged but do not affect the booking result already returned to the client.
     * The confirmation email was already queued by the DB trigger during the document INSERT,
     * and links to the cart, not to this link — so a first click racing this still works
     * (the cart page mints the link itself when the cart has none).
     */
    private static void syncCartAccessLink(Object cartPk) {
        if (cartPk == null) return;
        ServiceLoader<GuestBookingAccessService> loader = ServiceLoader.load(GuestBookingAccessService.class);
        for (GuestBookingAccessService service : loader) {
            service.syncCartAccessLink(cartPk, DataSourceModelService.getDefaultDataSourceModel())
                .onFailure(err -> Console.log("GuestBookingAccessService failed for cart " + cartPk + ": " + err));
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

    @Override
    public Future<String> mintMateInviteToken(Object documentId) {
        // Capture the caller synchronously — the thread-local is gone after the first async hop.
        Object accountId = getUserAccountId(ThreadLocalStateHolder.getUserId());
        return authorisedRoomOf(documentId, accountId).compose(ownerLine ->
            MateInviteTokenStore.mint(ownerLine.ownerDocumentLineId(), ownerLine.eventId(), accountId));
    }

    /**
     * The room line of the caller's OWN booking, with a bed still free in it — or a failure saying which
     * of those was not true.
     *
     * <p>Shared by the two ways a bed is offered, the link the booker copies and the invitation we send
     * on their behalf, because they are the same act with different delivery and a second copy of these
     * checks is a second thing to keep right.
     */
    private Future<MateInviteTokenStore.OwnerLine> authorisedRoomOf(Object documentId, Object accountId) {
        if (accountId == null)
            return Future.failedFuture("[MateInviteError] Only a signed-in booker can create a room-share invite");
        if (documentId == null)
            return Future.failedFuture("[MateInviteError] No booking to invite for");
        return MateInviteTokenStore.loadOwnerLineForBooking(documentId).compose(ownerLine -> {
            if (ownerLine == null)
                return Future.failedFuture("[MateInviteError] This booking has no room booking that can be shared");
            if (!MateLinkRules.sameId(ownerLine.frontendAccountId(), accountId))
                return Future.failedFuture("[MateInviteError] You can only invite a roommate to your own booking");
            // Refuse early when the room is already full, so the booker is told now rather than
            // after sending a link that would be refused at the other end. The link itself is
            // guarded too (the capacity test is inside that UPDATE) — this is the friendly half.
            return MateInviteTokenStore.hasFreeBed(ownerLine.ownerDocumentLineId()).compose(free -> {
                if (!free)
                    // A stable code the client can recognise, rather than prose it would have to match:
                    // a full room is not an error from the booker's point of view — everyone has booked
                    // — and the page should say so instead of reporting a failure.
                    return Future.failedFuture("[MateInviteError:ROOM_FULL] Every bed in this room is already taken");
                return Future.succeededFuture(ownerLine);
            });
        });
    }

    @Override
    public Future<Integer> revokeMateInvitations(Object documentId, int mateSlot) {
        Object accountId = getUserAccountId(ThreadLocalStateHolder.getUserId());
        if (accountId == null)
            return Future.failedFuture("[MateInviteError] Only a signed-in booker can stop an invitation");
        if (documentId == null)
            return Future.succeededFuture(0);
        return MateInviteTokenStore.loadOwnerLineForBooking(documentId).compose(ownerLine -> {
            // Nothing to stop, and — for a booking that is somebody else's — the same answer as a
            // booking with no room, for the reason the read beside this one gives: two distinguishable
            // answers are an oracle, and this one is reachable without a booking of your own.
            if (ownerLine == null || !MateLinkRules.sameId(ownerLine.frontendAccountId(), accountId))
                return Future.succeededFuture(0);
            // By BOOKING, not by the line the lookup happened to pick: the room above is the ownership
            // check, and a booking owning two share-owner lines must not have half its links unreachable.
            return MateInviteTokenStore.revokeInvitations(documentId, mateSlot);
        });
    }

    @Override
    public Future<String> listMateInvitations(Object documentId) {
        Object accountId = getUserAccountId(ThreadLocalStateHolder.getUserId());
        // The same gate as the two ways of offering a bed, minus the free-bed test: a booker whose room
        // has filled still has every right to see who was written to on its behalf.
        if (accountId == null)
            return Future.failedFuture("[MateInviteError] Only a signed-in booker can see their invitations");
        if (documentId == null)
            return Future.succeededFuture("[]");
        return MateInviteTokenStore.loadOwnerLineForBooking(documentId).compose(ownerLine -> {
            if (ownerLine == null)
                // No room, so no invitations — not an error, just nothing to show.
                return Future.succeededFuture("[]");
            if (!MateLinkRules.sameId(ownerLine.frontendAccountId(), accountId))
                // Nothing, rather than "not yours". A booking that does not exist, and one that is
                // somebody else's, must answer alike: this is a cheap side-effect-free read with no
                // throttle, so two distinguishable answers are an oracle for walking document ids to
                // find live bookings holding a shareable room. The client treats both the same way.
                return Future.succeededFuture("[]");
            return MateInviteTokenStore.loadInvitations(ownerLine.ownerDocumentLineId());
        });
    }

    @Override
    public Future<Void> sendMateInvitation(Object documentId, int mateSlot, String email, String lang) {
        // Captured synchronously, before the first async hop, like every other caller-derived value here.
        Object accountId = getUserAccountId(ThreadLocalStateHolder.getUserId());
        if (mateSlot < 1 || mateSlot > 7)
            return Future.failedFuture("[MateInviteError] That is not a roommate slot of this room");
        String address = email == null ? "" : email.trim();
        // Shape only, and deliberately not a strict parser: the point is to refuse junk and anything
        // carrying a newline, not to adjudicate what a mailbox may be called.
        if (address.isEmpty() || address.length() > 127 || !address.matches("[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+"))
            return Future.failedFuture("[MateInviteError] That is not an address we can write to");
        String origin = MateInvitationMail.configuredFrontOfficeOrigin();
        if (origin == null)
            // The booker is told, rather than a mail going out whose only button leads nowhere. The cart
            // still offers them the link to copy, so this is recoverable where a broken link is not.
            return Future.failedFuture("[MateInviteError:NO_ORIGIN] Invitations are not configured on this server");
        // Before any work, and before the room is even loaded: a refusal here must cost nothing and leak
        // nothing about the booking, and starting the window only on success would let a tight loop keep
        // the gate open by failing.
        if (!MateInvitationMail.maySend(address, documentId, mateSlot))
            return Future.failedFuture("[MateInviteError:TOO_SOON] That invitation has just been sent — give it a few minutes");
        return authorisedRoomOf(documentId, accountId).compose(ownerLine ->
            MateInviteTokenStore.loadInvitationFacts(ownerLine.ownerDocumentLineId(), mateSlot).compose(facts -> {
                if (facts == null || facts.mateName() == null || facts.mateName().isBlank())
                    // Nobody is named in that slot, so there is nobody to invite. Refused rather than
                    // sent to "Dear ,": the name is read from the booking, never from the request, and an
                    // empty one means the request and the booking disagree about who is in the room.
                    return Future.failedFuture("[MateInviteError] No roommate is named in that slot");
                return MateInviteTokenStore.mint(ownerLine.ownerDocumentLineId(), ownerLine.eventId(), accountId, true)
                    .compose(rawToken -> MateInvitationMail.send(
                        MateInviteTokenStore.hashToken(rawToken),
                        MateInvitationMail.inviteLink(origin, ownerLine.eventId(), rawToken),
                        mateSlot, address, facts.mateName(), facts.bookerName(), facts.eventName(), lang));
            }));
    }

    @Override
    public Future<String> resolveMateInvite(String token, Object eventId) {
        // No authentication: the person following an invite may not have an account yet. Safe only
        // because the reply is a bare status and never names the booker, the room or the booking —
        // see MateInviteTokenStore's status constants.
        if (token == null || token.isBlank() || eventId == null)
            return Future.succeededFuture(MateInviteTokenStore.STATUS_UNKNOWN);
        // Optional — a guest simply has none. Captured synchronously: the thread-local is gone after
        // the first async hop. It only ever turns FULL into JOINED for an account already in the room.
        Object callerAccountId = getUserAccountId(ThreadLocalStateHolder.getUserId());
        return MateInviteTokenStore.resolve(token, eventId).compose(ownerLineId -> {
            if (ownerLineId == null)
                // Not resolvable: either genuinely unknown, or issued for another event (which must
                // not be confirmed), or lapsed — only the last is worth telling apart.
                return MateInviteTokenStore.isExpired(token, eventId)
                    .map(expired -> MateInviteTokenStore.statusOf(false, expired, false, false));
            return MateInviteTokenStore.hasFreeBed(ownerLineId).compose(free -> {
                if (free || callerAccountId == null)
                    return Future.succeededFuture(MateInviteTokenStore.statusOf(true, false, free, false));
                return MateInviteTokenStore.accountHoldsBedInRoom(ownerLineId, callerAccountId)
                    .map(holds -> MateInviteTokenStore.statusOf(true, false, false, holds));
            });
        }).recover(e -> {
            // Left to FAIL rather than answered UNKNOWN: a lookup that errored says nothing about the
            // link, and the client carries the token on a failed lookup but drops it on UNKNOWN — so
            // reporting UNKNOWN here would strip a good link during a brief database outage. The
            // failure is generic because the caller is unauthenticated: the database's own message
            // (query text, constraint names) stays in the server log.
            Console.log("[MateInvite] resolve errored: " + e);
            return Future.failedFuture("[MateInviteError] The invite link could not be checked");
        });
    }

    @Override
    public Future<String> describeMateInviteRoom(String token, Object eventId) {
        // No authentication, as for resolveMateInvite: the invited mate may not have an account yet. What
        // it discloses was agreed for step 7 — the room's accommodation item and the room booking's first
        // and last day, never a name or a booking reference — so the booking form can choose the matching
        // sharing option and part of the event for the mate. Only for a link that can still be followed:
        // an unknown, expired or full link describes nothing.
        if (token == null || token.isBlank() || eventId == null)
            return Future.succeededFuture("");
        return MateInviteTokenStore.resolve(token, eventId).compose(ownerLineId -> {
            if (ownerLineId == null)
                return Future.succeededFuture("");
            return MateInviteTokenStore.hasFreeBed(ownerLineId).compose(free -> free
                ? MateInviteTokenStore.describeRoom(ownerLineId)
                : Future.succeededFuture(""));
        }).recover(e -> {
            // Generic, as for resolve: the caller is unauthenticated, so the database's message stays here.
            Console.log("[MateInvite] describe errored: " + e);
            return Future.failedFuture("[MateInviteError] The invite link could not be checked");
        });
    }

    /**
     * Refuses, as SOLD_OUT, a new front-office sharing place when no bed is free for it (room-mate plan
     * §1c, step 6). Returns the sold-out result to send back, or null to go ahead.
     *
     * <p>Needed because a sharing line never reaches the database's allocation check: the defer-allocate
     * trigger skips share-mate items, so without this only the browser stood between a stale page (or a
     * crafted request) and a sharing booking with no bed to take.
     *
     * <p>Runs in submitDocumentChangesNow, after the event queue. Each added sharing line is judged against
     * its OWN booking's event — a submit can name several documents — and all of a submit's sharing lines
     * for one event are counted together against that event's free beds, so one crafted submit cannot put
     * several sharers on one bed. The policy is loaded once per event.
     *
     * <p>Not applied to a back-office submit, which is authoritative for placement as it is for the
     * allocation check itself. When the submit adds a single sharing line to an event, an invite token that
     * resolves to a room of that event which STILL has a free bed stands in for the count: the link names that
     * room, and linking places the mate in it whatever sharing item they picked (it copies the room onto their
     * line). A token for a full room, or one that does not resolve, stands in for nothing — otherwise any
     * forwarded link would unlock places with no bed behind them — and with several lines it is not used.
     */
    private static Future<SubmitDocumentChangesResult> refuseSharingPlaceWithoutFreeBed(DocumentSubmitRequest request) {
        if (request.backoffice())
            return Future.succeededFuture(null);
        // Documents created by this very submit name their event; any other document is looked up.
        Map<Object, Object> createdDocumentEvents = new HashMap<>();
        List<AddDocumentLineEvent> addedLines = new ArrayList<>();
        // The lines this submit declares to be sharing places in their own right. A VIRTUAL sharing option
        // (room-mate plan Part B) names the ROOM's item and says what it is on the line, so its item tells
        // us nothing; the EditShareMateInfo event travelling with it does.
        Set<String> shareMateLinePks = new HashSet<>();
        for (AbstractDocumentEvent documentEvent : request.argument().documentEvents()) {
            if (documentEvent instanceof AddDocumentEvent addDocument)
                createdDocumentEvents.put(addDocument.getDocumentPrimaryKey(), addDocument.getEventPrimaryKey());
            else if (documentEvent instanceof AddDocumentLineEvent add && add.getItemPrimaryKey() != null)
                addedLines.add(add);
            else if (documentEvent instanceof EditShareMateInfoDocumentLineEvent mate)
                shareMateLinePks.add(String.valueOf(mate.getDocumentLinePrimaryKey()));
        }
        if (addedLines.isEmpty())
            return Future.succeededFuture(null);
        List<Object> itemPks = new ArrayList<>();
        for (AddDocumentLineEvent add : addedLines)
            if (!itemPks.contains(add.getItemPrimaryKey()))
                itemPks.add(add.getItemPrimaryKey());
        return sharingItemsAmong(itemPks, 0, new ArrayList<>()).compose(sharingItemPks -> {
            List<AddDocumentLineEvent> sharingLines = new ArrayList<>();
            // A sharing place by its item OR by its own flag, as IS_SHARE_MATE_LINE reads one. Without the
            // second, a virtual sharing option would be classified as an ordinary room booking and refused
            // by the ROOM's sold-out check -- exactly when its booker has no room and needs the bed.
            //
            // A no-op until virtual sharing options exist, and NOT sufficient for them on its own:
            // firstOverbooked refuses any item the policy does not offer as a sharing option, and keys its
            // bed arithmetic on sharing item pks, so a line naming a ROOM item is refused today and would
            // draw on no offer even if it were not. Both belong with the front-office half (room-mate plan
            // Part B step 2), which is also where a test for this rule belongs, since there is nothing to
            // assert about it until a virtual option can be booked.
            for (AddDocumentLineEvent add : addedLines)
                // Keys compared as strings, as groupByEvent does just below and for the same reason: the two
                // events carry the same line key by different routes, and a number type must not decide
                // whether a sharing place is bed-checked.
                if (sharingItemPks.contains(add.getItemPrimaryKey())
                    || shareMateLinePks.contains(String.valueOf(add.getDocumentLinePrimaryKey())))
                    sharingLines.add(add);
            if (sharingLines.isEmpty())
                return Future.succeededFuture(null);
            return groupByEvent(sharingLines, 0, createdDocumentEvents, new HashMap<>(), new LinkedHashMap<>())
                .compose(linesByEvent -> refuseEventsFrom(request, new ArrayList<>(linesByEvent.values()), 0));
        });
    }

    /** A submit's sharing lines for one event. */
    private record EventSharingLines(Object eventPk, List<AddDocumentLineEvent> lines) {
    }

    /**
     * Groups the sharing lines by their own booking's event, looking each document's event up once. Keyed by
     * the key's text, so a new document's event key and an existing document's compare equal whatever number
     * type each arrived as. A line whose booking cannot be found is left out: it cannot be written either.
     */
    private static Future<Map<String, EventSharingLines>> groupByEvent(List<AddDocumentLineEvent> lines, int index,
                                                                      Map<Object, Object> createdDocumentEvents,
                                                                      Map<Object, Object> eventByDocumentPk,
                                                                      Map<String, EventSharingLines> linesByEvent) {
        if (index >= lines.size())
            return Future.succeededFuture(linesByEvent);
        AddDocumentLineEvent line = lines.get(index);
        Object documentPk = line.getDocumentPrimaryKey();
        Future<Object> eventOfLine = eventByDocumentPk.containsKey(documentPk)
            ? Future.succeededFuture(eventByDocumentPk.get(documentPk))
            : eventOfDocument(documentPk, createdDocumentEvents).map(eventPk -> {
                eventByDocumentPk.put(documentPk, eventPk);
                return eventPk;
            });
        return eventOfLine.compose(eventPk -> {
            if (eventPk != null)
                linesByEvent.computeIfAbsent(String.valueOf(eventPk), key -> new EventSharingLines(eventPk, new ArrayList<>()))
                    .lines().add(line);
            return groupByEvent(lines, index + 1, createdDocumentEvents, eventByDocumentPk, linesByEvent);
        });
    }

    /**
     * The event of the document a line is added to. A modification adds lines to an existing booking without
     * naming its event, and only the queue path looks it up for the request as a whole — so it is looked up
     * here, per document, or a modification sent outside the queue would never be checked at all.
     */
    private static Future<Object> eventOfDocument(Object documentPk, Map<Object, Object> createdDocumentEvents) {
        if (documentPk == null)
            return Future.succeededFuture(null);
        if (createdDocumentEvents.containsKey(documentPk))
            return Future.succeededFuture(createdDocumentEvents.get(documentPk));
        return EntityStore.create().<Document>executeQuery("select event from Document where id=$1", documentPk)
            .map(documents -> documents.isEmpty() ? null : Entities.getPrimaryKey(documents.get(0).getEventId()));
    }

    /** Judges each event's sharing lines, one event after another; the first refusal ends the walk. */
    private static Future<SubmitDocumentChangesResult> refuseEventsFrom(DocumentSubmitRequest request, List<EventSharingLines> events, int index) {
        if (index >= events.size())
            return Future.succeededFuture(null);
        return refuseEventSharingLines(request, events.get(index))
            .compose(soldOut -> soldOut != null ? Future.succeededFuture(soldOut) : refuseEventsFrom(request, events, index + 1));
    }

    private static Future<SubmitDocumentChangesResult> refuseEventSharingLines(DocumentSubmitRequest request, EventSharingLines event) {
        Object eventPk = event.eventPk();
        Map<Object, Integer> requestedLinesByItemPk = new LinkedHashMap<>();
        Map<Object, AddDocumentLineEvent> firstLineByItemPk = new HashMap<>();
        for (AddDocumentLineEvent line : event.lines()) {
            requestedLinesByItemPk.merge(line.getItemPrimaryKey(), 1, Integer::sum);
            firstLineByItemPk.putIfAbsent(line.getItemPrimaryKey(), line);
        }
        // An invited mate sends exactly one sharing line. Only then does a usable link stand in for the count —
        // with more lines, which only a crafted submit sends, every line is counted, so a link cannot carry extras.
        String token = request.argument().inviteToken();
        boolean singleLine = event.lines().size() == 1;
        Future<Boolean> invitedToFreeBed = !singleLine || token == null || token.isBlank() ? Future.succeededFuture(false)
            : MateInviteTokenStore.resolve(token, eventPk).compose(ownerLineId -> ownerLineId == null
                ? Future.succeededFuture(false)
                : MateInviteTokenStore.hasFreeBed(ownerLineId));
        // A BOOKER putting someone in their own room is exempt from the event-wide POOL too (room-mate
        // plan Part C). That pool is the free beds in the event's booked rooms LESS every unlinked
        // sharing place anywhere in it, so a queue of other people's unplaced sharers can exhaust it —
        // and a booker who has just paid for a whole twin is then told there is no bed in it. The figure
        // is wrong about that one room, which is what this drops.
        //
        // It does NOT drop the room's OWN count, although the plan said to drop both. The reasoning there
        // — that the worst a booker can do is put a third person in their own twin — is true of the
        // booking FORM and not of the submit: with no per-room ceiling anywhere, a crafted request can
        // hang any number of £0 lines off one room, and nothing downstream counts them, because a LINKED
        // mate never reaches the allocation trigger (defer_allocate skips it) and so meets neither its
        // capacity check nor its sold-out check. The pool used to be that ceiling by accident, being
        // self-limiting as it is consumed. Asking the room itself restores a ceiling and makes it the
        // thing the booker actually controls; it is also the check the invited-mate path keeps.
        //
        // Gated on OWNERSHIP, and it has to be, because this runs BEFORE the write — the ownership check
        // in linkBookerMateIfPresent is too late to decide whether the submit may be accepted at all.
        // Without it, naming any line id would buy a sharing place the event cannot supply; the link would
        // then be refused afterwards, leaving exactly the unlinked £0 sharing line this gate exists to
        // prevent. The singleLine guard applies for the same reason it applies to a token.
        Object bookerRoomLineId = request.argument().ownerDocumentLine();
        Future<Boolean> booksOwnRoom = !singleLine || bookerRoomLineId == null ? Future.succeededFuture(false)
            : ownsRoomAtEvent(bookerRoomLineId, eventPk, getUserAccountId(request.userId()))
                .compose(ownsRoom -> ownsRoom ? MateInviteTokenStore.hasFreeBed(bookerRoomLineId)
                                              : Future.succeededFuture(false));
        return Future.all(invitedToFreeBed, booksOwnRoom)
            .map(exemptions -> Boolean.TRUE.equals(exemptions.resultAt(0)) || Boolean.TRUE.equals(exemptions.resultAt(1)))
            // Either exemption stands in for the bed COUNT only; the item must still be one this event
            // offers as a sharing place, which is what firstOverbooked keeps checking.
            .compose(exempt -> SharingPlaceAvailability.firstOverbooked(eventPk, requestedLinesByItemPk, !exempt))
            .map(refusedItemPk -> {
                if (refusedItemPk == null)
                    return null;
                Console.log("[SharingAvailability] refused a sharing place with no free bed: event=" + eventPk + " item=" + refusedItemPk);
                return SubmitDocumentChangesResult.createSoldOutResult(firstLineByItemPk.get(refusedItemPk).getSitePrimaryKey(), refusedItemPk);
            });
    }

    /**
     * Whether {@code ownerDocumentLineId} is a live room of {@code eventPk} that {@code accountId} may act
     * on — the pre-write half of the booker's room-share authorisation (room-mate plan Part C).
     *
     * <p>Asks the same question as {@link MateLinkRules}' ownership test, and must keep asking it: this
     * decides whether the submit is exempt from the event's sharing pool, and that decision is made before
     * anything is written, where the post-write check cannot help. Answering yes for a room that is not
     * the caller's would hand them a sharing place the event cannot supply, and the link would then be
     * refused afterwards — leaving an unlinked line priced at zero.
     *
     * <p>Two queries rather than one, deliberately. {@code accountCanAccessPersonOrders} is passed a bare
     * {@code person} at every other call site in the repository, and this would have been the first to
     * hand it a traversed one ({@code document.person}). That may well compile, but its failure mode is
     * silent — the query errors, this answers false, and the exemption simply never applies, which is the
     * bug it exists to fix wearing the costume of working correctly. So the line is resolved to its
     * booking first, and the ownership question is then asked in the shape everything else uses.
     *
     * <p>Any failure answers false: the pool check then stays on, which refuses too much rather than too
     * little.
     */
    private static Future<Boolean> ownsRoomAtEvent(Object ownerDocumentLineId, Object eventPk, Object accountId) {
        if (ownerDocumentLineId == null || eventPk == null || accountId == null)
            return Future.succeededFuture(false);
        return EntityStore.create().<DocumentLine>executeQuery(
                "select document from DocumentLine where id=$1 and share_owner and !cancelled"
                + " and item.family.code='acco' and document.event=$2",
                ownerDocumentLineId, eventPk)
            .compose(lines -> {
                if (lines.isEmpty())
                    return Future.succeededFuture(false); // not a live room of this event
                Object documentPk = Entities.getPrimaryKey(lines.get(0).getForeignEntityId("document"));
                return EntityStore.create().executeQuery(
                        "select id from Document where id=$1 and !cancelled and accountCanAccessPersonOrders($2, person)",
                        documentPk, accountId)
                    .map(documents -> !documents.isEmpty());
            })
            .otherwise(e -> {
                Console.log("[MateLink] could not confirm the booker owns the room; keeping the bed count on: " + e);
                return false;
            });
    }

    /** The keys among {@code itemPks} that are sharing items, checked one after another (a submit adds few). */
    private static Future<List<Object>> sharingItemsAmong(List<Object> itemPks, int index, List<Object> sharingItemPks) {
        if (index >= itemPks.size())
            return Future.succeededFuture(sharingItemPks);
        Object itemPk = itemPks.get(index);
        return EntityStore.create().<Item>executeQuery("select share_mate from Item where id=$1", itemPk)
            .compose(items -> {
                if (!items.isEmpty() && Boolean.TRUE.equals(items.get(0).isShare_mate()))
                    sharingItemPks.add(itemPk);
                return sharingItemsAmong(itemPks, index + 1, sharingItemPks);
            });
    }

    /**
     * If this submit carried a room-share invite token (steps 4-5), consume it: validate it and link the
     * just-created mate line to the room booker the token names. The booking is already committed and
     * valid, so a token that cannot be consumed (unknown, room full, expired, wrong event, or no mate
     * line found) is logged and the booking still succeeds — the mate simply stays unlinked, which the
     * back office can resolve. The TOKEN is the authorization; the client never names the owner line.
     */
    /**
     * Tells the room booker that one of their beds has been taken, and by whom.
     *
     * <p>Reads both sides from the two lines rather than from anything the mate's request carried: who
     * holds the room, where to write to them, and the name on the booking that just joined.
     *
     * <p>Never fails the link. Its own result is swallowed here as well as by the chain it hangs off,
     * because the three follow-ups are independent and one going wrong should not cost the others.
     */
    private static Future<Void> notifyBookerOfJoin(Object ownerLineId, Object mateLineId) {
        // The window first, so a refusal costs nothing: no query, no mail, and the link already stands.
        if (!MateJoinedMail.maySend(ownerLineId))
            return Future.succeededFuture();
        return MateInviteTokenStore.loadJoinNotice(ownerLineId, mateLineId)
            .compose(notice -> {
                if (notice == null)
                    return Future.succeededFuture();
                // The booker may follow their own link rather than the cart's button — it stays usable
                // for an account already in the room, to put a second person in a triple. Telling them
                // somebody took a bed, when that somebody was them, reads as a stranger walking in.
                if (notice.mateIsTheBooker())
                    return Future.succeededFuture();
                return MateJoinedMail.send(notice.bookerName(), notice.bookerEmail(),
                                           notice.bookerPersonId(), notice.mateName(),
                                           notice.eventName(), notice.lang());
            })
            .otherwise(e -> {
                Console.log("[MateInvite] linked, but the booker could not be told: " + e);
                return null;
            });
    }

    private static Future<SubmitDocumentChangesResult> consumeMateInviteTokenIfPresent(DocumentSubmitRequest request, SubmitDocumentChangesResult result) {
        String token = request.argument().inviteToken();
        if (token == null || token.isBlank())
            return Future.succeededFuture(result);
        // Find the line to link first: a booking with no share-mate line has nothing to link, and
        // there is no point resolving a token on its behalf.
        return MateInviteTokenStore.findMateShareLineId(result.documentPrimaryKey()).compose(mateLineId -> {
            if (mateLineId == null) {
                Console.log("[MateInvite] no share-mate line on the new booking; nothing to link");
                return Future.succeededFuture(result);
            }
            // Resolving is read-only and carries the event. Whether another mate may join is NOT
            // decided here — a link belongs to the room, so that question is the room's remaining
            // capacity, tested inside link() where it can be enforced against concurrent linkers.
            // From here the booking holds a sharing place the mate expects to be linked, so the outcome
            // is reported back either way: a mate left unlinked must be TOLD, or they walk away
            // believing they share the room. (Both tabs of one link booking the last bed is how.)
            return MateInviteTokenStore.resolve(token, request.eventPrimaryKey()).compose(ownerLineId -> {
                if (ownerLineId == null) {
                    Console.log("[MateInvite] token did not resolve (unknown, expired, or another event); booking left unlinked");
                    return Future.succeededFuture(SubmitDocumentChangesResult.withMateInvite(result, SubmitDocumentChangesResult.MATE_INVITE_NOT_LINKED));
                }
                // An emailed token can be forwarded, so the bed count stays on for this caller.
                return MateInviteTokenStore.link(mateLineId, ownerLineId, true).compose(linked -> {
                    if (!linked) { // the room filled or was cancelled after the invite was sent
                        Console.log("[MateInvite] link refused (no free bed, room cancelled, or not a share-mate line); booking left unlinked");
                        return Future.succeededFuture(SubmitDocumentChangesResult.withMateInvite(result, SubmitDocumentChangesResult.MATE_INVITE_NOT_LINKED));
                    }
                    // Linked: record the first follower for audit, and label the line with the
                    // owner's real name — the mate may have typed it wrong. Neither undoes the link,
                    // so a failure in them still reports LINKED.
                    return MateInviteTokenStore.recordFirstUse(token, mateLineId)
                        .compose(ignored -> MateInviteTokenStore.stampOwnerName(mateLineId, ownerLineId))
                        // And tell the booker, who otherwise finds out only by looking at their own
                        // booking — which answers the question for somebody who wonders, and misses the
                        // person whose link is used days later. Sent whichever way the link reached its
                        // reader: we emailed it, or they copied it themselves, and the booker wants to
                        // know either way. Best effort, like the two above: a bed that is taken is
                        // taken, and failing to mention it must never undo that.
                        .compose(ownerName -> notifyBookerOfJoin(ownerLineId, mateLineId).map(ignored -> ownerName))
                        .otherwise(e -> {
                            Console.log("[MateInvite] linked, but recording first use, the owner name or the booker's note failed: " + e);
                            return null;
                        })
                        // Named only now. Before the link, this caller is just somebody holding a URL and
                        // the endpoints they can reach must not name anybody (plan constraint 4); after
                        // it, they have a bed in that person's room and are about to share it with them.
                        .map(ownerName -> SubmitDocumentChangesResult.linkedToRoomOf(result, ownerName));
                });
            }).otherwise(e -> {
                // Resolving or linking errored after the booking took a sharing place: it is unlinked
                // just as surely as a refusal, and the mate must be told just the same.
                Console.log("[MateInvite] resolve or link errored; booking left unlinked: " + e);
                return SubmitDocumentChangesResult.withMateInvite(result, SubmitDocumentChangesResult.MATE_INVITE_NOT_LINKED);
            });
        }).otherwise(e -> {
            // Errored before the outcome was known — in practice while finding the share-mate line, since
            // the paths after it handle their own errors. The token rides only a usable invite, so the
            // mate almost certainly chose the sharing place and expects to be placed: say NOT_LINKED
            // rather than leave them believing they share the room.
            Console.log("[MateInvite] token consume errored; booking left unlinked: " + e);
            return SubmitDocumentChangesResult.withMateInvite(result, SubmitDocumentChangesResult.MATE_INVITE_NOT_LINKED);
        });
    }

    /**
     * Tells a booking that just took a whole room which line that is, so the booker's NEXT submit — their
     * roommate's booking — can name the exact room (room-mate plan Part C). Without it the server would
     * have to guess from the document, and 242 of 7,539 bookings own two room lines, where guessing wrong
     * replaces the mate's attendances with the wrong line's.
     *
     * <p>Reports the line the submit NAMED, not one re-derived from the booking: re-deriving would put
     * back the very guess this field exists to remove. It is still validated — the same event stamps a
     * headcount on non-accommodation lines (a multi-head public talk marks refectory and diet lines), and
     * none of those is a room anyone can join.
     *
     * <p>Only for a submit that declared a share-owner line, so an ordinary booking pays for no extra
     * query. Best-effort throughout: a booking is not failed, nor its result withheld, because the
     * follow-up convenience could not be filled in.
     */
    private static Future<SubmitDocumentChangesResult> reportRoomLineIfBooked(DocumentSubmitRequest request, SubmitDocumentChangesResult result) {
        if (result.status() != DocumentChangesStatus.APPROVED || result.documentPrimaryKey() == null)
            return Future.succeededFuture(result);
        // The lines the submit itself declared to be share-owner lines. Their primary keys are already
        // resolved: HistoryRecorder.completeDocumentHistoriesAfterSubmit rewrites the client's negative
        // ids to real ones, synchronously, earlier in this same compose chain.
        List<Object> namedRoomLines = new ArrayList<>();
        for (AbstractDocumentEvent documentEvent : request.argument().documentEvents())
            if (documentEvent instanceof EditShareOwnerInfoDocumentLineEvent shareOwner
                && shareOwner.getDocumentLinePrimaryKey() != null)
                namedRoomLines.add(shareOwner.getDocumentLinePrimaryKey());
        if (namedRoomLines.isEmpty())
            return Future.succeededFuture(result);
        return MateInviteTokenStore.pickRoomLineAmong(namedRoomLines)
            .map(roomLineId -> roomLineId == null ? result
                : SubmitDocumentChangesResult.withOwnerDocumentLine(result, roomLineId))
            .otherwise(e -> {
                Console.log("[MateLink] could not report the room line of the new booking: " + e);
                return result;
            });
    }

    /**
     * Links a roommate's booking to the room its booker names (room-mate plan Part C) — the booker's own
     * counterpart to {@link #consumeMateInviteTokenIfPresent}, and deliberately shaped like it: the link is
     * written AFTER the booking, so nothing in the submit has to name a line that does not exist yet.
     *
     * <p>What differs is the authorisation, because the two carry different things. An invite token is an
     * unguessable credential and names the room by itself. A line id is sequential and shown in the UI, so
     * here the ownership check IS the authorisation: {@link MateLinkRules} requires the named line to
     * belong to a booking of the submitting account. Part C switches the bed count off for a booker
     * putting someone in their own room, so this check is what stands between a guessed id and a mate
     * attached to a stranger's room.
     *
     * <p>Judged by the FRONT-OFFICE rule whoever sends it: the back office has its own link action with
     * its own rule, so there is no reason for this argument to grant back-office powers to anything that
     * sets it.
     *
     * <p>Reported like the token path, and for the same reason: once the booking holds a sharing place,
     * a mate left unlinked must be TOLD rather than left believing they share the room.
     */
    private static Future<SubmitDocumentChangesResult> linkBookerMateIfPresent(DocumentSubmitRequest request, SubmitDocumentChangesResult result) {
        Object ownerLineId = request.argument().ownerDocumentLine();
        if (ownerLineId == null || result.status() != DocumentChangesStatus.APPROVED || result.documentPrimaryKey() == null)
            return Future.succeededFuture(result);
        Object submitterAccountId = getUserAccountId(request.userId());
        return MateInviteTokenStore.findMateShareLineId(result.documentPrimaryKey()).compose(mateLineId -> {
            if (mateLineId == null) {
                Console.log("[MateLink] no share-mate line on the new booking; nothing to link");
                return Future.succeededFuture(result);
            }
            return linesOwnedBy(submitterAccountId, mateLineId, ownerLineId).compose(owned ->
                EntityStore.create().<DocumentLine>executeQuery(
                    "select share_mate, share_owner, item.share_mate, document.event from DocumentLine where id=$1 or id=$2",
                    mateLineId, ownerLineId)
                .compose(lines -> {
                    MateLinkRules.LineFacts mate = null, owner = null;
                    for (DocumentLine line : lines) {
                        Object linePk = line.getPrimaryKey();
                        MateLinkRules.LineFacts facts = lineFacts(line, isOwned(owned, linePk));
                        if (MateLinkRules.sameId(linePk, mateLineId))
                            mate = facts;
                        if (MateLinkRules.sameId(linePk, ownerLineId))
                            owner = facts;
                    }
                    String refusal = MateLinkRules.check(false, false, mate, true, owner, false, submitterAccountId);
                    if (refusal != null) {
                        Console.log("[MateLink] " + refusal + " (owner line " + ownerLineId + ")");
                        return Future.succeededFuture(SubmitDocumentChangesResult.withMateInvite(result, SubmitDocumentChangesResult.MATE_INVITE_NOT_LINKED));
                    }
                    // The room's remaining capacity is NOT tested here: Part C accepts a booker putting an
                    // extra person in their own room, which is visible to them and to registration and is
                    // their own doing. link() still refuses a cancelled room and a line that is not a
                    // sharing place.
                    return MateInviteTokenStore.link(mateLineId, ownerLineId, false).compose(linked -> {
                        if (!linked) {
                            Console.log("[MateLink] link refused (room cancelled, or not a share-mate line); booking left unlinked");
                            return Future.succeededFuture(SubmitDocumentChangesResult.withMateInvite(result, SubmitDocumentChangesResult.MATE_INVITE_NOT_LINKED));
                        }
                        // Fills the owner's name only where the booker left it blank — it never overwrites
                        // what they wrote. Both link paths label the line the same way for that reason.
                        return MateInviteTokenStore.stampOwnerName(mateLineId, ownerLineId)
                            .map(ownerName -> SubmitDocumentChangesResult.linkedToRoomOf(result, ownerName));
                    });
                }));
        }).otherwise(e -> {
            Console.log("[MateLink] booker link errored; booking left unlinked: " + e);
            return SubmitDocumentChangesResult.withMateInvite(result, SubmitDocumentChangesResult.MATE_INVITE_NOT_LINKED);
        });
    }
}
