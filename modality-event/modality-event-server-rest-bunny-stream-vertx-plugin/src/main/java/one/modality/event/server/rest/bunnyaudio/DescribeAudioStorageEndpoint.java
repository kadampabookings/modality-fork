package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Where an event's audio recordings are stored, and whether the back office can upload there: the zone, the
 * event's folder and where it comes from, a suggestion when it has none, each language's subfolder, and —
 * when a path is given — that folder's subfolders, for the folder picker.
 *
 * <p>Argument {@code [eventId, browsePath?]}; reply JSON text (see {@link AudioStorageService#describe}). The
 * caller must be able to reach {@code /medias} for the event's organisation.
 *
 * @author Claude Code
 */
public final class DescribeAudioStorageEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** Mirrored in the back office's {@code AUDIO_STORAGE_ADDRESSES.DESCRIBE}. */
    public static final String ADDRESS = "modality/service/media/describeAudioStorage";

    public DescribeAudioStorageEndpoint() {
        super(ADDRESS, argument -> {
            Object state = AudioBusArguments.callerState();
            if (state == null)
                return AudioBusArguments.refused();
            Object[] args = AudioBusArguments.array(argument);
            return AudioBusArguments.withPublicErrors(AudioStorageService.describe(AudioBusArguments.get(args, 0), AudioBusArguments.string(args, 1),
                AudioBusArguments.authorizer(state)));
        });
    }
}
