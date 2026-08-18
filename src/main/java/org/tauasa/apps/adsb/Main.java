package org.tauasa.apps.adsb;

import javafx.application.Application;
import org.tauasa.apps.adsb.ui.AdsbApp;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Application.launch(AdsbApp.class, args);
    }
}
