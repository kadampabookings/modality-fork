package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Checks the files the back office is about to upload, and hands out a ticket for each that may go ahead.
 *
 * <p>Argument {@code [eventId, slotId, fileName, size, slotId, fileName, size, …]}: per file, the audio slot
 * (one session in one language), the name staff kept or gave it, and its size. Reply, per file: the cleaned
 * name, where it lands, whether a file of that name is there already and which sessions play it, and a
 * single-use upload ticket — or why not ({@link AudioStorageService#prepareUploads}).
 *
 * <p>This is where uploading is authorised: the caller must be able to reach {@code /medias} for the event's
 * organisation, and every slot must be one of THAT event's. {@code POST /rest/audio/upload} accepts a ticket
 * and nothing else.
 *
 * @author Claude Code
 */
public final class PrepareAudioUploadsEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** Mirrored in the back office's {@code AUDIO_STORAGE_ADDRESSES.PREPARE_UPLOADS}. */
    public static final String ADDRESS = "modality/service/media/prepareAudioUploads";

    public PrepareAudioUploadsEndpoint() {
        super(ADDRESS, argument -> {
            Object state = AudioBusArguments.callerState();
            if (state == null)
                return AudioBusArguments.refused();
            Object[] args = AudioBusArguments.array(argument);
            return AudioBusArguments.withPublicErrors(AudioStorageService.prepareUploads(AudioBusArguments.get(args, 0),
                AudioStorageService.proposedFiles(args), AudioBusArguments.authorizer(state)));
        });
    }
}
