package org.tauasa.apps.adsb.decode;

/**
 * A decoded downlink message, keyed by the transmitting aircraft's address.
 *
 * <p>Boxed types mark fields that a given transmission may legitimately omit —
 * a velocity message with no vertical rate source carries no vertical rate, and
 * a zero there would be a lie rather than a reading.
 */
public sealed interface AdsbMessage {

    int icao();

    long receivedAtMillis();

    /** DF 11 all-call reply — carries no payload, but proves the aircraft is in range. */
    record Acquisition(int icao, long receivedAtMillis) implements AdsbMessage {
    }

    /** TC 1-4: callsign and wake vortex category. */
    record Identification(
            int icao,
            long receivedAtMillis,
            String callsign,
            String category) implements AdsbMessage {
    }

    /** TC 9-18 and 20-22: airborne position, half of a CPR pair. */
    record AirbornePosition(
            int icao,
            long receivedAtMillis,
            CprFrame cpr,
            Integer altitudeFeet,
            boolean barometric) implements AdsbMessage {
    }

    /** TC 5-8: surface position and movement. */
    record SurfacePosition(
            int icao,
            long receivedAtMillis,
            CprFrame cpr,
            Double groundSpeedKnots,
            Double trackDegrees) implements AdsbMessage {
    }

    /** TC 19: airborne velocity, either ground referenced or airspeed. */
    record Velocity(
            int icao,
            long receivedAtMillis,
            Double groundSpeedKnots,
            Double trackDegrees,
            Double airspeedKnots,
            Double headingDegrees,
            Integer verticalRateFpm,
            boolean supersonic) implements AdsbMessage {
    }
}
