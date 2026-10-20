package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.util.Numbers;
import dev.webfx.stack.db.query.QueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the audio-storage endpoints need to know about one event, read in one go: who owns it (what the
 * caller's grant is checked against), where its files go, and its organisation's Bunny settings.
 *
 * <p>Audio slots are found the way the back-office audio screen lists them — the per-language children of the
 * event's programme sessions ({@code programScheduledItem.event}, item family {@code record}) — so a slot the
 * screen shows is a slot these endpoints accept, and nothing else is.
 */
record AudioEventScope(
    Object eventId,
    Object organizationId,
    String eventName,
    int year,
    int month,
    Object eventTypeId,
    String eventTypeName,
    Object eventTypeOrganizationId,
    String eventTypePattern,
    String eventFolder,
    Object repeatedEventId,
    boolean repeatAudio,
    String storageZone,
    String pullZone,
    String accountApiKey) {

    /** Codes the back office maps to translated rows of its "connect Bunny" panel. */
    static final String MISSING_STORAGE_ZONE = "organization.audioBunnyStorageZoneId";
    static final String MISSING_API_KEY = "organization.bunnyApiKey";

    private static final String EVENT_SQL =
        "select e.id, e.organization_id, e.name, extract(year from e.start_date)::int, extract(month from e.start_date)::int," +
        " e.type_id, et.name, et.organization_id, et.audio_bunny_folder_pattern, e.audio_bunny_folder," +
        " e.repeated_event_id, e.repeat_audio," +
        " o.audio_bunny_storage_zone_id, o.audio_bunny_pull_zone_id, o.bunny_api_key" +
        " from event e join organization o on o.id = e.organization_id left join event_type et on et.id = e.type_id" +
        " where e.id = $1";

    /** An audio slot of the event: what language it is, and through it, which folder its file goes in. */
    record Slot(long id, String itemCode, String languageFolder) {
    }

    private static final String SLOTS_SQL =
        "select si.id, i.code, l.iso_639_3 from scheduled_item si" +
        " join scheduled_item p on p.id = si.program_scheduled_item_id" +
        " join item i on i.id = si.item_id join item_family f on f.id = i.family_id" +
        " left join language l on l.id = i.language_id" +
        " where p.event_id = $1 and f.code = 'record'";

    /** The event, or null when there is no such event. */
    static Future<AudioEventScope> load(Object eventId) {
        Long id = Numbers.toLong(eventId);
        if (id == null)
            return Future.succeededFuture(null);
        return AudioServerSql.query(EVENT_SQL, id).map(r -> r.getRowCount() == 0 ? null : new AudioEventScope(
            r.getValue(0, 0), r.getValue(0, 1), r.getValue(0, 2),
            intOf(r.getValue(0, 3)), intOf(r.getValue(0, 4)),
            r.getValue(0, 5), r.getValue(0, 6), r.getValue(0, 7), blankToNull(r.getValue(0, 8)), blankToNull(r.getValue(0, 9)),
            r.getValue(0, 10), Boolean.TRUE.equals(r.getValue(0, 11)),
            blankToNull(r.getValue(0, 12)), blankToNull(r.getValue(0, 13)), blankToNull(r.getValue(0, 14))));
    }

    /** The event's audio slots by id, in no particular order. */
    Future<Map<Long, Slot>> loadSlots() {
        return AudioServerSql.query(SLOTS_SQL, Numbers.toLong(eventId)).map(r -> {
            Map<Long, Slot> slots = new LinkedHashMap<>();
            for (int row = 0; row < r.getRowCount(); row++) {
                long id = Numbers.toLong(r.getValue(row, 0));
                String code = r.getValue(row, 1);
                slots.put(id, new Slot(id, code, AudioStorageNames.languageFolder(code, r.getValue(row, 2))));
            }
            return slots;
        });
    }

    /** The Bunny settings the organisation has not filled in yet; empty when the zone can be looked up. */
    List<String> missingFields() {
        List<String> missing = new ArrayList<>(2);
        if (storageZone == null) missing.add(MISSING_STORAGE_ZONE);
        if (accountApiKey == null) missing.add(MISSING_API_KEY);
        return missing;
    }

    /** The organisation's zone. Only called once {@link #missingFields()} is empty. */
    Future<BunnyStorageClient.Zone> zone() {
        return BunnyStorageClient.resolveZone(accountApiKey, storageZone, pullZone);
    }

    /** The folder this event's files go in — its own, else its type's pattern resolved — or null when neither. */
    String effectiveFolder() {
        if (eventFolder != null)
            return eventFolder;
        return AudioStorageNames.resolvePattern(eventTypePattern, year, month, eventName);
    }

    /** Where {@link #effectiveFolder()} comes from: {@code event}, {@code pattern} or {@code none}. */
    String folderSource() {
        return eventFolder != null ? "event" : effectiveFolder() != null ? "pattern" : "none";
    }

    /**
     * Whether a pattern may be saved on this event's type from here: only a type of the event's OWN
     * organisation. A global type ({@code organization_id} null, V0051) is shared by every centre, and a
     * pattern saved on it by one would decide where every other centre's files go.
     */
    boolean canSaveTypePattern() {
        return eventTypeId != null && eventTypeOrganizationId != null
            && Numbers.toLong(eventTypeOrganizationId).equals(Numbers.toLong(organizationId));
    }

    private static int intOf(Object value) {
        Integer i = Numbers.toInteger(value);
        return i == null ? 0 : i;
    }

    private static String blankToNull(Object value) {
        if (value == null)
            return null;
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Never print the API key, whatever logs a record. */
    @Override
    public String toString() {
        return "AudioEventScope[event=" + eventId + ", organization=" + organizationId + "]";
    }
}
