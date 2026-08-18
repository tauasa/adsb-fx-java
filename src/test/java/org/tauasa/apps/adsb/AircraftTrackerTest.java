package org.tauasa.apps.adsb;

import org.junit.jupiter.api.Test;
import org.tauasa.apps.adsb.decode.AdsbDecoder;
import org.tauasa.apps.adsb.decode.AdsbMessage;
import org.tauasa.apps.adsb.decode.ModeS;
import org.tauasa.apps.adsb.decode.Position;
import org.tauasa.apps.adsb.model.Aircraft;
import org.tauasa.apps.adsb.model.AircraftTracker;
import org.tauasa.apps.adsb.model.Geo;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tracker only needs JavaFX properties and collections, not a running
 * toolkit, so it can be exercised head-on without starting an application.
 */
class AircraftTrackerTest {

    private static byte[] frame(String hex) {
        return Objects.requireNonNull(ModeS.parseHex(hex));
    }

    @Test
    void aMatchedPairProducesATrackedPosition() {
        AircraftTracker tracker = new AircraftTracker();
        tracker.receiverPosition(new Position(52.0, 4.0));

        tracker.apply(AdsbDecoder.decode(frame("8D40621D58C386435CC412692AD6"), 1_000L));
        tracker.apply(AdsbDecoder.decode(frame("8D40621D58C382D690C8AC2863A7"), 2_000L));

        assertEquals(1, tracker.aircraft().size());
        Aircraft target = tracker.aircraft().get(0);
        Position fix = target.position();
        assertNotNull(fix, "expected the pair to resolve");
        assertEquals(52.2572, fix.latitude(), 1e-4);
        assertEquals(3.91937, fix.longitude(), 1e-4);
        assertEquals(38000, target.altitudeFeetProperty().get());
        assertEquals(2, target.messageCountProperty().get());
        assertTrue(target.distanceNmProperty().get() > 0);
        assertEquals(1, target.trail().size(), "the first fix arrives only once the pair completes");
    }

    @Test
    void callsignAndVelocityMergeOntoTheSameContact() {
        AircraftTracker tracker = new AircraftTracker();
        tracker.apply(AdsbDecoder.decode(frame("8D4840D6202CC371C32CE0576098")));
        assertEquals("KLM1023", tracker.aircraft().get(0).callsignProperty().get());
        assertEquals(1, tracker.aircraft().size());
    }

    @Test
    void aSingleFrameAloneYieldsNoPosition() {
        AircraftTracker tracker = new AircraftTracker();
        tracker.receiverPosition(new Position(52.0, 4.0));
        tracker.apply(AdsbDecoder.decode(frame("8D40621D58C382D690C8AC2863A7"), 1_000L));

        assertEquals(1, tracker.aircraft().size());
        assertEquals(0, tracker.withPositionCount(),
                "an unpaired frame must not be locally decoded against the receiver");
    }

    @Test
    void impossiblyDistantFixesAreRejected() {
        AircraftTracker tracker = new AircraftTracker();
        // A receiver on the far side of the world cannot be hearing this aircraft.
        tracker.receiverPosition(new Position(-35.0, 150.0));

        tracker.apply(AdsbDecoder.decode(frame("8D40621D58C386435CC412692AD6"), 1_000L));
        tracker.apply(AdsbDecoder.decode(frame("8D40621D58C382D690C8AC2863A7"), 2_000L));

        assertEquals(1, tracker.aircraft().size());
        assertEquals(0, tracker.withPositionCount());
    }

    @Test
    void quietContactsAreDroppedOnceStale() {
        AircraftTracker tracker = new AircraftTracker(5_000L);
        AdsbMessage message = AdsbDecoder.decode(frame("8D4840D6202CC371C32CE0576098"), 10_000L);
        tracker.apply(message);
        assertEquals(1, tracker.aircraft().size());

        tracker.tick(12_000L);
        assertEquals(1, tracker.aircraft().size());
        assertEquals(2, tracker.aircraft().get(0).ageSecondsProperty().get());

        tracker.tick(20_000L);
        assertEquals(0, tracker.aircraft().size());
    }

    @Test
    void greatCircleHelpersAgreeWithKnownDistances() {
        // Sacramento to San Francisco, roughly south-west.
        double distance = Geo.distanceNm(new Position(38.58, -121.49), new Position(37.77, -122.42));
        assertEquals(65.5, distance, 1.0);
        double bearing = Geo.bearingDegrees(new Position(38.58, -121.49), new Position(37.77, -122.42));
        assertTrue(bearing > 200 && bearing < 250, "expected a south-westerly bearing, got " + bearing);
    }
}
