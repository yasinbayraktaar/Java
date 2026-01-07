package com.example.kentseldonusum.bridge;

import com.example.kentseldonusum.service.ReportService;
import javafx.application.Platform;
import javafx.scene.control.Label;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * WebView (JS) tarafından çağrılan Java köprüsü
 * - Bölge seçimi (onZoneSelected)
 * - Rapor oluşturma (createReport)
 * - Katman yönetimi callback'leri
 */
public class MapBridge {

    private static final Logger LOGGER = Logger.getLogger(MapBridge.class.getName());

    private final Label areaNameLabel;
    private final Label ilceMahalleLabel;
    private final Label buildingStatsLabel;

    // Son seçim bilgileri
    private String selectedDistrict = "-";
    private String selectedNeighbourhood = "-";
    private double selectedCenterLat = 41.0082;
    private double selectedCenterLng = 28.9784;
    private String selectedBbox = "";
    private List<String> activeLayers = Arrays.asList("OSM");
    private String selectedScenario = "Mw 7.5 - Gece Senaryosu";

    public MapBridge(Label areaNameLabel, Label ilceMahalleLabel, Label buildingStatsLabel) {
        this.areaNameLabel = areaNameLabel;
        this.ilceMahalleLabel = ilceMahalleLabel;
        this.buildingStatsLabel = buildingStatsLabel;
    }

    /**
     * JS tarafından çağrılır: Haritada bölge seçildi
     */
    public void onZoneSelected(String district, String neighbourhood, double lat, double lng, String bbox) {
        LOGGER.info("Zone selected: " + district + " / " + neighbourhood);

        this.selectedDistrict = district != null ? district : "-";
        this.selectedNeighbourhood = neighbourhood != null ? neighbourhood : "-";
        this.selectedCenterLat = lat;
        this.selectedCenterLng = lng;
        this.selectedBbox = bbox != null ? bbox : "";

        Platform.runLater(() -> {
            areaNameLabel.setText("Bölge: " + selectedDistrict + " / " + selectedNeighbourhood);
            ilceMahalleLabel.setText("Konum: (" + selectedCenterLat + ", " + selectedCenterLng + ")");
            buildingStatsLabel.setText(
                    "Seçilen Alan:\n" +
                            " • İlçe: " + selectedDistrict + "\n" +
                            " • Mahalle: " + selectedNeighbourhood + "\n" +
                            " • Merkez: (" + String.format("%.4f", selectedCenterLat) + ", " + String.format("%.4f", selectedCenterLng) + ")\n" +
                            " • BBox: " + (selectedBbox.isEmpty() ? "-" : selectedBbox)
            );
        });
    }

    /**
     * JS tarafından çağrılır: Katman durumu değişti
     */
    public void onLayerStateChanged(String layerName, boolean isActive) {
        LOGGER.info("Layer state changed: " + layerName + " = " + isActive);

        if (isActive) {
            if (!activeLayers.contains(layerName)) {
                activeLayers.add(layerName);
            }
        } else {
            activeLayers.remove(layerName);
        }
    }

    /**
     * JS tarafından çağrılır: Senaryo değişti
     */
    public void onScenarioChanged(String scenario) {
        LOGGER.info("Scenario changed: " + scenario);
        this.selectedScenario = scenario != null ? scenario : "Mw 7.5 - Gece Senaryosu";
    }

    /**
     * Rapor oluştur ve dışa aktar
     */
    public void createReport() {
        LOGGER.info("Report creation started");

        String reportPath = ReportService.createJsonReport(
                selectedDistrict,
                selectedNeighbourhood,
                selectedScenario,
                selectedCenterLat,
                selectedCenterLng,
                selectedBbox,
                activeLayers
        );

        Platform.runLater(() -> {
            if (reportPath != null) {
                areaNameLabel.setText("✓ Rapor kaydedildi: " + reportPath);
            } else {
                areaNameLabel.setText("✗ Rapor kaydedilirken hata!");
            }
        });
    }

    // Getter'lar (test/debug için)
    public String getSelectedDistrict() { return selectedDistrict; }
    public String getSelectedNeighbourhood() { return selectedNeighbourhood; }
    public List<String> getActiveLayers() { return activeLayers; }
}

