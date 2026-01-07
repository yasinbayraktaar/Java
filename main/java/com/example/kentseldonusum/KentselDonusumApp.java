package com.example.kentseldonusum;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import com.example.kentseldonusum.bridge.MapBridge;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.logging.Logger;

/**
 * Kentsel Dönüşüm Analiz Paneli
 * JavaFX UI + WebView (Leaflet harita) + MapBridge (JS<->Java köprüsü)
 */
public class KentselDonusumApp extends Application {

    private static final Logger LOGGER = Logger.getLogger(KentselDonusumApp.class.getName());

    // Sağ panelde güncelleyeceğimiz label'ları field yaptık
    private Label areaNameLabel;
    private Label ilceMahalleLabel;
    private Label buildingStatsLabel;
    private Label damageStatsLabel;
    private Label populationStatsLabel;
    private Label renewalAdviceLabel;

    private WebView webView;
    private WebEngine webEngine;

    private static final String DEFAULT_DISTRICT = "maltepe";

    // ============ IBB MAPSERVER İNFO ÇEKME ============
    private String getIbbTileUrl() {
        try {
            // MapServer endpoint'ine GET isteği at
            String mapServerUrl = "https://api.ibb.gov.tr/cbsaltlik/arcgis/rest/services/MAKS/YAPI_YOGUNLUK/MapServer";

            LOGGER.info("IBB MapServer'dan bilgi çekiliyor: " + mapServerUrl);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(mapServerUrl + "?f=json"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                LOGGER.info("MapServer response status: 200");
                LOGGER.info("MapServer response (ilk 1000 char): " + body.substring(0, Math.min(1000, body.length())));

                // Üç tile URL formatını dene:
                // 1. Standart: /tile/{z}/{y}/{x}
                // 2. MapServer imageserver: /ImageServer/tile/{z}/{y}/{x}
                // 3. Map-based: /tile?z={z}&x={x}&y={y}

                String[] urlCandidates = {
                        mapServerUrl + "/tile/{z}/{y}/{x}",
                        mapServerUrl + "/ImageServer/tile/{z}/{y}/{x}",
                        "https://api.ibb.gov.tr/cbsaltlik/arcgis/services/MAKS/YAPI_YOGUNLUK/MapServer/tile/{z}/{y}/{x}"
                };

                for (String candidate : urlCandidates) {
                    LOGGER.info("İBB Tile URL candidate: " + candidate);
                }

                // Asıl kullanılacak URL
                String tileUrl = urlCandidates[0];
                LOGGER.info("IBB Tile URL (seçilen): " + tileUrl);
                return tileUrl;
            } else {
                LOGGER.warning("MapServer request failed with status: " + response.statusCode());
                // Fallback URL
                return "https://api.ibb.gov.tr/cbsaltlik/arcgis/rest/services/MAKS/YAPI_YOGUNLUK/MapServer/tile/{z}/{y}/{x}";
            }
        } catch (Exception e) {
            LOGGER.warning("IBB MapServer bilgi çekme hatası: " + e.getMessage());
            e.printStackTrace();
            // Fallback URL
            return "https://api.ibb.gov.tr/cbsaltlik/arcgis/rest/services/MAKS/YAPI_YOGUNLUK/MapServer/tile/{z}/{y}/{x}";
        }
    }

    @Override
    public void start(Stage stage) {

        BorderPane root = new BorderPane();
        root.setStyle("-fx-padding: 0;");

        root.setTop(buildTopBar());
        root.setLeft(buildLeftPanel());
        root.setCenter(buildMapArea());
        root.setRight(buildRightPanel());

        Scene scene = new Scene(root, 1200, 750);
        stage.setTitle("Kentsel Dönüşüm Analiz Paneli");
        stage.setScene(scene);
        stage.show();

        // ============ STAGE SHOW SONRASI invalidateSize (AGRESIF) ============
        new Thread(() -> {
            try {
                // Hızlı seri çağrılar - tile buffer'ı fill etmek için
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(200);
                    final int iteration = i + 1;
                    Platform.runLater(() -> {
                        try {
                            webEngine.executeScript("if(window.map) { window.map.invalidateSize(false); }");
                            LOGGER.info("Post-show invalidateSize #" + iteration);
                        } catch (Exception e) {
                            // Silent
                        }
                    });
                }

                // Son refresh - animasyonlu
                Thread.sleep(200);
                Platform.runLater(() -> {
                    try {
                        webEngine.executeScript("if(window.map) { window.map.invalidateSize(true); }");
                        LOGGER.info("Post-show final invalidateSize");
                    } catch (Exception e) {
                        // Silent
                    }
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        // ============ WEBENGINE LOAD LISTENER ============
        webEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                LOGGER.info("WebEngine load succeeded, calling initMapDelayed()");

                // Harita init'ini çağır (300ms gecikmeli)
                new Thread(() -> {
                    try {
                        Thread.sleep(300);
                        Platform.runLater(() -> {
                            try {
                                webEngine.executeScript("if (typeof initMapDelayed === 'function') { initMapDelayed(); }");
                                LOGGER.info("initMapDelayed() called from Java");
                            } catch (Exception e) {
                                LOGGER.warning("initMapDelayed() error: " + e.getMessage());
                            }
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start();

                // MapBridge'i aktif et (harita init'i ile eşzamanlı çalışabilir)
                JSObject window = (JSObject) webEngine.executeScript("window");
                MapBridge mapBridge = new MapBridge(
                        areaNameLabel,
                        ilceMahalleLabel,
                        buildingStatsLabel
                );
                window.setMember("mapBridge", mapBridge);
                LOGGER.info("MapBridge aktif edildi");

                // İlk ilçeyi yükle
                loadDistrictInWebView(DEFAULT_DISTRICT);
            }
        });
    }


    // ÜST BAR
    private HBox buildTopBar() {
        Label title = new Label("Kentsel Dönüşüm Analiz Paneli");

        ComboBox<String> scenarioCombo = new ComboBox<>();
        scenarioCombo.getItems().addAll(
                "Mw 7.5 - Gece Senaryosu",
                "Mw 7.5 - Gündüz Senaryosu"
        );
        scenarioCombo.setValue("Mw 7.5 - Gece Senaryosu");

        Button refreshButton = new Button("Uygula");
        // TODO: senaryo seçimine göre ileride veriyi yeniden yükle

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox topBar = new HBox(15, title, spacer, scenarioCombo, refreshButton);
        topBar.setPadding(new Insets(10));
        topBar.setAlignment(Pos.CENTER_LEFT);

        topBar.setStyle("-fx-background-color: #1f2933;");
        title.setStyle("-fx-text-fill: white; -fx-font-size: 20px; -fx-font-weight: bold;");
        scenarioCombo.setStyle("-fx-background-radius: 6;");
        refreshButton.setStyle("-fx-background-radius: 6;");

        return topBar;
    }

    // SOL PANEL (filtreler)
    private VBox buildLeftPanel() {
        Label filterLabel = new Label("Filtreler");
        filterLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label ilceLabel = new Label("İlçe");
        ComboBox<String> ilceComboBox = new ComboBox<>();
        ilceComboBox.getItems().addAll(
                "istanbul_geneli",
                "zeytinburnu",
                "kadikoy",
                "besiktas",
                "maltepe"
        );
        ilceComboBox.setValue(DEFAULT_DISTRICT);

        ilceComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.isEmpty()) {
                loadDistrictInWebView(newVal);
            }
        });

        Label mahalleLabel = new Label("Mahalle");
        ComboBox<String> mahalleCombo = new ComboBox<>();
        mahalleCombo.getItems().addAll(
                "Tüm Mahalleler"
        );
        mahalleCombo.setValue("Tüm Mahalleler");

        Label layerLabel = new Label("Katmanlar");
        CheckBox cbBuildingRisk = new CheckBox("Bina Risk Skoru");
        cbBuildingRisk.setSelected(true);

        CheckBox cbRenewalZones = new CheckBox("Kentsel Dönüşüm Alanları");
        cbRenewalZones.setSelected(true);

        CheckBox cbWms = new CheckBox("WMS - İBB Yapı Yoğunluğu");
        cbWms.setSelected(false);
        cbWms.setOnAction(e -> {
            LOGGER.info("IBB checkbox state changed: " + cbWms.isSelected());

            if (webEngine == null) {
                LOGGER.warning("WebEngine null, IBB REST toggle yapılamadı");
                cbWms.setSelected(!cbWms.isSelected());
                return;
            }
            try {
                String script = "console.log('[JAVA] toggleIbbRestLayerSafe çağrısı yapılacak'); " +
                        "if (typeof window.toggleIbbRestLayerSafe === 'function') {" +
                        "  console.log('[JAVA] toggleIbbRestLayerSafe bulundu, çağırılıyor'); " +
                        "  window.toggleIbbRestLayerSafe();" +
                        "} else if (typeof window.toggleIbbRestLayer === 'function') {" +
                        "  console.log('[JAVA] toggleIbbRestLayer bulundu, çağırılıyor'); " +
                        "  window.toggleIbbRestLayer();" +
                        "} else { " +
                        "  console.error('[JAVA] Hiçbir toggle fonksiyonu bulunamadı!'); " +
                        "}";
                webEngine.executeScript(script);
                LOGGER.info("IBB toggle script executed");
            } catch (Exception ex) {
                LOGGER.warning("IBB REST toggle hatası: " + ex.getMessage());
                ex.printStackTrace();
                cbWms.setSelected(!cbWms.isSelected());
            }
        });

        CheckBox cbInfra = new CheckBox("Altyapı (Gaz/Su/Atık Su)");
        CheckBox cbRoadClosure = new CheckBox("Yol Kapanma Riski");
        CheckBox cbPopulation = new CheckBox("Nüfus Yoğunluğu");

        Label riskLabel = new Label("Kentsel dönüşüm eşiği");
        Slider riskSlider = new Slider(0, 100, 70);
        riskSlider.setShowTickLabels(true);
        riskSlider.setShowTickMarks(true);
        riskSlider.setMajorTickUnit(25);

        Label riskValueLabel = new Label("Min Risk Skoru: 70");
        riskSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            int val = newVal.intValue();
            riskValueLabel.setText("Min Risk Skoru: " + val);
        });

        Button applyFilters = new Button("Filtreleri Uygula");
        applyFilters.setMaxWidth(Double.MAX_VALUE);

        // Haritayı Yenile butonu - gri alan sorununu manuel düzeltmek için
        Button refreshMapButton = new Button("🔄 Haritayı Yenile");
        refreshMapButton.setMaxWidth(Double.MAX_VALUE);
        refreshMapButton.setStyle("-fx-background-color: #f59e0b; -fx-text-fill: white; -fx-font-weight: bold;");
        refreshMapButton.setOnAction(e -> {
            if (webEngine != null) {
                LOGGER.info("Manuel harita yenileme tetiklendi");
                webEngine.executeScript("if (typeof window.forceRefreshMap === 'function') { window.forceRefreshMap(); }");
            }
        });

        VBox left = new VBox(10,
                filterLabel,
                new Separator(),
                ilceLabel, ilceComboBox,
                mahalleLabel, mahalleCombo,
                new Separator(),
                layerLabel,
                cbBuildingRisk,
                cbRenewalZones,
                cbWms,
                cbInfra,
                cbRoadClosure,
                cbPopulation,
                new Separator(),
                riskLabel,
                riskSlider,
                riskValueLabel,
                applyFilters,
                new Separator(),
                refreshMapButton
        );
        left.setPadding(new Insets(10));
        left.setPrefWidth(260);
        left.setStyle("-fx-background-color: #f5f7fa;");

        return left;
    }

    // ORTA (HARİTA) ALANI - WebView + Leaflet tabanlı harita
    private WebView buildMapArea() {
        webView = new WebView();
        webEngine = webView.getEngine();

        // IBB MapServer'dan tile URL'sini çek
        String ibbTileUrl = getIbbTileUrl();
        LOGGER.info("IBB Tile URL (buildMapArea'da): " + ibbTileUrl);

        // HTML string'i yükle (tile URL'yi parametre olarak geç)
        webEngine.loadContent(getMapHTML(ibbTileUrl));

        // loadContent() sonrası, harita init'ini tetikle (Java tarafından)
        // loadWorker stateProperty listener'da yapılacak

        return webView;
    }

    // ============ LEAFLET HARITA HTML ============
    private String getMapHTML(String ibbTileUrl) {
        // ibbTileUrl null ise fallback
        if (ibbTileUrl == null || ibbTileUrl.isEmpty()) {
            ibbTileUrl = "https://api.ibb.gov.tr/cbsaltlik/arcgis/rest/services/MAKS/YAPI_YOGUNLUK/MapServer/tile/{z}/{y}/{x}";
        }

        // JavaScript'te kullanılacak tile URL'sini escape et
        String escapedUrl = ibbTileUrl.replace("'", "\\'");

        return "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "    <meta charset=\"utf-8\" />\n" +
                "    <link rel=\"stylesheet\" href=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.css\" />\n" +
                "    <script src=\"https://unpkg.com/leaflet@1.9.4/dist/leaflet.js\"></script>\n" +
                "    <style>\n" +
                "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
                "        html, body { width: 100%; height: 100%; overflow: hidden; }\n" +
                "        #map { width: 100%; height: 100%; display: block; background-color: white; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div id=\"map\"></div>\n" +
                "    <script>\n" +
                "        // ============ GLOBAL STATE ============\n" +
                "        var map = null;\n" +
                "        var osmLayer = null;\n" +
                "        var ibbLayer = null;\n" +
                "        var mapInitialized = false;\n" +
                "        var IBB_TILE_URL = '" + escapedUrl + "';\n" +
                "\n" +
                "        console.log(\"[INIT] IBB_TILE_URL = \" + IBB_TILE_URL);\n" +
                "\n" +
                "        // ============ LEAFLET HARITA İNİTİALİZASYONU ============\n" +
                "        function initMapDelayed() {\n" +
                "            if (mapInitialized) {\n" +
                "                console.warn(\"[LEAFLET] Map already initialized\");\n" +
                "                return;\n" +
                "            }\n" +
                "\n" +
                "            var mapContainer = document.getElementById('map');\n" +
                "            if (!mapContainer) {\n" +
                "                console.error(\"[LEAFLET] Map container not found\");\n" +
                "                return;\n" +
                "            }\n" +
                "\n" +
                "            console.log(\"[LEAFLET] Creating map...\");\n" +
                "\n" +
                "            // Harita oluştur - İstanbul'a ortalanmış\n" +
                "            map = L.map('map', {\n" +
                "                preferCanvas: true,\n" +
                "                attributionControl: true,\n" +
                "                zoomControl: true\n" +
                "            }).setView([41.0082, 28.9784], 11);\n" +
                "\n" +
                "            console.log(\"[LEAFLET] Map object created\");\n" +
                "\n" +
                "            // ============ OSM BASE LAYER ============\n" +
                "            osmLayer = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {\n" +
                "                attribution: '&copy; OpenStreetMap contributors',\n" +
                "                subdomains: ['a', 'b', 'c'],\n" +
                "                maxZoom: 19,\n" +
                "                minZoom: 1,\n" +
                "                crossOrigin: 'anonymous',\n" +
                "                keepBuffer: 8,\n" +
                "                updateWhenIdle: true,\n" +
                "                updateWhenZooming: true,\n" +
                "                fadeAnimation: false,\n" +
                "                zoomAnimation: false,\n" +
                "                errorTileUrl: 'data:image/svg+xml,<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"256\" height=\"256\"><rect fill=\"%23ffffff\" width=\"256\" height=\"256\"/></svg>',\n" +
                "                zIndex: 1\n" +
                "            }).addTo(map);\n" +
                "\n" +
                "            // OSM tile error logging\n" +
                "            osmLayer.on('tileerror', function(err) {\n" +
                "                if (err && err.tile) {\n" +
                "                    console.error(\"[OSM TILE ERROR]\", {\n" +
                "                        url: err.tile.src,\n" +
                "                        coords: err.coords\n" +
                "                    });\n" +
                "                }\n" +
                "            });\n" +
                "\n" +
                "            console.log(\"[LEAFLET] OSM layer added\");\n" +
                "\n" +
                "            // ============ MAP EVENT LISTENERS (Gri alan temizlemek için) ============\n" +
                "            map.on('moveend', function() {\n" +
                "                setTimeout(function() {\n" +
                "                    map.invalidateSize(false);\n" +
                "                }, 100);\n" +
                "            });\n" +
                "\n" +
                "            map.on('zoomend', function() {\n" +
                "                setTimeout(function() {\n" +
                "                    map.invalidateSize(false);\n" +
                "                }, 100);\n" +
                "            });\n" +
                "\n" +
                "            // ============ IBB LAYER (ilk başta null) ============\n" +
                "            window.ibbLayer = null;\n" +
                "\n" +
                "            // ============ EXAMPLE MARKERS ============\n" +
                "            // İstanbul\n" +
                "            L.marker([41.0082, 28.9784]).addTo(map)\n" +
                "                .bindPopup('<b>İstanbul</b><br>Marmara Bölgesi')\n" +
                "                .openPopup();\n" +
                "\n" +
                "            // Ankara\n" +
                "            L.marker([39.9334, 32.8597]).addTo(map)\n" +
                "                .bindPopup('<b>Ankara</b><br>Başkent');\n" +
                "\n" +
                "            // İzmir\n" +
                "            L.marker([38.4161, 27.1330]).addTo(map)\n" +
                "                .bindPopup('<b>İzmir</b><br>Ege Bölgesi');\n" +
                "\n" +
                "            mapInitialized = true;\n" +
                "            console.log(\"[LEAFLET] Initialization complete\");\n" +
                "\n" +
                "            // ============ CONTINUOUS INVALIDATE SIZE (Gri alan fix) ============\n" +
                "            var invalidateInterval = setInterval(function() {\n" +
                "                if (map && mapInitialized) {\n" +
                "                    map.invalidateSize(false);\n" +
                "                }\n" +
                "            }, 500);\n" +
                "\n" +
                "            // 5 saniye sonra durdur\n" +
                "            setTimeout(function() {\n" +
                "                clearInterval(invalidateInterval);\n" +
                "                console.log(\"[LEAFLET] Continuous invalidateSize stopped\");\n" +
                "            }, 5000);\n" +
                "        }\n" +
                "\n" +
                "        // ============ IBB REST LAYER TOGGLE ============\n" +
                "        window.toggleIbbRestLayer = function() {\n" +
                "            if (!mapInitialized || !map) {\n" +
                "                console.warn(\"[IBB] Map not initialized yet\");\n" +
                "                return;\n" +
                "            }\n" +
                "\n" +
                "            console.log(\"[IBB] Toggle called\");\n" +
                "\n" +
                "            if (!window.ibbLayer) {\n" +
                "                console.log(\"[IBB] Creating ArcGIS REST layer...\");\n" +
                "                console.log(\"[IBB] Using tile URL: \" + IBB_TILE_URL);\n" +
                "                try {\n" +
                "                    window.ibbLayer = L.tileLayer(IBB_TILE_URL, {\n" +
                "                        opacity: 0.65,\n" +
                "                        maxZoom: 18,\n" +
                "                        minZoom: 10,\n" +
                "                        crossOrigin: 'anonymous',\n" +
                "                        keepBuffer: 2,\n" +
                "                        updateWhenIdle: false,\n" +
                "                        updateWhenZooming: false,\n" +
                "                        attribution: '&copy; İBB CBS',\n" +
                "                        tileSize: 256,\n" +
                "                        zIndex: 10\n" +
                "                    });\n" +
                "\n" +
                "                    window.ibbLayer.on('tileerror', function(err) {\n" +
                "                        if (err && err.tile) {\n" +
                "                            console.error(\"[IBB TILE ERROR] URL: \" + err.tile.src + \" | Status: \" + (err.tile.status || 'unknown') + \" | Coords: \" + JSON.stringify(err.coords));\n" +
                "                        }\n" +
                "                    });\n" +
                "\n" +
                "                    window.ibbLayer.on('tileload', function(err) {\n" +
                "                        console.log(\"[IBB TILE LOADED] SUCCESS\");\n" +
                "                    });\n" +
                "\n" +
                "                    window.ibbLayer.on('loading', function() {\n" +
                "                        console.log(\"[IBB] Loading tiles...\");\n" +
                "                    });\n" +
                "\n" +
                "                    window.ibbLayer.on('error', function(err) {\n" +
                "                        console.error(\"[IBB LAYER ERROR]\", err);\n" +
                "                    });\n" +
                "\n" +
                "                    // Timeout kontrol - 10 saniye sonra hala yüklenmezse uyar\n" +
                "                    setTimeout(function() {\n" +
                "                        if (!map.hasLayer(window.ibbLayer)) return;\n" +
                "                        var tiles = document.querySelectorAll('img[src*=\"YAPI_YOGUNLUK\"]');\n" +
                "                        console.log(\"[IBB] Loaded tiles count: \" + tiles.length);\n" +
                "                        if (tiles.length === 0) {\n" +
                "                            console.error(\"[IBB] WARNING: No tiles loaded after 10 seconds\");\n" +
                "                        }\n" +
                "                    }, 10000);\n" +
                "\n" +
                "                    window.ibbLayer.addTo(map);\n" +
                "                    console.log(\"[IBB] Layer created and added\");\n" +
                "\n" +
                "                } catch (e) {\n" +
                "                    console.error(\"[IBB] Layer creation failed:\", e.message);\n" +
                "                    return;\n" +
                "                }\n" +
                "            } else {\n" +
                "                if (map.hasLayer(window.ibbLayer)) {\n" +
                "                    map.removeLayer(window.ibbLayer);\n" +
                "                    console.log(\"[IBB] Layer removed\");\n" +
                "                } else {\n" +
                "                    map.addLayer(window.ibbLayer);\n" +
                "                    console.log(\"[IBB] Layer added\");\n" +
                "                }\n" +
                "            }\n" +
                "\n" +
                "            setTimeout(function() {\n" +
                "                if (map) {\n" +
                "                    map.invalidateSize(false);\n" +
                "                }\n" +
                "            }, 100);\n" +
                "        };\n" +
                "\n" +
                "        // Alias (Java tarafından toggleIbbRestLayerSafe() çağrılacak)\n" +
                "        window.toggleIbbRestLayerSafe = window.toggleIbbRestLayer;\n" +
                "\n" +
                "        // ============ DISTRICT LOADER ============\n" +
                "        window.setDistrict = function(districtName) {\n" +
                "            if (!mapInitialized || !map) {\n" +
                "                console.warn(\"[DISTRICT] Map not initialized\");\n" +
                "                return;\n" +
                "            }\n" +
                "            console.log(\"[DISTRICT] Loading district:\", districtName);\n" +
                "        };\n" +
                "\n" +
                "        // ============ MANUAL REFRESH ============\n" +
                "        window.forceRefreshMap = function() {\n" +
                "            if (map) {\n" +
                "                map.invalidateSize(true);\n" +
                "            }\n" +
                "        };\n" +
                "\n" +
                "        // ============ DEBUG ============\n" +
                "        window.debugMapState = function() {\n" +
                "            if (!map) {\n" +
                "                console.log(\"[DEBUG] Map is null\");\n" +
                "                return;\n" +
                "            }\n" +
                "            var size = map.getSize();\n" +
                "            console.log(\"[DEBUG] Map state:\", {\n" +
                "                center: map.getCenter(),\n" +
                "                zoom: map.getZoom(),\n" +
                "                size: { width: size.x, height: size.y },\n" +
                "                osmLayerActive: osmLayer && map.hasLayer(osmLayer),\n" +
                "                ibbLayerActive: window.ibbLayer && map.hasLayer(window.ibbLayer)\n" +
                "            });\n" +
                "        };\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    // SAĞ PANEL (detay bilgileri)
    private VBox buildRightPanel() {
        Label detailTitle = new Label("Seçilen Bölge / Bina");
        detailTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        areaNameLabel = new Label("Bölge: -");
        ilceMahalleLabel = new Label("İlçe/Mahalle: -");

        buildingStatsLabel = new Label("Toplam bina: -\nEski (2000 öncesi): -\n5–8 kat arası: -");
        damageStatsLabel = new Label(
                "Tahmini Hasar (Mw 7.5):\n" +
                        " • Çok ağır + ağır: -\n" +
                        " • Orta: -\n" +
                        " • Hafif: -"
        );
        populationStatsLabel = new Label(
                "Nüfus / Barınma:\n" +
                        " • Gece nüfusu: -\n" +
                        " • Tahmini hane: -\n" +
                        " • Geçici barınma ihtiyacı: -"
        );
        renewalAdviceLabel = new Label(
                "Kentsel Dönüşüm Önerisi:\n" +
                        " • Öncelik: -\n" +
                        " • Öneri tipi: -\n" +
                        " • Gerekçe: -"
        );

        Button reportButton = new Button("Bu Bölgeyi Raporla");
        reportButton.setMaxWidth(Double.MAX_VALUE);
        reportButton.setOnAction(e -> {
            try {
                String script = "if(typeof window.mapBridge !== 'undefined' && typeof window.mapBridge.createReport === 'function') {" +
                        "window.mapBridge.createReport();" +
                        "}";
                webEngine.executeScript(script);
            } catch (Exception ex) {
                LOGGER.warning("Rapor oluşturma hatası: " + ex.getMessage());
                areaNameLabel.setText("✗ Rapor oluşturulurken hata!");
            }
        });

        VBox right = new VBox(10,
                detailTitle,
                new Separator(),
                areaNameLabel,
                ilceMahalleLabel,
                new Separator(),
                buildingStatsLabel,
                new Separator(),
                damageStatsLabel,
                new Separator(),
                populationStatsLabel,
                new Separator(),
                renewalAdviceLabel,
                new Separator(),
                reportButton
        );
        right.setPadding(new Insets(10));
        right.setPrefWidth(300);
        right.setStyle("-fx-background-color: #f9fafb;");

        return right;
    }


    private void loadDistrictInWebView(String districtFolderName) {
        if (webEngine == null) {
            return;
        }
        try {
            String script = "if (typeof window.setDistrict === 'function') { " +
                    "window.setDistrict('" + districtFolderName.replace("'", "\\'") + "');" +
                    "} else { console.warn('setDistrict fonksiyonu tanımlı değil'); }";
            webEngine.executeScript(script);
            ilceMahalleLabel.setText("İlçe/Mahalle: " + districtFolderName + " / -");
        } catch (Exception e) {
            LOGGER.severe("District yüklenirken hata: " + e.getMessage());
            areaNameLabel.setText("Bölge: -");
            ilceMahalleLabel.setText("İlçe/Mahalle: " + districtFolderName + " / -");
            buildingStatsLabel.setText("İlçe klasörü bulunamadı veya yüklenemedi.");
        }
    }

    @Override
    public void stop() throws Exception {
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
