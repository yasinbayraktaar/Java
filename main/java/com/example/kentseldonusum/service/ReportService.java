package com.example.kentseldonusum.service;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.logging.Logger;

/**
 * Seçim ve harita durumunu JSON/PDF raporu olarak dışa aktar
 */
public class ReportService {

    private static final Logger LOGGER = Logger.getLogger(ReportService.class.getName());

    /**
     * Seçili bölge ve katman durumunu JSON raporu olarak kaydet
     *
     * @param district İlçe adı
     * @param neighbourhood Mahalle adı
     * @param scenario Seçilen senaryo (ör: "Mw 7.5 - Gece")
     * @param centerLat Merkez enlem
     * @param centerLng Merkez boylam
     * @param bboxString Bbox string (ör: "28.5,41.0,29.0,41.5")
     * @param activeLayers Aktif katmanlar listesi
     * @return Kaydedilen dosyanın path'i
     */
    public static String createJsonReport(
            String district,
            String neighbourhood,
            String scenario,
            double centerLat,
            double centerLng,
            String bboxString,
            List<String> activeLayers) {

        try {
            JsonObject report = new JsonObject();

            // Temel bilgiler
            report.addProperty("reportType", "Selection Report");
            report.addProperty("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            report.addProperty("district", district != null ? district : "-");
            report.addProperty("neighbourhood", neighbourhood != null ? neighbourhood : "-");
            report.addProperty("scenario", scenario != null ? scenario : "-");

            // Konum bilgileri
            JsonObject location = new JsonObject();
            location.addProperty("centerLat", centerLat);
            location.addProperty("centerLng", centerLng);
            location.addProperty("bbox", bboxString);
            report.add("location", location);

            // Aktif katmanlar
            JsonArray layersJson = new JsonArray();
            if (activeLayers != null) {
                for (String layer : activeLayers) {
                    layersJson.add(layer);
                }
            }
            report.add("activeLayers", layersJson);

            // Dosya adı ve kaydetme
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = "report_" + (district != null ? district : "unknown") + "_" + timestamp + ".json";
            Path exportsPath = Paths.get("exports");
            Path reportPath = exportsPath.resolve(filename);

            Files.createDirectories(exportsPath);
            String jsonContent = new GsonBuilder().setPrettyPrinting().create().toJson(report);
            Files.writeString(reportPath, jsonContent);

            LOGGER.info("JSON raporu kaydedildi: " + reportPath);
            return reportPath.toString();

        } catch (Exception e) {
            LOGGER.severe("Rapor kaydedilirken hata: " + e.getMessage());
            return null;
        }
    }

    /**
     * PDF raporu oluştur (şimdilik opsiyonel - JSON export'u öncelikli)
     * PDF desteği için iLibreOffice API veya iText kütüphanesi gerekir.
     */
    public static String createPdfReport(
            String district,
            String neighbourhood,
            String scenario,
            double centerLat,
            double centerLng,
            String bboxString,
            List<String> activeLayers) {

        // TODO: PDF generation (iText5 veya benzeri kütüphane ile)
        LOGGER.info("PDF raporu henüz uygulanmadı; JSON raporu kullanılıyor.");
        return createJsonReport(district, neighbourhood, scenario, centerLat, centerLng, bboxString, activeLayers);
    }
}

