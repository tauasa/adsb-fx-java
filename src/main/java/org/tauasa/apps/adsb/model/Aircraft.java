package org.tauasa.apps.adsb.model;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.tauasa.apps.adsb.decode.CprFrame;
import org.tauasa.apps.adsb.decode.ModeS;
import org.tauasa.apps.adsb.decode.Position;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * One tracked aircraft.
 *
 * <p>All fields are JavaFX properties and are only ever touched on the FX
 * application thread — the receive thread hands decoded messages across a queue
 * rather than mutating this directly, which keeps the table bindings honest
 * without a single lock.
 */
public final class Aircraft {

    public static final int MAX_TRAIL_POINTS = 60;

    private final int icao;
    private final String address;

    private final StringProperty callsign = new SimpleStringProperty("");
    private final StringProperty category = new SimpleStringProperty("");
    private final ObjectProperty<Integer> altitudeFeet = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Integer> groundSpeedKnots = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Integer> trackDegrees = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Integer> verticalRateFpm = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Double> latitude = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Double> longitude = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Double> distanceNm = new SimpleObjectProperty<>(null);
    private final ObjectProperty<Double> bearingDegrees = new SimpleObjectProperty<>(null);
    private final IntegerProperty messageCount = new SimpleIntegerProperty(0);
    private final IntegerProperty ageSeconds = new SimpleIntegerProperty(0);
    private final ObjectProperty<Boolean> onGround = new SimpleObjectProperty<>(false);

    private final Deque<Position> trail = new ArrayDeque<>();

    private long lastMessageMillis;
    private long lastPositionMillis;

    /** Most recent even and odd CPR halves, held for pairing. */
    private CprFrame evenFrame;
    private CprFrame oddFrame;

    public Aircraft(int icao) {
        this.icao = icao;
        this.address = ModeS.formatIcao(icao);
    }

    public int icao() {
        return icao;
    }

    public String address() {
        return address;
    }

    public StringProperty callsignProperty() {
        return callsign;
    }

    public StringProperty categoryProperty() {
        return category;
    }

    public ObjectProperty<Integer> altitudeFeetProperty() {
        return altitudeFeet;
    }

    public ObjectProperty<Integer> groundSpeedKnotsProperty() {
        return groundSpeedKnots;
    }

    public ObjectProperty<Integer> trackDegreesProperty() {
        return trackDegrees;
    }

    public ObjectProperty<Integer> verticalRateFpmProperty() {
        return verticalRateFpm;
    }

    public ObjectProperty<Double> latitudeProperty() {
        return latitude;
    }

    public ObjectProperty<Double> longitudeProperty() {
        return longitude;
    }

    public ObjectProperty<Double> distanceNmProperty() {
        return distanceNm;
    }

    public ObjectProperty<Double> bearingDegreesProperty() {
        return bearingDegrees;
    }

    public IntegerProperty messageCountProperty() {
        return messageCount;
    }

    public IntegerProperty ageSecondsProperty() {
        return ageSeconds;
    }

    public ObjectProperty<Boolean> onGroundProperty() {
        return onGround;
    }

    public Deque<Position> trail() {
        return trail;
    }

    public long lastMessageMillis() {
        return lastMessageMillis;
    }

    public void lastMessageMillis(long value) {
        this.lastMessageMillis = value;
    }

    public long lastPositionMillis() {
        return lastPositionMillis;
    }

    public CprFrame evenFrame() {
        return evenFrame;
    }

    public CprFrame oddFrame() {
        return oddFrame;
    }

    public void recordCpr(CprFrame frame) {
        if (frame.odd()) {
            oddFrame = frame;
        } else {
            evenFrame = frame;
        }
    }

    public Position position() {
        Double lat = latitude.get();
        Double lon = longitude.get();
        return (lat == null || lon == null) ? null : new Position(lat, lon);
    }

    public void recordPosition(Position fix, long atMillis) {
        latitude.set(fix.latitude());
        longitude.set(fix.longitude());
        lastPositionMillis = atMillis;
        trail.addLast(fix);
        while (trail.size() > MAX_TRAIL_POINTS) {
            trail.removeFirst();
        }
    }

    /** Callsign when it is known, otherwise the raw address. */
    public String label() {
        String sign = callsign.get();
        return (sign == null || sign.isBlank()) ? address : sign;
    }
}
