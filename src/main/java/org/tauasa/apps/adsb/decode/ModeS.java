package org.tauasa.apps.adsb.decode;

/**
 * Frame-level Mode S handling: hex parsing, parity checking and the header
 * fields that are common to every downlink format.
 */
public final class ModeS {

    /** Mode S CRC-24 generator, with the implicit top bit of 0x1FFF409 dropped. */
    private static final int GENERATOR = 0xFFF409;

    /** 56-bit frames: DF 0, 4, 5, 11. */
    public static final int SHORT_FRAME_BYTES = 7;

    /** 112-bit frames: DF 16, 17, 18, 19, 20, 21, 24. */
    public static final int LONG_FRAME_BYTES = 14;

    private ModeS() {
    }

    /**
     * Parse an AVR-style hex payload into bytes. Returns null for anything that
     * is not a well-formed 56- or 112-bit frame.
     */
    public static byte[] parseHex(String hex) {
        String clean = hex.trim();
        while (clean.endsWith(";")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.length() != SHORT_FRAME_BYTES * 2 && clean.length() != LONG_FRAME_BYTES * 2) {
            return null;
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * CRC-24 over the supplied bytes.
     *
     * <p>Run over a whole frame, parity field included, an intact frame leaves a
     * remainder of zero, so this doubles as the validity check. Run over the 88
     * data bits alone it produces the parity field itself.
     */
    public static int crc(byte[] data) {
        return crc(data, data.length);
    }

    /** CRC-24 over the first {@code length} bytes of {@code data}. */
    public static int crc(byte[] data, int length) {
        int remainder = 0;
        for (int i = 0; i < length; i++) {
            remainder ^= (data[i] & 0xFF) << 16;
            for (int bit = 0; bit < 8; bit++) {
                remainder = ((remainder & 0x800000) != 0)
                        ? ((remainder << 1) ^ GENERATOR)
                        : (remainder << 1);
                remainder &= 0xFFFFFF;
            }
        }
        return remainder;
    }

    /** True when the frame's parity checks out. */
    public static boolean isValid(byte[] frame) {
        return crc(frame) == 0;
    }

    /** Downlink format, frame bits 1-5. */
    public static int downlinkFormat(byte[] frame) {
        return Bits.bits(frame, 1, 5);
    }

    /** Transponder address, frame bits 9-32. Only meaningful for DF 11, 17 and 18. */
    public static int icao(byte[] frame) {
        return Bits.bits(frame, 9, 24);
    }

    /** Capability / control field, frame bits 6-8. */
    public static int capability(byte[] frame) {
        return Bits.bits(frame, 6, 3);
    }

    /** Extended squitter type code, ME bits 1-5 (frame bits 33-37). */
    public static int typeCode(byte[] frame) {
        return Bits.bits(frame, 33, 5);
    }

    public static String formatIcao(int icao) {
        return String.format("%06X", icao);
    }
}
