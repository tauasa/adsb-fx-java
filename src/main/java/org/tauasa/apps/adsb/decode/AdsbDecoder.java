package org.tauasa.apps.adsb.decode;

import static org.tauasa.apps.adsb.decode.Bits.bits;
import static org.tauasa.apps.adsb.decode.Bits.flag;

/**
 * Turns a validated Mode S frame into an {@link AdsbMessage}.
 *
 * <p>Stateless by design: everything that needs history — CPR pairing, track
 * building, staleness — lives in the tracker, so this can run on the receive
 * thread without locking.
 */
public final class AdsbDecoder {

    /**
     * Index 32 is the pad character, written as a space so that trailing padding
     * disappears with a plain strip().
     */
    private static final String ID_CHARSET =
            "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######";

    private AdsbDecoder() {
    }

    public static AdsbMessage decode(byte[] frame) {
        return decode(frame, System.currentTimeMillis());
    }

    /**
     * Decode a frame. Returns null for frames that fail parity, use a downlink
     * format this app does not handle, or carry a type code with no decoder yet.
     */
    public static AdsbMessage decode(byte[] frame, long receivedAtMillis) {
        if (!ModeS.isValid(frame)) {
            return null;
        }
        return switch (ModeS.downlinkFormat(frame)) {
            case 11 -> frame.length == ModeS.SHORT_FRAME_BYTES
                    ? new AdsbMessage.Acquisition(ModeS.icao(frame), receivedAtMillis)
                    : null;
            // 17 is a Mode S transponder; 18 is TIS-B / ADS-R, same ME layout.
            case 17, 18 -> frame.length == ModeS.LONG_FRAME_BYTES
                    ? decodeExtendedSquitter(frame, receivedAtMillis)
                    : null;
            default -> null;
        };
    }

    private static AdsbMessage decodeExtendedSquitter(byte[] frame, long now) {
        int icao = ModeS.icao(frame);
        int typeCode = ModeS.typeCode(frame);
        if (typeCode >= 1 && typeCode <= 4) {
            return identification(frame, icao, now, typeCode);
        }
        if (typeCode >= 5 && typeCode <= 8) {
            return surfacePosition(frame, icao, now);
        }
        if (typeCode >= 9 && typeCode <= 18) {
            return airbornePosition(frame, icao, now, true);
        }
        if (typeCode == 19) {
            return velocity(frame, icao, now);
        }
        if (typeCode >= 20 && typeCode <= 22) {
            return airbornePosition(frame, icao, now, false);
        }
        return null;
    }

    private static AdsbMessage identification(byte[] frame, int icao, long now, int typeCode) {
        StringBuilder builder = new StringBuilder(8);
        // Eight 6-bit characters, ME bits 9-56.
        for (int i = 0; i < 8; i++) {
            builder.append(ID_CHARSET.charAt(bits(frame, 41 + i * 6, 6)));
        }
        String callsign = builder.toString().replace("#", "").strip();
        return new AdsbMessage.Identification(
                icao,
                now,
                callsign,
                WakeCategory.of(typeCode, bits(frame, 38, 3)));
    }

    private static AdsbMessage airbornePosition(byte[] frame, int icao, long now, boolean barometric) {
        CprFrame cpr = new CprFrame(
                bits(frame, 55, 17),
                bits(frame, 72, 17),
                flag(frame, 54),
                now,
                false);
        return new AdsbMessage.AirbornePosition(
                icao, now, cpr, decodeAltitude(bits(frame, 41, 12)), barometric);
    }

    private static AdsbMessage surfacePosition(byte[] frame, int icao, long now) {
        CprFrame cpr = new CprFrame(
                bits(frame, 55, 17),
                bits(frame, 72, 17),
                flag(frame, 54),
                now,
                true);
        Double track = flag(frame, 45) ? bits(frame, 46, 7) * 360.0 / 128.0 : null;
        return new AdsbMessage.SurfacePosition(
                icao, now, cpr, decodeMovement(bits(frame, 38, 7)), track);
    }

    private static AdsbMessage velocity(byte[] frame, int icao, long now) {
        int subtype = bits(frame, 38, 3);
        if (subtype < 1 || subtype > 4) {
            return null;
        }
        boolean supersonic = subtype == 2 || subtype == 4;
        int multiplier = supersonic ? 4 : 1;

        Double groundSpeed = null;
        Double track = null;
        Double airspeed = null;
        Double heading = null;

        if (subtype == 1 || subtype == 2) {
            int eastWest = bits(frame, 47, 10);
            int northSouth = bits(frame, 58, 10);
            // Zero means "no data"; 1 means zero velocity, hence the offset.
            if (eastWest != 0 && northSouth != 0) {
                int vx = (eastWest - 1) * multiplier * (flag(frame, 46) ? -1 : 1);
                int vy = (northSouth - 1) * multiplier * (flag(frame, 57) ? -1 : 1);
                groundSpeed = Math.hypot(vx, vy);
                track = normalizeAngle(Math.toDegrees(Math.atan2(vx, vy)));
            }
        } else {
            if (flag(frame, 46)) {
                heading = bits(frame, 47, 10) * 360.0 / 1024.0;
            }
            int speed = bits(frame, 58, 10);
            if (speed != 0) {
                airspeed = (double) ((speed - 1) * multiplier);
            }
        }

        int rawVerticalRate = bits(frame, 70, 9);
        Integer verticalRate = rawVerticalRate == 0
                ? null
                : (rawVerticalRate - 1) * 64 * (flag(frame, 69) ? -1 : 1);

        return new AdsbMessage.Velocity(
                icao, now, groundSpeed, track, airspeed, heading, verticalRate, supersonic);
    }

    /**
     * 12-bit altitude field. With the Q bit set the encoding is a plain 25 ft
     * counter; with it clear the field is Gillham (Gray) coded in 100 ft steps,
     * which is only seen above FL500 and is not decoded here.
     */
    static Integer decodeAltitude(int raw) {
        if (raw == 0) {
            return null;
        }
        boolean qBitSet = ((raw >> 4) & 1) == 1;
        if (!qBitSet) {
            return null;
        }
        int n = ((raw & 0xFE0) >> 1) | (raw & 0x0F);
        return n * 25 - 1000;
    }

    /** 7-bit surface movement field, a piecewise ground speed encoding in knots. */
    static Double decodeMovement(int raw) {
        if (raw == 0) {
            return null;
        }
        if (raw == 1) {
            return 0.0;
        }
        if (raw <= 8) {
            return 0.125 + (raw - 2) * 0.125;
        }
        if (raw <= 12) {
            return 1.0 + (raw - 9) * 0.25;
        }
        if (raw <= 38) {
            return 2.0 + (raw - 13) * 0.5;
        }
        if (raw <= 93) {
            return 15.0 + (raw - 39) * 1.0;
        }
        if (raw <= 108) {
            return 70.0 + (raw - 94) * 2.0;
        }
        if (raw <= 123) {
            return 100.0 + (raw - 109) * 5.0;
        }
        if (raw == 124) {
            return 175.0;
        }
        return null;
    }

    private static double normalizeAngle(double degrees) {
        return ((degrees % 360.0) + 360.0) % 360.0;
    }
}
