package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.platform.ast.AST;
import dev.webfx.platform.ast.AstArray;
import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryResult;
import one.modality.crm.server.authn.gateway.shared.RouteAccessGuard;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What the audio-storage endpoints do, once they know who is asking: describe where an event's recordings go,
 * choose that folder, prepare uploads, store one, and remove one.
 *
 * <p><b>Every entry point checks the caller against the TARGET's owner.</b> The endpoint resolves the event
 * (or, for a recording, the event its session belongs to) and hands its organisation and id to the
 * {@link Authorizer}, which is {@code RouteAccessGuard.whenCallerMayReach("/medias", …)} with the caller's
 * state carried in — the guard {@code DeleteTeachingSessionEndpoint} uses. The upload itself, an HTTP request,
 * carries a ticket instead ({@link AudioUploadTickets}), and only {@link #prepareUploads} issues one, after the
 * same check.
 *
 * <p>Replies are JSON text that the back office parses and validates; failures are short codes
 * ({@code audio.noFolder}, {@code bunny.badApiKey}, …) that it translates. A refusal reads like every other
 * refusal: "This operation is not available to you".
 */
final class AudioStorageService {

    private AudioStorageService() {
    }

    /** The endpoint's authorization, applied once the target's scope is known. */
    @FunctionalInterface
    interface Authorizer {
        Future<String> authorize(Object organizationId, Object eventId, Supplier<Future<String>> work);
    }

    /** The largest file accepted: a three-hour teaching at 320 kbit/s is ~430 MB. */
    static final long MAX_UPLOAD_BYTES = 1024L * 1024 * 1024;
    /** A recording longer than this is a bad reading of its duration, not a teaching. */
    private static final long MAX_DURATION_MILLIS = 24L * 3600 * 1000;
    /** How many folder names a browse returns; the zone's busiest folder holds a dozen. */
    private static final int MAX_BROWSE_FOLDERS = 500;

    /** Failure codes the back office translates (audio-storage-errors in the medias feature). */
    static final String NOT_CONFIGURED = "audio.notConfigured";
    static final String NO_FOLDER = "audio.noFolder";
    static final String INVALID_FOLDER = "audio.invalidFolder";
    static final String PATTERN_NOT_ALLOWED = "audio.patternNotAllowed";
    static final String NOT_AUDIO_SLOT = "audio.notAudioSlot";
    static final String UNSUPPORTED_TYPE = "audio.unsupportedType";
    static final String TOO_LARGE = "audio.tooLarge";
    static final String EMPTY_FILE = "audio.empty";
    static final String DUPLICATE_IN_BATCH = "audio.duplicateInBatch";
    static final String MULTIPLE_RECORDINGS = "audio.multipleRecordings";
    static final String USED_BY_ANOTHER_SESSION = "audio.usedByAnotherSession";
    static final String EXISTS = "audio.exists";
    static final String BUSY = "audio.busy";
    /** The event plays its series event's recordings: they are managed there, not here. */
    static final String REPEATED_EVENT = "audio.repeatedEvent";

    // ─── Describe ────────────────────────────────────────────────────────────────────────────────────

    /** Where the event's recordings go, and whether uploading can work at all. */
    static Future<String> describe(Object eventId, String browsePath, Authorizer authorizer) {
        return AudioEventScope.load(eventId).compose(scope -> scope == null ? RouteAccessGuard.<String>refused()
            : authorizer.authorize(scope.organizationId(), scope.eventId(), () -> describeAuthorized(scope, browsePath, authorizer)));
    }

    /**
     * Whether the caller may set the pattern of the event's type: the type must be the event's own
     * organisation's, and the caller's /medias grant must cover that whole organisation — the pattern decides
     * where OTHER events of the type upload, which a grant scoped to one event does not reach (the rule
     * {@code DuplicateEventEndpoint} applies by asking with no event).
     */
    private static Future<Boolean> mayWritePattern(AudioEventScope scope, Authorizer authorizer) {
        if (!scope.canSaveTypePattern())
            return Future.succeededFuture(false);
        return authorizer.authorize(scope.organizationId(), null, () -> Future.succeededFuture("yes"))
            .map(yes -> true)
            .recover(refused -> Future.succeededFuture(false));
    }

    private static Future<String> describeAuthorized(AudioEventScope scope, String browsePath, Authorizer authorizer) {
        // A folder listing (browsePath) is read only for its subfolders: it skips the slots, the suggestion and
        // the pattern question.
        boolean listing = browsePath != null;
        Future<Boolean> mayWrite = listing ? Future.succeededFuture(false) : mayWritePattern(scope, authorizer);
        Future<Map<Long, AudioEventScope.Slot>> loadedSlots = listing ? Future.succeededFuture(Collections.emptyMap()) : scope.loadSlots();
        return loadedSlots.compose(slots -> mayWrite.compose(canSavePattern -> {
            AstObject json = AST.createObject();
            json.set("folder", scope.effectiveFolder());
            json.set("folderSource", scope.folderSource());
            json.set("eventTypeName", scope.eventTypeName());
            json.set("eventTypePattern", scope.eventTypePattern());
            json.set("canSavePattern", canSavePattern);
            json.set("repeatedEventId", scope.repeatAudio() ? scope.repeatedEventId() : null);
            AstObject languageFolders = AST.createObject();
            for (AudioEventScope.Slot slot : slots.values())
                if (slot.itemCode() != null) // an item without a code has nothing to key it by; its slot still uploads
                    languageFolders.set(slot.itemCode(), slot.languageFolder());
            json.set("languageFolders", languageFolders);
            List<String> missing = scope.missingFields();
            if (!missing.isEmpty()) {
                json.set("ready", false);
                json.set("missing", stringArray(missing));
                return Future.succeededFuture(AST.formatObject(json, "json"));
            }
            return scope.zone()
                .compose(zone -> {
                    json.set("ready", true);
                    json.set("zoneName", zone.name());
                    json.set("cdnBaseUrl", "https://" + zone.cdnHostname() + "/");
                    // Every hostname that serves the zone: the screen offers to delete a recording's file only
                    // when its address is on one of them, the rule deleteFileIfUnused starts with.
                    json.set("cdnHostnames", stringArray(zone.hostnames()));
                    // Neither a missing suggestion nor a folder that cannot be listed makes the zone unusable, so
                    // they are reported as what they are rather than failing the description.
                    Future<Void> suggested = listing || !"none".equals(scope.folderSource()) ? Future.<Void>succeededFuture()
                        : suggest(scope, zone).<Void>map(s -> { json.set("suggestion", s[0]); json.set("suggestionPattern", s[1]); return null; })
                            .recover(err -> {
                                Console.log("[BUNNY-AUDIO] no folder suggestion for event " + scope.eventId() + ": " + err);
                                return Future.succeededFuture();
                            });
                    return suggested.compose(v -> !listing ? Future.<Void>succeededFuture() : browse(zone, browsePath, json)
                        .recover(err -> {
                            json.set("browseError", AudioBusArguments.publicCode(err));
                            return Future.succeededFuture();
                        }));
                })
                .map(v -> AST.formatObject(json, "json"))
                .recover(err -> {
                    // Only resolving the zone gets here. A zone that cannot be reached is not a server error: it
                    // is what the "connect Bunny" panel exists to explain, so the code goes to the screen.
                    json.set("ready", false);
                    json.set("error", codeOf(err, BunnyStorageClient.UNAVAILABLE));
                    Console.log("[BUNNY-AUDIO] zone for event " + scope.eventId() + " unavailable: " + err.getMessage());
                    return Future.succeededFuture(AST.formatObject(json, "json"));
                });
        }));
    }

    /**
     * A folder to propose when the event has none: where its own files already are (uploaded by hand before
     * this screen existed), else where the latest earlier event of the same type put its files, with the year
     * moved on. {@code [folder, pattern]}, either possibly null.
     */
    private static final String SUGGESTION_SQL_HEAD =
        "select extract(year from e.start_date)::int, m.url from media m" +
        " join scheduled_item si on si.id = m.scheduled_item_id join event e on e.id = si.event_id" +
        " where (e.id = $1 or (e.type_id = $2 and e.organization_id = $3)) and m.type = 'AUDIO' and ";
    private static final String SUGGESTION_SQL_TAIL = " order by (e.id = $1) desc, e.start_date desc, m.id limit 1";

    private static Future<String[]> suggest(AudioEventScope scope, BunnyStorageClient.Zone zone) {
        // An event without a type is matched only to itself (e.type_id = null is never true): an ArrayList,
        // because List.of refuses the null.
        List<Object> parameters = new ArrayList<>();
        parameters.add(Numbers.toLong(scope.eventId()));
        parameters.add(Numbers.toLong(scope.eventTypeId()));
        parameters.add(Numbers.toLong(scope.organizationId()));
        String onZone = onZoneHosts("m.url", parameters, zone);
        return AudioServerSql.query(SUGGESTION_SQL_HEAD + onZone + SUGGESTION_SQL_TAIL, parameters.toArray())
            .map(r -> {
                if (r.getRowCount() == 0)
                    return new String[2];
                int year = Numbers.toInteger(r.getValue(0, 0));
                String path = AudioStorageNames.zonePath(r.getValue(0, 1), zone.hostnames());
                int slash = path == null ? -1 : path.lastIndexOf('/');
                if (slash <= 0)
                    return new String[2];
                String pattern = AudioStorageNames.patternFromEarlierFolder(path.substring(0, slash), year);
                return new String[] { AudioStorageNames.resolvePattern(pattern, scope.year(), scope.month(), scope.eventName()), pattern };
            });
    }

    /** The subfolders of {@code path} in the zone, for the folder picker. */
    private static Future<Void> browse(BunnyStorageClient.Zone zone, String path, AstObject json) {
        String folder = path.isBlank() ? "" : AudioStorageNames.normaliseFolder(path, false);
        if (folder == null || folder.contains("{"))
            return Future.failedFuture(INVALID_FOLDER);
        return BunnyStorageClient.list(zone, folder).map(entries -> {
            List<String> folders = new ArrayList<>();
            int files = 0;
            for (BunnyStorageClient.Entry entry : entries)
                if (entry.directory()) folders.add(entry.name());
                else if (!entry.name().startsWith(".")) files++; // a folder's placeholder is not one of its files
            folders.sort(String.CASE_INSENSITIVE_ORDER);
            AstObject browse = AST.createObject();
            browse.set("path", folder);
            browse.set("folders", stringArray(folders.subList(0, Math.min(folders.size(), MAX_BROWSE_FOLDERS))));
            browse.set("fileCount", files);
            json.set("browse", browse);
            return null;
        });
    }

    // ─── Choose the folder ───────────────────────────────────────────────────────────────────────────

    private static final String SET_EVENT_FOLDER_SQL = "update event set audio_bunny_folder = $2 where id = $1";
    private static final String SET_TYPE_PATTERN_SQL =
        "update event_type set audio_bunny_folder_pattern = $2 where id = $1 and organization_id = $3";

    /**
     * Sets the event's folder ({@code {lang}} allowed, nothing else) and, when {@code pattern} is given, its
     * type's pattern. Replies with a fresh description.
     */
    static Future<String> setFolder(Object eventId, String folder, String pattern, Authorizer authorizer) {
        String normalisedFolder = folder == null || folder.isBlank() ? null : AudioStorageNames.normaliseFolder(folder, false);
        if (folder != null && !folder.isBlank() && normalisedFolder == null)
            return Future.failedFuture(INVALID_FOLDER);
        String normalisedPattern = pattern == null || pattern.isBlank() ? null : AudioStorageNames.normaliseFolder(pattern, true);
        if (pattern != null && !pattern.isBlank() && normalisedPattern == null)
            return Future.failedFuture(INVALID_FOLDER);
        return AudioEventScope.load(eventId).compose(scope -> scope == null ? RouteAccessGuard.<String>refused()
            : authorizer.authorize(scope.organizationId(), scope.eventId(), () -> {
                if (normalisedPattern != null && !scope.canSaveTypePattern())
                    return Future.failedFuture(PATTERN_NOT_ALLOWED);
                List<AudioServerSql.Statement> statements = new ArrayList<>(2);
                statements.add(new AudioServerSql.Statement(SET_EVENT_FOLDER_SQL, Numbers.toLong(scope.eventId()), normalisedFolder));
                if (normalisedPattern != null)
                    statements.add(new AudioServerSql.Statement(SET_TYPE_PATTERN_SQL, Numbers.toLong(scope.eventTypeId()),
                        normalisedPattern, Numbers.toLong(scope.organizationId())));
                Supplier<Future<String>> write = () -> AudioServerSql.submitInOneTransaction(statements)
                    .onSuccess(v -> Console.log("[BUNNY-AUDIO] event " + scope.eventId() + " audio folder set to " + normalisedFolder
                        + (normalisedPattern == null ? "" : ", type " + scope.eventTypeId() + " pattern " + normalisedPattern)))
                    .compose(v -> AudioEventScope.load(scope.eventId()))
                    .compose(fresh -> describeAuthorized(fresh, null, authorizer));
                // The pattern reaches every event of the type that has no folder of its own yet, so it is asked of
                // the organisation as a whole: a grant scoped to this one event does not cover it.
                return normalisedPattern == null ? write.get() : authorizer.authorize(scope.organizationId(), null, write);
            }));
    }

    // ─── Create folders ──────────────────────────────────────────────────────────────────────────────

    /** More than any event's languages; a request asking for more is not one the screen sends. */
    private static final int MAX_FOLDERS_AT_ONCE = 30;

    /**
     * Creates {@code folder} in the organisation's zone — or, when it holds {@code {lang}}, one folder per
     * language of the event — so the folders exist before any recording is uploaded (for files put there
     * through Bunny's own panel, say). A folder already there is left as it is. Replies
     * {@code {"folders": [{"path", "outcome"}]}}, outcome one of {@code existing}, {@code created} and
     * {@code placeholder} (see {@link BunnyStorageClient#createFolder}).
     *
     * <p>The folder is validated as the event's own folder is (relative, no {@code ..}, safe characters, only
     * {@code {lang}}), and the caller must be able to reach {@code /medias} for the event's organisation.
     */
    static Future<String> createFolders(Object eventId, String folder, Authorizer authorizer) {
        String normalised = folder == null ? null : AudioStorageNames.normaliseFolder(folder, false);
        if (normalised == null)
            return Future.failedFuture(INVALID_FOLDER);
        return AudioEventScope.load(eventId).compose(scope -> scope == null ? RouteAccessGuard.<String>refused()
            : authorizer.authorize(scope.organizationId(), scope.eventId(), () -> {
                if (!scope.missingFields().isEmpty())
                    return Future.failedFuture(NOT_CONFIGURED);
                return scope.loadSlots().compose(slots -> {
                    List<String> paths = AudioStorageNames.foldersToCreate(normalised,
                        slots.values().stream().map(AudioEventScope.Slot::languageFolder).sorted().toList());
                    if (paths.size() > MAX_FOLDERS_AT_ONCE)
                        return Future.failedFuture(INVALID_FOLDER);
                    AstArray created = AST.createArray();
                    return scope.zone()
                        .compose(zone -> createInTurn(zone, paths, 0, created))
                        .map(v -> {
                            Console.log("[BUNNY-AUDIO] folders for event " + scope.eventId() + ": " + AST.formatArray(created, "json"));
                            AstObject json = AST.createObject();
                            json.set("folders", created);
                            return AST.formatObject(json, "json");
                        });
                });
            }));
    }

    /** One folder after the other: siblings share a parent, whose listing tells whether each appeared. */
    private static Future<Void> createInTurn(BunnyStorageClient.Zone zone, List<String> paths, int index, AstArray created) {
        if (index >= paths.size())
            return Future.succeededFuture();
        String path = paths.get(index);
        return BunnyStorageClient.createFolder(zone, path).compose(outcome -> {
            AstObject entry = AST.createObject();
            entry.set("path", path);
            entry.set("outcome", outcome);
            created.push(entry);
            return createInTurn(zone, paths, index + 1, created);
        });
    }

    // ─── Prepare uploads ─────────────────────────────────────────────────────────────────────────────

    /** One file the browser wants to upload: the audio slot, the name it proposes, its size in bytes. */
    record ProposedFile(Long slotId, String name, Long size) {
    }

    private static final String FREEZE_FOLDER_SQL =
        "update event set audio_bunny_folder = $2 where id = $1 and audio_bunny_folder is null";

    private static final String RECORDING_COUNTS_SQL =
        "select m.scheduled_item_id, count(*) from media m" +
        " join scheduled_item si on si.id = m.scheduled_item_id join scheduled_item p on p.id = si.program_scheduled_item_id" +
        " where p.event_id = $1 and m.type = 'AUDIO' group by m.scheduled_item_id";

    /**
     * Checks each proposed file and issues a ticket for those that may go ahead.
     *
     * <p>Per file, the reply says where it would land, whether a file of that name is already there and who
     * plays it. A file whose address another session's recording already uses gets no ticket — overwriting it
     * would silently change that session's audio too — and neither does a session holding two recordings
     * already (the library plays only one of them; the screen asks staff to remove one first).
     *
     * <p>The first preparation fixes the event's folder: the type's pattern, resolved, is written onto the
     * event, so a later edit of the pattern never moves an event that already has files.
     */
    static Future<String> prepareUploads(Object eventId, List<ProposedFile> files, Authorizer authorizer) {
        return AudioEventScope.load(eventId).compose(scope -> scope == null ? RouteAccessGuard.<String>refused()
            : authorizer.authorize(scope.organizationId(), scope.eventId(), () -> {
                if (!scope.missingFields().isEmpty())
                    return Future.failedFuture(NOT_CONFIGURED);
                if (scope.repeatAudio() && scope.repeatedEventId() != null)
                    return Future.failedFuture(REPEATED_EVENT);
                String folder = scope.effectiveFolder();
                if (folder == null)
                    return Future.failedFuture(NO_FOLDER);
                Future<Void> frozen = scope.eventFolder() != null ? Future.succeededFuture()
                    : AudioServerSql.submitInOneTransaction(List.of(
                        new AudioServerSql.Statement(FREEZE_FOLDER_SQL, Numbers.toLong(scope.eventId()), folder)));
                return frozen.compose(v -> scope.zone())
                    .compose(zone -> scope.loadSlots().compose(slots ->
                        AudioServerSql.query(RECORDING_COUNTS_SQL, Numbers.toLong(scope.eventId())).compose(counts ->
                            prepareAuthorized(scope, zone, folder, slots, countsBySlot(counts), files))));
            }));
    }

    private static Future<String> prepareAuthorized(AudioEventScope scope, BunnyStorageClient.Zone zone, String folder,
                                                    Map<Long, AudioEventScope.Slot> slots, Map<Long, Long> recordingCounts,
                                                    List<ProposedFile> files) {
        // First pass: what each file would be, and every folder that has to be listed to know what is there.
        List<AstObject> replies = new ArrayList<>(files.size());
        List<String> paths = new ArrayList<>(files.size());
        Set<String> folders = new LinkedHashSet<>();
        Set<String> seenPaths = new HashSet<>();
        for (ProposedFile file : files) {
            AstObject reply = AST.createObject();
            reply.set("slotId", file.slotId());
            replies.add(reply);
            paths.add(null);
            AudioEventScope.Slot slot = file.slotId() == null ? null : slots.get(file.slotId());
            String extension = AudioStorageNames.declaredExtension(file.name());
            String error = slot == null ? NOT_AUDIO_SLOT
                : extension == null ? UNSUPPORTED_TYPE
                : file.size() == null || file.size() <= 0 ? EMPTY_FILE
                : file.size() > MAX_UPLOAD_BYTES ? TOO_LARGE
                : recordingCounts.getOrDefault(slot.id(), 0L) > 1 ? MULTIPLE_RECORDINGS
                : null;
            if (error != null) {
                reply.set("error", error);
                continue;
            }
            String languageFolder = AudioStorageNames.folderForLanguage(folder, slot.languageFolder());
            String fileName = AudioStorageNames.cleanFileName(file.name(), extension);
            String path = languageFolder + "/" + fileName;
            reply.set("fileName", fileName);
            reply.set("path", path);
            reply.set("url", zone.cdnUrl(path));
            if (!seenPaths.add(path.toLowerCase(Locale.ROOT))) {
                reply.set("error", DUPLICATE_IN_BATCH);
                continue;
            }
            paths.set(replies.size() - 1, path);
            folders.add(languageFolder);
        }
        // Second pass: what is in those folders, and which recordings play which address.
        List<String> folderList = new ArrayList<>(folders);
        List<Future<List<BunnyStorageClient.Entry>>> listings = new ArrayList<>(folderList.size());
        for (String f : folderList)
            listings.add(BunnyStorageClient.list(zone, f));
        Future<?> listed = listings.isEmpty() ? Future.succeededFuture() : Future.all(listings);
        return listed
            .compose(v -> loadUsers(zone))
            .map(users -> {
                Set<String> existing = new HashSet<>();
                for (int i = 0; i < folderList.size(); i++)
                    for (BunnyStorageClient.Entry entry : listings.get(i).result())
                        if (!entry.directory())
                            existing.add((folderList.get(i) + "/" + entry.name()).toLowerCase(Locale.ROOT));
                AstArray array = AST.createArray();
                for (int i = 0; i < replies.size(); i++) {
                    AstObject reply = replies.get(i);
                    String path = paths.get(i);
                    if (path != null) {
                        long slotId = files.get(i).slotId();
                        boolean exists = existing.contains(path.toLowerCase(Locale.ROOT));
                        List<RecordingUser> usedBy = users.getOrDefault(pathKey(path), Collections.emptyList());
                        boolean usedElsewhere = usedBy.stream().anyMatch(u -> u.slotId() != slotId);
                        reply.set("exists", exists);
                        reply.set("sameSlot", usedBy.stream().anyMatch(u -> u.slotId() == slotId));
                        reply.set("usedBy", usersJson(usedBy, slotId));
                        if (usedElsewhere)
                            reply.set("error", USED_BY_ANOTHER_SESSION);
                        else
                            reply.set("ticket", AudioUploadTickets.issue(slotId, scope.eventId(), scope.organizationId(), path,
                                AudioStorageNames.declaredExtension(path), zone, scope.accountApiKey()));
                    }
                    array.push(reply);
                }
                AstObject json = AST.createObject();
                json.set("folder", folder);
                json.set("files", array);
                return AST.formatObject(json, "json");
            });
    }

    /** A recording that plays an address: its session, for "already used by Teaching 3 (French, 26 Jul)". */
    record RecordingUser(long mediaId, long slotId, String itemCode, String date, String sessionName) {
    }

    private static final String USERS_SQL_HEAD =
        "select m.id, m.url, m.scheduled_item_id, i.code, si.date, p.name from media m" +
        " join scheduled_item si on si.id = m.scheduled_item_id join item i on i.id = si.item_id" +
        " left join scheduled_item p on p.id = si.program_scheduled_item_id" +
        " where ";

    /**
     * Every recording whose address is on one of this zone's hostnames, keyed by the zone path it names
     * ({@link AudioStorageNames#zonePathKey}). Addresses typed by hand spell one file in many ways — another
     * scheme, upper-case host, a custom hostname, {@code %20} or a raw space, doubled slashes, backslashes — and
     * all of them must count as "this file is played", or a delete or an overwrite would miss a session.
     */
    private static Future<Map<String, List<RecordingUser>>> loadUsers(BunnyStorageClient.Zone zone) {
        List<Object> parameters = new ArrayList<>();
        String onZone = onZoneHosts("m.url", parameters, zone);
        return AudioServerSql.query(USERS_SQL_HEAD + onZone, parameters.toArray()).map(r -> {
            Map<String, List<RecordingUser>> users = new HashMap<>();
            for (int row = 0; row < r.getRowCount(); row++) {
                String key = AudioStorageNames.zonePathKey(r.getValue(row, 1), zone.hostnames());
                if (key == null)
                    continue;
                Object date = r.getValue(row, 4);
                users.computeIfAbsent(key, k -> new ArrayList<>()).add(new RecordingUser(
                    Numbers.toLong(r.getValue(row, 0)), Numbers.toLong(r.getValue(row, 2)), r.getValue(row, 3),
                    date == null ? null : date.toString(), r.getValue(row, 5)));
            }
            return users;
        });
    }

    /**
     * {@code (lower(column) like $n or …)}, one placeholder per hostname of the zone, the patterns appended to
     * {@code parameters}. The statement's text holds only placeholders; the hostnames, which come from Bunny,
     * are bound as values with LIKE's wildcards escaped. It only narrows the rows: the host is matched anywhere
     * in the address, since a port or user info would hide {@code //host/}, and {@link AudioStorageNames#zonePath}
     * then decides which addresses really are on the zone.
     */
    private static String onZoneHosts(String column, List<Object> parameters, BunnyStorageClient.Zone zone) {
        if (zone.hostnames().isEmpty())
            return "false";
        StringBuilder sb = new StringBuilder("(");
        for (String host : zone.hostnames()) {
            parameters.add("%" + likeEscape(host) + "%");
            if (sb.length() > 1) sb.append(" or ");
            sb.append("lower(").append(column).append(") like $").append(parameters.size());
        }
        return sb.append(")").toString();
    }

    /** {@code text} with LIKE's own wildcards escaped so they match themselves. */
    private static String likeEscape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** The comparison key of a zone path built here (already normalised): case folded. */
    static String pathKey(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private static AstArray usersJson(List<RecordingUser> users, long excludingSlotId) {
        AstArray array = AST.createArray();
        for (RecordingUser user : users) {
            if (user.slotId() == excludingSlotId)
                continue;
            AstObject o = AST.createObject();
            o.set("slotId", user.slotId());
            o.set("itemCode", user.itemCode());
            o.set("date", user.date());
            o.set("sessionName", user.sessionName());
            array.push(o);
        }
        return array;
    }

    private static Map<Long, Long> countsBySlot(QueryResult r) {
        Map<Long, Long> counts = new HashMap<>();
        for (int row = 0; row < r.getRowCount(); row++)
            counts.put(Numbers.toLong(r.getValue(row, 0)), Numbers.toLong(r.getValue(row, 1)));
        return counts;
    }

    // ─── Store one file ──────────────────────────────────────────────────────────────────────────────

    /** Why an upload was turned away: an HTTP status and a code for the screen. */
    static final class UploadRefusal extends RuntimeException {
        final int status;
        final String code;

        UploadRefusal(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }
    }

    /**
     * Slots with an upload in progress, with when it started: two at once on one slot could each insert a
     * recording. An entry older than {@link #BUSY_FOR_AT_MOST_MILLIS} is taken for one whose request never came
     * back (a Bunny call that stalled), so a slot cannot stay locked until the server restarts.
     */
    private static final ConcurrentHashMap<Object, Long> BUSY_SLOTS = new ConcurrentHashMap<>();
    /**
     * Storage paths an upload is writing, by {@link #pathKey}: two sessions given files of the same name could
     * otherwise both pass the "is it there, who plays it?" checks and both write the one object.
     */
    private static final ConcurrentHashMap<Object, Long> BUSY_PATHS = new ConcurrentHashMap<>();
    private static final long BUSY_FOR_AT_MOST_MILLIS = 2 * 60 * 60 * 1000L;

    /** Marks {@code key} busy in {@code busy}; false when work that is not stale holds it already. */
    private static boolean markBusy(ConcurrentHashMap<Object, Long> busy, Object key) {
        long now = System.currentTimeMillis();
        Long[] previous = new Long[1];
        busy.compute(key, (k, since) -> {
            previous[0] = since;
            return since != null && now - since < BUSY_FOR_AT_MOST_MILLIS ? since : now;
        });
        return previous[0] == null || now - previous[0] >= BUSY_FOR_AT_MOST_MILLIS;
    }

    private static final String SLOT_RECORDINGS_SQL =
        "select id, url from media where scheduled_item_id = $1 and type = 'AUDIO' order by id";
    private static final String UPDATE_RECORDING_SQL = "update media set url = $2, duration_millis = $3 where id = $1";
    private static final String INSERT_RECORDING_SQL =
        "insert into media (scheduled_item_id, type, url, duration_millis) values ($1, 'AUDIO', $2, $3)";
    private static final String PUBLISH_SLOT_SQL = "update scheduled_item set published = true where id = $1";

    /**
     * Stores the file a ticket was issued for, then records it.
     *
     * <ol>
     *   <li>A file already at that address is overwritten only when {@code overwrite} says so AND it is this
     *       session's own recording — no other session's, and no file nothing plays (one uploaded by hand, or
     *       kept when its recording was removed): those keep their name, and the new file takes another.
     *       Re-checked here, because the folder may have changed since the ticket was issued.</li>
     *   <li>The session's recording is updated in place when it has one (so listening history stays attached to
     *       it), inserted otherwise; a session with two is refused.</li>
     *   <li>{@code publish} sets the session published; it never unpublishes.</li>
     *   <li>After the database commit: the CDN is purged when a file was overwritten, and the previous file is
     *       deleted when {@code deletePrevious} asks and nothing else plays it. Those two are reported in the
     *       reply rather than failing it: the recording IS stored by then.</li>
     * </ol>
     */
    static Future<String> storeUpload(AudioUploadTickets.Ticket ticket, File file, String sniffedExtension, String sha256,
                                      Long durationMillis, boolean publish, boolean overwrite, boolean deletePrevious) {
        if (!sniffedExtension.equals(ticket.extension()))
            return Future.failedFuture(new UploadRefusal(415, UNSUPPORTED_TYPE));
        Long duration = durationMillis == null || durationMillis <= 0 || durationMillis > MAX_DURATION_MILLIS ? null : durationMillis;
        if (!markBusy(BUSY_SLOTS, ticket.slotId()))
            return Future.failedFuture(new UploadRefusal(409, BUSY));
        BunnyStorageClient.Zone zone = ticket.zone();
        String path = ticket.storagePath();
        if (!markBusy(BUSY_PATHS, pathKey(path))) {
            BUSY_SLOTS.remove(ticket.slotId());
            return Future.failedFuture(new UploadRefusal(409, BUSY));
        }
        String url = zone.cdnUrl(path);
        int slash = path.lastIndexOf('/');
        String folder = path.substring(0, slash), name = path.substring(slash + 1);
        Future<String> stored = BunnyStorageClient.list(zone, folder)
            .compose(entries -> {
                boolean exists = entries.stream().anyMatch(e -> !e.directory() && e.name().equalsIgnoreCase(name));
                if (exists && !overwrite)
                    return Future.failedFuture(new UploadRefusal(409, EXISTS));
                return loadUsers(zone).compose(users -> {
                    List<RecordingUser> usedBy = users.getOrDefault(pathKey(path), Collections.emptyList());
                    if (usedBy.stream().anyMatch(u -> u.slotId() != ticket.slotId()))
                        return Future.failedFuture(new UploadRefusal(409, USED_BY_ANOTHER_SESSION));
                    if (exists && usedBy.stream().noneMatch(u -> u.slotId() == ticket.slotId()))
                        return Future.failedFuture(new UploadRefusal(409, EXISTS));
                    return AudioServerSql.query(SLOT_RECORDINGS_SQL, ticket.slotId()).compose(recordings -> {
                        if (recordings.getRowCount() > 1)
                            return Future.failedFuture(new UploadRefusal(409, MULTIPLE_RECORDINGS));
                        return BunnyStorageClient.upload(zone, path, file, sha256)
                            // Read again after the transfer, which can take minutes: the recording may have been
                            // removed or added meanwhile (another tab), and the row written must be the one there NOW.
                            .compose(v -> AudioServerSql.query(SLOT_RECORDINGS_SQL, ticket.slotId()))
                            .compose(now -> {
                                Long mediaId = now.getRowCount() >= 1 ? Numbers.toLong(now.getValue(0, 0)) : null;
                                String previousUrl = mediaId == null ? null : now.getValue(0, 1);
                                return recordInDatabase(ticket.slotId(), mediaId, url, duration, publish)
                                    .compose(newMediaId -> afterCommit(ticket, folder, exists, url, previousUrl, deletePrevious)
                                        .map(notes -> uploadReply(newMediaId, url, duration, publish, notes)));
                            });
                    });
                });
            });
        return stored.eventually(() -> {
            BUSY_SLOTS.remove(ticket.slotId());
            BUSY_PATHS.remove(pathKey(path));
            return Future.succeededFuture();
        });
    }

    private static Future<Long> recordInDatabase(long slotId, Long mediaId, String url, Long duration, boolean publish) {
        List<AudioServerSql.Statement> statements = new ArrayList<>(2);
        statements.add(mediaId != null
            ? new AudioServerSql.Statement(UPDATE_RECORDING_SQL, mediaId, url, duration)
            : new AudioServerSql.Statement(INSERT_RECORDING_SQL, slotId, url, duration));
        if (publish)
            statements.add(new AudioServerSql.Statement(PUBLISH_SLOT_SQL, slotId));
        return AudioServerSql.submitInOneTransaction(statements)
            .compose(v -> AudioServerSql.query(SLOT_RECORDINGS_SQL, slotId))
            .map(r -> r.getRowCount() == 0 ? null : Numbers.toLong(r.getValue(0, 0)))
            .onSuccess(id -> Console.log("[BUNNY-AUDIO] slot " + slotId + " recording " + id + " ← " + url));
    }

    /** What happened after the commit that the screen should know: {@code [purgeFailed, previousFileDeleted]}. */
    private static Future<boolean[]> afterCommit(AudioUploadTickets.Ticket ticket, String folder, boolean overwrote, String url,
                                                 String previousUrl, boolean deletePrevious) {
        boolean[] notes = new boolean[2];
        Future<Void> purge = !overwrote ? Future.succeededFuture()
            : BunnyStorageClient.purge(ticket.accountApiKey(), url).recover(err -> {
                notes[0] = true;
                Console.log("[BUNNY-AUDIO] purge of " + url + " failed: " + err.getMessage());
                return Future.succeededFuture();
            });
        return purge.compose(v -> {
            String previousKey = AudioStorageNames.zonePathKey(previousUrl, ticket.zone().hostnames());
            if (!deletePrevious || previousUrl == null || pathKey(ticket.storagePath()).equals(previousKey))
                return Future.succeededFuture(notes);
            // The previous file goes only from the folder the new one went to: this session's, in this language.
            return deleteFileIfUnused(ticket.zone(), ticket.accountApiKey(), previousUrl, folder, NO_MEDIA)
                .map(outcome -> { notes[1] = outcome == null; return notes; })
                .recover(err -> {
                    Console.log("[BUNNY-AUDIO] previous file " + previousUrl + " not deleted: " + err.getMessage());
                    return Future.succeededFuture(notes);
                });
        });
    }

    private static String uploadReply(Long mediaId, String url, Long duration, boolean publish, boolean[] notes) {
        AstObject json = AST.createObject();
        json.set("mediaId", mediaId);
        json.set("url", url);
        json.set("durationMillis", duration);
        json.set("published", publish);
        json.set("purgeFailed", notes[0]);
        json.set("previousFileDeleted", notes[1]);
        return AST.formatObject(json, "json");
    }

    // ─── Remove one recording ────────────────────────────────────────────────────────────────────────

    private static final String RECORDING_SCOPE_SQL =
        "select m.url, si.id, p.event_id, e.organization_id, i.code, l.iso_639_3 from media m" +
        " join scheduled_item si on si.id = m.scheduled_item_id" +
        " join scheduled_item p on p.id = si.program_scheduled_item_id join event e on e.id = p.event_id" +
        " join item i on i.id = si.item_id join item_family f on f.id = i.family_id" +
        " left join language l on l.id = i.language_id" +
        " where m.id = $1 and f.code = 'record'";
    private static final String DELETE_CONSUMPTION_SQL = "delete from media_consumption where media_id = $1";
    private static final String DELETE_RECORDING_SQL = "delete from media where id = $1";
    private static final String UNPUBLISH_IF_EMPTY_SQL =
        "update scheduled_item set published = false where id = $1 and not exists (select 1 from media where scheduled_item_id = $1)";

    /**
     * Removes an audio recording — its listening history, the row, and the session's published flag when it
     * was the last one — and, when {@code deleteFile} asks, the file itself. The file goes only when it is an
     * audio file in this event's own folder for the recording's language, in the organisation's zone, and no
     * other recording plays it; otherwise the reply says why it stayed ({@code external}, {@code unsafeName},
     * {@code notAudioFile}, {@code outsideEventFolder}, {@code usedElsewhere}, {@code notFound},
     * {@code notConfigured}).
     *
     * <p>The file goes FIRST. The row is the only thing that leads to it — neither the staging anonymiser nor
     * {@code erase_past_event()} reaches the zone, so this is the deletion path for a recording — and a Bunny
     * failure must leave the row in place for a retry rather than strand the file. Should the database then
     * fail, the row points at a file that is gone, and removing it again finishes the job ({@code notFound}).
     */
    static Future<String> deleteRecording(Object mediaId, boolean deleteFile, Authorizer authorizer) {
        Long id = Numbers.toLong(mediaId);
        if (id == null)
            return RouteAccessGuard.refused();
        return AudioServerSql.query(RECORDING_SCOPE_SQL, id).compose(r -> {
            if (r.getRowCount() == 0)
                return RouteAccessGuard.refused();
            String url = r.getValue(0, 0);
            long slotId = Numbers.toLong(r.getValue(0, 1));
            Object eventId = r.getValue(0, 2);
            String languageFolder = AudioStorageNames.languageFolder(r.getValue(0, 4), r.getValue(0, 5));
            return authorizer.authorize(r.getValue(0, 3), eventId, () -> {
                // The slot is held while the recording goes, as an upload holds it: an upload under way would write
                // the row this removes, and one starting now would update a row about to be deleted.
                if (!markBusy(BUSY_SLOTS, slotId))
                    return Future.failedFuture(BUSY);
                return (deleteFile ? deleteFileOf(eventId, url, languageFolder, id) : Future.succeededFuture("notRequested"))
                    .compose(kept -> AudioServerSql.submitInOneTransaction(List.of(
                            new AudioServerSql.Statement(DELETE_CONSUMPTION_SQL, id),
                            new AudioServerSql.Statement(DELETE_RECORDING_SQL, id),
                            new AudioServerSql.Statement(UNPUBLISH_IF_EMPTY_SQL, slotId)))
                        .onSuccess(v -> Console.log("[BUNNY-AUDIO] recording " + id + " of slot " + slotId + " removed"))
                        .map(v -> {
                            AstObject json = AST.createObject();
                            json.set("deleted", true);
                            json.set("fileDeleted", kept == null);
                            json.set("fileKept", kept);
                            return AST.formatObject(json, "json");
                        }))
                    .eventually(() -> {
                        BUSY_SLOTS.remove(slotId);
                        return Future.succeededFuture();
                    });
            });
        });
    }

    /** The file of recording {@code mediaId}, about to be removed: deleted when it may be, else why it stays. */
    private static Future<String> deleteFileOf(Object eventId, String url, String languageFolder, long mediaId) {
        return AudioEventScope.load(eventId).compose(scope -> scope == null || !scope.missingFields().isEmpty()
            ? Future.succeededFuture("notConfigured")
            : scope.zone().compose(zone -> deleteFileIfUnused(zone, scope.accountApiKey(), url,
                scope.effectiveFolder() == null ? null : AudioStorageNames.folderForLanguage(scope.effectiveFolder(), languageFolder),
                mediaId)));
    }

    /** "No recording to leave out" for {@link #deleteFileIfUnused}. */
    private static final long NO_MEDIA = -1;

    /**
     * Deletes the file behind {@code url} from the zone and purges it — and refuses everything else. Null when
     * deleted; otherwise why it stayed:
     * <ul>
     *   <li>{@code external} — not an address of this zone;</li>
     *   <li>{@code unsafeName} — typed by hand with characters this will not name;</li>
     *   <li>{@code notAudioFile} — not an MP3 or M4A name: an address like {@code https://zone/international-
     *       festivals} names a FOLDER, and Bunny deletes a folder with everything in it;</li>
     *   <li>{@code outsideEventFolder} — not in {@code allowedFolder}, this event's folder for the recording's
     *       language: a grant for one event must not reach another event's files through a pasted address;</li>
     *   <li>{@code usedElsewhere} — a recording other than {@code removedMediaId} (the one being removed, still
     *       in the database while its file goes first) plays it, whatever way its address is written;</li>
     *   <li>{@code notFound} — the folder holds no file of that name (the DELETE is only sent for a file the
     *       listing shows, by the name the listing gives).</li>
     * </ul>
     */
    private static Future<String> deleteFileIfUnused(BunnyStorageClient.Zone zone, String accountApiKey, String url,
                                                     String allowedFolder, long removedMediaId) {
        String path = AudioStorageNames.zonePath(url, zone.hostnames());
        if (path == null)
            return Future.succeededFuture("external");
        if (path.contains("{") || !path.equals(AudioStorageNames.normaliseFolder(path, false)))
            return Future.succeededFuture("unsafeName");
        if (AudioStorageNames.declaredExtension(path) == null)
            return Future.succeededFuture("notAudioFile");
        int slash = path.lastIndexOf('/');
        String folder = slash < 0 ? "" : path.substring(0, slash), name = path.substring(slash + 1);
        if (allowedFolder == null || !folder.equals(allowedFolder))
            return Future.succeededFuture("outsideEventFolder");
        return loadUsers(zone).compose(users -> {
            if (users.getOrDefault(pathKey(path), Collections.emptyList()).stream().anyMatch(u -> u.mediaId() != removedMediaId))
                return Future.succeededFuture("usedElsewhere");
            return BunnyStorageClient.list(zone, folder).compose(entries -> {
                // The exact name, else the one file that differs from it only by case (an address typed by hand);
                // two such files and neither is chosen.
                List<BunnyStorageClient.Entry> files = entries.stream().filter(e -> !e.directory()).toList();
                BunnyStorageClient.Entry file = files.stream().filter(e -> e.name().equals(name)).findFirst().orElse(null);
                if (file == null) {
                    List<BunnyStorageClient.Entry> byCase = files.stream().filter(e -> e.name().equalsIgnoreCase(name)).toList();
                    file = byCase.size() == 1 ? byCase.get(0) : null;
                }
                if (file == null)
                    return Future.succeededFuture("notFound");
                String stored = folder + "/" + file.name();
                String storedUrl = zone.cdnUrl(stored);
                return BunnyStorageClient.delete(zone, stored)
                    .compose(v -> purgeQuietly(accountApiKey, storedUrl))
                    .compose(v -> storedUrl.equals(url) ? Future.<Void>succeededFuture() : purgeQuietly(accountApiKey, url))
                    .map(v -> (String) null)
                    .onSuccess(v -> Console.log("[BUNNY-AUDIO] file deleted: " + stored));
            });
        });
    }

    /** Purges a CDN address; a failure is logged, not thrown — the file is gone either way. */
    private static Future<Void> purgeQuietly(String accountApiKey, String url) {
        return BunnyStorageClient.purge(accountApiKey, url).recover(err -> {
            Console.log("[BUNNY-AUDIO] purge of deleted " + url + " failed: " + err.getMessage());
            return Future.succeededFuture();
        });
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    private static AstArray stringArray(List<String> values) {
        AstArray array = AST.createArray();
        for (String value : values)
            array.push(value);
        return array;
    }

    static String codeOf(Throwable err, String fallback) {
        if (err instanceof BunnyStorageClient.StorageException storage)
            return storage.code;
        Throwable cause = err == null ? null : err.getCause();
        if (cause instanceof BunnyStorageClient.StorageException storage)
            return storage.code;
        return fallback;
    }

    /** Arguments {@code [eventId, slotId, name, size, slotId, name, size, …]} as files. */
    static List<ProposedFile> proposedFiles(Object[] arguments) {
        List<ProposedFile> files = new ArrayList<>();
        for (int i = 1; i + 2 < arguments.length; i += 3)
            files.add(new ProposedFile(Numbers.toLong(arguments[i]), arguments[i + 1] == null ? null : arguments[i + 1].toString(),
                Numbers.toLong(arguments[i + 2])));
        return files;
    }
}
