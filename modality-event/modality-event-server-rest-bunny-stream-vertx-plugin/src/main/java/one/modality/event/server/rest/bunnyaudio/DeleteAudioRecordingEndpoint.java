package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Removes an audio recording — with its listening history, unpublishing the session when it was the last one —
 * and, when asked, deletes its file from the organisation's Bunny zone and purges it from the CDN.
 *
 * <p>Argument {@code [mediaId, deleteFile]}. Only a recording of an audio slot is accepted, and the caller must
 * be able to reach {@code /medias} for the organisation of the event its session belongs to. The file is kept
 * when it lies outside the zone or another recording plays it, and the reply says which.
 *
 * @author Claude Code
 */
public final class DeleteAudioRecordingEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** Mirrored in the back office's {@code AUDIO_STORAGE_ADDRESSES.DELETE_RECORDING}. */
    public static final String ADDRESS = "modality/service/media/deleteAudioRecording";

    public DeleteAudioRecordingEndpoint() {
        super(ADDRESS, argument -> {
            Object state = AudioBusArguments.callerState();
            if (state == null)
                return AudioBusArguments.refused();
            Object[] args = AudioBusArguments.array(argument);
            return AudioBusArguments.withPublicErrors(AudioStorageService.deleteRecording(AudioBusArguments.get(args, 0), AudioBusArguments.bool(args, 1),
                AudioBusArguments.authorizer(state)));
        });
    }
}
