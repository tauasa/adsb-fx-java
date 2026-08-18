package org.tauasa.apps.adsb.ui;

import javafx.animation.AnimationTimer;
import javafx.css.PseudoClass;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.tauasa.apps.adsb.decode.AdsbDecoder;
import org.tauasa.apps.adsb.decode.AdsbMessage;
import org.tauasa.apps.adsb.decode.Position;
import org.tauasa.apps.adsb.model.Aircraft;
import org.tauasa.apps.adsb.model.AircraftTracker;
import org.tauasa.apps.adsb.source.AdsbSource;
import org.tauasa.apps.adsb.source.Dump1090ProcessSource;
import org.tauasa.apps.adsb.source.Dump1090TcpSource;
import org.tauasa.apps.adsb.source.SimulatedSource;
import org.tauasa.apps.adsb.source.SourceEvent;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class AdsbApp extends Application {

    private static final int MAX_DRAIN_PER_FRAME = 400;
    private static final int MAX_QUEUE_DEPTH = 20_000;
    private static final long REDRAW_INTERVAL_MILLIS = 250L;

    /** Styling hook for a coordinate field that has text in it but no usable number. */
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");

    /**
     * Where simulated traffic orbits when no receiver position has been entered.
     * The value is arbitrary — a simulation needs a centre, not a true one.
     */
    private static final Position DEFAULT_SIMULATION_CENTRE = new Position(38.70, -121.20);

    private enum SourceKind {
        TCP("dump1090 over TCP"),
        PROCESS("Launch dump1090"),
        SIMULATED("Simulated traffic");

        private final String display;

        SourceKind(String display) {
            this.display = display;
        }

        @Override
        public String toString() {
            return display;
        }
    }

    private final AircraftTracker tracker = new AircraftTracker();
    private final ConcurrentLinkedQueue<AdsbMessage> inbound = new ConcurrentLinkedQueue<>();
    private final AtomicLong framesSeen = new AtomicLong();
    private final AtomicLong framesDecoded = new AtomicLong();

    private AdsbSource source;
    private AnimationTimer pump;

    private final ChoiceBox<SourceKind> sourceChoice = new ChoiceBox<>();
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = new TextField("30002");
    private final TextField executableField = new TextField("dump1090");
    private final TextField latitudeField = new TextField();
    private final TextField longitudeField = new TextField();
    private final ChoiceBox<Integer> rangeChoice = new ChoiceBox<>();
    private final Button connectButton = new Button("Start receiving");

    private final Label statusLabel = new Label("Idle");
    private final Label rateLabel = new Label("\u2014");
    private final Label countLabel = new Label("0 aircraft");

    private final TableView<Aircraft> table = AircraftTable.create();
    private final SituationDisplay display = new SituationDisplay(tracker);

    @Override
    public void start(Stage stage) {
        buildControls();
        wireSelection();

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setTop(new VBox(header(), controlBar()));
        root.setCenter(centreSplit());
        root.setBottom(statusBar());

        Scene scene = new Scene(root, 1360, 840);
        scene.getStylesheets().add(
                AdsbApp.class.getResource("/org/tauasa/apps/adsb/adsb.css").toExternalForm());

        stage.setTitle("adsb-fx");
        stage.setScene(scene);
        stage.show();

        startPump();
    }

    // ---------------------------------------------------------------- layout

    private Region header() {
        Label title = new Label("ADS-B RECEIVER");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("1090 MHz extended squitter");
        subtitle.getStyleClass().add("app-subtitle");
        HBox bar = new HBox(12, title, subtitle);
        bar.getStyleClass().add("header-bar");
        bar.setAlignment(Pos.BASELINE_LEFT);
        return bar;
    }

    private Region controlBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("control-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 10, 14));

        HBox tcpFields = new HBox(6, field("Host", hostField, 128), field("Port", portField, 68));
        HBox processFields = new HBox(6, field("Command", executableField, 150));

        tcpFields.visibleProperty().bind(sourceChoice.valueProperty().isEqualTo(SourceKind.TCP));
        tcpFields.managedProperty().bind(tcpFields.visibleProperty());
        processFields.visibleProperty().bind(sourceChoice.valueProperty().isEqualTo(SourceKind.PROCESS));
        processFields.managedProperty().bind(processFields.visibleProperty());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bar.getChildren().addAll(
                field("Source", sourceChoice, 160),
                tcpFields,
                processFields,
                connectButton,
                spacer,
                field("Receiver lat", latitudeField, 96),
                field("Receiver lon", longitudeField, 96),
                field("Range nm", rangeChoice, 90));
        return bar;
    }

    private Region field(String label, Region control, double width) {
        control.setPrefWidth(width);
        Label caption = new Label(label.toUpperCase());
        caption.getStyleClass().add("field-label");
        return new VBox(3, caption, control);
    }

    private Region centreSplit() {
        display.getStyleClass().add("situation-display");
        SplitPane split = new SplitPane(display, table);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.56);
        SplitPane.setResizableWithParent(table, true);
        return split;
    }

    private Region statusBar() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        statusLabel.getStyleClass().add("status-item");
        rateLabel.getStyleClass().add("status-item");
        countLabel.getStyleClass().add("status-item");
        HBox bar = new HBox(18, statusLabel, spacer, rateLabel, countLabel);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(7, 14, 7, 14));
        return bar;
    }

    // --------------------------------------------------------------- wiring

    private void buildControls() {
        sourceChoice.getItems().addAll(SourceKind.values());
        sourceChoice.setValue(SourceKind.TCP);

        rangeChoice.getItems().addAll(20, 60, 120, 250);
        rangeChoice.setValue(120);
        rangeChoice.valueProperty().addListener((observable, was, is) -> {
            if (is != null) {
                display.rangeNmProperty().set(is);
            }
        });

        latitudeField.setPromptText("e.g. 38.7000");
        longitudeField.setPromptText("e.g. -121.2000");
        latitudeField.textProperty().addListener((observable, was, is) -> updateReceiverPosition());
        longitudeField.textProperty().addListener((observable, was, is) -> updateReceiverPosition());

        connectButton.setOnAction(event -> {
            if (source == null) {
                startSource();
            } else {
                stopSource();
            }
        });
    }

    private void wireSelection() {
        table.getSelectionModel().selectedItemProperty().addListener(
                (observable, was, is) -> display.selectedProperty().set(is));
        display.selectedProperty().addListener((observable, was, is) -> {
            if (is != null && table.getSelectionModel().getSelectedItem() != is) {
                table.getSelectionModel().select(is);
                table.scrollTo(is);
            }
        });
    }

    private void updateReceiverPosition() {
        Double latitude = parseCoordinate(latitudeField.getText());
        Double longitude = parseCoordinate(longitudeField.getText());
        boolean latitudeOk = latitude != null && latitude >= -90 && latitude <= 90;
        boolean longitudeOk = longitude != null && longitude >= -180 && longitude <= 180;

        tracker.receiverPosition(
                latitudeOk && longitudeOk ? new Position(latitude, longitude) : null);

        // Mark a field only once it has something in it — an empty field is not
        // yet an error, and reddening it before the person has typed is noise.
        latitudeField.pseudoClassStateChanged(
                INVALID, !latitudeOk && !latitudeField.getText().isBlank());
        longitudeField.pseudoClassStateChanged(
                INVALID, !longitudeOk && !longitudeField.getText().isBlank());

        display.draw();
    }

    /**
     * Parse a coordinate leniently. Decimal commas and a trailing degree sign are
     * how people actually type latitudes, and rejecting them silently leaves the
     * app looking broken for a reason nothing on screen explains.
     */
    private static Double parseCoordinate(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.strip().replace('\u00B0', ' ').replace(',', '.').strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -------------------------------------------------------------- sources

    private void startSource() {
        SourceKind kind = sourceChoice.getValue();
        if (kind == null) {
            return;
        }

        AdsbSource created;
        try {
            created = switch (kind) {
                case TCP -> {
                    String host = hostField.getText().strip();
                    Integer port = parseInt(portField.getText());
                    yield new Dump1090TcpSource(
                            host.isEmpty() ? "127.0.0.1" : host,
                            port == null ? 30002 : port);
                }
                case PROCESS -> {
                    String executable = executableField.getText().strip();
                    yield new Dump1090ProcessSource(executable.isEmpty() ? "dump1090" : executable);
                }
                case SIMULATED -> {
                    Position centre = tracker.receiverPosition();
                    if (centre == null) {
                        // Fill the fields in rather than refusing to start: the
                        // centre of a simulation is arbitrary, so asking the person
                        // to supply one is a demand the app can meet itself.
                        centre = DEFAULT_SIMULATION_CENTRE;
                        latitudeField.setText(
                                String.format(Locale.ROOT, "%.4f", centre.latitude()));
                        longitudeField.setText(
                                String.format(Locale.ROOT, "%.4f", centre.longitude()));
                    }
                    yield new SimulatedSource(centre);
                }
            };
        } catch (Exception e) {
            statusLabel.setText("Could not start source: " + e.getMessage());
            return;
        }
        if (created == null) {
            return;
        }

        framesSeen.set(0);
        framesDecoded.set(0);
        tracker.clear();

        created.start(
                frame -> {
                    framesSeen.incrementAndGet();
                    AdsbMessage message = AdsbDecoder.decode(frame);
                    if (message != null) {
                        framesDecoded.incrementAndGet();
                        // Drop rather than grow without bound if the UI ever falls behind.
                        if (inbound.size() < MAX_QUEUE_DEPTH) {
                            inbound.add(message);
                        }
                    }
                },
                event -> Platform.runLater(() -> report(event)));

        source = created;
        connectButton.setText("Stop");
        statusLabel.setText("Connecting to " + created.description() + "\u2026");
        setSourceControlsDisabled(true);
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.valueOf(text.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void stopSource() {
        if (source != null) {
            source.close();
            source = null;
        }
        connectButton.setText("Start receiving");
        statusLabel.setText("Stopped");
        setSourceControlsDisabled(false);
    }

    private void setSourceControlsDisabled(boolean disabled) {
        sourceChoice.setDisable(disabled);
        hostField.setDisable(disabled);
        portField.setDisable(disabled);
        executableField.setDisable(disabled);
    }

    private void report(SourceEvent event) {
        statusLabel.setText(switch (event) {
            case SourceEvent.Connected connected -> "Receiving from " + connected.detail();
            case SourceEvent.Disconnected disconnected -> disconnected.detail() + " \u2014 retrying";
            case SourceEvent.Failed failed -> failed.detail();
        });
        statusLabel.getStyleClass().remove("status-error");
        if (event instanceof SourceEvent.Failed) {
            statusLabel.getStyleClass().add("status-error");
        }
    }

    // ----------------------------------------------------------------- pump

    /**
     * Everything that touches the model runs here, on the FX thread. Sources only
     * ever hand over immutable decoded messages, which is what lets the table
     * bind directly to the aircraft objects without synchronisation.
     */
    private void startPump() {
        pump = new AnimationTimer() {
            private long lastTickMillis;
            private long lastRateMillis;
            private long framesAtLastRate;

            @Override
            public void handle(long nowNanos) {
                int drained = 0;
                while (drained < MAX_DRAIN_PER_FRAME) {
                    AdsbMessage message = inbound.poll();
                    if (message == null) {
                        break;
                    }
                    tracker.apply(message);
                    drained++;
                }

                long nowMillis = nowNanos / 1_000_000;
                if (nowMillis - lastTickMillis >= REDRAW_INTERVAL_MILLIS) {
                    lastTickMillis = nowMillis;
                    tracker.tick(System.currentTimeMillis());
                    table.sort();
                    display.draw();
                    countLabel.setText(tracker.aircraft().size() + " aircraft \u00B7 "
                            + tracker.withPositionCount() + " with position");
                }

                if (nowMillis - lastRateMillis >= 1_000) {
                    long elapsed = Math.max(nowMillis - lastRateMillis, 1);
                    long total = framesSeen.get();
                    double perSecond = (total - framesAtLastRate) * 1000.0 / elapsed;
                    framesAtLastRate = total;
                    lastRateMillis = nowMillis;
                    rateLabel.setText(String.format(
                            "%.0f frames/s \u00B7 %,d decoded of %,d",
                            perSecond, framesDecoded.get(), total));
                }
            }
        };
        pump.start();
    }

    @Override
    public void stop() {
        if (pump != null) {
            pump.stop();
        }
        if (source != null) {
            source.close();
        }
    }
}
