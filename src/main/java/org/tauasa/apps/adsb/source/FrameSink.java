package org.tauasa.apps.adsb.source;

/** Receives raw Mode S frames off the source thread. */
@FunctionalInterface
public interface FrameSink {
    void accept(byte[] frame);
}
