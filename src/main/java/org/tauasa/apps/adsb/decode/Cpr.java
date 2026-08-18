package org.tauasa.apps.adsb.decode;

/**
 * Compact Position Reporting.
 *
 * <p>ADS-B does not transmit a position outright. Each message carries a
 * position modulo a latitude zone, and the zone itself has to be recovered
 * either from a matched odd/even pair (global decoding, no prior knowledge
 * needed) or from a position already known to be within half a zone (local
 * decoding, cheaper and usable on a single message).
 */
public final class Cpr {

    private static final double NZ = 15.0;
    private static final double TWO_POW_17 = 131072.0;

    /** Longest gap between an odd and even frame that may be paired, per DO-260B. */
    public static final long MAX_PAIR_AGE_MILLIS = 10_000L;

    private Cpr() {
    }

    /** Number of longitude zones at {@code lat}. */
    public static int longitudeZones(double lat) {
        double l = Math.abs(lat);
        if (l >= 90.0) {
            return 1;
        }
        if (l > 87.0) {
            return 1;
        }
        if (l == 87.0) {
            return 2;
        }
        if (l == 0.0) {
            return 59;
        }
        double a = 1 - Math.cos(Math.PI / (2 * NZ));
        double b = Math.pow(Math.cos(Math.PI / 180.0 * l), 2);
        return (int) Math.floor(2 * Math.PI / Math.acos(1 - a / b));
    }

    /**
     * Recover an unambiguous position from a matched odd/even pair.
     *
     * <p>Returns null when the two frames straddle a latitude zone boundary,
     * which makes the pair unusable — the aircraft has moved far enough between
     * frames that the zone count no longer agrees, so the result would be wrong
     * rather than merely imprecise.
     */
    public static Position global(CprFrame even, CprFrame odd) {
        if (even.surface() != odd.surface()) {
            return null;
        }
        double scale = even.surface() ? 90.0 : 360.0;

        double latCprEven = even.latEncoded() / TWO_POW_17;
        double latCprOdd = odd.latEncoded() / TWO_POW_17;
        double dLatEven = scale / 60.0;
        double dLatOdd = scale / 59.0;

        double j = Math.floor(59 * latCprEven - 60 * latCprOdd + 0.5);
        double latEven = dLatEven * (mod(j, 60.0) + latCprEven);
        double latOdd = dLatOdd * (mod(j, 59.0) + latCprOdd);
        if (!even.surface()) {
            if (latEven >= 270.0) {
                latEven -= 360.0;
            }
            if (latOdd >= 270.0) {
                latOdd -= 360.0;
            }
        }

        if (longitudeZones(latEven) != longitudeZones(latOdd)) {
            return null;
        }

        boolean useEven = even.timestampMillis() >= odd.timestampMillis();
        double latitude = useEven ? latEven : latOdd;
        int nl = longitudeZones(latitude);

        double lonCprEven = even.lonEncoded() / TWO_POW_17;
        double lonCprOdd = odd.lonEncoded() / TWO_POW_17;
        double m = Math.floor(lonCprEven * (nl - 1) - lonCprOdd * nl + 0.5);
        int ni = Math.max(nl - (useEven ? 0 : 1), 1);
        double dLon = scale / ni;
        double lonCpr = useEven ? lonCprEven : lonCprOdd;
        double longitude = dLon * (mod(m, ni) + lonCpr);

        return new Position(latitude, normalizeLongitude(longitude));
    }

    /**
     * Recover a position from a single frame using {@code reference} — either the
     * receiver's own position or the aircraft's last known fix. The reference
     * must be within half a zone (about 180 NM airborne) or the result silently
     * lands in the wrong zone, so callers should sanity-check the distance.
     */
    public static Position local(Position reference, CprFrame frame) {
        double scale = frame.surface() ? 90.0 : 360.0;
        int i = frame.odd() ? 1 : 0;

        double dLat = scale / (60 - i);
        double latCpr = frame.latEncoded() / TWO_POW_17;
        double j = Math.floor(reference.latitude() / dLat)
                + Math.floor(0.5 + mod(reference.latitude(), dLat) / dLat - latCpr);
        double latitude = dLat * (j + latCpr);

        int ni = Math.max(longitudeZones(latitude) - i, 1);
        double dLon = scale / ni;
        double lonCpr = frame.lonEncoded() / TWO_POW_17;
        double m = Math.floor(reference.longitude() / dLon)
                + Math.floor(0.5 + mod(reference.longitude(), dLon) / dLon - lonCpr);
        double longitude = dLon * (m + lonCpr);

        return new Position(latitude, normalizeLongitude(longitude));
    }

    private static double mod(double a, double b) {
        return ((a % b) + b) % b;
    }

    private static double normalizeLongitude(double lon) {
        double l = lon;
        while (l >= 180.0) {
            l -= 360.0;
        }
        while (l < -180.0) {
            l += 360.0;
        }
        return l;
    }
}
