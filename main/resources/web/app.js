// Yeni app.js — district-only uygulaması
const api = {
  getState: () => fetch('/api/state').then(r => r.json()),
  postZone: (z) => fetch('/api/zone', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(z) }),
  postScenario: (s) => fetch('/api/scenario', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify(s) }),
  getProvinceGeojson: () => fetch('/api/geojson/province').then(r => { if(!r.ok) throw new Error('province geojson not found'); return r.json(); }),
  getDistrictGeojson: () => fetch('/api/geojson/district').then(r => { if(!r.ok) throw new Error('district geojson not found'); return r.json(); }),
  getRiskScores: () => fetch('/api/risk-scores').then(r => { if(!r.ok) throw new Error('risk csv not found'); return r.json(); }),
};

// DOM elements
const selectedNameEl = document.getElementById('selected-name');
const summaryNameEl = document.getElementById('summary-name');
const summaryScenarioEl = document.getElementById('summary-scenario');
const summaryRiskEl = document.getElementById('summary-risk');
const summaryPriorityEl = document.getElementById('summary-priority');
const reportBtn = document.getElementById('reportBtn');

const toggleProvince = document.getElementById('toggleProvince');
const toggleDistrict = document.getElementById('toggleDistrict');
const filterLow = document.getElementById('filterLow');
const filterMid = document.getElementById('filterMid');
const filterHigh = document.getElementById('filterHigh');
const candidateModeEl = document.getElementById('candidateMode');
const scenarioSelect = document.getElementById('scenarioSelect');
const applyScenario = document.getElementById('applyScenario');

// Map init
let map = L.map('map', {preferCanvas:true}).setView([41.0082, 28.9784], 11);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 }).addTo(map);

let provinceLayer = null;
let districtLayer = null;
let riskScores = []; // [{province,district,neighbourhood,risk}]
let selectedDistrictLayer = null;
let districtLayerMap = new Map(); // id -> layer

// Initial smart zoom to Istanbul
(function initialSmartZoom(){
  try { const istanbulBbox = [28.7,40.8,29.4,41.2]; map.fitBounds([[istanbulBbox[1],istanbulBbox[0]],[istanbulBbox[3],istanbulBbox[2]]], {padding:[40,40]}); } catch(e){}
})();

// Utility: get feature display name
function getFeatureName(properties) {
  if (!properties) return null;
  const keys = ['name','NAME','NAME_1','adi','ADI','Il_adi','il_adi','IL_ADI','Ilce','Ilçe','ilce_adi','ilce','NAME_TR','NAMES','NAME_2'];
  for (const k of keys) if (properties[k]) return properties[k];
  for (const k in properties) if (typeof properties[k]==='string'&&properties[k].length>0) return properties[k];
  return null;
}

// Normalize string for comparison: keep Turkish chars but handle case and spaces
function normalizeName(s) {
  if (!s) return '';
  let t = s.toString().trim();
  // remove text in parentheses and after slash or dash
  t = t.replace(/\(.*?\)/g, '');
  t = t.split(/[\/\-\|,;]+/)[0].trim();
  // lowercase using Turkish locale (preserves ç,ğ,ş,ü,ö,ı properly)
  t = t.toLocaleLowerCase('tr-TR');
  // collapse multiple spaces
  t = t.replace(/\s+/g, ' ').trim();
  return t;
}

// Levenshtein distance and similarity helpers for fuzzy matching
function levenshtein(a, b) {
  if (!a) return (b||'').length;
  if (!b) return a.length;
  const m = a.length, n = b.length;
  const dp = Array.from({length: m+1}, () => new Array(n+1).fill(0));
  for (let i=0;i<=m;i++) dp[i][0]=i;
  for (let j=0;j<=n;j++) dp[0][j]=j;
  for (let i=1;i<=m;i++) {
    for (let j=1;j<=n;j++) {
      const cost = a[i-1] === b[j-1] ? 0 : 1;
      dp[i][j] = Math.min(dp[i-1][j]+1, dp[i][j-1]+1, dp[i-1][j-1]+cost);
    }
  }
  return dp[m][n];
}
function similarity(a,b){
  a = a||''; b = b||'';
  const maxLen = Math.max(a.length, b.length);
  if (maxLen===0) return 1.0;
  const dist = levenshtein(a,b);
  return 1.0 - (dist / maxLen);
}

// Robust lookup: tries exact normalized match first, then fuzzy
function lookupRiskForFeature(feature) {
  const name = getFeatureName(feature.properties) || '';
  const nameNorm = normalizeName(name);
  if (!nameNorm) return null;
  if (!riskScores || !riskScores.length) return null;

  // Build normalized map from risk scores (normalize district names)
  const scoreMap = {};
  for (const r of riskScores) {
    if (!r || !r.district) continue;
    const distNorm = normalizeName(r.district);
    if (distNorm) {
      scoreMap[distNorm] = r.risk;
    }
  }

  // 1. Exact normalized match
  if (scoreMap[nameNorm] !== undefined) {
    return scoreMap[nameNorm];
  }

  // 2. Fuzzy fallback: find best match by string similarity
  let bestScore = 0;
  let bestRisk = null;
  for (const key in scoreMap) {
    const sim = stringSimilarity(key, nameNorm);
    if (sim > bestScore && sim >= 0.80) {
      bestScore = sim;
      bestRisk = scoreMap[key];
    }
  }
  if (bestRisk !== null) {
    console.debug('Fuzzy matched:', name, '=>', nameNorm, 'similarity:', bestScore);
    return bestRisk;
  }

  return null;
}

// Simple string similarity (Levenshtein-based)
function stringSimilarity(a, b) {
  a = a || ''; b = b || '';
  const maxLen = Math.max(a.length, b.length);
  if (maxLen === 0) return 1.0;
  const dist = levenshteinDistance(a, b);
  return 1.0 - (dist / maxLen);
}

function levenshteinDistance(a, b) {
  const m = a.length, n = b.length;
  const dp = Array.from({length: m+1}, () => Array(n+1).fill(0));
  for (let i = 0; i <= m; i++) dp[i][0] = i;
  for (let j = 0; j <= n; j++) dp[0][j] = j;
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      const cost = a[i-1] === b[j-1] ? 0 : 1;
      dp[i][j] = Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1, dp[i-1][j-1] + cost);
    }
  }
  return dp[m][n];
}

function riskCategory(risk) {
  const v = parseFloat(risk);
  if (isNaN(v)) return 'low';
  if (v <= 33) return 'low';
  if (v <= 66) return 'mid';
  return 'high';
}

function riskColor(r) {
  if (!r) return '#c7ebc9';
  const v = parseFloat(r);
  if (isNaN(v)) return '#c7ebc9';

  // Green (20-33): light green to dark green
  if (v <= 33) {
    const ratio = (v - 20) / (33 - 20); // 0 to 1
    // Light green #c7ebc9 to dark green #2d5016
    const r1 = Math.round(199 + (45 - 199) * ratio);
    const g1 = Math.round(235 + (80 - 235) * ratio);
    const b1 = Math.round(201 + (22 - 201) * ratio);
    return `rgb(${r1}, ${g1}, ${b1})`;
  }

  // Yellow (33-66): light yellow to dark yellow/orange
  if (v <= 66) {
    const ratio = (v - 33) / (66 - 33); // 0 to 1
    // Light yellow #ffd8a8 to dark orange #cc7700
    const r2 = Math.round(255 + (204 - 255) * ratio);
    const g2 = Math.round(216 + (119 - 216) * ratio);
    const b2 = Math.round(168 + (0 - 168) * ratio);
    return `rgb(${r2}, ${g2}, ${b2})`;
  }

  // Red (66-100): light red to dark red
  const ratio = (v - 66) / (100 - 66); // 0 to 1
  // Light red #d7191c to dark red #7f0f1f
  const r3 = Math.round(215 + (127 - 215) * ratio);
  const g3 = Math.round(25 + (15 - 25) * ratio);
  const b3 = Math.round(28 + (31 - 28) * ratio);
  return `rgb(${r3}, ${g3}, ${b3})`;
}

// Helper: bboxFromBounds
function bboxFromBounds(bounds) {
  if (!bounds) return '';
  const sw = bounds.getSouthWest();
  const ne = bounds.getNorthEast();
  return `${sw.lng.toFixed(6)},${sw.lat.toFixed(6)},${ne.lng.toFixed(6)},${ne.lat.toFixed(6)}`;
}

// Style for province (outline only)
function styleProvince(feature) {
  return { color: '#1f78b4', weight: 1.5, fill: false, opacity: 0.9 };
}

function styleDistrictFeature(feature) {
  const risk = lookupRiskForFeature(feature);
  const cat = riskCategory(risk);
  const base = { color: '#444', weight: 1, opacity: 0.9, fillOpacity: 0.45 };
  const fillColor = riskColor(risk);

  if (cat==='low') {
    base.fillColor = fillColor;
    base.color = '#2d5016'; // dark green border
    base.fillOpacity = 0.35;
  }
  else if (cat==='mid') {
    base.fillColor = fillColor;
    base.color = '#cc7700'; // dark orange border
    base.fillOpacity = 0.4;
  }
  else {
    base.fillColor = fillColor;
    base.color = '#7f0f1f'; // dark red border
    base.fillOpacity = 0.45;
  }
  return base;
}

// WMS settings
const WMS_URL = 'https://api.ibb.gov.tr/cbsaltlik/arcgis/services/MAKS/YAPI_YOGUNLUK/MapServer/WMSServer';
let wmsLayer = null;

// Add WMS layer when analysis mode is toggled
function ensureWmsLayer() {
  if (!wmsLayer) {
    wmsLayer = L.tileLayer.wms(WMS_URL, {
      layers: '0',
      format: 'image/png',
      transparent: true,
      opacity: 0.4,
      attribution: 'İBB - YAPI_YOGUNLUK'
    });
  }
}

// Hook analysisMode and opacity controls
const analysisModeEl = document.getElementById('analysisMode');
const wmsControlsEl = document.getElementById('wmsControls');
const wmsOpacityEl = document.getElementById('wmsOpacity');
const wmsOpacityValEl = document.getElementById('wmsOpacityVal');

analysisModeEl && analysisModeEl.addEventListener('change', ()=>{
  if (analysisModeEl.checked) {
    ensureWmsLayer();
    wmsLayer.addTo(map);
    wmsControlsEl.style.display = 'block';
  } else {
    if (wmsLayer) map.removeLayer(wmsLayer);
    wmsControlsEl.style.display = 'none';
  }
});

wmsOpacityEl && wmsOpacityEl.addEventListener('input', ()=>{
  const v = parseInt(wmsOpacityEl.value || '40');
  if (wmsLayer) wmsLayer.setParams({opacity: v/100});
  wmsOpacityValEl.textContent = v + '%';
  if (wmsLayer) wmsLayer.setOpacity && wmsLayer.setOpacity(v/100);
});

// Candidate priority logic (simple rule)
function computePriorityForFeature(feature) {
  const risk = lookupRiskForFeature(feature);
  const cat = riskCategory(risk);
  // density heuristic: sample WMS not directly queryable; approximate via risk-weighted thresholds
  // if analysis mode active, we assume wms indicates 'yogunluk' - we use a simplistic rule: risk band + random minor factor
  const densityHigh = analysisModeEl && analysisModeEl.checked; // placeholder flag
  if (cat==='high' && densityHigh) return 1;
  if (cat==='high' && !densityHigh) return 2;
  if (cat==='mid' && densityHigh) return 2;
  if (cat==='mid' && !densityHigh) return 3;
  return 4; // low priority
}

// Update legend dynamically
function updateLegend() {
  const legendEl = document.getElementById('legend');
  if (!legendEl) return;
  const showLow = filterLow && filterLow.checked;
  const showMid = filterMid && filterMid.checked;
  const showHigh = filterHigh && filterHigh.checked;
  const candidate = candidateModeEl && candidateModeEl.checked;
  let html = '<div style="font-weight:700;margin-bottom:6px;">Legend</div>';
  if (showLow) html += '<div class="item"><span class="swatch" style="background:#c7ebc9"></span> Düşük</div>';
  if (showMid) html += '<div class="item"><span class="swatch" style="background:#ffd8a8"></span> Orta</div>';
  if (showHigh) html += '<div class="item"><span class="swatch" style="background:#d7191c"></span> Yüksek</div>';
  if (candidate) html += '<div style="margin-top:8px;font-size:13px;"><strong>Kentsel Dönüşüm Adayları Aktif</strong></div>';
  legendEl.innerHTML = html;
}

// Override applyFilters to also apply priority highlight and update legend
function applyFilters() {
  const showLow = !!filterLow.checked;
  const showMid = !!filterMid.checked;
  const showHigh = !!filterHigh.checked;
  const candidate = !!candidateModeEl.checked;
  districtLayer.eachLayer(layer => {
    const f = layer.feature;
    if (!f) return;
    const risk = lookupRiskForFeature(f);
    const cat = riskCategory(risk);
    const matches = (cat==='low' && showLow) || (cat==='mid' && showMid) || (cat==='high' && showHigh);
    if (matches) {
      // visible
      const baseStyle = styleDistrictFeature(f);
      layer.setStyle(baseStyle);
      layer.getElement && layer.getElement().classList.remove('dimmed');
      // candidate mode: boost mid/high
      if (candidate) {
        const pr = computePriorityForFeature(f);
        if (pr===1) layer.setStyle({ fillOpacity: 0.9, color:'#7f0f1f' });
        else if (pr===2) layer.setStyle({ fillOpacity: 0.75 });
        else if (pr===3) layer.setStyle({ fillOpacity: 0.55 });
      }
    } else {
      // dim
      layer.setStyle({ fillOpacity: 0.06, color: '#999', opacity: 0.5 });
      layer.getElement && layer.getElement().classList.add('dimmed');
    }
  });
  updateLegend();
}

// Selection handling
function clearSelectedDistrict() {
  if (!selectedDistrictLayer) return;
  const feat = selectedDistrictLayer.feature;
  if (feat) selectedDistrictLayer.setStyle(styleDistrictFeature(feat));
  // remove selected class
  try { selectedDistrictLayer.getElement() && selectedDistrictLayer.getElement().classList.remove('selected-poly'); } catch(e){}
  selectedDistrictLayer = null;
}

function selectDistrictLayer(layer) {
  if (!layer) return;
  // clear previous
  clearSelectedDistrict();
  selectedDistrictLayer = layer;
  // add selected style
  try { layer.setStyle({ color:'#ff1e2e', weight:3, fillOpacity:0.18 }); } catch(e){}
  try { layer.getElement() && layer.getElement().classList.add('selected-poly'); } catch(e){}
  // animate via CSS transition
  // fit bounds with short fly
  try {
    const b = layer.getBounds();
    map.flyToBounds(b, { padding:[40,40], duration: 0.3 });
  } catch(e){}
  // post zone to backend
  try {
    const f = layer.feature;
    const name = getFeatureName(f.properties) || '-';
    const centre = layer.getBounds().getCenter();
    api.postZone({ district: name, neighbourhood: '-', lat: centre.lat, lng: centre.lng, bbox: bboxFromBounds(layer.getBounds()) });
    // update summary
    updateSummaryCard(name, lookupRiskForFeature(f));
  } catch(e){}
}

// Hover handlers
function bindDistrictInteractions(layer) {
  layer.on('mouseover', function(e){
    const tgt = e.target;
    try { tgt.setStyle({ weight:2, fillOpacity: Math.max((tgt.options.fillOpacity||0.4),0.5) }); } catch(e){}
    try { tgt.bringToFront && tgt.bringToFront(); } catch(e){}
    const f = layer.feature;
    if (f) {
      const name = getFeatureName(f.properties) || '-';
      const r = lookupRiskForFeature(f) || 'N/A';
      layer.bindTooltip(`${name} — Risk: ${r}`, {className:'small-tooltip'}).openTooltip();
    }
  });
  layer.on('mouseout', function(e){
    const tgt = e.target;
    try { if (selectedDistrictLayer !== layer) tgt.setStyle(styleDistrictFeature(layer.feature)); else tgt.setStyle({ color:'#ff1e2e', weight:3, fillOpacity:0.18 }); } catch(e){}
    try { layer.closeTooltip && layer.closeTooltip(); } catch(e){}
  });
  layer.on('click', function(e){ selectDistrictLayer(layer); });
}

// Load GeoJSON layers and risk scores
async function loadLayers() {
  try {
    riskScores = await api.getRiskScores();
    console.log('Risk scores:', riskScores.length);
  } catch(e) { console.warn('Risk scores not loaded:', e.message); }

  // provinces
  try {
    const prov = await api.getProvinceGeojson();
    if (provinceLayer) map.removeLayer(provinceLayer);
    provinceLayer = L.geoJSON(prov, { style: styleProvince, onEachFeature: function(f,l){ l.bindTooltip(getFeatureName(f.properties)||'İl',{className:'small-tooltip'}); } });
    if (toggleProvince.checked) provinceLayer.addTo(map);
  } catch(e) { console.warn('Province load failed:', e.message); }

  // districts
  try {
    const dist = await api.getDistrictGeojson();
    if (districtLayer) map.removeLayer(districtLayer);
    districtLayer = L.geoJSON(dist, { style: styleDistrictFeature, onEachFeature: function(f,l){
      // store mapping id
      const id = (getFeatureName(f.properties)||'') + '::' + (Math.random().toString(36).slice(2,7));
      districtLayerMap.set(id,l);
      l._customId = id;
      bindDistrictInteractions(l);
    }});
    if (toggleDistrict.checked) districtLayer.addTo(map);
    applyFilters();
  } catch(e) { console.warn('District load failed:', e.message); }
}

// Update summary card
function updateSummaryCard(name, risk) {
  if (selectedNameEl) selectedNameEl.textContent = name || '-';
  if (summaryNameEl) summaryNameEl.textContent = name || '-';
  if (summaryScenarioEl) summaryScenarioEl.textContent = scenarioSelect.value || '-';
  if (summaryRiskEl) { summaryRiskEl.textContent = risk || 'N/A'; summaryRiskEl.style.color = risk ? riskColor(risk) : '#0f1724'; }
  if (summaryPriorityEl) { const p = (!risk || isNaN(parseFloat(risk))) ? '-' : (parseFloat(risk)>66 ? 'Yüksek' : (parseFloat(risk)>33 ? 'Orta' : 'Düşük')); summaryPriorityEl.textContent = p; }
}

// Hook UI controls
filterLow && filterLow.addEventListener('change', applyFilters);
filterMid && filterMid.addEventListener('change', applyFilters);
filterHigh && filterHigh.addEventListener('change', applyFilters);
candidateModeEl && candidateModeEl.addEventListener('change', applyFilters);

toggleProvince && toggleProvince.addEventListener('change', ()=>{ if (toggleProvince.checked) provinceLayer && provinceLayer.addTo(map); else provinceLayer && map.removeLayer(provinceLayer); });
toggleDistrict && toggleDistrict.addEventListener('change', ()=>{ if (toggleDistrict.checked) districtLayer && districtLayer.addTo(map); else districtLayer && map.removeLayer(districtLayer); });

applyScenario && applyScenario.addEventListener('click', ()=>{ api.postScenario({scenario: scenarioSelect.value}); updateSummaryCard(summaryNameEl.textContent, summaryRiskEl.textContent); });

reportBtn && reportBtn.addEventListener('click', ()=>{
  // open overlay for selected district
  if (selectedDistrictLayer && selectedDistrictLayer.feature) openReportOverlay(selectedDistrictLayer.feature);
  else api.getState().then(s=> openReportOverlay({ properties: { NAME: s.district || '-' } }));
});

// Report overlay handlers
const closeReportBtn = document.getElementById('closeReport');
closeReportBtn && closeReportBtn.addEventListener('click', ()=>{ document.getElementById('reportOverlay').classList.add('hidden'); });

// initial load
loadLayers();
updateSummaryCard('-', null);

// Helper: detect if coordinates appear to be reversed (lat,lng) and fix to (lng,lat)
function normalizeGeojsonCoordsIfNeeded(geojson) {
  if (!geojson || !geojson.features || !geojson.features.length) return;
  try {
    // check first coordinate of first feature
    const geom = geojson.features[0].geometry;
    if (!geom) return;
    let sample = null;
    if (geom.type === 'Polygon') {
      sample = geom.coordinates && geom.coordinates[0] && geom.coordinates[0][0];
    } else if (geom.type === 'MultiPolygon') {
      sample = geom.coordinates && geom.coordinates[0] && geom.coordinates[0][0] && geom.coordinates[0][0][0];
    } else if (geom.type === 'Point') {
      sample = geom.coordinates;
    } else if (geom.type === 'MultiLineString' || geom.type === 'LineString') {
      sample = geom.coordinates && geom.coordinates[0];
    }
    if (!sample || sample.length < 2) return;
    const a = Number(sample[0]);
    const b = Number(sample[1]);
    // Heuristic: longitude should be between -180..180, latitude -90..90
    // If first value is in latitude range and second in longitude range, they may be swapped
    const looksLikeLatLng = (a >= -90 && a <= 90) && (b >= -180 && b <= 180);
    const looksLikeLngLat = (a >= -180 && a <= 180) && (b >= -90 && b <= 90);
    if (looksLikeLatLng && !looksLikeLngLat) {
      console.log('GeoJSON koordinatları ters görünüyor (lat,lng) — düzeltme uygulanıyor');
      // Deep map through coordinates and swap
      geojson.features.forEach(f => {
        f.geometry = swapCoordsRecursively(f.geometry);
      });
    } else {
      console.log('GeoJSON koordinat düzeni normal (lng,lat) gibi görünüyor)');
    }
  } catch (e) {
    console.warn('normalizeGeojsonCoordsIfNeeded hata:', e.message);
  }
}

function swapCoordsRecursively(geometry) {
  if (!geometry) return geometry;
  const t = geometry.type;
  const c = geometry.coordinates;
  function swapPoint(pt) { return [pt[1], pt[0]]; }
  if (t === 'Point') {
    return { type: 'Point', coordinates: swapPoint(c) };
  }
  if (t === 'LineString') {
    return { type: 'LineString', coordinates: c.map(swapPoint) };
  }
  if (t === 'MultiLineString' || t === 'Polygon') {
    return { type: t, coordinates: c.map(ring => ring.map(swapPoint)) };
  }
  if (t === 'MultiPolygon') {
    return { type: 'MultiPolygon', coordinates: c.map(poly => poly.map(ring => ring.map(swapPoint))) };
  }
  // Fallback: try to recursively handle arrays
  if (Array.isArray(c)) {
    return { type: t, coordinates: swapArrayCoords(c) };
  }
  return geometry;
}

function swapArrayCoords(arr) {
  if (!Array.isArray(arr)) return arr;
  if (arr.length === 0) return arr;
  if (typeof arr[0] === 'number') {
    // point
    return [arr[1], arr[0]];
  }
  return arr.map(swapArrayCoords);
}

// Report panel: render a small Chart.js bar chart
let reportChart = null;
function openReportOverlay(feature) {
  const panel = document.getElementById('reportOverlay');
  const content = document.getElementById('reportContent');
  if (!panel || !content) return;

  const name = getFeatureName(feature.properties) || 'Seçim';
  const nameNorm = normalizeName(name);

  // Find risk data from backend JSON by normalized district name
  let riskData = null;
  for (const r of riskScores) {
    if (r && r.district && normalizeName(r.district) === nameNorm) {
      riskData = r;
      break;
    }
  }

  // Fallback to property lookup if not found
  if (!riskData) {
    riskData = {
      risk: 'N/A',
      can_kaybi_toplam: (feature.properties && feature.properties['can_kaybi_sayisi']) || 0,
      cok_agir_toplam: (feature.properties && feature.properties['cok_agir_hasarli_bina_sayisi']) || 0,
      agir_toplam: (feature.properties && feature.properties['agir_hasarli_bina_sayisi']) || 0,
      orta_toplam: (feature.properties && feature.properties['orta_hasarli_bina_sayisi']) || 0,
      hafif_toplam: (feature.properties && feature.properties['hafif_hasarli_bina_sayisi']) || 0,
      mahalle_observed: 0,
      category: 'Bilinmiyor'
    };
  }

  const risk = riskData.risk || 'N/A';
  const category = riskData.category || 'Bilinmiyor';
  const priority = riskData.priority || 'İzleme';
  const explanation = riskData.explanation || 'Veri mevcut değil';
  const can_kaybi = riskData.can_kaybi_toplam || 0;
  const cok_agir = riskData.cok_agir_toplam || 0;
  const agir = riskData.agir_toplam || 0;
  const orta = riskData.orta_toplam || 0;
  const hafif = riskData.hafif_toplam || 0;
  const mahalle_observed = riskData.mahalle_observed || 0;

  const html = `
    <h3>${name} - Deprem Risk Raporu</h3>
    <div style="margin-bottom:12px; padding:8px; background:#f0f0f0; border-radius:4px;">
      <div><strong>Risk Seviyesi:</strong> <span style="color:${riskColor(risk)}; font-weight:bold;">${category}</span></div>
      <div><strong>Risk Skoru:</strong> ${risk}/100</div>
      <div><strong>Kentsel Dönüşüm Önceliği:</strong> ${priority}</div>
      <div><strong>Gözlemlenen Mahalle:</strong> ${mahalle_observed}</div>
    </div>
    <div style="margin-bottom:12px; padding:8px; background:#fff9e6; border-left:3px solid #f59e0b;">
      <strong>Ana Etkenler:</strong> ${explanation}
    </div>
    <div class="report-metrics" style="display:grid; grid-template-columns:1fr 1fr; gap:8px; margin-bottom:12px;">
      <div class="metric" style="padding:8px; background:#fff0f0; border-radius:4px;">
        <h4 style="margin:0 0 4px 0; color:#7f0f1f;">Can Kaybı</h4>
        <p style="margin:0; font-size:20px; font-weight:bold;">${can_kaybi}</p>
      </div>
      <div class="metric" style="padding:8px; background:#fef3e6; border-radius:4px;">
        <h4 style="margin:0 0 4px 0; color:#d9735d;">Çok Ağır Hasar</h4>
        <p style="margin:0; font-size:20px; font-weight:bold;">${cok_agir}</p>
      </div>
      <div class="metric" style="padding:8px; background:#fffef0; border-radius:4px;">
        <h4 style="margin:0 0 4px 0; color:#f5b05b;">Ağır Hasar</h4>
        <p style="margin:0; font-size:20px; font-weight:bold;">${agir}</p>
      </div>
      <div class="metric" style="padding:8px; background:#f0fdf0; border-radius:4px;">
        <h4 style="margin:0 0 4px 0; color:#86c77b;">Orta Hasar</h4>
        <p style="margin:0; font-size:20px; font-weight:bold;">${orta}</p>
      </div>
    </div>
    <hr style="margin:12px 0;" />
    <div style="margin-top:8px"><canvas id="reportChart" width="380" height="200"></canvas></div>
  `;
  content.innerHTML = html;
  panel.classList.remove('hidden');

  // draw chart
  try {
    const ctx = document.getElementById('reportChart').getContext('2d');
    if (reportChart) { reportChart.destroy(); reportChart = null; }
    reportChart = new Chart(ctx, {
      type: 'bar',
      data: {
        labels: ['Çok Ağır','Ağır','Orta','Hafif'],
        datasets: [{ label: 'Hasarlı Bina Sayısı', data: [cok_agir, agir, orta, hafif], backgroundColor: ['#7f0f1f','#d9735d','#f5b05b','#c7ebc9'] }]
      },
      options: { responsive:true, maintainAspectRatio:false, indexAxis:'y' }
    });
  } catch(e) { console.warn('Chart render failed', e); }
}
