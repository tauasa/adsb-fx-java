package org.tauasa.apps.adsb.decode;

/** Wake vortex categories, indexed by type code then category subfield. */
final class WakeCategory {

    private static final String UNKNOWN = "\u2014";

    private WakeCategory() {
    }

    static String of(int typeCode, int category) {
        if (category == 0) {
            return UNKNOWN;
        }
        return switch (typeCode) {
            case 2 -> switch (category) {
                case 1 -> "Surface emergency vehicle";
                case 3 -> "Surface service vehicle";
                default -> category >= 4 ? "Ground obstruction" : UNKNOWN;
            };
            case 3 -> switch (category) {
                case 1 -> "Glider";
                case 2 -> "Lighter-than-air";
                case 3 -> "Parachutist";
                case 4 -> "Ultralight";
                case 6 -> "UAV";
                case 7 -> "Space vehicle";
                default -> UNKNOWN;
            };
            case 4 -> switch (category) {
                case 1 -> "Light";
                case 2 -> "Medium 1";
                case 3 -> "Medium 2";
                case 4 -> "High vortex";
                case 5 -> "Heavy";
                case 6 -> "High performance";
                case 7 -> "Rotorcraft";
                default -> UNKNOWN;
            };
            default -> UNKNOWN;
        };
    }
}
