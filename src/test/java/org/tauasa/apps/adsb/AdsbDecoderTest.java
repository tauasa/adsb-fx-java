package org.tauasa.apps.adsb;

import org.junit.jupiter.api.Test;
import org.tauasa.apps.adsb.decode.AdsbDecoder;
import org.tauasa.apps.adsb.decode.AdsbMessage;
import org.tauasa.apps.adsb.decode.Cpr;
import org.tauasa.apps.adsb.decode.ModeS;
import org.tauasa.apps.adsb.decode.Position;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reference frames widely published in ADS-B decoding literature. If these stop
 * passing, something in the bit offsets has drifted.
 */
class AdsbDecoderTest {

    private static byte[] frame(String hex) {
        return Objects.requireNonNull(ModeS.parseHex(hex), "bad test vector: " + hex);
    }

    @Test
    void parityChecksOutOnAGoodFrame() {
        assertTrue(ModeS.isValid(frame("8D4840D6202CC371C32CE0576098")));
    }

    @Test
    void parityFailsAfterASingleFlippedBit() {
        byte[] corrupted = frame("8D4840D6202CC371C32CE0576098");
        corrupted[6] ^= 0x08;
        assertFalse(ModeS.isValid(corrupted));
        assertNull(AdsbDecoder.decode(corrupted));
    }

    @Test
    void identificationMessageYieldsCallsign() {
        AdsbMessage.Identification message =
                (AdsbMessage.Identification) AdsbDecoder.decode(frame("8D4840D6202CC371C32CE0576098"));
        assertEquals(0x4840D6, message.icao());
        assertEquals("KLM1023", message.callsign());
    }

    @Test
    void airbornePositionReportsAltitude() {
        AdsbMessage.AirbornePosition message =
                (AdsbMessage.AirbornePosition) AdsbDecoder.decode(frame("8D40621D58C382D690C8AC2863A7"));
        assertEquals(38000, message.altitudeFeet());
    }

    @Test
    void matchedCprPairResolvesToAPosition() {
        AdsbMessage.AirbornePosition even = (AdsbMessage.AirbornePosition)
                AdsbDecoder.decode(frame("8D40621D58C382D690C8AC2863A7"), 2_000L);
        AdsbMessage.AirbornePosition odd = (AdsbMessage.AirbornePosition)
                AdsbDecoder.decode(frame("8D40621D58C386435CC412692AD6"), 1_000L);
        assertFalse(even.cpr().odd());
        assertTrue(odd.cpr().odd());

        Position fix = Cpr.global(even.cpr(), odd.cpr());
        assertNotNull(fix);
        assertEquals(52.2572, fix.latitude(), 1e-4);
        assertEquals(3.91937, fix.longitude(), 1e-4);
    }

    @Test
    void localCprAgreesWithTheGlobalSolution() {
        AdsbMessage.AirbornePosition even = (AdsbMessage.AirbornePosition)
                AdsbDecoder.decode(frame("8D40621D58C382D690C8AC2863A7"));
        Position fix = Cpr.local(new Position(52.258, 3.918), even.cpr());
        assertEquals(52.2572, fix.latitude(), 1e-3);
        assertEquals(3.91937, fix.longitude(), 1e-3);
    }

    @Test
    void velocityMessageYieldsGroundSpeedTrackAndVerticalRate() {
        AdsbMessage.Velocity message =
                (AdsbMessage.Velocity) AdsbDecoder.decode(frame("8D485020994409940838175B284F"));
        assertEquals(159.20, message.groundSpeedKnots(), 0.05);
        assertEquals(182.88, message.trackDegrees(), 0.05);
        assertEquals(-832, message.verticalRateFpm());
    }

    @Test
    void longitudeZoneCountMatchesKnownBoundaries() {
        assertEquals(59, Cpr.longitudeZones(0.0));
        assertEquals(2, Cpr.longitudeZones(87.0));
        assertEquals(1, Cpr.longitudeZones(89.0));
    }

    @Test
    void framesWithUnhandledDownlinkFormatsAreIgnored() {
        assertNull(AdsbDecoder.decode(frame("02E19838B12E39")));
    }
}
