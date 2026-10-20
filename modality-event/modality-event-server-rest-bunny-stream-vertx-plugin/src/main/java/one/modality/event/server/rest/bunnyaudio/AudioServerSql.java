package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.platform.async.Batch;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.db.query.QueryArgumentBuilder;
import dev.webfx.stack.db.query.QueryResult;
import dev.webfx.stack.db.query.QueryService;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitArgumentBuilder;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.session.state.StateAccessor;
import dev.webfx.stack.session.state.ThreadLocalStateHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The SQL the audio-storage endpoints run, run as the SERVER — the way {@code TeachingSessionCascade} runs
 * its own.
 *
 * <p>Raw SQL because two of the columns read here are deliberately absent from the domain model
 * ({@code event.audio_bunny_folder} and {@code event_type.audio_bunny_folder_pattern}, V0137: only these
 * endpoints may write them, and they validate what they write). Every statement passed in is a constant of
 * the calling class; callers supply values only, always as parameters. {@code ClientSubmitGuard} refuses raw
 * statements of client origin, hence {@link #asServer}: by the time anything runs here, the endpoint has
 * already decided this caller may do it.
 */
final class AudioServerSql {

    private AudioServerSql() {
    }

    /** One statement of a batch: a constant and its parameter values. */
    record Statement(String sql, Object... parameters) {
    }

    static Future<QueryResult> query(String sql, Object... parameters) {
        return asServer(() -> QueryService.executeQuery(new QueryArgumentBuilder()
            .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
            .setStatement(sql)
            .setParameters(parameters)
            .build()));
    }

    /** The statements in ONE transaction: all land, or none does. */
    static Future<Void> submitInOneTransaction(List<Statement> statements) {
        List<SubmitArgument> arguments = new ArrayList<>(statements.size());
        for (Statement statement : statements)
            arguments.add(new SubmitArgumentBuilder()
                .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                .setStatement(statement.sql())
                .setParameters(statement.parameters())
                .build());
        return asServer(() -> SubmitService.executeSubmitBatch(new Batch<>(arguments.toArray(new SubmitArgument[0]))))
            .mapEmpty();
    }

    /**
     * Runs {@code call} with the caller's state removed. Read on THIS thread, before the call reaches the async
     * queue, because {@link ThreadLocalStateHolder} is restored once the synchronous part returns.
     */
    private static <T> T asServer(Supplier<T> call) {
        return ThreadLocalStateHolder.runWithState(StateAccessor.createEmptyState(), call);
    }
}
