package com.geely.drivemem.sensors;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java OSM toll parser, coarse privacy-preserving tile calculator, and
 * segment-to-point passage matcher.
 *
 * Calibrated directly against real São Paulo highway trips (Tamoios, Ayrton
 * Senna, Rodoanel, Dutra Free Flow):
 * - Overpass queries use ONLY snapped 0.5 deg (~55 km) tiles so exact routes
 *   and home locations are never sent to a third-party server.
 * - Plaza names fall back: non-generic {@code name} -> {@code note} -> {@code ref}
 *   -> {@code operator} (most Brazilian OSM booths keep their plaza name in
 *   {@code note}).
 * - Express / tag lanes sit up to ~70 m from the booth node (and often closer
 *   to the opposite carriageway's node), so plazas use an 80 m match radius
 *   with +/-45 deg direction filtering and ~400 m / 60 s plaza deduping.
 * - Parses OSM {@code charge} tags for {@code motorcar}, including weekday vs
 *   weekend Free Flow schedules ({@code Mo-Fr ...;Sa-Su ...}). */
public final class TollMatcher {
    private TollMatcher() {}

    /** Coarse Overpass query tile size (~55 km x 55 km) to protect route privacy. */
    public static final double TILE_DEG = 0.5;
    /** Spatial index cell size (~11 km). */
    public static final double GRID_DEG = 0.1;

    public static final String KIND_PLAZA = "PLAZA";
    public static final String KIND_FREE_FLOW = "FREE_FLOW";

    public static final double PLAZA_RADIUS_M = 80.0;
    public static final double FREE_FLOW_RADIUS_M = 45.0;
    public static final double DEFAULT_BEARING_TOL_DEG = 45.0;

    /** Cluster radius for deduping duplicate/stale OSM nodes at the same plaza. */
    public static final double DEDUPE_RADIUS_M = 400.0;

    /** Max distance jump still matched (handles short tunnel/canopy GPS blinks
     * as long as implied speed is realistic <= 180 km/h). */
    public static final double MAX_SEGMENT_M = 1500.0;
    public static final double MAX_IMPLIED_KMH = 180.0;

    /** Per-booth cooldown (10 min + 500 m exit) and cross-plaza collapse window. */
    public static final long COOLDOWN_MS = 10 * 60_000L;
    public static final double COOLDOWN_EXIT_M = 500.0;
    public static final long CLUSTER_COLLAPSE_MS = 45_000L;
    public static final double CLUSTER_COLLAPSE_M = 450.0;
    /** Consecutive Free Flow gantries within this time & distance belong to one
     *  closed/corridor billing segment (e.g. Dutra express lane) and charge once
     *  when you drop off at the final gantry. */
    public static final long FREE_FLOW_CORRIDOR_GAP_MS = 10 * 60_000L;
    public static final double FREE_FLOW_CORRIDOR_GAP_M = 12_000.0;

    /** Normalized toll booth / gantry catalog entry. */
    public static final class Booth {
        public final long id;
        public final String externalId;
        public final String name;
        public final String operator;
        public final String kind;
        public final double lat;
        public final double lon;
        public final double radiusM;
        public final Double bearingDeg;
        public final double bearingTolDeg;
        public final String chargeRaw;
        public final Double catalogPrice;
        public final Double userPrice;
        public final String checkDate;

        public Booth(long id, String externalId, String name, String operator, String kind,
                     double lat, double lon, double radiusM, Double bearingDeg, double bearingTolDeg,
                     String chargeRaw, Double catalogPrice, Double userPrice, String checkDate) {
            this.id = id;
            this.externalId = externalId;
            this.name = name;
            this.operator = operator;
            this.kind = kind;
            this.lat = lat;
            this.lon = lon;
            this.radiusM = radiusM;
            this.bearingDeg = bearingDeg;
            this.bearingTolDeg = bearingTolDeg;
            this.chargeRaw = chargeRaw;
            this.catalogPrice = catalogPrice;
            this.userPrice = userPrice;
            this.checkDate = checkDate;
        }

        /** Effective price for a crossing at {@code tsMs}: user override wins,
         * else day-of-week parsed OSM charge, else static catalog price. */
        public Double effectivePrice(long tsMs, ZoneId zone) {
            if (userPrice != null) return userPrice;
            if (chargeRaw != null && !chargeRaw.isEmpty()) {
                DayOfWeek dow = Instant.ofEpochMilli(tsMs).atZone(zone).getDayOfWeek();
                boolean weekend = (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY);
                Double parsed = parseMotorcarCharge(chargeRaw, weekend);
                if (parsed != null) return parsed;
            }
            return catalogPrice;
        }
    }

    /** A detected toll passage along a GPS track or live fix stream. */
    public static final class Hit {
        public final Booth booth;
        public final long tsMs;
        public final double lat;
        public final double lon;
        public final double speedKmh;
        public final double distanceM;
        public final Double cost;
        /** Where and when a collapsed Free Flow corridor began (its first
         * gantry). Same as tsMs/lat/lon for a single booth. */
        public final long entryTsMs;
        public final double entryLat;
        public final double entryLon;

        public Hit(Booth booth, long tsMs, double lat, double lon,
                   double speedKmh, double distanceM, Double cost) {
            this(booth, tsMs, lat, lon, speedKmh, distanceM, cost, tsMs, lat, lon);
        }

        public Hit(Booth booth, long tsMs, double lat, double lon,
                   double speedKmh, double distanceM, Double cost,
                   long entryTsMs, double entryLat, double entryLon) {
            this.booth = booth;
            this.tsMs = tsMs;
            this.lat = lat;
            this.lon = lon;
            this.speedKmh = speedKmh;
            this.distanceM = distanceM;
            this.cost = cost;
            this.entryTsMs = entryTsMs;
            this.entryLat = entryLat;
            this.entryLon = entryLon;
        }
    }

    /** A passage already saved, for {@link #isDuplicate}. */
    public static final class Saved {
        public final long boothId;
        public final long tsMs;
        public final double lat;
        public final double lon;
        public final String kind;
        public Saved(long boothId, long tsMs, double lat, double lon, String kind) {
            this.boothId = boothId;
            this.tsMs = tsMs;
            this.lat = lat;
            this.lon = lon;
            this.kind = kind;
        }
    }

    /** Window of saved passages {@link #isDuplicate} needs to see for this hit. */
    public static long dedupeFromMs(Hit h) { return Math.min(h.entryTsMs, h.tsMs) - FREE_FLOW_CORRIDOR_GAP_MS; }
    public static long dedupeToMs(Hit h) { return h.tsMs + FREE_FLOW_CORRIDOR_GAP_MS; }

    /** True when a saved passage (dismissed ones too) already charges this hit.
     *  - The same booth, or a spot within the collapse radius, within 3 min.
     *  - For Free Flow: any saved Free Flow passage that the corridor rule
     *    would have merged with it. A trip first scanned with only some of a
     *    corridor's gantries cached saves "A"; a later scan with all of them
     *    finds "A → B" at B, minutes later and km away. Both would be charged. */
    public static boolean isDuplicate(Hit h, List<Saved> saved) {
        if (h == null || saved == null) return false;
        long lo = Math.min(h.entryTsMs, h.tsMs) - 180_000L, hi = h.tsMs + 180_000L;
        boolean ff = KIND_FREE_FLOW.equals(h.booth.kind);
        for (Saved p : saved) {
            if (p.tsMs >= lo && p.tsMs <= hi) {
                if (h.booth.id > 0 && p.boothId == h.booth.id) return true;
                if (distanceM(h.lat, h.lon, p.lat, p.lon) <= CLUSTER_COLLAPSE_M) return true;
            }
            if (ff && KIND_FREE_FLOW.equals(p.kind)
                    && p.tsMs >= dedupeFromMs(h) && p.tsMs <= dedupeToMs(h)
                    && (distanceM(h.lat, h.lon, p.lat, p.lon) <= FREE_FLOW_CORRIDOR_GAP_M
                        || distanceM(h.entryLat, h.entryLon, p.lat, p.lon) <= FREE_FLOW_CORRIDOR_GAP_M)) {
                return true;
            }
        }
        return false;
    }

    // ---- Coarse privacy-preserving 0.5 deg tile helpers ----

    public static String tileKey(double lat, double lon) {
        double s = Math.floor(lat / TILE_DEG) * TILE_DEG;
        double w = Math.floor(lon / TILE_DEG) * TILE_DEG;
        return String.format(Locale.US, "%.1f,%.1f", s, w);
    }

    /** Returns {south, west, north, east} padded slightly (0.01 deg ~1 km) so
     * plazas sitting right on a tile border are never missed. */
    public static double[] tileBounds(String tileKey) {
        String[] parts = tileKey.split(",");
        double s = Double.parseDouble(parts[0].trim());
        double w = Double.parseDouble(parts[1].trim());
        double pad = 0.01;
        return new double[]{s - pad, w - pad, s + TILE_DEG + pad, w + TILE_DEG + pad};
    }

    /** Distinct coarse 0.5 deg tiles touched by a set of GPS points. */
    public static List<String> tilesForPoints(double[] lat, double[] lon) {
        if (lat == null || lon == null) return Collections.emptyList();
        int n = Math.min(lat.length, lon.length);
        Set<String> set = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            if (!Double.isFinite(lat[i]) || !Double.isFinite(lon[i])) continue;
            set.add(tileKey(lat[i], lon[i]));
        }
        return new ArrayList<>(set);
    }

    /** Builds a fast indexed Overpass QL query for one coarse tile (no regex). */
    public static String overpassQueryForTile(String tileKey) {
        double[] b = tileBounds(tileKey);
        return String.format(Locale.US,
            "[out:json][timeout:15];("
            + "nw[\"barrier\"=\"toll_booth\"](%.4f,%.4f,%.4f,%.4f);"
            + "nw[\"highway\"=\"toll_gantry\"](%.4f,%.4f,%.4f,%.4f);"
            + ");out body center;",
            b[0], b[1], b[2], b[3],
            b[0], b[1], b[2], b[3]);
    }

    // ---- OSM JSON parsing, name resolution, charge parsing & deduplication ----

    public static List<Booth> parseOverpassJson(String json) {
        List<Booth> raw = new ArrayList<>();
        if (json == null || json.isEmpty()) return raw;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray els = root.optJSONArray("elements");
            if (els == null) return raw;
            for (int i = 0; i < els.length(); i++) {
                JSONObject el = els.optJSONObject(i);
                if (el == null) continue;
                String type = el.optString("type", "node");
                long osmId = el.optLong("id", 0);
                if (osmId <= 0) continue;
                double lat = el.optDouble("lat", Double.NaN);
                double lon = el.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) {
                    JSONObject center = el.optJSONObject("center");
                    if (center != null) {
                        lat = center.optDouble("lat", Double.NaN);
                        lon = center.optDouble("lon", Double.NaN);
                    }
                }
                if (!Double.isFinite(lat) || !Double.isFinite(lon)) continue;
                JSONObject tags = el.optJSONObject("tags");
                if (tags == null) tags = new JSONObject();

                boolean isGantry = "toll_gantry".equals(tags.optString("highway", ""));
                String kind = isGantry ? KIND_FREE_FLOW : KIND_PLAZA;
                double radius = isGantry ? FREE_FLOW_RADIUS_M : PLAZA_RADIUS_M;

                String name = resolveName(
                    optNull(tags, "name"), optNull(tags, "note"),
                    optNull(tags, "ref"), optNull(tags, "operator"));
                String operator = optNull(tags, "operator");
                Double bearing = parseDirection(optNull(tags, "direction"));
                String chargeRaw = optNull(tags, "charge");
                Double catalogPrice = chargeRaw != null ? parseMotorcarCharge(chargeRaw, false) : null;
                String checkDate = optNull(tags, "check_date");

                raw.add(new Booth(0, type + "/" + osmId, name, operator, kind,
                    lat, lon, radius, bearing, DEFAULT_BEARING_TOL_DEG,
                    chargeRaw, catalogPrice, null, checkDate));
            }
        } catch (Throwable ignored) {}
        return dedupeBooths(raw);
    }

    private static String optNull(JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) return null;
        String s = o.optString(k, "").trim();
        return s.isEmpty() ? null : s;
    }

    /** Picks the most informative plaza name (most SP OSM booths put the real
     * plaza name in {@code note} while leaving {@code name} blank or generic). */
    public static String resolveName(String name, String note, String ref, String operator) {
        if (name != null && !isGenericTollName(name)) return name;
        if (note != null && !note.isEmpty()) return note;
        if (ref != null && !ref.isEmpty()) return ref;
        if (operator != null && !operator.isEmpty()) return operator;
        if (name != null && !name.isEmpty()) return name;
        return "Toll";
    }

    private static boolean isGenericTollName(String s) {
        String lo = s.trim().toLowerCase(Locale.ROOT);
        return lo.isEmpty()
            || lo.equals("pedágio") || lo.equals("pedagio")
            || lo.equals("pedágio free flow") || lo.equals("pedagio free flow")
            || lo.equals("free flow") || lo.equals("toll") || lo.equals("toll booth");
    }

    /** Parses numeric OSM {@code direction} (e.g. "338" -> 338.0). Returns null
     * for bidirectional / multi-value ("68;248") or non-numeric strings. */
    public static Double parseDirection(String dir) {
        if (dir == null || dir.isEmpty() || dir.contains(";")) return null;
        try {
            double v = Double.parseDouble(dir.trim());
            if (!Double.isFinite(v)) return null;
            return ((v % 360.0) + 360.0) % 360.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Pattern PRICE_NUM = Pattern.compile("(\\d+(?:[.,]\\d{1,2})?)");

    /** Parses OSM {@code charge} strings for passenger cars ({@code motorcar}),
     * supporting both simple tariffs ("13.00BRL/motorcar;6.50BRL/motorcycle")
     * and weekday/weekend Free Flow schedules ("Mo-Fr 5.44BRL/motorcar;Sa-Su 1.81BRL/motorcar"). */
    public static Double parseMotorcarCharge(String chargeRaw, boolean isWeekend) {
        if (chargeRaw == null || chargeRaw.trim().isEmpty()) return null;
        String[] parts = chargeRaw.split(";");
        Double dayMatchedCar = null;
        Double genericCar = null;
        Double firstPlain = null;
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            String lo = p.toLowerCase(Locale.ROOT);
            boolean isOtherVehicle = lo.contains("motorcycle") || lo.contains("hgv")
                || lo.contains("bus") || lo.contains("truck") || lo.contains("axle");
            boolean isCar = lo.contains("motorcar") || lo.contains("car");
            if (isOtherVehicle && !isCar) continue;

            boolean mentionsWeekday = lo.contains("mo-fr") || lo.contains("mo-su");
            boolean mentionsWeekend = lo.contains("sa-su") || lo.contains("ph");
            Double val = extractPrice(p);
            if (val == null || val < 0) continue;

            if (isCar) {
                if (mentionsWeekday || mentionsWeekend) {
                    if ((isWeekend && mentionsWeekend) || (!isWeekend && mentionsWeekday)) {
                        dayMatchedCar = val;
                    }
                } else if (genericCar == null) {
                    genericCar = val;
                }
            } else if (firstPlain == null && !mentionsWeekday && !mentionsWeekend) {
                firstPlain = val;
            }
        }
        if (dayMatchedCar != null) return dayMatchedCar;
        if (genericCar != null) return genericCar;
        return firstPlain;
    }

    private static Double extractPrice(String segment) {
        // Strip leading day range tokens like "Mo-Fr " or "Sa-Su " before grabbing the number.
        String cleaned = segment.replaceAll("(?i)\\b(?:mo|tu|we|th|fr|sa|su)(?:-(?:mo|tu|we|th|fr|sa|su))?\\b", " ");
        Matcher m = PRICE_NUM.matcher(cleaned);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Collapses duplicate/stale OSM nodes at the same plaza & compatible direction
     * (e.g. old Ecopistas vs new Ecovias node, or parallel Free Flow lane nodes). */
    public static List<Booth> dedupeBooths(List<Booth> input) {
        if (input == null || input.size() <= 1) return input == null ? Collections.emptyList() : new ArrayList<>(input);
        List<Booth> out = new ArrayList<>();
        for (Booth b : input) {
            int matchIdx = -1;
            for (int i = 0; i < out.size(); i++) {
                Booth e = out.get(i);
                if (!e.kind.equals(b.kind)) continue;
                if (distanceM(e.lat, e.lon, b.lat, b.lon) > DEDUPE_RADIUS_M) continue;
                if (e.bearingDeg != null && b.bearingDeg != null
                        && angularDiff(e.bearingDeg, b.bearingDeg) > DEFAULT_BEARING_TOL_DEG) {
                    continue;
                }
                matchIdx = i;
                break;
            }
            if (matchIdx < 0) {
                out.add(b);
            } else if (boothQuality(b) > boothQuality(out.get(matchIdx))) {
                out.set(matchIdx, b);
            }
        }
        return out;
    }

    private static int boothQuality(Booth b) {
        int score = 0;
        if (b.userPrice != null) score += 1000;
        if (b.chargeRaw != null && !b.chargeRaw.isEmpty()) score += 200;
        if (b.catalogPrice != null) score += 100;
        if (b.name != null && !isGenericTollName(b.name)) score += 50;
        if (b.checkDate != null && b.checkDate.length() >= 4) {
            // Newer check_date (e.g. "2026-07-01" vs "2025-07-10") wins ties cleanly.
            try {
                score += Integer.parseInt(b.checkDate.replace("-", "").substring(0, Math.min(8, b.checkDate.replace("-", "").length()))) / 1000;
            } catch (NumberFormatException ignored) {}
        }
        return score;
    }

    // ---- Spatial-grid segment-to-point track matcher ----

    public static final class SpatialIndex {
        private final Map<Long, List<Booth>> cells = new HashMap<>();

        public SpatialIndex(List<Booth> booths) {
            if (booths == null) return;
            for (Booth b : booths) {
                long k = cellKey((int) Math.floor(b.lat / GRID_DEG), (int) Math.floor(b.lon / GRID_DEG));
                cells.computeIfAbsent(k, x -> new ArrayList<>()).add(b);
            }
        }

        public boolean isEmpty() { return cells.isEmpty(); }

        public List<Booth> query(double minLat, double minLon, double maxLat, double maxLon) {
            int r0 = (int) Math.floor((minLat - 0.002) / GRID_DEG);
            int r1 = (int) Math.floor((maxLat + 0.002) / GRID_DEG);
            int c0 = (int) Math.floor((minLon - 0.002) / GRID_DEG);
            int c1 = (int) Math.floor((maxLon + 0.002) / GRID_DEG);
            List<Booth> out = null;
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    List<Booth> bucket = cells.get(cellKey(r, c));
                    if (bucket != null) {
                        if (out == null) out = new ArrayList<>();
                        out.addAll(bucket);
                    }
                }
            }
            return out == null ? Collections.emptyList() : out;
        }

        private static long cellKey(int r, int c) {
            return (((long) r) << 32) ^ (c & 0xFFFFFFFFL);
        }
    }

    /** Stateful matcher usable both for live GPS fixes and historical track backfill. */
    public static final class Runner {
        private final SpatialIndex index;
        private final ZoneId zone;
        private final Map<Long, Long> lastHitMs = new HashMap<>();
        private final Map<Long, Boolean> exitedRadius = new HashMap<>();
        private boolean hasPrev;
        private long prevTs;
        private double prevLat, prevLon, prevSpeed;
        private Hit lastAcceptedHit;

        public Runner(SpatialIndex index, ZoneId zone) {
            this.index = index;
            this.zone = zone != null ? zone : ZoneId.systemDefault();
        }

        public void resetTrack() {
            hasPrev = false;
            lastAcceptedHit = null;
        }

        /** Feeds one GPS fix; returns any toll passage(s) crossed on segment prev -> curr. */
        public List<Hit> onPoint(long tsMs, double lat, double lon, double speedKmh) {
            if (!hasPrev) {
                hasPrev = true;
                prevTs = tsMs; prevLat = lat; prevLon = lon; prevSpeed = speedKmh;
                return Collections.emptyList();
            }
            if (tsMs <= prevTs) return Collections.emptyList();

            double segDistM = distanceM(prevLat, prevLon, lat, lon);
            double dtS = (tsMs - prevTs) / 1000.0;
            double impliedKmh = dtS > 0 ? (segDistM / dtS) * 3.6 : 0;

            // Stationary wobble: keep prev anchor so slow creep through a plaza still forms a real segment.
            if (segDistM < 8.0 && speedKmh < 3.0) {
                return Collections.emptyList();
            }
            // Teleport guard: advance prev without matching impossible jumps.
            if (segDistM > MAX_SEGMENT_M || impliedKmh > MAX_IMPLIED_KMH) {
                prevTs = tsMs; prevLat = lat; prevLon = lon; prevSpeed = speedKmh;
                return Collections.emptyList();
            }

            List<Booth> cands = index.query(
                Math.min(prevLat, lat), Math.min(prevLon, lon),
                Math.max(prevLat, lat), Math.max(prevLon, lon));

            // Update exit hysteresis for previously triggered booths.
            for (Long bid : new ArrayList<>(lastHitMs.keySet())) {
                if (Boolean.TRUE.equals(exitedRadius.get(bid))) continue;
                for (Booth b : cands) {
                    if (b.id == bid && distanceM(lat, lon, b.lat, b.lon) > COOLDOWN_EXIT_M) {
                        exitedRadius.put(bid, true);
                    }
                }
            }

            double segBearing = bearingDeg(prevLat, prevLon, lat, lon);
            List<Hit> stepHits = null;
            for (Booth b : cands) {
                if (b.bearingDeg != null && angularDiff(segBearing, b.bearingDeg) > b.bearingTolDeg) {
                    continue;
                }
                double[] proj = pointToSegment(b.lat, b.lon, prevLat, prevLon, lat, lon);
                if (proj[0] > b.radiusM) continue;

                // Per-booth cooldown: require 10 min AND 500 m exit (or >= 10 min on long return trip).
                Long prevHit = lastHitMs.get(b.id);
                if (prevHit != null && (tsMs - prevHit) < COOLDOWN_MS) continue;

                long crossTs = prevTs + Math.round(proj[1] * (tsMs - prevTs));
                double crossLat = prevLat + proj[1] * (lat - prevLat);
                double crossLon = prevLon + proj[1] * (lon - prevLon);
                double crossSpd = (prevSpeed + speedKmh) / 2.0;

                // Collapse closely spaced duplicate nodes at the same physical plaza.
                if (lastAcceptedHit != null
                        && Math.abs(crossTs - lastAcceptedHit.tsMs) <= CLUSTER_COLLAPSE_MS
                        && distanceM(crossLat, crossLon, lastAcceptedHit.lat, lastAcceptedHit.lon) <= CLUSTER_COLLAPSE_M) {
                    continue;
                }

                Hit h = new Hit(b, crossTs, crossLat, crossLon, crossSpd, proj[0],
                    b.effectivePrice(crossTs, zone));
                lastHitMs.put(b.id, crossTs);
                exitedRadius.put(b.id, false);
                lastAcceptedHit = h;
                if (stepHits == null) stepHits = new ArrayList<>();
                stepHits.add(h);
            }

            prevTs = tsMs; prevLat = lat; prevLon = lon; prevSpeed = speedKmh;
            return stepHits == null ? Collections.emptyList() : stepHits;
        }
    }

    /** Batch-matches a completed trip's saved GPS track against indexed booths,
     * collapsing consecutive Free Flow corridor gantries into one drop-off passage. */
    public static List<Hit> matchTrack(SpatialIndex index, long[] ts, double[] lat,
                                       double[] lon, double[] speedKmh, ZoneId zone) {
        if (index == null || index.isEmpty() || ts == null || ts.length < 2) return Collections.emptyList();
        Runner r = new Runner(index, zone);
        List<Hit> out = new ArrayList<>();
        int n = Math.min(Math.min(ts.length, lat.length), lon.length);
        for (int i = 0; i < n; i++) {
            double spd = (speedKmh != null && i < speedKmh.length) ? speedKmh[i] : 0;
            List<Hit> hits = r.onPoint(ts[i], lat[i], lon[i], spd);
            if (!hits.isEmpty()) out.addAll(hits);
        }
        return collapseFreeFlowCorridors(out);
    }

    /** Collapses consecutive Free Flow corridor gantries (e.g. Dutra express lane
     * sensors spaced 2-4 min apart) into ONE passage anchored at the final drop-off
     * gantry. Standalone gantries (> 10 min or > 12 km apart) and physical plazas
     * remain separate. */
    public static List<Hit> collapseFreeFlowCorridors(List<Hit> hits) {
        if (hits == null || hits.size() <= 1) return hits == null ? Collections.emptyList() : hits;
        List<Hit> out = new ArrayList<>();
        int i = 0;
        while (i < hits.size()) {
            Hit first = hits.get(i);
            if (!KIND_FREE_FLOW.equals(first.booth.kind)) {
                out.add(first);
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < hits.size()) {
                Hit cur = hits.get(j);
                Hit nxt = hits.get(j + 1);
                if (!KIND_FREE_FLOW.equals(nxt.booth.kind)) break;
                long dt = nxt.tsMs - cur.tsMs;
                double dist = distanceM(cur.lat, cur.lon, nxt.lat, nxt.lon);
                boolean opCompatible = cur.booth.operator == null || nxt.booth.operator == null
                    || cur.booth.operator.equalsIgnoreCase(nxt.booth.operator);
                if (dt <= FREE_FLOW_CORRIDOR_GAP_MS && dist <= FREE_FLOW_CORRIDOR_GAP_M && opCompatible) {
                    j++;
                } else {
                    break;
                }
            }
            if (j == i) {
                out.add(first);
            } else {
                Hit exit = hits.get(j);
                String entryName = first.booth.name;
                String exitName = exit.booth.name;
                String combined = (entryName != null && !entryName.equalsIgnoreCase(exitName))
                    ? (entryName + " → " + exitName) : exitName;
                Booth eb = exit.booth;
                Booth mergedBooth = new Booth(eb.id, eb.externalId, combined, eb.operator, eb.kind,
                    eb.lat, eb.lon, eb.radiusM, eb.bearingDeg, eb.bearingTolDeg,
                    eb.chargeRaw, eb.catalogPrice, eb.userPrice, eb.checkDate);
                // Multi-gantry corridor tariffs depend on entry->exit (often dynamic):
                // only auto-price if the user has taught a price for this exit gantry.
                Double cost = eb.userPrice != null ? eb.userPrice : null;
                out.add(new Hit(mergedBooth, exit.tsMs, exit.lat, exit.lon, exit.speedKmh, exit.distanceM, cost,
                    first.entryTsMs, first.entryLat, first.entryLon));
            }
            i = j + 1;
        }
        return out;
    }

    // ---- Pure geometry helpers ----

    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double R = 6_371_000.0;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
            + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2.0 * R * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    public static double angularDiff(double a, double b) {
        double d = Math.abs((a - b) % 360.0);
        return d > 180.0 ? 360.0 - d : d;
    }

    /** Returns {distanceM, t in [0..1]} from point P to segment AB. */
    public static double[] pointToSegment(double plat, double plon,
                                          double lat1, double lon1, double lat2, double lon2) {
        double cosLat = Math.cos(Math.toRadians(plat));
        double mPerDeg = 111_320.0;
        double x1 = (lon1 - plon) * mPerDeg * cosLat, y1 = (lat1 - plat) * mPerDeg;
        double x2 = (lon2 - plon) * mPerDeg * cosLat, y2 = (lat2 - plat) * mPerDeg;
        double dx = x2 - x1, dy = y2 - y1;
        double l2 = dx * dx + dy * dy;
        if (l2 < 1e-6) return new double[]{Math.hypot(x1, y1), 0.0};
        double t = Math.max(0.0, Math.min(1.0, -(x1 * dx + y1 * dy) / l2));
        return new double[]{Math.hypot(x1 + t * dx, y1 + t * dy), t};
    }
}
