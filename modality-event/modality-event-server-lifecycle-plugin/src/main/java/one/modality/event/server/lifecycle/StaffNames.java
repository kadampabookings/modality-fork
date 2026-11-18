package one.modality.event.server.lifecycle;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.query.QueryResult;

import java.util.Collection;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The names of the staff members the DPO's ledgers name by person id ({@code requested_by_person_id},
 * {@code set_by_person_id}, {@code superseded_by_person_id}), served beside those rows as {@code {"<id>":"<name>"}} so
 * the Data protection pages say who acted rather than {@code #id}.
 *
 * <p><b>Staff only, read apart.</b> The ids come from the ledgers' "who ran it" columns, never a subject's, and are
 * looked up in a query of their own, so the ledger reads keep selecting no name at all (DataProtectionCheck holds the
 * activity read to that). A name is first + last name, nothing else; an anonymised staff record answers with its
 * pseudonym, and an id with no person row is left out (the page falls back to {@code #id}).
 *
 * @author Claude Code
 */
final class StaffNames {

    private StaffNames() {
    }

    private static final String LOG_PREFIX = "[data-protection] ";

    /** At most this many ids are looked up: the ledger reads are themselves capped (200 and 500 rows). */
    static final int MAX_IDS = 500;

    /**
     * The lookup for these ids, or null when there is none to make. The ids are integers the server read from its own
     * ledgers (never text from the caller), so they are inlined; sorted and de-duplicated.
     */
    static String sql(Collection<? extends Number> ids) {
        TreeSet<Long> distinct = new TreeSet<>();
        for (Number id : ids)
            if (id != null && distinct.size() < MAX_IDS)
                distinct.add(id.longValue());
        if (distinct.isEmpty())
            return null;
        return "select id, trim(concat_ws(' ', first_name, last_name)) from person where id in ("
               + distinct.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";
    }

    /** {@code {"<id>":"<name>"}} from {@link #sql}'s rows; a blank name is left out. Pure, for the check. */
    static String json(QueryResult r) {
        EventErasureJson.ObjectWriter writer = new EventErasureJson.ObjectWriter();
        for (int row = 0; r != null && row < r.getRowCount(); row++) {
            Object id = r.getValue(row, 0);
            Object name = r.getValue(row, 1);
            if (id instanceof Number n && name != null && !name.toString().isBlank())
                writer.string(Long.toString(n.longValue()), name.toString());
        }
        return writer.toJson();
    }

    /**
     * The names of these staff ids as JSON. Never fails: a lookup that goes wrong is logged and answers {@code {}}, and
     * the page then shows ids, as it did before names existed.
     */
    static Future<String> lookup(Collection<? extends Number> ids) {
        String sql = sql(ids);
        if (sql == null)
            return Future.succeededFuture("{}");
        return EventErasureRunner.query(sql)
            .map(StaffNames::json)
            .recover(cause -> {
                Console.log(LOG_PREFIX + "Reading staff names failed: " + (cause == null ? null : cause.getMessage()));
                return Future.succeededFuture("{}");
            });
    }
}
