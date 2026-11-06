package one.modality.crm.server.authn.gateway.shared;

import dev.webfx.platform.console.Console;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where an IP address is, as "City, Country", read from an OFFLINE database — so a super administrator deciding
 * whether a new sign-in device is really their colleague's can see that the request came from Lyon and not from
 * somewhere the colleague has never been.
 *
 * <h3>Why offline, and why read here</h3>
 *
 * <p>An IP address is personal data. Asking an online service would hand every staff member's address to a third
 * party (and need a data-processing agreement); reading a file shipped with the deploy hands it to nobody. The file
 * is DB-IP's "IP to City Lite" (CC BY 4.0, attribution shown on the approval page), in the MaxMind DB format.
 *
 * <p>The reader is a minimal implementation of that format (https://maxmind.github.io/MaxMind-DB/) rather than the
 * {@code com.maxmind.db} library, because a new Maven dependency means regenerating WebFX-managed POMs — and this
 * needs only the lookup half: walk the search tree, decode one record. The file is memory-mapped, not loaded, so its
 * ~120 MB costs page cache rather than heap.
 *
 * <h3>Never a failure</h3>
 *
 * <p>No file configured, a file that will not open, an address that is private or not in the file, or a malformed
 * record all answer null: a location is a hint for a person, and nothing may be refused because a hint is missing.
 * The file is trusted (it comes with the image, never from a client), but the decoder still bounds every read so a
 * corrupt file fails as "no location" rather than as an exception in a sign-in path.
 *
 * @author Claude Code
 */
public final class IpLocator {

    private static final String LOG_PREFIX = "[2fa] ";
    private static final byte[] METADATA_MARKER = "«ÍïMaxMind.com".getBytes(StandardCharsets.ISO_8859_1);
    private static final int DATA_SECTION_SEPARATOR = 16;
    private static final int MAX_DEPTH = 32; // nesting bound for a corrupt file

    private static volatile IpLocator current; // null = no database

    private final ByteBuffer buffer;
    private final int nodeCount;
    private final int recordSize;
    private final int ipVersion;
    private final int treeSize;
    private final int ipv4Start; // the node the IPv4 subtree starts at, in an IPv6 tree

    private IpLocator(ByteBuffer buffer, int nodeCount, int recordSize, int ipVersion) {
        this.buffer = buffer;
        this.nodeCount = nodeCount;
        this.recordSize = recordSize;
        this.ipVersion = ipVersion;
        this.treeSize = (recordSize * 2 / 8) * nodeCount;
        int node = 0;
        if (ipVersion == 6)
            for (int i = 0; i < 96 && node < nodeCount; i++)
                node = readNode(node, 0);
        this.ipv4Start = node;
    }

    /** Opens the database at {@code path}, or switches location off when it is null or unreadable. Logged either way. */
    static void init(String path) {
        if (path == null) {
            current = null;
            Console.log(LOG_PREFIX + "IP location: off (IP_LOCATION_DATABASE unset) — approvers see addresses without places");
            return;
        }
        try (FileChannel channel = FileChannel.open(Path.of(path), StandardOpenOption.READ)) {
            long size = channel.size();
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            current = open(mapped);
            Console.log(LOG_PREFIX + "IP location: " + size / (1024 * 1024) + " MB database loaded");
        } catch (IOException | RuntimeException e) {
            current = null;
            Console.log(LOG_PREFIX + "⚠️ IP location: the database at the configured path could not be opened ("
                        + e.getClass().getSimpleName() + ") — approvers see addresses without places");
        }
    }

    /** Opens a database already in memory — the entry point for tests. */
    static IpLocator open(ByteBuffer buffer) {
        int metadataStart = findMetadataStart(buffer);
        if (metadataStart < 0)
            throw new IllegalArgumentException("not a MaxMind DB file");
        IpLocator reader = new IpLocator(buffer, 0, 24, 4); // decoder only; tree fields unused while reading metadata
        Object metadata = reader.decode(metadataStart, metadataStart, 0).value;
        if (!(metadata instanceof Map<?, ?> map))
            throw new IllegalArgumentException("unreadable metadata");
        int nodeCount = intOf(map.get("node_count"));
        int recordSize = intOf(map.get("record_size"));
        int ipVersion = intOf(map.get("ip_version"));
        if (nodeCount <= 0 || (recordSize != 24 && recordSize != 28 && recordSize != 32) || (ipVersion != 4 && ipVersion != 6))
            throw new IllegalArgumentException("unsupported metadata");
        return new IpLocator(buffer, nodeCount, recordSize, ipVersion);
    }

    /** Whether a database is loaded — the approval page shows the attribution only then. */
    public static boolean isEnabled() {
        return current != null;
    }

    /** "City, Country", "Country", or null — never throws. */
    public static String locate(String ip) {
        IpLocator locator = current;
        return locator == null ? null : locator.lookup(ip);
    }

    String lookup(String ip) {
        if (ip == null || ip.isBlank())
            return null;
        try {
            // Literal addresses only: InetAddress.getByName on a hostname would resolve it over DNS
            String literal = FactorRequest.ipLiteralOrNull(ip);
            if (literal == null)
                return null;
            InetAddress address = InetAddress.getByName(literal);
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress())
                return null;
            byte[] bytes = address.getAddress();
            if (bytes.length == 16 && ipVersion == 4)
                return null;
            int node = bytes.length == 4 && ipVersion == 6 ? ipv4Start : 0;
            int bitCount = bytes.length * 8;
            for (int i = 0; i < bitCount && node < nodeCount; i++) {
                int bit = (bytes[i >> 3] >> (7 - (i & 7))) & 1;
                node = readNode(node, bit);
            }
            if (node <= nodeCount) // == nodeCount: not found
                return null;
            int dataSectionStart = treeSize + DATA_SECTION_SEPARATOR;
            int offset = (node - nodeCount) - DATA_SECTION_SEPARATOR + dataSectionStart;
            Object record = decode(offset, dataSectionStart, 0).value;
            return placeOf(record);
        } catch (UnknownHostException | RuntimeException e) {
            return null;
        }
    }

    private static String placeOf(Object record) {
        if (!(record instanceof Map<?, ?> map))
            return null;
        String city = englishName(map.get("city"));
        String country = englishName(map.get("country"));
        if (city != null && country != null)
            return city + ", " + country;
        return country != null ? country : city;
    }

    private static String englishName(Object part) {
        if (part instanceof Map<?, ?> map && map.get("names") instanceof Map<?, ?> names && names.get("en") instanceof String en)
            return en.isBlank() ? null : en;
        return null;
    }

    private int readNode(int node, int index) {
        int base = node * recordSize * 2 / 8;
        switch (recordSize) {
            case 24: {
                int p = base + index * 3;
                return ((buffer.get(p) & 0xFF) << 16) | ((buffer.get(p + 1) & 0xFF) << 8) | (buffer.get(p + 2) & 0xFF);
            }
            case 28: {
                int middle = buffer.get(base + 3) & 0xFF;
                if (index == 0)
                    return ((middle & 0xF0) << 20) | ((buffer.get(base) & 0xFF) << 16) | ((buffer.get(base + 1) & 0xFF) << 8) | (buffer.get(base + 2) & 0xFF);
                return ((middle & 0x0F) << 24) | ((buffer.get(base + 4) & 0xFF) << 16) | ((buffer.get(base + 5) & 0xFF) << 8) | (buffer.get(base + 6) & 0xFF);
            }
            default: {
                int p = base + index * 4;
                return buffer.getInt(p);
            }
        }
    }

    private record Decoded(Object value, int next) {}

    /**
     * Decodes the field at {@code offset}. {@code pointerBase} is where pointers count from: the data section for
     * records, the metadata start for the metadata map.
     */
    private Decoded decode(int offset, int pointerBase, int depth) {
        if (depth > MAX_DEPTH)
            throw new IllegalStateException("nesting too deep");
        int ctrl = buffer.get(offset) & 0xFF;
        int p = offset + 1;
        int type = ctrl >> 5;
        if (type == 1) { // pointer
            int sizeBits = (ctrl >> 3) & 0x3;
            int low = ctrl & 0x7;
            int pointer;
            switch (sizeBits) {
                case 0: pointer = (low << 8) | (buffer.get(p) & 0xFF); p += 1; break;
                case 1: pointer = ((low << 16) | ((buffer.get(p) & 0xFF) << 8) | (buffer.get(p + 1) & 0xFF)) + 2048; p += 2; break;
                case 2: pointer = ((low << 24) | ((buffer.get(p) & 0xFF) << 16) | ((buffer.get(p + 1) & 0xFF) << 8) | (buffer.get(p + 2) & 0xFF)) + 526336; p += 3; break;
                default: pointer = buffer.getInt(p); p += 4; break;
            }
            Object target = decode(pointerBase + pointer, pointerBase, depth + 1).value;
            return new Decoded(target, p);
        }
        if (type == 0) { // extended type
            type = 7 + (buffer.get(p) & 0xFF);
            p++;
        }
        int size = ctrl & 0x1F;
        if (size == 29) {
            size = 29 + (buffer.get(p) & 0xFF);
            p += 1;
        } else if (size == 30) {
            size = 285 + (((buffer.get(p) & 0xFF) << 8) | (buffer.get(p + 1) & 0xFF));
            p += 2;
        } else if (size == 31) {
            size = 65821 + (((buffer.get(p) & 0xFF) << 16) | ((buffer.get(p + 1) & 0xFF) << 8) | (buffer.get(p + 2) & 0xFF));
            p += 3;
        }
        switch (type) {
            case 2: { // UTF-8 string
                byte[] bytes = new byte[size];
                buffer.get(p, bytes);
                return new Decoded(new String(bytes, StandardCharsets.UTF_8), p + size);
            }
            case 3: return new Decoded(buffer.getDouble(p), p + 8);
            case 4: return new Decoded(null, p + size); // bytes: not needed
            case 5: case 6: case 9: case 10: case 8: { // unsigned/signed integers, big-endian, `size` bytes
                long value = 0;
                for (int i = 0; i < size; i++)
                    value = (value << 8) | (buffer.get(p + i) & 0xFF);
                return new Decoded(value, p + size);
            }
            case 7: { // map
                Map<String, Object> map = new HashMap<>();
                int next = p;
                for (int i = 0; i < size; i++) {
                    Decoded key = decode(next, pointerBase, depth + 1);
                    Decoded value = decode(key.next, pointerBase, depth + 1);
                    if (key.value instanceof String k)
                        map.put(k, value.value);
                    next = value.next;
                }
                return new Decoded(map, next);
            }
            case 11: { // array
                List<Object> list = new ArrayList<>(Math.min(size, 64));
                int next = p;
                for (int i = 0; i < size; i++) {
                    Decoded value = decode(next, pointerBase, depth + 1);
                    list.add(value.value);
                    next = value.next;
                }
                return new Decoded(list, next);
            }
            case 14: return new Decoded(size != 0, p); // boolean: the value is in the size bits
            case 15: return new Decoded(buffer.getFloat(p), p + 4);
            default: return new Decoded(null, p + size); // data cache container, end marker: nothing to read here
        }
    }

    private static int findMetadataStart(ByteBuffer buffer) {
        int limit = buffer.limit();
        int searchFrom = Math.max(0, limit - 128 * 1024); // the metadata sits in the last 128 KiB
        for (int i = limit - METADATA_MARKER.length; i >= searchFrom; i--) {
            boolean match = true;
            for (int j = 0; j < METADATA_MARKER.length && match; j++)
                match = buffer.get(i + j) == METADATA_MARKER[j];
            if (match)
                return i + METADATA_MARKER.length;
        }
        return -1;
    }

    private static int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }
}
