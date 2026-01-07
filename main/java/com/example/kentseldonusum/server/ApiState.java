package com.example.kentseldonusum.server;

import com.example.kentseldonusum.service.ReportService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Uygulama durumunu (seçilen alan, katmanlar, senaryo) saklayan basit singleton.
 */
public class ApiState {

    private static final ApiState INSTANCE = new ApiState();

    private String district = "-";
    private String neighbourhood = "-";
    private double centerLat = 41.0082;
    private double centerLng = 28.9784;
    private String bbox = "";
    private final List<String> activeLayers = new ArrayList<>();
    private String scenario = "Mw 7.5 - Gece Senaryosu";

    private ApiState() {
        activeLayers.add("OSM");
    }

    public static ApiState getInstance() {
        return INSTANCE;
    }

    public synchronized void setZone(String district, String neighbourhood, double lat, double lng, String bbox) {
        this.district = district != null ? district : "-";
        this.neighbourhood = neighbourhood != null ? neighbourhood : "-";
        this.centerLat = lat;
        this.centerLng = lng;
        this.bbox = bbox != null ? bbox : "";
    }

    public synchronized void setLayerState(String layer, boolean active) {
        if (active) {
            if (!activeLayers.contains(layer)) activeLayers.add(layer);
        } else {
            activeLayers.remove(layer);
        }
    }

    public synchronized void setScenario(String scenario) {
        this.scenario = scenario != null ? scenario : this.scenario;
    }

    public synchronized String createReport() {
        return ReportService.createJsonReport(district, neighbourhood, scenario, centerLat, centerLng, bbox, new ArrayList<>(activeLayers));
    }

    public synchronized String getDistrict() { return district; }
    public synchronized String getNeighbourhood() { return neighbourhood; }
    public synchronized double getCenterLat() { return centerLat; }
    public synchronized double getCenterLng() { return centerLng; }
    public synchronized String getBbox() { return bbox; }
    public synchronized List<String> getActiveLayers() { return Collections.unmodifiableList(activeLayers); }
    public synchronized String getScenario() { return scenario; }
}

