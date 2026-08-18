package org.tauasa.apps.adsb.model;

import org.tauasa.apps.adsb.decode.Position;

/** Distances here are nautical miles, because that is what the rest of aviation uses. */
public final class Geo {

    private static final double EARTH_RADIUS_NM = 3440.065;

    private Geo() {
    }

    public static double distanceNm(Position from, Position to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.longitude() - from.longitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_NM * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Initial great-circle bearing from {@code from} to {@code to}, degrees true. */
    public static double bearingDegrees(Position from, Position to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double dLon = Math.toRadians(to.longitude() - from.longitude());
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }
}
