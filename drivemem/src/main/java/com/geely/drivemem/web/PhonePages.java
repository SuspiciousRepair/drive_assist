package com.geely.drivemem.web;

import android.content.Context;

import com.geely.drivemem.R;
import com.geely.drivemem.state.CarDiary;
import com.geely.drivemem.util.AppBackup;
import com.geely.drivemem.util.CarCaps;
import com.geely.drivemem.util.Clips;
import com.geely.drivemem.util.DiaryClipTrimmer;
import com.geely.drivemem.util.Prefs;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** The HTML the phone sees. Plain pages, no script: the phone does the
 * drawing, the car only fills in a few values. Every value goes through
 * {@link Http#esc}. Text comes from the app's strings, so it follows the
 * car's language. */
final class PhonePages {
    private PhonePages() {}

    enum Msg { CLOSED, SCAN_AGAIN, PARK, NO_BACKUP, TOO_BIG, NO_SPACE, BUSY, NO_FILE, NO_ROUTE }

    /** The long settings the phone can type. secret = never shown back.
     * Only keys that Prefs.isPhoneRestorable allows (PhonePagesTest checks
     * the list). The update address is here by the owner's choice: it is
     * how a car away from home is pointed at its update server. */
    static final class Field {
        final String key; final int label; final boolean secret;
        Field(String key, int label, boolean secret) { this.key = key; this.label = label; this.secret = secret; }
    }

    static final Field[] FIELDS = {
        new Field("mqtt_uri", R.string.phone_f_mqtt_uri, false),
        new Field("mqtt_user", R.string.phone_f_mqtt_user, false),
        new Field("mqtt_pass", R.string.phone_f_mqtt_pass, true),
        new Field("abrp_user_token", R.string.phone_f_abrp_token, true),
        new Field("update_url", R.string.phone_f_update_url, false),
    };

    private static final String CSS =
        ":root{color-scheme:light dark;--bg:#f4f5f7;--card:#fff;--text:#16181d;--dim:#5b616e;--line:#d9dce1;--accent:#1f6feb}"
        + "@media (prefers-color-scheme:dark){:root{--bg:#0f1115;--card:#1a1d23;--text:#e8eaed;--dim:#9aa0aa;--line:#2c3038;--accent:#5b9dff}}"
        + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);"
        + "font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}"
        + "main{max-width:640px;margin:0 auto;padding:16px}h1{font-size:22px;margin:8px 0 16px}"
        + "h2{font-size:17px;margin:0 0 8px}.card{background:var(--card);border:1px solid var(--line);"
        + "border-radius:12px;padding:16px;margin:0 0 12px}p{margin:4px 0}.dim{color:var(--dim);font-size:14px}"
        + "a.btn,button{display:inline-block;background:var(--accent);color:#fff;border:0;border-radius:8px;"
        + "padding:10px 14px;margin:6px 6px 0 0;text-decoration:none;font:inherit;cursor:pointer}"
        + "label{display:block;margin:12px 0 4px;font-weight:600}"
        + "input{width:100%;padding:10px;border:1px solid var(--line);border-radius:8px;background:var(--bg);"
        + "color:var(--text);font:15px ui-monospace,monospace}"
        + "ul{list-style:none;padding:0;margin:0}li{display:flex;justify-content:space-between;gap:12px;"
        + "align-items:center;padding:10px 0;border-top:1px solid var(--line)}li:first-child{border-top:0}"
        + ".ok{color:#1a7f37;font-weight:600}a{color:var(--accent)}"
        + "input[type=checkbox]{width:auto;margin:0 8px 0 0}label.check{display:flex;align-items:center;font-weight:400}"
        + "select{width:100%;padding:10px;border:1px solid var(--line);border-radius:8px;background:var(--bg);"
        + "color:var(--text);font:inherit}button.quiet{background:transparent;color:var(--accent);border:1px solid var(--line)}";

    private static String page(Context c, String title, String body) {
        return page(c, title, body, 0);
    }

    /** refreshS > 0: the phone reloads the page by itself (no script). */
    private static String page(Context c, String title, String body, int refreshS) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + (refreshS > 0 ? "<meta http-equiv=\"refresh\" content=\"" + refreshS + "\">" : "")
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>" + Http.esc(title) + "</title><style>" + CSS + "</style></head><body><main>"
            + body + "</main></body></html>";
    }

    private static String back(Context c) {
        return "<p><a href=\"/\">&larr; " + Http.esc(c.getString(R.string.phone_back)) + "</a></p>";
    }

    static String message(Context c, Msg m) {
        int text;
        switch (m) {
            case CLOSED: text = R.string.phone_msg_closed; break;
            case PARK: text = R.string.phone_msg_park; break;
            case NO_BACKUP: text = R.string.phone_msg_no_backup; break;
            case TOO_BIG: text = R.string.phone_msg_too_big; break;
            case NO_SPACE: text = R.string.phone_msg_no_space; break;
            case BUSY: text = R.string.phone_msg_busy; break;
            case NO_FILE: text = R.string.phone_msg_no_file; break;
            case NO_ROUTE: text = R.string.phone_msg_no_route; break;
            default: text = R.string.phone_msg_scan_again; break;
        }
        String title = c.getString(R.string.phone_title);
        return page(c, title, back(c) + "<h1>" + Http.esc(title) + "</h1><div class=\"card\"><p>"
            + Http.esc(c.getString(text)) + "</p></div>");
    }

    static String index(Context c) {
        StringBuilder b = new StringBuilder();
        String title = c.getString(R.string.phone_title);
        b.append("<h1>").append(Http.esc(title)).append("</h1>");

        b.append("<div class=\"card\"><h2>").append(Http.esc(c.getString(R.string.phone_stats))).append("</h2>")
         .append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_stats_hint))).append("</p>")
         .append("<a class=\"btn\" href=\"/trips.csv\">").append(Http.esc(c.getString(R.string.phone_trips_csv))).append("</a>")
         .append("<a class=\"btn\" href=\"/charges.csv\">").append(Http.esc(c.getString(R.string.phone_charges_csv))).append("</a>")
         .append("</div>");

        b.append("<div class=\"card\"><h2>").append(Http.esc(c.getString(R.string.phone_diary))).append("</h2>")
         .append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_diary_hint))).append("</p>")
         .append("<a class=\"btn\" href=\"/diary\">").append(Http.esc(c.getString(R.string.phone_diary_open))).append("</a>")
         .append("<a class=\"btn\" href=\"/diary.csv\">").append(Http.esc(c.getString(R.string.phone_diary_csv))).append("</a>")
         .append("</div>");

        b.append("<div class=\"card\"><h2>").append(Http.esc(c.getString(R.string.phone_costs))).append("</h2>")
         .append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_costs_hint))).append("</p>")
         .append("<a class=\"btn\" href=\"/tolls\">").append(Http.esc(c.getString(R.string.phone_tolls_title))).append("</a>")
         .append("<a class=\"btn\" href=\"/costs\">").append(Http.esc(c.getString(R.string.parking_title))).append("</a>")
         .append("<a class=\"btn\" href=\"/tolls.csv\">").append(Http.esc(c.getString(R.string.phone_tolls_csv))).append("</a>")
         .append("<a class=\"btn\" href=\"/parking.csv\">").append(Http.esc(c.getString(R.string.phone_parking_csv))).append("</a>")
         .append("</div>");

        b.append("<div class=\"card\"><h2>").append(Http.esc(c.getString(R.string.phone_routes))).append("</h2>")
         .append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_routes_hint))).append("</p>")
         .append("<a class=\"btn\" href=\"/routes\">").append(Http.esc(c.getString(R.string.phone_routes_open))).append("</a></div>");

        b.append("<div class=\"card\"><h2>").append(Http.esc(c.getString(R.string.phone_backup))).append("</h2>");
        List<AppBackup.Found> backups = AppBackup.list();
        if (backups.isEmpty()) {
            b.append("<p>").append(Http.esc(c.getString(R.string.phone_msg_no_backup))).append("</p>");
        } else {
            String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(backups.get(0).createdMs));
            b.append("<p>").append(Http.esc(c.getString(R.string.phone_backup_newest, when))).append("</p>")
             .append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_backup_hint))).append("</p>")
             .append("<a class=\"btn\" href=\"/backup\">").append(Http.esc(c.getString(R.string.phone_download))).append("</a>");
        }
        b.append("<a class=\"btn\" href=\"/restore\">").append(Http.esc(c.getString(R.string.phone_restore_open))).append("</a>");
        b.append("</div>");

        // No 360 cameras, no dashcam, no clips: the card is left out.
        CarCaps caps = CarCaps.read(c);
        if (!CarCaps.lacksCameras(caps)) {
            b.append("<div class=\"card\"><h2>").append(Http.esc(c.getString(R.string.phone_clips))).append("</h2>")
             .append("<a class=\"btn\" href=\"/clips\">").append(Http.esc(c.getString(R.string.phone_clips_open))).append("</a></div>");
        }

        b.append("<div class=\"card\"><h2>").append(Http.esc(c.getString(R.string.phone_settings))).append("</h2>")
         .append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_settings_hint))).append("</p>")
         .append("<a class=\"btn\" href=\"/settings\">").append(Http.esc(c.getString(R.string.phone_settings_open))).append("</a></div>");

        b.append(carCaps(c, caps));
        return page(c, title, b.toString());
    }

    /** The System page's "Car equipment" card, as text the owner can copy
     * into an issue. */
    static String carCaps(Context c, CarCaps caps) {
        StringBuilder b = new StringBuilder("<div class=\"card\"><h2>")
            .append(Http.esc(c.getString(R.string.caps_header))).append("</h2><p>")
            .append(Http.esc(c.getString(caps == null || caps.cameras == CarCaps.Cameras.UNKNOWN
                ? R.string.caps_cameras_unknown
                : caps.cameras == CarCaps.Cameras.YES ? R.string.caps_cameras_yes : R.string.caps_cameras_no)))
            .append("</p>");
        if (caps != null && !caps.config.isEmpty()) {
            b.append("<p style=\"word-break:break-all\"><code>").append(Http.esc(c.getString(R.string.caps_readings,
                    caps.type, caps.variant, caps.avm, caps.rvc, caps.dvr,
                CarCaps.shown(caps.aeb), CarCaps.shown(caps.ambient), CarCaps.shown(caps.rhd))))
             .append("<br>").append(Http.esc(caps.config)).append("</code></p>");
        }
        return b.append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.caps_report)))
            .append("</p></div>").toString();
    }

    static String clips(Context c, List<Clips.Clip> all) {
        StringBuilder b = new StringBuilder(back(c));
        String title = c.getString(R.string.phone_clips);
        b.append("<h1>").append(Http.esc(title)).append("</h1><div class=\"card\">");
        DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        int n = 0;
        StringBuilder list = new StringBuilder("<ul>");
        for (Clips.Clip clip : all) {
            if (clip.kind != Clips.Kind.DONE) continue;
            n++;
            StringBuilder what = new StringBuilder(fmt.format(new Date(clip.whenMs)));
            if (clip.seconds > 0) what.append(" · ").append(clip.seconds / 60).append(':')
                .append(String.format(java.util.Locale.US, "%02d", clip.seconds % 60));
            what.append(" · ").append(Clips.mb(clip.bytes));
            if (clip.held) what.append(" · ").append(c.getString(R.string.phone_clip_held));
            if (clip.motion) what.append(" · ").append(c.getString(R.string.phone_clip_park));
            list.append("<li><span>").append(Http.esc(what.toString())).append("</span>")
                .append("<a class=\"btn\" href=\"/clip?f=").append(Http.esc(Http.urlEnc(clip.mp4.getName())))
                .append("\">").append(Http.esc(c.getString(R.string.phone_download))).append("</a></li>");
        }
        list.append("</ul>");
        if (n == 0) b.append("<p>").append(Http.esc(c.getString(R.string.phone_no_clips))).append("</p>");
        else b.append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_clips_hint))).append("</p>").append(list);
        b.append("</div>");
        return page(c, title, b.toString());
    }

    /** Printable/viewable Car Diary report with CSV & 5s incident clip downloads. */
    static String diary(Context c, List<CarDiary.Entry> entries) {
        StringBuilder b = new StringBuilder(back(c));
        String title = c.getString(R.string.phone_diary);
        b.append("<h1>").append(Http.esc(title)).append("</h1>");
        b.append("<div class=\"card\">");
        double totalCost = 0;
        int count = 0;
        if (entries != null) {
            for (CarDiary.Entry e : entries) {
                if (e.parentId != null) continue;
                count++;
                if (e.cost != null && e.cost > 0) totalCost += e.cost;
            }
        }
        String summary = totalCost > 0
            ? count + " · " + Prefs.formatMoney(totalCost)
            : String.valueOf(count);
        b.append("<p class=\"dim\">").append(Http.esc(summary)).append("</p>")
         .append("<a class=\"btn\" href=\"/diary.csv\">").append(Http.esc(c.getString(R.string.phone_diary_csv))).append("</a>");

        if (count == 0) {
            b.append("<p style=\"margin-top:12px\">").append(Http.esc(c.getString(R.string.diary_empty))).append("</p>");
        } else {
            DateFormat fmt = DateFormat.getDateInstance(DateFormat.MEDIUM);
            b.append("<ul style=\"margin-top:12px\">");
            for (CarDiary.Entry e : entries) {
                if (e.parentId != null) continue;
                String label = (e.title != null && !e.title.isEmpty())
                    ? e.title : (e.category != null ? c.getString(e.category.labelRes) : "");
                StringBuilder meta = new StringBuilder(fmt.format(new Date(e.tsMs)));
                if (e.odoKm != null && e.odoKm > 0) {
                    meta.append(" · ").append(String.format(java.util.Locale.getDefault(), "%,.0f km", e.odoKm));
                }
                if (e.cost != null && e.cost > 0) {
                    meta.append(" · ").append(Prefs.formatMoney(e.cost));
                }
                if (e.note != null && !e.note.isEmpty()) {
                    meta.append(" — ").append(e.note);
                }
                b.append("<li><div><strong>").append(Http.esc(label)).append("</strong>")
                 .append("<p class=\"dim\">").append(Http.esc(meta.toString())).append("</p></div>");
                if (e.clipName != null && DiaryClipTrimmer.resolveClip(c, e.clipName) != null) {
                    b.append("<a class=\"btn\" href=\"/diary-clip?f=").append(Http.esc(Http.urlEnc(e.clipName)))
                     .append("\">").append(Http.esc(c.getString(R.string.diary_btn_play_clip))).append("</a>");
                }
                b.append("</li>");
            }
            b.append("</ul>");
        }
        b.append("</div>");
        return page(c, title, b.toString());
    }

    /** A price for an input field: "12,50" in the car's locale, empty when unset. */
    static String moneyField(Double v) {
        if (v == null || v <= 0) return "";
        return String.format(java.util.Locale.getDefault(), "%.2f", v);
    }

    private static String savedCard(Context c) {
        return "<div class=\"card\"><p class=\"ok\">" + Http.esc(c.getString(R.string.phone_saved)) + "</p></div>";
    }

    /** The toll booths this car crossed (or the owner priced, pinned or
     * turned off): one small form each for the price, both directions, and
     * on/off. */
    static String tolls(Context c, List<com.geely.drivemem.state.TollTracker.BoothRow> booths, boolean saved) {
        StringBuilder b = new StringBuilder(back(c));
        String title = c.getString(R.string.phone_tolls_title);
        b.append("<h1>").append(Http.esc(title)).append("</h1>");
        if (saved) b.append(savedCard(c));
        b.append("<div class=\"card\"><p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_tolls_hint)))
         .append("</p><a class=\"btn\" href=\"/tolls.csv\">").append(Http.esc(c.getString(R.string.phone_tolls_csv)))
         .append("</a></div>");
        if (booths.isEmpty()) {
            b.append("<div class=\"card\"><p>").append(Http.esc(c.getString(R.string.phone_no_tolls))).append("</p></div>");
            return page(c, title, b.toString());
        }
        for (com.geely.drivemem.state.TollTracker.BoothRow t : booths) {
            String kind = c.getString(com.geely.drivemem.sensors.TollMatcher.KIND_FREE_FLOW.equals(t.kind)
                ? R.string.toll_kind_free_flow : R.string.toll_kind_plaza);
            StringBuilder meta = new StringBuilder(kind).append(" · ")
                .append(c.getString(R.string.phone_toll_passages, t.passages));
            if (t.catalogPrice != null && t.catalogPrice > 0) {
                meta.append(" · ").append(c.getString(R.string.phone_toll_osm_price, Prefs.formatMoney(t.catalogPrice)));
            }
            if (t.bearingDeg == null) meta.append(" · ").append(c.getString(R.string.phone_toll_both_ways));
            b.append("<form class=\"card\" method=\"post\" action=\"/tolls\" autocomplete=\"off\">")
             .append("<h2>").append(Http.esc(t.name)).append("</h2>")
             .append("<p class=\"dim\">").append(Http.esc(meta.toString())).append("</p>")
             .append("<input type=\"hidden\" name=\"id\" value=\"").append(t.id).append("\">")
             .append("<label for=\"p").append(t.id).append("\">").append(Http.esc(c.getString(R.string.phone_price,
                 Prefs.currencySymbol()))).append("</label>")
             .append("<input id=\"p").append(t.id).append("\" name=\"price\" inputmode=\"decimal\" value=\"")
             .append(Http.esc(moneyField(t.userPrice))).append("\" placeholder=\"")
             .append(Http.esc(moneyField(t.catalogPrice))).append("\">");
            if (t.bearingDeg != null) {
                b.append("<label class=\"check\"><input type=\"checkbox\" name=\"any\" value=\"1\">")
                 .append(Http.esc(c.getString(R.string.phone_toll_any_dir))).append("</label>");
            }
            b.append("<label class=\"check\"><input type=\"checkbox\" name=\"on\" value=\"1\"")
             .append(t.enabled ? " checked" : "").append(">")
             .append(Http.esc(c.getString(R.string.phone_toll_enabled))).append("</label>")
             .append("<button type=\"submit\">").append(Http.esc(c.getString(R.string.phone_save))).append("</button></form>");
        }
        return page(c, title, b.toString());
    }

    /** Parking costs, newest first: price, place and kind, or delete. */
    static String costs(Context c, List<com.geely.drivemem.state.ParkingCost.Row> rows, boolean saved) {
        StringBuilder b = new StringBuilder(back(c));
        String title = c.getString(R.string.parking_title);
        b.append("<h1>").append(Http.esc(title)).append("</h1>");
        if (saved) b.append(savedCard(c));
        b.append("<div class=\"card\"><a class=\"btn\" href=\"/parking.csv\">")
         .append(Http.esc(c.getString(R.string.phone_parking_csv))).append("</a></div>");
        if (rows.isEmpty()) {
            b.append("<div class=\"card\"><p>").append(Http.esc(c.getString(R.string.phone_no_parking))).append("</p></div>");
            return page(c, title, b.toString());
        }
        DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
        DateFormat timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT);
        java.util.List<com.geely.drivemem.state.ParkingCost.Row> sorted = new java.util.ArrayList<>(rows);
        sorted.sort((x, y) -> Long.compare(y.startMs, x.startMs));
        for (com.geely.drivemem.state.ParkingCost.Row r : sorted) {
            String when = fmt.format(new Date(r.startMs))
                + (r.endMs != null && r.endMs > r.startMs ? " – " + timeFmt.format(new Date(r.endMs)) : "");
            String what = r.valetId != null ? c.getString(R.string.parking_valet_title)
                : c.getString(r.isStreet() ? R.string.parking_kind_street : R.string.parking_kind_lot);
            b.append("<form class=\"card\" method=\"post\" action=\"/costs\" autocomplete=\"off\">")
             .append("<h2>").append(Http.esc(when)).append("</h2>")
             .append("<p class=\"dim\">").append(Http.esc(what)).append("</p>")
             .append("<input type=\"hidden\" name=\"id\" value=\"").append(r.id).append("\">")
             .append("<label for=\"n").append(r.id).append("\">").append(Http.esc(c.getString(R.string.parking_place_hint)))
             .append("</label><input id=\"n").append(r.id).append("\" name=\"name\" maxlength=\"80\" value=\"")
             .append(Http.esc(r.placeName == null ? "" : r.placeName)).append("\">")
             .append("<label for=\"p").append(r.id).append("\">").append(Http.esc(c.getString(R.string.phone_price,
                 Prefs.currencySymbol()))).append("</label>")
             .append("<input id=\"p").append(r.id).append("\" name=\"price\" inputmode=\"decimal\" value=\"")
             .append(Http.esc(moneyField(r.cost))).append("\">");
            if (r.valetId == null) {
                b.append("<label for=\"k").append(r.id).append("\">").append(Http.esc(c.getString(R.string.phone_parking_kind)))
                 .append("</label><select id=\"k").append(r.id).append("\" name=\"kind\">")
                 .append("<option value=\"lot\"").append(r.isStreet() ? "" : " selected").append(">")
                 .append(Http.esc(c.getString(R.string.parking_kind_lot))).append("</option>")
                 .append("<option value=\"street\"").append(r.isStreet() ? " selected" : "").append(">")
                 .append(Http.esc(c.getString(R.string.parking_kind_street))).append("</option></select>");
            }
            b.append("<button type=\"submit\">").append(Http.esc(c.getString(R.string.phone_save))).append("</button>")
             .append("<button class=\"quiet\" type=\"submit\" name=\"del\" value=\"1\">")
             .append(Http.esc(c.getString(R.string.parking_btn_clear))).append("</button></form>");
        }
        return page(c, title, b.toString());
    }

    /** The months that have routes, newest first. A month opens its days. */
    static String routes(Context c, List<String> months) {
        StringBuilder b = new StringBuilder(back(c));
        String title = c.getString(R.string.phone_routes);
        b.append("<h1>").append(Http.esc(title)).append("</h1><div class=\"card\">");
        if (months.isEmpty()) {
            b.append("<p>").append(Http.esc(c.getString(R.string.phone_msg_no_route))).append("</p>");
        } else {
            b.append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_routes_hint))).append("</p><ul>");
            for (String m : months) {
                b.append("<li><span>").append(Http.esc(m)).append("</span>")
                 .append("<a class=\"btn\" href=\"/routes?m=").append(Http.esc(m)).append("\">")
                 .append(Http.esc(c.getString(R.string.phone_routes_open))).append("</a></li>");
            }
            b.append("</ul>");
        }
        b.append("</div>");
        return page(c, title, b.toString());
    }

    /** One month: a card per day, with the day's GPX and one GPX per trip. */
    static String routesMonth(Context c, String month, List<com.geely.drivemem.sensors.DailyStatsProvider.DriveSession> trips) {
        StringBuilder b = new StringBuilder("<p><a href=\"/routes\">&larr; ")
            .append(Http.esc(c.getString(R.string.phone_routes))).append("</a></p>");
        b.append("<h1>").append(Http.esc(month)).append("</h1>");
        if (trips.isEmpty()) {
            b.append("<div class=\"card\"><p>").append(Http.esc(c.getString(R.string.phone_msg_no_route))).append("</p></div>");
            return page(c, month, b.toString());
        }
        java.text.SimpleDateFormat key = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        DateFormat dayFmt = DateFormat.getDateInstance(DateFormat.FULL);
        DateFormat timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT);
        String open = null;
        for (com.geely.drivemem.sensors.DailyStatsProvider.DriveSession t : trips) {
            Date start = new Date(t.startMs);
            String day = key.format(start);
            if (!day.equals(open)) {
                if (open != null) b.append("</ul></div>");
                open = day;
                b.append("<div class=\"card\"><h2>").append(Http.esc(dayFmt.format(start))).append("</h2>")
                 .append("<a class=\"btn\" href=\"/routes.gpx?d=").append(day).append("\">")
                 .append(Http.esc(c.getString(R.string.phone_routes_day))).append("</a><ul style=\"margin-top:12px\">");
            }
            String what = timeFmt.format(start) + String.format(java.util.Locale.US, " · %.1f km", t.distanceKm);
            b.append("<li><span>").append(Http.esc(what)).append("</span>")
             .append("<a class=\"btn\" href=\"/routes.gpx?trip=").append(t.tripId).append("\">")
             .append(Http.esc(c.getString(R.string.phone_routes_trip))).append("</a></li>");
        }
        b.append("</ul></div>");
        return page(c, month, b.toString());
    }

    /** The upload form, or how the restore is going. */
    static String restore(Context c, boolean running, String error) {
        StringBuilder b = new StringBuilder(back(c));
        String title = c.getString(R.string.phone_restore_title);
        b.append("<h1>").append(Http.esc(title)).append("</h1>");
        if (running) {
            b.append("<div class=\"card\"><p>").append(Http.esc(c.getString(R.string.phone_restore_running)))
             .append("</p></div>");
            return page(c, title, b.toString(), 3);
        }
        if (error != null) {
            b.append("<div class=\"card\"><p>").append(Http.esc(error)).append("</p></div>");
        }
        b.append("<form class=\"card\" method=\"post\" action=\"/restore\" enctype=\"multipart/form-data\" autocomplete=\"off\">")
         .append("<p>").append(Http.esc(c.getString(R.string.phone_restore_hint))).append("</p>")
         // The password goes first: the car reads the form in order, and
         // needs it before the file is done.
         .append("<label for=\"password\">").append(Http.esc(c.getString(R.string.phone_restore_pw))).append("</label>")
         .append("<input id=\"password\" name=\"password\" type=\"password\" placeholder=\"")
         .append(Http.esc(c.getString(R.string.phone_restore_pw_hint))).append("\">")
         .append("<label for=\"file\">").append(Http.esc(c.getString(R.string.phone_restore_file))).append("</label>")
         .append("<input id=\"file\" name=\"file\" type=\"file\" accept=\"").append(AppBackup.EXT).append("\" required>")
         .append("<button type=\"submit\">").append(Http.esc(c.getString(R.string.backup_restore))).append("</button></form>");
        return page(c, title, b.toString());
    }

    static String settings(Context c, boolean saved) {
        StringBuilder b = new StringBuilder(back(c));
        String title = c.getString(R.string.phone_settings);
        b.append("<h1>").append(Http.esc(title)).append("</h1>");
        if (saved) b.append("<div class=\"card\"><p class=\"ok\">")
            .append(Http.esc(c.getString(R.string.phone_saved))).append("</p></div>");
        b.append("<form class=\"card\" method=\"post\" action=\"/settings\" autocomplete=\"off\">")
         .append("<p class=\"dim\">").append(Http.esc(c.getString(R.string.phone_settings_form_hint))).append("</p>");
        android.content.SharedPreferences p = Prefs.file(c);
        for (Field f : FIELDS) {
            String now = p.getString(f.key, "");
            boolean set = now != null && !now.isEmpty();
            b.append("<label for=\"").append(f.key).append("\">").append(Http.esc(c.getString(f.label))).append("</label>")
             .append("<input id=\"").append(f.key).append("\" name=\"").append(f.key).append("\" ")
             .append(f.secret ? "type=\"password\" " : "type=\"text\" ")
             .append("autocapitalize=\"off\" spellcheck=\"false\" placeholder=\"")
             .append(Http.esc(f.secret ? c.getString(set ? R.string.phone_secret_set : R.string.phone_secret_unset)
                                       : (set ? now : "")))
             .append("\">");
        }
        b.append("<button type=\"submit\">").append(Http.esc(c.getString(R.string.phone_save))).append("</button></form>");
        return page(c, title, b.toString());
    }
}
