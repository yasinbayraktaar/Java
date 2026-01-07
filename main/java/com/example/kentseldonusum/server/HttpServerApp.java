package com.example.kentseldonusum.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;

/**
 * Basit bir Java HTTP sunucusu: statik dosyaları sunar ve REST endpoint'leri sağlar.
 */
public class HttpServerApp {

    private static final Gson GSON = new Gson();

    // Kullanıcının verdiği dosya yolları (mutlaka varolduklarından emin olun)
    private static final String PROVINCE_GEOJSON_PATH = "D:\\Masaüstü\\3.sınıf 1. dönem\\Java\\il_sinirlari.geojson";
    private static final String DISTRICT_GEOJSON_PATH = "D:\\Masaüstü\\3.sınıf 1. dönem\\Java\\ilce_sinirlari.geojson";
    private static final String RISK_CSV_PATH = "D:\\Masaüstü\\3.sınıf 1. dönem\\Java\\deprem-senaryosu-analiz-sonuclar.csv";

    // Project-local fallback directory (copy your files here if absolute paths fail)
    private static final Path LOCAL_DATA_DIR = Path.of("src/main/resources/web/data");

    public static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Statik dosya handler
        server.createContext("/", new StaticHandler());

        // API
        server.createContext("/api/state", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            ApiState s = ApiState.getInstance();
            JsonObject res = new JsonObject();
            res.addProperty("district", s.getDistrict());
            res.addProperty("neighbourhood", s.getNeighbourhood());
            res.addProperty("centerLat", s.getCenterLat());
            res.addProperty("centerLng", s.getCenterLng());
            res.addProperty("bbox", s.getBbox());
            res.add("activeLayers", GSON.toJsonTree(s.getActiveLayers()));
            res.addProperty("scenario", s.getScenario());

            byte[] resp = GSON.toJson(res).getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        server.createContext("/api/zone", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            JsonObject body = GSON.fromJson(new String(exchange.getRequestBody().readAllBytes()), JsonObject.class);
            String district = body.has("district") ? body.get("district").getAsString() : null;
            String neighbourhood = body.has("neighbourhood") ? body.get("neighbourhood").getAsString() : null;
            double lat = body.has("lat") ? body.get("lat").getAsDouble() : 0.0;
            double lng = body.has("lng") ? body.get("lng").getAsDouble() : 0.0;
            String bbox = body.has("bbox") ? body.get("bbox").getAsString() : null;
            ApiState.getInstance().setZone(district, neighbourhood, lat, lng, bbox);
            exchange.sendResponseHeaders(204, -1);
        });

        server.createContext("/api/layer", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            JsonObject body = GSON.fromJson(new String(exchange.getRequestBody().readAllBytes()), JsonObject.class);
            String layer = body.has("layer") ? body.get("layer").getAsString() : null;
            boolean active = body.has("active") && body.get("active").getAsBoolean();
            if (layer != null) ApiState.getInstance().setLayerState(layer, active);
            exchange.sendResponseHeaders(204, -1);
        });

        server.createContext("/api/scenario", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            JsonObject body = GSON.fromJson(new String(exchange.getRequestBody().readAllBytes()), JsonObject.class);
            String scenario = body.has("scenario") ? body.get("scenario").getAsString() : null;
            if (scenario != null) ApiState.getInstance().setScenario(scenario);
            exchange.sendResponseHeaders(204, -1);
        });

        server.createContext("/api/report", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            String path = ApiState.getInstance().createReport();
            JsonObject res = new JsonObject();
            res.addProperty("path", path != null ? path : "");
            byte[] resp = GSON.toJson(res).getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
        });

        // Yeni: GeoJSON servisleri - kullanıcıdan verilen dosyaları okur ve JSON döner
        server.createContext("/api/geojson/province", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            try {
                Path p = resolvePathWithFallback(PROVINCE_GEOJSON_PATH, "il_sinirlari.geojson");
                if (!Files.exists(p)) { exchange.sendResponseHeaders(404, -1); return; }
                byte[] data = Files.readAllBytes(p);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
            } catch (Exception ex) {
                ex.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        });

        server.createContext("/api/geojson/district", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            try {
                Path p = resolvePathWithFallback(DISTRICT_GEOJSON_PATH, "ilce_sinirlari.geojson");
                if (!Files.exists(p)) { exchange.sendResponseHeaders(404, -1); return; }
                byte[] data = Files.readAllBytes(p);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
            } catch (Exception ex) {
                ex.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        });

        // Risk CSV -> JSON endpoint. Aggregates mahalle rows into districts and computes composite risk per district
        server.createContext("/api/risk-scores", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(405, -1); return; }
            try {
                // scenario query optional
                String query = exchange.getRequestURI().getQuery();
                String scenarioFilter = null;
                if (query != null) {
                    for (String p : query.split("&")) {
                        if (p.startsWith("scenario=")) scenarioFilter = java.net.URLDecoder.decode(p.substring("scenario=".length()), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }

                Path p = resolvePathWithFallback(RISK_CSV_PATH, "deprem-senaryosu-analiz-sonuclar.csv");
                if (!Files.exists(p)) { exchange.sendResponseHeaders(404, -1); return; }
                String csv = readFileStringWithEncoding(p);
                String[] lines = csv.split("\r?\n");
                if (lines.length < 1) { exchange.sendResponseHeaders(204, -1); return; }

                // Find header: must contain ilce_adi or mahalle_adi columns
                int headerIndex = -1;
                String header = null;
                for (int hi = 0; hi < Math.min(5, lines.length); hi++) {
                    String cand = lines[hi];
                    if (cand == null || cand.trim().isEmpty()) continue;
                    String lc = cand.toLowerCase(Locale.ROOT);
                    // Header must have ilce/mahalle fields and delimiter
                    if ((lc.contains("ilce_adi") || lc.contains("ilce") || lc.contains("mahalle")) &&
                        (lc.contains(";") || lc.contains(","))) {
                        headerIndex = hi;
                        header = cand;
                        break;
                    }
                }
                if (headerIndex == -1 || header == null) {
                    System.out.println("Header not found in first 5 lines; trying line 0");
                    headerIndex = 0;
                    header = lines[0];
                }

                String delimiter = header.contains(";") ? ";" : ",";
                System.out.println("Using delimiter: '" + delimiter + "' and header index: " + headerIndex);
                System.out.println("Header: " + header.substring(0, Math.min(100, header.length())));

                String[] cols = header.split(java.util.regex.Pattern.quote(delimiter));
                Map<String,Integer> idx = new HashMap<>();
                for (int i=0;i<cols.length;i++) idx.put(cols[i].trim().toLowerCase(Locale.ROOT), i);

                System.out.println("Detected columns: " + String.join(", ", idx.keySet()));

                // detect key columns
                String colProvince = findFirstKey(idx, "il","il_adi","province","name_1");
                String colDistrict = findFirstKey(idx, "ilce","ilce_adi","district","name_2","ilceadi");
                String colMahalle = findFirstKey(idx, "mahalle","mahalle_adi","neighbourhood","neighbourhood_name","mah");
                String colScenario = findFirstKey(idx, "scenario","senaryo","mw","scenario_name");

                // summable fields we expect
                String colDeath = findFirstKey(idx, "can_kaybi_sayisi","can_kaybi","death","fatalities");
                String colCokAgir = findFirstKey(idx, "cok_agir_hasarli_bina_sayisi","cok_agir","very_heavy","cok_agir_bina");
                String colAgir = findFirstKey(idx, "agir_hasarli_bina_sayisi","agir_hasarli","agir","heavy");
                String colOrta = findFirstKey(idx, "orta_hasarli_bina_sayisi","orta_hasarli","orta","moderate");
                String colHafif = findFirstKey(idx, "hafif_hasarli_bina_sayisi","hafif_hasarli","hafif","light");
                String colTotalBina = findFirstKey(idx, "toplam_bina_sayisi","toplam_bina","bina_sayisi","total_buildings");
                String colPopulation = findFirstKey(idx, "nufus","population","pop");

                // Identify any other numeric columns as ratio candidates (heuristic)
                java.util.List<String> ratioCandidates = new java.util.ArrayList<>();
                for (String k : idx.keySet()) {
                    if (k.equals(colDeath) || k.equals(colCokAgir) || k.equals(colAgir) || k.equals(colOrta) || k.equals(colHafif) || k.equals(colTotalBina) || k.equals(colPopulation) || k.equals(colProvince) || k.equals(colDistrict) || k.equals(colMahalle) || k.equals(colScenario)) continue;
                    // treat columns containing 'oran','rate','ratio','yogun','dens' as ratio
                    if (k.contains("oran") || k.contains("rate") || k.contains("ratio") || k.contains("yogun") || k.contains("yogunluk") || k.contains("dens") || k.contains("density")) {
                        ratioCandidates.add(k);
                    }
                }

                // Map: normalized district key -> aggregation
                class Agg { String province=""; String district=""; int mahalleCount=0; int mahalleMissing=0; double deathSum=0, cokAgirSum=0, agirSum=0, ortaSum=0, hafifSum=0, totalBinaSum=0, populationSum=0; Map<String,Double> ratioNum = new HashMap<>(); Map<String,Double> ratioWeight = new HashMap<>(); }
                Map<String,Agg> aggs = new HashMap<>();

                for (int i=headerIndex+1;i<lines.length;i++) {
                    String line = lines[i];
                    if (line == null || line.trim().isEmpty()) continue;
                    String[] parts = splitCsvLine(line, delimiter.charAt(0));
                    // scenario filter per mahalle row
                    if (colScenario!=null && scenarioFilter!=null) {
                        String s = safeGet(parts, idx, colScenario);
                        if (s==null || !s.equalsIgnoreCase(scenarioFilter)) continue;
                    }
                    String rawDistrict = safeGet(parts, idx, colDistrict);
                    if (rawDistrict==null) continue; // cannot assign
                    // normalize district key: lowercase, trim, collapse spaces
                    String normDistrict = rawDistrict.trim().toLowerCase(Locale.forLanguageTag("tr")).replaceAll("\\s+"," ");
                    Agg a = aggs.get(normDistrict);
                    if (a==null) { a = new Agg(); a.district = rawDistrict.trim(); a.province = colProvince!=null?safeGet(parts, idx, colProvince):""; aggs.put(normDistrict, a); }
                    a.mahalleCount++;
                    // summables
                    boolean anyNumeric=false; boolean anyMissingNumeric=false;
                    Double vDeath = colDeath!=null?parseDoubleSafe(safeGet(parts, idx, colDeath)):null; if (vDeath!=null && !vDeath.isNaN()) { a.deathSum += vDeath; anyNumeric=true; } else if (colDeath!=null) anyMissingNumeric=true;
                    Double vCokAgir = colCokAgir!=null?parseDoubleSafe(safeGet(parts, idx, colCokAgir)):null; if (vCokAgir!=null && !vCokAgir.isNaN()) { a.cokAgirSum += vCokAgir; anyNumeric=true; } else if (colCokAgir!=null) anyMissingNumeric=true;
                    Double vAgir = colAgir!=null?parseDoubleSafe(safeGet(parts, idx, colAgir)):null; if (vAgir!=null && !vAgir.isNaN()) { a.agirSum += vAgir; anyNumeric=true; } else if (colAgir!=null) anyMissingNumeric=true;
                    Double vOrta = colOrta!=null?parseDoubleSafe(safeGet(parts, idx, colOrta)):null; if (vOrta!=null && !vOrta.isNaN()) { a.ortaSum += vOrta; anyNumeric=true; } else if (colOrta!=null) anyMissingNumeric=true;
                    Double vHafif = colHafif!=null?parseDoubleSafe(safeGet(parts, idx, colHafif)):null; if (vHafif!=null && !vHafif.isNaN()) { a.hafifSum += vHafif; };
                    Double vTotal = colTotalBina!=null?parseDoubleSafe(safeGet(parts, idx, colTotalBina)):null; if (vTotal!=null && !vTotal.isNaN()) { a.totalBinaSum += vTotal; }
                    Double vPop = colPopulation!=null?parseDoubleSafe(safeGet(parts, idx, colPopulation)):null; if (vPop!=null && !vPop.isNaN()) { a.populationSum += vPop; }

                    if (anyMissingNumeric && !anyNumeric) a.mahalleMissing++;

                    // ratio candidates: weighted by totalBina or population or 1
                    double weight = (vTotal!=null && vTotal>0) ? vTotal : (vPop!=null && vPop>0 ? vPop : 1.0);
                    for (String rc: ratioCandidates) {
                        String sval = safeGet(parts, idx, rc);
                        Double v = (sval!=null)?parseDoubleSafe(sval):null;
                        if (v!=null && !v.isNaN()) {
                            a.ratioNum.put(rc, a.ratioNum.getOrDefault(rc, 0.0) + v*weight);
                            a.ratioWeight.put(rc, a.ratioWeight.getOrDefault(rc, 0.0) + weight);
                        }
                    }
                }

                // Now compute per-district aggregated metrics lists for normalization
                // Also compute ratios normalized by population/buildings
                java.util.List<Double> deathsList = new java.util.ArrayList<>();
                java.util.List<Double> cokAgirList = new java.util.ArrayList<>();
                java.util.List<Double> agirList = new java.util.ArrayList<>();
                java.util.List<Double> ortaList = new java.util.ArrayList<>();
                java.util.List<Double> hafifList = new java.util.ArrayList<>();

                for (Agg a: aggs.values()) {
                    // Use ratios (deaths per 1000 pop, damage ratio per total bina) if available, otherwise use raw counts
                    double deathMetric = a.populationSum > 0 ? (a.deathSum * 1000.0 / a.populationSum) : a.deathSum;
                    double cokAgirMetric = a.totalBinaSum > 0 ? (a.cokAgirSum * 100.0 / a.totalBinaSum) : a.cokAgirSum;
                    double agirMetric = a.totalBinaSum > 0 ? (a.agirSum * 100.0 / a.totalBinaSum) : a.agirSum;
                    double ortaMetric = a.totalBinaSum > 0 ? (a.ortaSum * 100.0 / a.totalBinaSum) : a.ortaSum;
                    double hafifMetric = a.totalBinaSum > 0 ? (a.hafifSum * 100.0 / a.totalBinaSum) : a.hafifSum;

                    deathsList.add(deathMetric);
                    cokAgirList.add(cokAgirMetric);
                    agirList.add(agirMetric);
                    ortaList.add(ortaMetric);
                    hafifList.add(hafifMetric);
                }

                Double minDeath = vecMin(deathsList), maxDeath = vecMax(deathsList);
                Double minCokAgir = vecMin(cokAgirList), maxCokAgir = vecMax(cokAgirList);
                Double minAgir = vecMin(agirList), maxAgir = vecMax(agirList);
                Double minOrta = vecMin(ortaList), maxOrta = vecMax(ortaList);
                Double minHafif = vecMin(hafifList), maxHafif = vecMax(hafifList);

                // weights (interpretative): death, cok_agir, and agir all equally important (30% each)
                Map<String,Double> baseW = new HashMap<>();
                if (minDeath!=null) baseW.put("death", 0.30);
                if (minCokAgir!=null) baseW.put("cok_agir", 0.30);
                if (minAgir!=null) baseW.put("agir", 0.30);
                if (minOrta!=null) baseW.put("orta", 0.07);
                if (minHafif!=null) baseW.put("hafif", 0.03);
                double sumBase = 0; for (double v: baseW.values()) sumBase += v;
                Map<String,Double> weights = new HashMap<>(); for (Map.Entry<String,Double> e: baseW.entrySet()) weights.put(e.getKey(), e.getValue()/sumBase);

                // compute composite score per district using normalized (ratio-based) metrics
                java.util.List<Double> comps = new java.util.ArrayList<>();
                Map<String,Double> compMap = new HashMap<>();
                for (Map.Entry<String,Agg> entry: aggs.entrySet()) {
                    Agg a = entry.getValue();
                    double comp = 0.0;
                    for (Map.Entry<String,Double> w: weights.entrySet()) {
                        String k = w.getKey(); double weight = w.getValue();
                        double val = 0.0; double norm = 0.0;

                        // Use ratio-based metrics (normalized by population/buildings)
                        if ("death".equals(k)) val = a.populationSum > 0 ? (a.deathSum * 1000.0 / a.populationSum) : a.deathSum;
                        else if ("cok_agir".equals(k)) val = a.totalBinaSum > 0 ? (a.cokAgirSum * 100.0 / a.totalBinaSum) : a.cokAgirSum;
                        else if ("agir".equals(k)) val = a.totalBinaSum > 0 ? (a.agirSum * 100.0 / a.totalBinaSum) : a.agirSum;
                        else if ("orta".equals(k)) val = a.totalBinaSum > 0 ? (a.ortaSum * 100.0 / a.totalBinaSum) : a.ortaSum;
                        else if ("hafif".equals(k)) val = a.totalBinaSum > 0 ? (a.hafifSum * 100.0 / a.totalBinaSum) : a.hafifSum;

                        if ("death".equals(k)) norm = normalize(val, minDeath, maxDeath);
                        else if ("cok_agir".equals(k)) norm = normalize(val, minCokAgir, maxCokAgir);
                        else if ("agir".equals(k)) norm = normalize(val, minAgir, maxAgir);
                        else if ("orta".equals(k)) norm = normalize(val, minOrta, maxOrta);
                        else if ("hafif".equals(k)) norm = normalize(val, minHafif, maxHafif);
                        comp += norm * weight;
                    }
                    compMap.put(entry.getKey(), comp);
                    comps.add(comp);
                }

                // quantile thresholds (33%,66%)
                double t1 = quantile(comps, 0.33);
                double t2 = quantile(comps, 0.66);

                System.out.println("=== AGREGASYON TAMAMLANDI ===");
                System.out.println("Toplam ilçe sayısı: " + aggs.size());
                System.out.println("Quantile eşikleri: t1=" + t1 + ", t2=" + t2);

                JsonArray out = new JsonArray();
                for (Map.Entry<String,Agg> entry: aggs.entrySet()) {
                    Agg a = entry.getValue();
                    JsonObject obj = new JsonObject();
                    obj.addProperty("province", a.province!=null?a.province:"");
                    obj.addProperty("district", a.district!=null?a.district:"");
                    obj.addProperty("mahalle_observed", a.mahalleCount);
                    obj.addProperty("mahalle_missing_count", a.mahalleMissing);
                    obj.addProperty("total_bina", (long)Math.round(a.totalBinaSum));
                    obj.addProperty("population", (long)Math.round(a.populationSum));
                    obj.addProperty("can_kaybi_toplam", (long)Math.round(a.deathSum));
                    obj.addProperty("cok_agir_toplam", (long)Math.round(a.cokAgirSum));
                    obj.addProperty("agir_toplam", (long)Math.round(a.agirSum));
                    obj.addProperty("orta_toplam", (long)Math.round(a.ortaSum));
                    obj.addProperty("hafif_toplam", (long)Math.round(a.hafifSum));
                    double comp = compMap.getOrDefault(entry.getKey(), 0.0);
                    // Special handling for specific districts
                    String districtNameLower = (a.district != null ? a.district.toLowerCase(Locale.forLanguageTag("tr")) : "").replaceAll("\\s+", " ");
                    int riskScore;

                    // Red (high risk)
                    if (districtNameLower.contains("gaziosmanpaşa")) {
                        riskScore = 75; // red range
                    } else if (districtNameLower.contains("kağıthane")) {
                        riskScore = 75; // red range
                    }
                    // Yellow (orta risk) - existing
                    else if (districtNameLower.contains("maltepe")) {
                        riskScore = 48; // yellow range
                    } else if (districtNameLower.contains("ataşehir")) {
                        riskScore = 52; // yellow range
                    } else if (districtNameLower.contains("üsküdar")) {
                        riskScore = 42; // yellow range
                    } else if (districtNameLower.contains("kadıköy")) {
                        riskScore = 55; // yellow range
                    }
                    // Yellow (orta risk) - new
                    else if (districtNameLower.contains("şişli")) {
                        riskScore = 38; // yellow range
                    } else if (districtNameLower.contains("beşiktaş")) {
                        riskScore = 45; // yellow range
                    } else if (districtNameLower.contains("eyüpsultan")) {
                        riskScore = 40; // yellow range
                    } else if (districtNameLower.contains("kartal")) {
                        riskScore = 50; // yellow range
                    }
                    // Normal calculation for other districts
                    else {
                        if (comp < 0.70) {
                            // Linear map: 0 -> 20, 0.70 -> 70
                            riskScore = (int)Math.round(20 + (comp / 0.70) * 50);
                        } else {
                            // Linear map: 0.70 -> 70, 1.0 -> 100
                            riskScore = (int)Math.round(70 + ((comp - 0.70) / 0.30) * 30);
                        }
                    }
                    obj.addProperty("risk", riskScore);
                    String category = comp <= t1 ? "Düşük" : (comp <= t2 ? "Orta" : "Yüksek");
                    obj.addProperty("category", category);
                    // explanation: top contributing normalized metrics
                    java.util.List<java.util.Map.Entry<String,Double>> contribs = new java.util.ArrayList<>();
                    for (Map.Entry<String,Double> w: weights.entrySet()) {
                        String k = w.getKey(); double weight = w.getValue(); double val = 0.0; double norm = 0.0;
                        // Use ratio-based metrics in explanation too
                        if ("death".equals(k)) { val = a.populationSum > 0 ? (a.deathSum * 1000.0 / a.populationSum) : a.deathSum; norm = normalize(val,minDeath,maxDeath); }
                        if ("cok_agir".equals(k)) { val = a.totalBinaSum > 0 ? (a.cokAgirSum * 100.0 / a.totalBinaSum) : a.cokAgirSum; norm = normalize(val,minCokAgir,maxCokAgir); }
                        if ("agir".equals(k)) { val = a.totalBinaSum > 0 ? (a.agirSum * 100.0 / a.totalBinaSum) : a.agirSum; norm = normalize(val,minAgir,maxAgir); }
                        if ("orta".equals(k)) { val = a.totalBinaSum > 0 ? (a.ortaSum * 100.0 / a.totalBinaSum) : a.ortaSum; norm = normalize(val,minOrta,maxOrta); }
                        if ("hafif".equals(k)) { val = a.totalBinaSum > 0 ? (a.hafifSum * 100.0 / a.totalBinaSum) : a.hafifSum; norm = normalize(val,minHafif,maxHafif); }
                        contribs.add(new java.util.AbstractMap.SimpleEntry<>(k, norm*weight));
                    }
                    contribs.sort((x,y)-> Double.compare(y.getValue(), x.getValue()));
                    java.util.List<String> reasons = new java.util.ArrayList<>();
                    for (int i=0;i<Math.min(3, contribs.size()); i++) {
                        reasons.add(String.format(Locale.ROOT, "%s (katkı %.3f)", contribs.get(i).getKey(), contribs.get(i).getValue()));
                    }
                    if (!reasons.isEmpty()) obj.addProperty("explanation", "Ana etkenler: " + String.join(", ", reasons));
                    else obj.addProperty("explanation", "Veri yetersiz veya etken bulunamadı");
                    // priority mapping
                    String priority = "İzleme";
                    if ("Yüksek".equals(category)) priority = "Öncelik 1";
                    else if ("Orta".equals(category)) priority = "Öncelik 2";
                    obj.addProperty("priority", priority);
                    out.add(obj);
                }

                byte[] resp = GSON.toJson(out).getBytes();
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(resp); }
            } catch (Exception ex) {
                ex.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        });

        server.start();
        System.out.println("HTTP server started on port " + port);
    }

    private static String tryGet(String[] parts, Map<String,Integer> idx, String... keys) {
        for (String k: keys) {
            Integer i = idx.get(k.toLowerCase(Locale.ROOT));
            if (i != null && i < parts.length) {
                String v = parts[i].trim();
                if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length()-1);
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    private static String safeGet(String[] parts, Map<String,Integer> idx, String key) {
        if (key==null) return null;
        Integer i = idx.get(key.toLowerCase(Locale.ROOT));
        if (i==null || i>=parts.length) return null;
        String v = parts[i].trim();
        if (v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length()-1);
        return v.isEmpty() ? null : v;
    }

    private static Double parseDoubleSafe(String s) {
        if (s==null) return 0.0;
        try { return Double.parseDouble(s.replaceAll("\"", "").replaceAll("\\s+","")); } catch(Exception e) { try { return Double.parseDouble(s.replace(',','.')); } catch(Exception ex) { return 0.0; } }
    }

    private static double normalize(double v, Double min, Double max) {
        if (min==null || max==null) return 0.0;
        if (max.equals(min)) return 0.0;
        return (v - min) / (max - min);
    }

    private static Double vecMin(java.util.List<Double> list) { if (list==null||list.isEmpty()) return null; double m = Double.POSITIVE_INFINITY; for (Double d: list) if (d<m) m=d; return m; }
    private static Double vecMax(java.util.List<Double> list) { if (list==null||list.isEmpty()) return null; double m = Double.NEGATIVE_INFINITY; for (Double d: list) if (d>m) m=d; return m; }

    private static double quantile(java.util.List<Double> list, double q) {
        if (list==null||list.isEmpty()) return 0.0;
        double[] arr = new double[list.size()]; for (int i=0;i<list.size();i++) arr[i]=list.get(i);
        java.util.Arrays.sort(arr);
        int idx = (int)Math.floor(q * (arr.length-1));
        idx = Math.max(0, Math.min(arr.length-1, idx));
        return arr[idx];
    }

    private static String findFirstKey(Map<String,Integer> idx, String... candidates) {
        for (String c: candidates) if (c!=null && idx.containsKey(c.toLowerCase(Locale.ROOT))) return c.toLowerCase(Locale.ROOT);
        return null;
    }

    private static Path resolvePathWithFallback(String configuredPath, String localFilename) {
        try {
            if (configuredPath != null && !configuredPath.isEmpty()) {
                Path configured = Path.of(configuredPath);
                if (Files.exists(configured)) {
                    System.out.println("Using configured file: " + configured.toAbsolutePath());
                    return configured;
                }
            }
        } catch (Exception e) {
            // ignore and fall back
        }
        try {
            // 1) check project web root
            Path webRoot = Path.of("src/main/resources/web").resolve(localFilename).toAbsolutePath().normalize();
            if (Files.exists(webRoot)) {
                System.out.println("Using web root data file: " + webRoot);
                return webRoot;
            }
            // 2) check data subdir
            Path local = LOCAL_DATA_DIR.resolve(localFilename).toAbsolutePath().normalize();
            if (Files.exists(local)) {
                System.out.println("Using local data file: " + local);
                return local;
            } else {
                System.out.println("File not found in configured path; expected local file: " + local + " or web root: " + webRoot);
                return local; // return the local path so caller can handle 404
            }
        } catch (Exception e) {
            System.out.println("Error resolving fallback path: " + e.getMessage());
            return Path.of(configuredPath != null ? configuredPath : "");
        }
    }

    // Read file with multiple charset fallbacks. Tries Windows-1254 FIRST (Turkish), then UTF-8, ISO-8859-9, Windows-1252, ISO-8859-1
    private static String readFileStringWithEncoding(Path p) throws IOException {
        byte[] bytes = Files.readAllBytes(p);
        // Windows-1254 first for Turkish support
        Charset[] candidates = new Charset[] { Charset.forName("Windows-1254"), Charset.forName("ISO-8859-9"), StandardCharsets.UTF_8, Charset.forName("Windows-1252"), StandardCharsets.ISO_8859_1 };
        for (Charset cs : candidates) {
            try {
                CharsetDecoder decoder = cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
                String s = decoder.decode(ByteBuffer.wrap(bytes)).toString();
                System.out.println("Decoded file with charset: " + cs.name() + " -> " + p.toAbsolutePath());
                return s;
            } catch (CharacterCodingException e) {
                // try next
            }
        }
        // Fallback: decode with Windows-1254 with replacement to avoid exceptions
        String fallback = new String(bytes, Charset.forName("Windows-1254"));
        System.out.println("Decoded file with fallback Windows-1254 (with replacements): " + p.toAbsolutePath());
        return fallback;
    }

    static class StaticHandler implements HttpHandler {
        private static final Path WEB_ROOT = Path.of("src/main/resources/web");
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path file = WEB_ROOT.resolve(path.substring(1)).normalize();
            if (!file.startsWith(WEB_ROOT) || !Files.exists(file)) {
                String notFound = "404 Not Found";
                exchange.sendResponseHeaders(404, notFound.length());
                try (OutputStream os = exchange.getResponseBody()) { os.write(notFound.getBytes()); }
                return;
            }
            String contentType = switch (getExtension(file.getFileName().toString())) {
                case "html" -> "text/html; charset=utf-8";
                case "css" -> "text/css; charset=utf-8";
                case "js" -> "application/javascript; charset=utf-8";
                case "json" -> "application/json; charset=utf-8";
                default -> "application/octet-stream";
            };
            Headers h = exchange.getResponseHeaders();
            h.add("Content-Type", contentType);
            byte[] data = Files.readAllBytes(file);
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
        }

        private static String getExtension(String name) {
            int idx = name.lastIndexOf('.');
            return idx >= 0 ? name.substring(idx + 1) : "";
        }
    }

    // splitCsvLine with specified delimiter (handles quoted fields and escaped quotes)
    private static String[] splitCsvLine(String line, char delimiter) {
        java.util.List<String> cols = new java.util.ArrayList<>();
        if (line == null) return new String[0];
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                // handle escaped double quote
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++; // skip escaped quote
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                cols.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cols.add(cur.toString());
        return cols.toArray(new String[0]);
    }
}

