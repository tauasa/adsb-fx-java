package org.tauasa.apps.adsb.decode;

/**
 * One half of a CPR pair: the raw 17-bit latitude and longitude encodings plus
 * the odd/even format flag and the time it arrived.
 */
public record CprFrame(
        int latEncoded,
        int lonEncoded,
        boolean odd,
        long timestampMillis,
        boolean surface) {
}
