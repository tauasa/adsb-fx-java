package org.tauasa.apps.adsb.source;

/**
 * A supply of Mode S frames.
 *
 * <p>Implementations own their threads and must tolerate {@link #close()} being
 * called at any point, including mid-connect.
 */
public interface AdsbSource extends AutoCloseable {

    String description();

    boolean isRunning();

    void start(FrameSink onFrame, SourceListener onEvent);

    @Override
    void close();
}
