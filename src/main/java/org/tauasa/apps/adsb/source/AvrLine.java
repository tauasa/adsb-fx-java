package org.tauasa.apps.adsb.source;

import org.tauasa.apps.adsb.decode.ModeS;

/**
 * The AVR text format that dump1090, readsb and friends emit on port 30002 and
 * with {@code --raw}: a hex frame wrapped in {@code *...;}, or {@code @...;}
 * when a 12-digit receiver timestamp is prefixed.
 */
public final class AvrLine {

    private AvrLine() {
    }

    public static byte[] parse(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return switch (trimmed.charAt(0)) {
            case '*' -> ModeS.parseHex(trimmed.substring(1));
            case '@' -> trimmed.length() > 13 ? ModeS.parseHex(trimmed.substring(13)) : null;
            default -> null;
        };
    }
}
