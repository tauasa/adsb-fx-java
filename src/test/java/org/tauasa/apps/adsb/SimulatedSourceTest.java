package org.tauasa.apps.adsb;

import org.junit.jupiter.api.Test;
import org.tauasa.apps.adsb.decode.AdsbDecoder;
import org.tauasa.apps.adsb.decode.AdsbMessage;
import org.tauasa.apps.adsb.decode.ModeS;
import org.tauasa.apps.adsb.decode.Position;
import org.tauasa.apps.adsb.source.AdsbSource;
import org.tauasa.apps.adsb.source.SimulatedSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simulator writes frames with the same bit layout the decoder reads, so a
 * round trip catches an error on either side. It caught a parity bug the first
 * time it ran.
 */
class SimulatedSourceTest {

    @Test
    void everySimulatedFramePassesParityAndDecodes() throws InterruptedException {
        ConcurrentLinkedQueue<byte[]> frames = new ConcurrentLinkedQueue<>();

        try (AdsbSource source = new SimulatedSource(new Position(38.74, -121.22), 6)) {
            source.start(frames::add, event -> { });
            Thread.sleep(1_500);
        }

        List<byte[]> collected = new ArrayList<>(frames);
        assertTrue(collected.size() >= 12,
                "expected a steady stream, got " + collected.size() + " frames");
        assertTrue(collected.stream().allMatch(ModeS::isValid), "some frames failed parity");

        List<AdsbMessage> decoded = collected.stream()
                .map(AdsbDecoder::decode)
                .filter(Objects::nonNull)
                .toList();
        assertEquals(collected.size(), decoded.size(), "some frames did not decode");
        assertEquals(6, decoded.stream().map(AdsbMessage::icao).distinct().count());
        assertTrue(decoded.stream().anyMatch(m -> m instanceof AdsbMessage.AirbornePosition));
        assertTrue(decoded.stream().anyMatch(m -> m instanceof AdsbMessage.Velocity));
        assertTrue(decoded.stream().anyMatch(m -> m instanceof AdsbMessage.Identification));
    }
}
