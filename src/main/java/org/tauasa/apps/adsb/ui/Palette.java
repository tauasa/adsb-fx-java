package org.tauasa.apps.adsb.ui;

import javafx.scene.paint.Color;

/**
 * Instrument-panel palette: sodium amber on ink, the way night-lit avionics and
 * older ATC displays are lit, with a single cool accent for history trails so
 * that past and present read as different kinds of information.
 */
public final class Palette {

    public static final Color INK = Color.web("#0C1016");
    public static final Color PANEL = Color.web("#141A22");
    public static final Color RULE = Color.web("#232D3A");
    public static final Color GRID = Color.web("#1D2733");
    public static final Color TEXT = Color.web("#DCE3EC");
    public static final Color DIM = Color.web("#8A97A5");
    public static final Color AMBER = Color.web("#F0A93B");
    public static final Color TRAIL = Color.web("#4E9DB8");
    public static final Color ALERT = Color.web("#D9553B");

    private static final Color LOW_ALTITUDE = Color.web("#F0A93B");
    private static final Color HIGH_ALTITUDE = Color.web("#D8E4EE");

    private Palette() {
    }

    /**
     * Targets are tinted by altitude band — saturated amber down low, pale and
     * cool up high. It gives the display a vertical dimension without adding a
     * second symbol set.
     */
    public static Color altitudeTint(Integer feet) {
        if (feet == null) {
            return DIM;
        }
        double fraction = Math.max(0.0, Math.min(1.0, feet / 45_000.0));
        return LOW_ALTITUDE.interpolate(HIGH_ALTITUDE, fraction);
    }
}
