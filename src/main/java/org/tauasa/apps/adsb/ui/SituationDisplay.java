package org.tauasa.apps.adsb.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import org.tauasa.apps.adsb.decode.Position;
import org.tauasa.apps.adsb.model.Aircraft;
import org.tauasa.apps.adsb.model.AircraftTracker;
import org.tauasa.apps.adsb.model.Geo;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan position display centred on the receiver.
 *
 * <p>Deliberately not a sweeping radar scope — ADS-B is a continuous broadcast,
 * not a rotating antenna, and pretending otherwise would misrepresent what the
 * data is. Instead each target carries the two things a controller reads first:
 * a leader line showing where it will be in one minute, and a data block giving
 * callsign, flight level and groundspeed.
 */
public final class SituationDisplay extends Pane {

    private static final double MARGIN = 26.0;
    private static final int RING_COUNT = 4;
    private static final String MONO_FAMILY = "Monospaced";

    private final AircraftTracker tracker;
    private final Canvas canvas = new Canvas();
    private final DoubleProperty rangeNm = new SimpleDoubleProperty(120.0);
    private final ObjectProperty<Aircraft> selected = new SimpleObjectProperty<>(null);
    private final List<Marker> markers = new ArrayList<>();

    private final Font dataFont = Font.font(MONO_FAMILY, 10.5);
    private final Font labelFont = Font.font(MONO_FAMILY, 9.5);

    private record Marker(Aircraft aircraft, double x, double y) {
    }

    public SituationDisplay(AircraftTracker tracker) {
        this.tracker = tracker;
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        widthProperty().addListener((observable, was, is) -> draw());
        heightProperty().addListener((observable, was, is) -> draw());
        rangeNm.addListener((observable, was, is) -> draw());
        selected.addListener((observable, was, is) -> draw());
        canvas.setOnMouseClicked(event -> selected.set(nearestTo(event.getX(), event.getY())));
    }

    public DoubleProperty rangeNmProperty() {
        return rangeNm;
    }

    public ObjectProperty<Aircraft> selectedProperty() {
        return selected;
    }

    private Aircraft nearestTo(double x, double y) {
        Marker closest = null;
        double best = Double.MAX_VALUE;
        for (Marker marker : markers) {
            double distance = Math.hypot(marker.x() - x, marker.y() - y);
            if (distance < best) {
                best = distance;
                closest = marker;
            }
        }
        return (closest != null && best < 18.0) ? closest.aircraft() : null;
    }

    public void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        g.setFill(Palette.INK);
        g.fillRect(0, 0, width, height);

        Position home = tracker.receiverPosition();
        if (home == null) {
            drawEmptyState(g, width, height);
            return;
        }

        double centreX = width / 2;
        double centreY = height / 2;
        double radius = Math.min(width, height) / 2 - MARGIN;
        if (radius <= 20) {
            return;
        }

        drawRangeRings(g, centreX, centreY, radius);
        drawCompass(g, centreX, centreY, radius);

        markers.clear();
        double pixelsPerNm = radius / rangeNm.get();
        for (Aircraft target : tracker.aircraft()) {
            Position fix = target.position();
            if (fix == null) {
                continue;
            }
            double distance = Geo.distanceNm(home, fix);
            if (distance > rangeNm.get()) {
                continue;
            }
            double bearing = Math.toRadians(Geo.bearingDegrees(home, fix));
            double x = centreX + Math.sin(bearing) * distance * pixelsPerNm;
            double y = centreY - Math.cos(bearing) * distance * pixelsPerNm;
            drawTrail(g, target, home, centreX, centreY, pixelsPerNm);
            drawTarget(g, target, x, y, pixelsPerNm);
            markers.add(new Marker(target, x, y));
        }
    }

    private void drawEmptyState(GraphicsContext g, double width, double height) {
        g.setFill(Palette.DIM);
        g.setFont(Font.font(MONO_FAMILY, 12));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("Enter the receiver's latitude and longitude to plot targets.", width / 2, height / 2);
        g.setTextAlign(TextAlignment.LEFT);
    }

    private void drawRangeRings(GraphicsContext g, double cx, double cy, double radius) {
        g.setLineWidth(1.0);
        g.setFont(labelFont);
        for (int step = 1; step <= RING_COUNT; step++) {
            double r = radius * step / RING_COUNT;
            g.setStroke(step == RING_COUNT ? Palette.RULE : Palette.GRID);
            g.strokeOval(cx - r, cy - r, r * 2, r * 2);
            g.setFill(Palette.DIM);
            g.fillText(String.valueOf(Math.round(rangeNm.get() * step / RING_COUNT)), cx + 4, cy - r - 3);
        }
        // Receiver mark: a small cross rather than a dot, so it never reads as traffic.
        g.setStroke(Palette.TRAIL);
        g.strokeLine(cx - 5, cy, cx + 5, cy);
        g.strokeLine(cx, cy - 5, cx, cy + 5);
    }

    private void drawCompass(GraphicsContext g, double cx, double cy, double radius) {
        g.setFont(labelFont);
        for (int degrees = 0; degrees < 360; degrees += 10) {
            double angle = Math.toRadians(degrees);
            boolean major = degrees % 30 == 0;
            double inner = radius - (major ? 9.0 : 5.0);
            g.setStroke(major ? Palette.RULE : Palette.GRID);
            g.strokeLine(
                    cx + Math.sin(angle) * inner,
                    cy - Math.cos(angle) * inner,
                    cx + Math.sin(angle) * radius,
                    cy - Math.cos(angle) * radius);
            if (major) {
                g.setFill(Palette.DIM);
                g.setTextAlign(TextAlignment.CENTER);
                g.fillText(
                        String.format("%03d", degrees),
                        cx + Math.sin(angle) * (radius - 20),
                        cy - Math.cos(angle) * (radius - 20) + 3);
                g.setTextAlign(TextAlignment.LEFT);
            }
        }
    }

    private void drawTrail(GraphicsContext g, Aircraft target, Position home,
                           double cx, double cy, double pixelsPerNm) {
        if (target.trail().size() < 2) {
            return;
        }
        g.setStroke(Palette.TRAIL.deriveColor(0, 1, 1, 0.45));
        g.setLineWidth(1.0);
        double previousX = 0;
        double previousY = 0;
        boolean first = true;
        for (Position fix : target.trail()) {
            double distance = Geo.distanceNm(home, fix);
            double bearing = Math.toRadians(Geo.bearingDegrees(home, fix));
            double x = cx + Math.sin(bearing) * distance * pixelsPerNm;
            double y = cy - Math.cos(bearing) * distance * pixelsPerNm;
            if (!first) {
                g.strokeLine(previousX, previousY, x, y);
            }
            previousX = x;
            previousY = y;
            first = false;
        }
    }

    private void drawTarget(GraphicsContext g, Aircraft target, double x, double y, double pixelsPerNm) {
        Color tint = Palette.altitudeTint(target.altitudeFeetProperty().get());
        boolean isSelected = selected.get() == target;
        Integer track = target.trackDegreesProperty().get();
        Integer speed = target.groundSpeedKnotsProperty().get();

        // Leader line: one minute of travel at present groundspeed. Length is the
        // information, so it is drawn before the symbol sits on top of it.
        if (track != null && speed != null && speed > 0) {
            double angle = Math.toRadians(track);
            double leaderNm = speed / 60.0;
            g.setStroke(tint.deriveColor(0, 1, 1, 0.55));
            g.setLineWidth(1.0);
            g.strokeLine(x, y,
                    x + Math.sin(angle) * leaderNm * pixelsPerNm,
                    y - Math.cos(angle) * leaderNm * pixelsPerNm);
        }

        g.setFill(tint);
        g.setStroke(tint);
        if (track != null) {
            drawChevron(g, x, y, track);
        } else {
            g.fillOval(x - 2.5, y - 2.5, 5, 5);
        }

        if (isSelected) {
            g.setStroke(Palette.AMBER);
            g.setLineWidth(1.2);
            g.strokeOval(x - 11, y - 11, 22, 22);
        }

        drawDataBlock(g, target, x, y, tint, isSelected);
    }

    private void drawChevron(GraphicsContext g, double x, double y, double headingDegrees) {
        double angle = Math.toRadians(headingDegrees);
        double nose = 7.0;
        double tail = 4.5;
        double spread = Math.toRadians(140.0);
        double[] xs = {
                x + Math.sin(angle) * nose,
                x + Math.sin(angle + spread) * tail,
                x + Math.sin(angle - spread) * tail,
        };
        double[] ys = {
                y - Math.cos(angle) * nose,
                y - Math.cos(angle + spread) * tail,
                y - Math.cos(angle - spread) * tail,
        };
        g.fillPolygon(xs, ys, 3);
    }

    private void drawDataBlock(GraphicsContext g, Aircraft target, double x, double y,
                               Color tint, boolean isSelected) {
        g.setFont(dataFont);
        g.setFill(isSelected ? Palette.AMBER : tint.deriveColor(0, 1, 1, 0.9));
        g.fillText(target.label(), x + 10, y - 4);

        Integer altitude = target.altitudeFeetProperty().get();
        Integer speed = target.groundSpeedKnotsProperty().get();
        Integer climb = target.verticalRateFpmProperty().get();

        String flightLevel = altitude == null ? "---" : String.format("%03d", altitude / 100);
        String groundSpeed = speed == null ? "--" : String.format("%02d", speed / 10);
        String arrow = " ";
        if (climb != null && climb > 200) {
            arrow = "\u2191";
        } else if (climb != null && climb < -200) {
            arrow = "\u2193";
        }

        g.setFill(Palette.DIM);
        g.fillText(flightLevel + arrow + " " + groundSpeed, x + 10, y + 7);
    }
}
