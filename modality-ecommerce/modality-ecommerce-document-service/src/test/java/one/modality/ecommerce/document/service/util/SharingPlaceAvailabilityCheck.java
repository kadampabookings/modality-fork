package one.modality.ecommerce.document.service.util;

/**
 * Check for {@link SharingPlaceAvailability#isVirtualSharingRoom} — which rooms carry a VIRTUAL sharing
 * option, the ones a mate joins by naming the ROOM's item rather than a sharing Item of its own
 * (room-mate plan Part B).
 *
 * <p>No test framework: this repository declares no JUnit, so this runs from main() and exits non-zero on
 * failure, following MateLinkRulesCheck and ProtectedEntityWritesCheck.
 *
 * <p>Why this rule is worth pinning on both sides: it decides what a submit may claim to be a sharing
 * place, and a sharing place is priced at zero. The booking form's {@code getAccommodationOptions} has a
 * twin of it, and the two drifting apart shows up as the server refusing a bed the card offered — or, in
 * the direction that costs money, accepting one it never did.
 */
public class SharingPlaceAvailabilityCheck {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    /** A twin room: two beds, no sole-occupancy rule, sold as a whole room, offered in person. */
    static boolean twin(Integer capacity, Integer minOccupancy, boolean configuredPairingCovers) {
        return SharingPlaceAvailability.isVirtualSharingRoom(capacity, minOccupancy, configuredPairingCovers, false, true);
    }

    public static void main(String[] args) {
        System.out.println("SharingPlaceAvailability.isVirtualSharingRoom");

        check("a two-bed room nobody configured a way of sharing carries one",
            twin(2, null, false));
        check("so does a larger room", twin(6, null, false));

        // ── The organisation's own statement governs ──
        check("a room a configured sharing option pairs with carries none",
            !twin(2, null, true));
        check("...whatever its capacity, since the configured option answers for it",
            !twin(8, null, true));

        // ── Rooms with no bed to share ──
        check("a single room carries none: one bed cannot be shared",
            !twin(1, null, false));
        check("a room with no capacity carries none: it is not a whole-room type",
            !twin(null, null, false));
        check("a zero-capacity room carries none",
            !twin(0, null, false));
        check("a room held for sole occupancy carries none: its booker hosts nobody",
            !twin(2, 1, false));
        check("a minimum occupancy above one still carries one -- it is not sole occupancy",
            twin(4, 2, false));
        // ── The server must be at least as strict as the card, because this rule decides what is free ──
        check("a room sold per person carries none: everyone in it has paid for their own place",
            !SharingPlaceAvailability.isVirtualSharingRoom(4, null, false, true, true));
        check("a room its policy does not offer in person carries none",
            !SharingPlaceAvailability.isVirtualSharingRoom(2, null, false, false, false));

        // Not asserted here, because it cannot happen: minOccupancy is compared with Objects.equals, which
        // a Long 1 would defeat. ItemPolicy.getMinOccupancy() is typed Integer, so the value always arrives
        // as one -- if that ever changes, this comparison has to become samePrimaryKey-style.

        System.out.println(pass + " passed, " + fail + " failed");
        if (fail > 0)
            System.exit(1);
    }
}
