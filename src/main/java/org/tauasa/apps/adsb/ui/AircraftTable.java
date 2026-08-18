package org.tauasa.apps.adsb.ui;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import org.tauasa.apps.adsb.model.Aircraft;

import java.util.function.Function;

/**
 * The tabular half of the picture. Columns hold real types rather than
 * pre-formatted strings so that sorting by altitude sorts by altitude and not by
 * the first digit of a right-aligned string.
 */
public final class AircraftTable {

    private AircraftTable() {
    }

    public static TableView<Aircraft> create() {
        TableView<Aircraft> table = new TableView<>();
        table.getStyleClass().add("aircraft-table");
        table.setPlaceholder(new Label("No aircraft heard yet."));

        TableColumn<Aircraft, String> address = new TableColumn<>("ICAO");
        address.setPrefWidth(76);
        address.setCellValueFactory(row -> new ReadOnlyStringWrapper(row.getValue().address()));
        address.getStyleClass().add("mono-column");

        TableColumn<Aircraft, String> callsign = new TableColumn<>("Callsign");
        callsign.setPrefWidth(92);
        callsign.setCellValueFactory(row -> row.getValue().callsignProperty());
        callsign.getStyleClass().add("mono-column");

        TableColumn<Aircraft, Integer> altitude = valueColumn(
                "Alt ft", 78, Aircraft::altitudeFeetProperty, value -> String.format("%,d", value));
        TableColumn<Aircraft, Integer> speed = valueColumn(
                "GS kt", 66, Aircraft::groundSpeedKnotsProperty, String::valueOf);
        TableColumn<Aircraft, Integer> track = valueColumn(
                "Track", 62, Aircraft::trackDegreesProperty, value -> String.format("%03d\u00B0", value));
        TableColumn<Aircraft, Integer> vertical = valueColumn(
                "V/S fpm", 76, Aircraft::verticalRateFpmProperty, value -> String.format("%+,d", value));
        TableColumn<Aircraft, Double> distance = valueColumn(
                "Range nm", 84, Aircraft::distanceNmProperty, value -> String.format("%.1f", value));
        TableColumn<Aircraft, Double> bearing = valueColumn(
                "Brg", 60, Aircraft::bearingDegreesProperty, value -> String.format("%03.0f\u00B0", value));

        TableColumn<Aircraft, Number> messages = counterColumn(
                "Msgs", 62, aircraft -> aircraft.messageCountProperty());
        TableColumn<Aircraft, Number> age = counterColumn(
                "Age s", 60, aircraft -> aircraft.ageSecondsProperty());

        table.getColumns().add(address);
        table.getColumns().add(callsign);
        table.getColumns().add(altitude);
        table.getColumns().add(speed);
        table.getColumns().add(track);
        table.getColumns().add(vertical);
        table.getColumns().add(distance);
        table.getColumns().add(bearing);
        table.getColumns().add(messages);
        table.getColumns().add(age);
        table.getSortOrder().add(distance);
        return table;
    }

    private static <T> TableColumn<Aircraft, T> valueColumn(
            String title,
            double width,
            Function<Aircraft, ObservableValue<T>> value,
            Function<T, String> format) {

        TableColumn<Aircraft, T> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(row -> value.apply(row.getValue()));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : format.apply(item));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        column.getStyleClass().add("mono-column");
        return column;
    }

    private static TableColumn<Aircraft, Number> counterColumn(
            String title,
            double width,
            Function<Aircraft, ObservableValue<Number>> value) {

        TableColumn<Aircraft, Number> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(row -> value.apply(row.getValue()));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.toString());
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        column.getStyleClass().add("mono-column");
        return column;
    }
}
