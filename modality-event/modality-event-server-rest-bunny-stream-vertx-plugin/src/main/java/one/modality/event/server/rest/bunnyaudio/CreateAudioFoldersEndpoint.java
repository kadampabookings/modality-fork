package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Creates a folder in the organisation's Bunny audio zone — or, for a folder holding {@code {lang}}, one per
 * language of the event — so it exists before anything is uploaded to it.
 *
 * <p>Argument {@code [eventId, folder]}, validated as {@link SetAudioFolderEndpoint} validates a folder. The
 * caller must be able to reach {@code /medias} for the event's organisation. Reply
 * {@code {"folders": [{"path", "outcome"}]}}.
 *
 * @author Claude Code
 */
public final class CreateAudioFoldersEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** Mirrored in the back office's {@code AUDIO_STORAGE_ADDRESSES.CREATE_FOLDERS}. */
    public static final String ADDRESS = "modality/service/media/createAudioFolders";

    public CreateAudioFoldersEndpoint() {
        super(ADDRESS, argument -> {
            Object state = AudioBusArguments.callerState();
            if (state == null)
                return AudioBusArguments.refused();
            Object[] args = AudioBusArguments.array(argument);
            return AudioBusArguments.withPublicErrors(AudioStorageService.createFolders(AudioBusArguments.get(args, 0),
                AudioBusArguments.string(args, 1), AudioBusArguments.authorizer(state)));
        });
    }
}
