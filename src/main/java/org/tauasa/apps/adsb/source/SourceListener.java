package org.tauasa.apps.adsb.source;

/** Receives lifecycle notifications off the source thread. */
@FunctionalInterface
public interface SourceListener {
    void onEvent(SourceEvent event);
}
