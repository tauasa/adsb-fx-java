package org.tauasa.apps.adsb.source;

/** Lifecycle notifications, delivered off the FX thread. */
public sealed interface SourceEvent {

    String detail();

    record Connected(String detail) implements SourceEvent {
    }

    record Disconnected(String detail) implements SourceEvent {
    }

    record Failed(String detail) implements SourceEvent {
    }
}
