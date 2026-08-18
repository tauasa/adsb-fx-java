package org.tauasa.apps.adsb.model;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.tauasa.apps.adsb.decode.AdsbMessage;
import org.tauasa.apps.adsb.decode.Cpr;
import org.tauasa.apps.adsb.decode.CprFrame;
import org.tauasa.apps.adsb.decode.Position;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the current picture: every aircraft heard recently, with positions
 * resolved from CPR pairs.
 *
 * <p>FX-thread confined. {@link #apply} is called from the UI pump, never from a
 * source.
 */
public final class AircraftTracker {

    public static final double MAX_PLAUSIBLE_RANGE_NM = 300.0;

    /** A fix is usable as its own CPR reference for this long. */
    private static final long POSITION_REFERENCE_VALIDITY_MILLIS = 60_000L;

    /**
     * Furthest a locally decoded fix may sit from the previous one. A CPR zone
     * error displaces a position by a whole zone — six degrees, several hundred
     * miles — so anything past this is a decode fault rather than a fast aeroplane.
     */
    private static final double MAX_POSITION_JUMP_NM = 60.0;

    private final long staleAfterMillis;
    private final ObservableList<Aircraft> aircraft = FXCollections.observableArrayList();
    private final Map<Integer, Aircraft> byIcao = new HashMap<>();

    /** Receiver position, used for range/bearing and as a CPR reference. */
    private Position receiverPosition;
    private long positionsResolved;

    public AircraftTracker() {
        this(60_000L);
    }

    public AircraftTracker(long staleAfterMillis) {
        this.staleAfterMillis = staleAfterMillis;
    }

    public ObservableList<Aircraft> aircraft() {
        return aircraft;
    }

    public Position receiverPosition() {
        return receiverPosition;
    }

    public void receiverPosition(Position value) {
        this.receiverPosition = value;
    }

    public long positionsResolved() {
        return positionsResolved;
    }

    public void apply(AdsbMessage message) {
        Aircraft target = byIcao.computeIfAbsent(message.icao(), icao -> {
            Aircraft created = new Aircraft(icao);
            aircraft.add(created);
            return created;
        });
        target.lastMessageMillis(message.receivedAtMillis());
        target.messageCountProperty().set(target.messageCountProperty().get() + 1);

        switch (message) {
            case AdsbMessage.Acquisition ignored -> {
                // Nothing but proof of life.
            }
            case AdsbMessage.Identification identification -> {
                target.callsignProperty().set(identification.callsign());
                target.categoryProperty().set(identification.category());
            }
            case AdsbMessage.AirbornePosition airborne -> {
                if (airborne.altitudeFeet() != null) {
                    target.altitudeFeetProperty().set(airborne.altitudeFeet());
                }
                target.onGroundProperty().set(false);
                resolve(target, airborne.cpr());
            }
            case AdsbMessage.SurfacePosition surface -> {
                target.onGroundProperty().set(true);
                target.altitudeFeetProperty().set(0);
                if (surface.groundSpeedKnots() != null) {
                    target.groundSpeedKnotsProperty().set((int) Math.round(surface.groundSpeedKnots()));
                }
                if (surface.trackDegrees() != null) {
                    target.trackDegreesProperty().set((int) Math.round(surface.trackDegrees()));
                }
                resolve(target, surface.cpr());
            }
            case AdsbMessage.Velocity velocity -> {
                if (velocity.groundSpeedKnots() != null) {
                    target.groundSpeedKnotsProperty().set((int) Math.round(velocity.groundSpeedKnots()));
                }
                if (velocity.trackDegrees() != null) {
                    target.trackDegreesProperty().set((int) Math.round(velocity.trackDegrees()));
                }
                if (velocity.headingDegrees() != null && target.trackDegreesProperty().get() == null) {
                    target.trackDegreesProperty().set((int) Math.round(velocity.headingDegrees()));
                }
                if (velocity.verticalRateFpm() != null) {
                    target.verticalRateFpmProperty().set(velocity.verticalRateFpm());
                }
            }
        }
    }

    private void resolve(Aircraft target, CprFrame frame) {
        target.recordCpr(frame);

        CprFrame even = target.evenFrame();
        CprFrame odd = target.oddFrame();
        boolean paired = even != null && odd != null
                && Math.abs(even.timestampMillis() - odd.timestampMillis()) <= Cpr.MAX_PAIR_AGE_MILLIS;

        Position previous = target.position();
        boolean previousIsFresh = previous != null
                && frame.timestampMillis() - target.lastPositionMillis() < POSITION_REFERENCE_VALIDITY_MILLIS;

        Position fix;
        if (paired) {
            fix = Cpr.global(even, odd);
        } else if (previousIsFresh) {
            // Local decoding needs a reference inside the aircraft's own latitude
            // zone. Its last fix qualifies. The receiver's position deliberately
            // does not: a wrong-zone local solve lands near whatever reference it
            // was given, so seeding from the receiver produces plausible-looking
            // positions that no range check can catch. Every aircraft's first fix
            // therefore comes from a matched pair, which costs a second or two.
            fix = Cpr.local(previous, frame);
        } else {
            return;
        }

        if (fix == null || !plausible(fix, previousIsFresh ? previous : null)) {
            return;
        }

        target.recordPosition(fix, frame.timestampMillis());
        positionsResolved++;
        if (receiverPosition != null) {
            target.distanceNmProperty().set(Geo.distanceNm(receiverPosition, fix));
            target.bearingDegreesProperty().set(Geo.bearingDegrees(receiverPosition, fix));
        }
    }

    /**
     * Reject fixes that cannot be real: outside the coordinate system, an
     * impossible jump from the previous fix, or beyond any range a 1090 MHz
     * receiver can hear.
     */
    private boolean plausible(Position fix, Position previous) {
        if (Double.isNaN(fix.latitude()) || Double.isNaN(fix.longitude())) {
            return false;
        }
        if (fix.latitude() < -90 || fix.latitude() > 90
                || fix.longitude() < -180 || fix.longitude() > 180) {
            return false;
        }
        if (previous != null && Geo.distanceNm(previous, fix) > MAX_POSITION_JUMP_NM) {
            return false;
        }
        return receiverPosition == null
                || Geo.distanceNm(receiverPosition, fix) <= MAX_PLAUSIBLE_RANGE_NM;
    }

    /** Refresh derived per-tick state and drop contacts that have gone quiet. */
    public void tick(long nowMillis) {
        List<Aircraft> expired = new ArrayList<>();
        for (Aircraft target : aircraft) {
            long age = nowMillis - target.lastMessageMillis();
            if (age > staleAfterMillis) {
                expired.add(target);
            } else {
                target.ageSecondsProperty().set((int) (age / 1000L));
            }
        }
        if (!expired.isEmpty()) {
            aircraft.removeAll(expired);
            expired.forEach(target -> byIcao.remove(target.icao()));
        }
    }

    public void clear() {
        aircraft.clear();
        byIcao.clear();
        positionsResolved = 0;
    }

    public int withPositionCount() {
        return (int) aircraft.stream().filter(target -> target.position() != null).count();
    }
}
