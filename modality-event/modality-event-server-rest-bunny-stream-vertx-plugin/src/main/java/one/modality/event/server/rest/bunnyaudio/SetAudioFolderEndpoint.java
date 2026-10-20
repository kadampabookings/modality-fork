package one.modality.event.server.rest.bunnyaudio;

import dev.webfx.stack.com.bus.call.spi.AsyncFunctionBusCallEndpoint;

/**
 * Chooses the folder of the Bunny zone an event's recordings go in, and optionally saves it as the pattern for
 * every new event of its type.
 *
 * <p>Argument {@code [eventId, folder, pattern?]}: {@code folder} may hold {@code {lang}} (one subfolder per
 * language) and nothing else, a blank one clears the event's own folder; {@code pattern} may also hold
 * {@code {year} {month} {event}}, and is refused for a type the event's organisation does not own. Both are
 * validated here — relative, no {@code ..}, safe characters — because this is the only place they are
 * written. Reply: the fresh description, as {@link DescribeAudioStorageEndpoint} gives it.
 *
 * @author Claude Code
 */
public final class SetAudioFolderEndpoint extends AsyncFunctionBusCallEndpoint<Object, String> {

    /** Mirrored in the back office's {@code AUDIO_STORAGE_ADDRESSES.SET_FOLDER}. */
    public static final String ADDRESS = "modality/service/media/setAudioFolder";

    public SetAudioFolderEndpoint() {
        super(ADDRESS, argument -> {
            Object state = AudioBusArguments.callerState();
            if (state == null)
                return AudioBusArguments.refused();
            Object[] args = AudioBusArguments.array(argument);
            return AudioBusArguments.withPublicErrors(AudioStorageService.setFolder(AudioBusArguments.get(args, 0), AudioBusArguments.string(args, 1),
                AudioBusArguments.string(args, 2), AudioBusArguments.authorizer(state)));
        });
    }
}
