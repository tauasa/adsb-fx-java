package org.tauasa.apps.adsb.source;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Launches dump1090 against the dongle and reads AVR frames from its stdout.
 *
 * <p>This is the "own the whole pipeline" option: nothing has to be running
 * beforehand, and the process dies with the app. The trade-off against the TCP
 * source is that only one program can hold the USB device at a time, so this
 * conflicts with an already-running receiver.
 */
public final class Dump1090ProcessSource implements AdsbSource {

    private final String executable;
    private final int deviceIndex;
    private final String gain;
    private final Integer ppmCorrection;
    private final List<String> extraArguments;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private Process process;

    public Dump1090ProcessSource(String executable) {
        this(executable, 0, null, null, List.of());
    }

    public Dump1090ProcessSource(
            String executable,
            int deviceIndex,
            String gain,
            Integer ppmCorrection,
            List<String> extraArguments) {
        this.executable = executable;
        this.deviceIndex = deviceIndex;
        this.gain = gain;
        this.ppmCorrection = ppmCorrection;
        this.extraArguments = List.copyOf(extraArguments);
    }

    @Override
    public String description() {
        return executable + " (device " + deviceIndex + ")";
    }

    @Override
    public boolean isRunning() {
        return active.get() && process != null && process.isAlive();
    }

    public List<String> command() {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("--raw");
        command.add("--device-index");
        command.add(Integer.toString(deviceIndex));
        if (gain != null) {
            command.add("--gain");
            command.add(gain);
        }
        if (ppmCorrection != null) {
            command.add("--ppm");
            command.add(Integer.toString(ppmCorrection));
        }
        command.addAll(extraArguments);
        return command;
    }

    @Override
    public void start(FrameSink onFrame, SourceListener onEvent) {
        if (!active.compareAndSet(false, true)) {
            return;
        }
        Process started;
        try {
            started = new ProcessBuilder(command()).start();
        } catch (Exception e) {
            active.set(false);
            onEvent.onEvent(new SourceEvent.Failed(
                    "Could not start " + executable
                            + " — check it is installed and on PATH (" + e.getMessage() + ")"));
            return;
        }
        process = started;
        onEvent.onEvent(new SourceEvent.Connected(String.join(" ", command())));

        Thread reader = new Thread(() -> {
            try (BufferedReader input =
                         new BufferedReader(new InputStreamReader(started.getInputStream()))) {
                String line;
                while (active.get() && (line = input.readLine()) != null) {
                    byte[] frame = AvrLine.parse(line);
                    if (frame != null) {
                        onFrame.accept(frame);
                    }
                }
            } catch (Exception ignored) {
                // stream closed under us; the disconnect below reports it
            }
            if (active.get()) {
                // The stream can close before the process does, so ask for the
                // exit code rather than assuming one is available.
                String suffix = "";
                try {
                    suffix = " (exit " + started.waitFor() + ")";
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                onEvent.onEvent(new SourceEvent.Disconnected(executable + " stopped" + suffix));
                active.set(false);
            }
        }, "adsb-process-reader");
        reader.setDaemon(true);
        reader.start();

        // dump1090 reports tuner errors on stderr; surfacing the last line is
        // the difference between "no aircraft" and "no dongle".
        Thread stderrPump = new Thread(() -> {
            try (BufferedReader input =
                         new BufferedReader(new InputStreamReader(started.getErrorStream()))) {
                String line;
                while (active.get() && (line = input.readLine()) != null) {
                    if (!line.isBlank()) {
                        onEvent.onEvent(new SourceEvent.Failed(line.strip()));
                    }
                }
            } catch (Exception ignored) {
                // nothing useful to add
            }
        }, "adsb-process-stderr");
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    @Override
    public void close() {
        if (!active.compareAndSet(true, false)) {
            return;
        }
        Process running = process;
        if (running != null) {
            running.destroy();
            try {
                if (!running.waitFor(2, TimeUnit.SECONDS)) {
                    running.destroyForcibly();
                }
            } catch (InterruptedException e) {
                running.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        process = null;
    }
}
