package org.tauasa.apps.adsb.source;

import org.tauasa.apps.adsb.decode.Cpr;
import org.tauasa.apps.adsb.decode.ModeS;
import org.tauasa.apps.adsb.decode.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates real, CRC-valid ADS-B frames for invented aircraft flying around a
 * centre point.
 *
 * <p>This exists so the decoder, tracker and display can be exercised without a
 * dongle plugged in — and because a bug in the encoder shows up immediately as a
 * bug in the decoder, the pair keeps each other honest.
 */
public final class SimulatedSource implements AdsbSource {

    private static final double STEP_SECONDS = 0.5;
    private static final double TURN_RATE_DEG_PER_SEC = 0.35;
    private static final String[] CARRIERS = {"UAL", "SWA", "DAL", "AAL", "ASA", "JBU", "SKW", "FDX"};

    private final Position centre;
    private final int aircraftCount;
    private final long seed;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private Thread worker;

    public SimulatedSource(Position centre) {
        this(centre, 12, 20260818L);
    }

    public SimulatedSource(Position centre, int aircraftCount) {
        this(centre, aircraftCount, 20260818L);
    }

    public SimulatedSource(Position centre, int aircraftCount, long seed) {
        this.centre = centre;
        this.aircraftCount = aircraftCount;
        this.seed = seed;
    }

    @Override
    public String description() {
        return String.format("Simulated traffic near %.3f, %.3f", centre.latitude(), centre.longitude());
    }

    @Override
    public boolean isRunning() {
        return active.get();
    }

    @Override
    public void start(FrameSink onFrame, SourceListener onEvent) {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(() -> fly(onFrame, onEvent), "adsb-simulator");
        worker.setDaemon(true);
        worker.start();
    }

    private void fly(FrameSink onFrame, SourceListener onEvent) {
        Random random = new Random(seed);
        List<SimAircraft> fleet = new ArrayList<>(aircraftCount);
        for (int i = 0; i < aircraftCount; i++) {
            fleet.add(newAircraft(i, random));
        }
        onEvent.onEvent(new SourceEvent.Connected(aircraftCount + " simulated aircraft"));

        long tick = 0;
        try {
            while (active.get()) {
                for (SimAircraft target : fleet) {
                    target.advance(STEP_SECONDS);
                    onFrame.accept(Encoder.airbornePosition(target, tick % 2 == 1));
                    onFrame.accept(Encoder.velocity(target));
                    if (tick % 10 == 0) {
                        onFrame.accept(Encoder.identification(target));
                    }
                }
                tick++;
                Thread.sleep((long) (STEP_SECONDS * 1000));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        onEvent.onEvent(new SourceEvent.Disconnected("Simulation stopped"));
    }

    private SimAircraft newAircraft(int index, Random random) {
        double bearing = random.nextDouble() * 360.0;
        double rangeNm = 8.0 + random.nextDouble() * 112.0;
        double latitude = centre.latitude() + rangeNm / 60.0 * Math.cos(Math.toRadians(bearing));
        double longitude = centre.longitude()
                + rangeNm / 60.0 * Math.sin(Math.toRadians(bearing))
                / Math.cos(Math.toRadians(centre.latitude()));
        int[] climbRates = {0, 0, 0, 1216, -960, 640};
        return new SimAircraft(
                0xA00000 + random.nextInt(0x0FFFFF),
                CARRIERS[index % CARRIERS.length] + (100 + random.nextInt(899)),
                latitude,
                longitude,
                (4 + random.nextInt(37)) * 1000,
                210.0 + random.nextDouble() * 300.0,
                random.nextDouble() * 360.0,
                climbRates[random.nextInt(climbRates.length)]);
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private static final class SimAircraft {
        final int icao;
        final String callsign;
        final double groundSpeedKnots;
        final int verticalRateFpm;
        double latitude;
        double longitude;
        double altitudeFeet;
        double trackDegrees;

        SimAircraft(int icao, String callsign, double latitude, double longitude,
                    double altitudeFeet, double groundSpeedKnots, double trackDegrees,
                    int verticalRateFpm) {
            this.icao = icao;
            this.callsign = callsign;
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitudeFeet = altitudeFeet;
            this.groundSpeedKnots = groundSpeedKnots;
            this.trackDegrees = trackDegrees;
            this.verticalRateFpm = verticalRateFpm;
        }

        void advance(double seconds) {
            double distanceNm = groundSpeedKnots * seconds / 3600.0;
            latitude += distanceNm / 60.0 * Math.cos(Math.toRadians(trackDegrees));
            longitude += distanceNm / 60.0 * Math.sin(Math.toRadians(trackDegrees))
                    / Math.cos(Math.toRadians(latitude));
            trackDegrees = (trackDegrees + TURN_RATE_DEG_PER_SEC * seconds + 360.0) % 360.0;
            altitudeFeet = Math.max(1000, Math.min(45000, altitudeFeet + verticalRateFpm * seconds / 60.0));
        }
    }

    /** Builds valid DF17 frames. The inverse of the decoder, used only for simulation. */
    private static final class Encoder {

        private static final String ID_CHARSET =
                "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######";

        private Encoder() {
        }

        static byte[] identification(SimAircraft a) {
            FrameWriter frame = new FrameWriter(a.icao, 4);
            frame.put(38, 3, 3); // wake category: medium
            String padded = (a.callsign + "        ").substring(0, 8);
            for (int i = 0; i < 8; i++) {
                frame.put(41 + i * 6, 6, Math.max(ID_CHARSET.indexOf(padded.charAt(i)), 0));
            }
            return frame.finish();
        }

        static byte[] airbornePosition(SimAircraft a, boolean odd) {
            FrameWriter frame = new FrameWriter(a.icao, 11);
            frame.put(41, 12, encodeAltitude((int) Math.round(a.altitudeFeet)));
            frame.put(54, 1, odd ? 1 : 0);
            int i = odd ? 1 : 0;
            double dLat = 360.0 / (60 - i);
            int yz = (int) Math.floor(131072.0 * (mod(a.latitude, dLat) / dLat) + 0.5);
            double rlat = dLat * (yz / 131072.0 + Math.floor(a.latitude / dLat));
            double dLon = 360.0 / Math.max(Cpr.longitudeZones(rlat) - i, 1);
            int xz = (int) Math.floor(131072.0 * (mod(a.longitude, dLon) / dLon) + 0.5);
            frame.put(55, 17, yz & 0x1FFFF);
            frame.put(72, 17, xz & 0x1FFFF);
            return frame.finish();
        }

        static byte[] velocity(SimAircraft a) {
            FrameWriter frame = new FrameWriter(a.icao, 19);
            frame.put(38, 3, 1); // subtype 1: ground referenced, subsonic
            double east = a.groundSpeedKnots * Math.sin(Math.toRadians(a.trackDegrees));
            double north = a.groundSpeedKnots * Math.cos(Math.toRadians(a.trackDegrees));
            frame.put(46, 1, east < 0 ? 1 : 0);
            frame.put(47, 10, clamp((int) Math.round(Math.abs(east)) + 1, 1, 1023));
            frame.put(57, 1, north < 0 ? 1 : 0);
            frame.put(58, 10, clamp((int) Math.round(Math.abs(north)) + 1, 1, 1023));
            frame.put(69, 1, a.verticalRateFpm < 0 ? 1 : 0);
            frame.put(70, 9, clamp(Math.abs(a.verticalRateFpm) / 64 + 1, 1, 511));
            return frame.finish();
        }

        private static int encodeAltitude(int feet) {
            int n = clamp((feet + 1000) / 25, 0, 2047);
            return ((n & 0x7F0) << 1) | 0x10 | (n & 0x0F);
        }

        private static int clamp(int value, int low, int high) {
            return Math.max(low, Math.min(high, value));
        }

        private static double mod(double a, double b) {
            return ((a % b) + b) % b;
        }
    }

    private static final class FrameWriter {

        private final byte[] bytes = new byte[ModeS.LONG_FRAME_BYTES];

        FrameWriter(int icao, int typeCode) {
            put(1, 5, 17);   // DF17
            put(6, 3, 5);    // capability: airborne, level 2+
            put(9, 24, icao);
            put(33, 5, typeCode);
        }

        void put(int start, int length, int value) {
            for (int offset = 0; offset < length; offset++) {
                if (((value >> (length - 1 - offset)) & 1) == 1) {
                    int bit = start + offset - 1;
                    bytes[bit / 8] |= (byte) (1 << (7 - bit % 8));
                }
            }
        }

        /**
         * Append parity so the frame checks out under {@link ModeS#crc(byte[])}.
         *
         * <p>The parity is the CRC of the 88 data bits alone. Running it over the
         * zero-padded 14-byte frame gives a different remainder and produces
         * frames that every receiver quietly discards.
         */
        byte[] finish() {
            int parity = ModeS.crc(bytes, 11);
            bytes[11] = (byte) ((parity >> 16) & 0xFF);
            bytes[12] = (byte) ((parity >> 8) & 0xFF);
            bytes[13] = (byte) (parity & 0xFF);
            return bytes;
        }
    }
}
