package forge.arena.server.view;

import org.testng.annotations.Test;

import java.util.OptionalInt;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

public class IdMapperTest {
    private static Object snapshot(final IdMapper ids, final String zone, final boolean down, final boolean visible) {
        ids.beginSnapshot();
        final Object ref = ids.wireRef(12345, zone, down, visible);
        assertEquals(ids.wireRef(12345, zone, down, visible), ref, "References within a snapshot agree");
        ids.endSnapshot();
        return ref;
    }

    @Test
    public void hiddenIdentityLifecycleAndResolution() {
        final IdMapper ids = new IdMapper();
        final Object first = snapshot(ids, "Hand", false, false);
        assertTrue(first instanceof String && ((String) first).matches("h-[0-9a-f]{12}"));
        assertEquals(ids.resolve(first), OptionalInt.of(12345));
        assertFalse(ids.resolve(12345).isPresent());
        assertEquals(snapshot(ids, "Hand", false, false), first);

        final Object library = snapshot(ids, "Library", false, false);
        assertNotEquals(library, first);
        assertFalse(ids.resolve(first).isPresent());
        assertEquals(snapshot(ids, "Library", false, false), library);
        final Object down = snapshot(ids, "Library", true, false);
        assertNotEquals(down, library, "Turning face down rotates even within one zone");
        assertEquals(snapshot(ids, "Library", true, false), down);
        assertEquals(snapshot(ids, "Library", false, false), down, "Turning face up but staying hidden keeps id");
        final Object downAgain = snapshot(ids, "Library", true, false);
        assertNotEquals(downAgain, down);

        assertEquals(snapshot(ids, "Hand", false, true), Integer.valueOf(12345));
        assertEquals(ids.resolve(12345), OptionalInt.of(12345));
        assertFalse(ids.resolve(downAgain).isPresent());
        final Object concealed = snapshot(ids, "Hand", false, false);
        assertNotEquals(concealed, first, "Visible-to-hidden in the same zone rotates");
        assertNotEquals(concealed, downAgain);
        assertFalse(ids.resolve(12345).isPresent());
        assertEquals(ids.resolve(concealed), OptionalInt.of(12345));

        ids.beginSnapshot();
        ids.endSnapshot();
        assertFalse(ids.resolve(concealed).isPresent(), "Vanished opaque id retired");
        assertFalse(ids.resolve(12345).isPresent());
        assertNotEquals(snapshot(ids, "Hand", false, false), concealed, "Reappearance has a fresh id");
        snapshot(ids, "Hand", false, true);
        ids.beginSnapshot();
        ids.endSnapshot();
        assertFalse(ids.resolve(12345).isPresent(), "Vanished visible id retired");
    }

    @Test
    public void seatsAreIsolatedAndOnlyCompletedSnapshotsResolve() {
        final IdMapper a = new IdMapper();
        final IdMapper b = new IdMapper();
        final Object aRef = snapshot(a, "Hand", false, false);
        final Object bRef = snapshot(b, "Hand", false, false);
        assertNotEquals(aRef, bRef);
        assertFalse(a.resolve(bRef).isPresent());
        assertFalse(b.resolve(aRef).isPresent());
        a.beginSnapshot();
        a.wireRef(12345, "Battlefield", false, true);
        assertEquals(a.resolve(aRef), OptionalInt.of(12345), "Previous completed snapshot remains authoritative");
        assertFalse(a.resolve(12345).isPresent());
        a.abortSnapshot();
        assertEquals(a.resolve(aRef), OptionalInt.of(12345));
        assertEquals(snapshot(a, "Hand", false, false), aRef);
        for (final Object invalid : new Object[] {null, 12345L, 12345.0, "12345", "h-deadbeef0000", new Object()}) {
            assertFalse(a.resolve(invalid).isPresent(), "Invalid reference rejected: " + invalid);
        }
    }
}
