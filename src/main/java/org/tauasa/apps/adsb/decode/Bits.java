package org.tauasa.apps.adsb.decode;

/** Bit-extraction helpers used by the Mode S decoder. */
final class Bits {

    private Bits() {
    }

    /** Unsigned byte at {@code index}. */
    static int u(byte[] frame, int index) {
        return frame[index] & 0xFF;
    }

    /**
     * Extract {@code length} bits starting at {@code start}, where {@code start}
     * is a <strong>1-based</strong> bit position within the frame, MSB first.
     *
     * <p>The ADS-B specification numbers frame bits from 1, and the ME (message
     * extended squitter) field begins at frame bit 33. Keeping the same
     * numbering as the spec means field offsets can be read straight off the
     * tables in DO-260B without a translation step, which is where most
     * hand-rolled decoders go wrong.
     */
    static int bits(byte[] frame, int start, int length) {
        if (length < 1 || length > 32) {
            throw new IllegalArgumentException("length must be 1..32, was " + length);
        }
        if (start < 1 || start + length - 1 > frame.length * 8) {
            throw new IllegalArgumentException("bit range out of frame");
        }
        int value = 0;
        for (int offset = 0; offset < length; offset++) {
            int bit = start + offset - 1;
            value = (value << 1) | ((u(frame, bit / 8) >> (7 - bit % 8)) & 1);
        }
        return value;
    }

    /** Bit {@code start} as a boolean. */
    static boolean flag(byte[] frame, int start) {
        return bits(frame, start, 1) == 1;
    }
}
