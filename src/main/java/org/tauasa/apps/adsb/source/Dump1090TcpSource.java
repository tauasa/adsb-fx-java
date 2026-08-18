package org.tauasa.apps.adsb.source;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads AVR-format frames from a dump1090 / readsb raw output port (30002 by
 * default). Reconnects on its own until closed, so pulling the dongle out and
 * plugging it back in does not require restarting the app.
 */
public final class Dump1090TcpSource implements AdsbSource {

    private static final int CONNECT_TIMEOUT_MILLIS = 4_000;

    private final String host;
    private final int port;
    private final long reconnectDelayMillis;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile Socket socket;
    private Thread worker;

    public Dump1090TcpSource(String host, int port) {
        this(host, port, 3_000L);
    }

    public Dump1090TcpSource(String host, int port, long reconnectDelayMillis) {
        this.host = host;
        this.port = port;
        this.reconnectDelayMillis = reconnectDelayMillis;
    }

    @Override
    public String description() {
        return "dump1090 at " + host + ":" + port;
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
        worker = new Thread(() -> loop(onFrame, onEvent), "adsb-tcp-" + host + "-" + port);
        worker.setDaemon(true);
        worker.start();
    }

    private void loop(FrameSink onFrame, SourceListener onEvent) {
        while (active.get()) {
            try (Socket s = new Socket()) {
                socket = s;
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
                onEvent.onEvent(new SourceEvent.Connected(description()));
                try (BufferedReader reader =
                             new BufferedReader(new InputStreamReader(s.getInputStream()))) {
                    String line;
                    while (active.get() && (line = reader.readLine()) != null) {
                        byte[] frame = AvrLine.parse(line);
                        if (frame != null) {
                            onFrame.accept(frame);
                        }
                    }
                }
                if (active.get()) {
                    onEvent.onEvent(new SourceEvent.Disconnected(description() + " closed the stream"));
                }
            } catch (Exception e) {
                if (active.get()) {
                    String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    onEvent.onEvent(new SourceEvent.Failed(detail));
                }
            } finally {
                socket = null;
            }
            if (!active.get()) {
                break;
            }
            try {
                Thread.sleep(reconnectDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break; // close() interrupted the wait
            }
        }
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        Socket open = socket;
        if (open != null) {
            try {
                open.close();
            } catch (Exception ignored) {
                // already gone
            }
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }
}
