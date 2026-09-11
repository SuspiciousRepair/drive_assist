package com.geely.drivemem.art;

import com.geely.drivemem.ui.ComfortActivity;
import com.geely.drivemem.util.Style;

import android.annotation.SuppressLint;
import android.content.Context;
import android.webkit.WebView;

// HA context card rendered as HTML, with the app's OWN CSS (dark theme, large
// fonts, accent colour = cabin ambient light). HA sends only the HTML body; the
// app injects the style, so every card comes out consistent and legible from the
// driver's seat, without each automation having to repeat CSS. Display only —
// JavaScript disabled.
/** HA context card rendered as HTML with the app's CSS. Display only. */
public class PanelCardView extends WebView {
    private String accentHex = hex(Style.ACCENT);   // default: theme accent
    private String bodyHtml = "";
    private String lastDoc = null;                  // what is on screen right now
    private Runnable onLoaded;                       // told to repack columns, see below

    // A WebView on wrap_content may not size itself correctly without a nudge,
    // especially with remote images that load after the HTML. With JS off,
    // onPageFinished plus a delayed follow-up ensures correct layout.
    public void setOnLoaded(Runnable r) { onLoaded = r; }

    @SuppressLint("SetJavaScriptEnabled")
    public PanelCardView(Context c) {
        super(c);
        setBackgroundColor(0x00000000);           // the CSS paints the background
        android.webkit.WebSettings s = getSettings();
        s.setJavaScriptEnabled(false);
        // remote images (e.g. the ferry camera): without this Android blocks them
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        // content loaded with an https baseURL + an http/https image: allow the mix
        if (android.os.Build.VERSION.SDK_INT >= 21)
            s.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (onLoaded != null) {
                    onLoaded.run();
                    postDelayed(() -> { if (onLoaded != null) onLoaded.run(); }, 400);
                }
            }
        });
    }

    // Record the cabin color to use at render time, not applied on every poll.
    // With JS off, reloading to change colors causes a visible blink. Instead,
    // each card is rendered with the color present at that moment.
    public void setAccent(int rgb) {
        accentHex = String.format("#%06X", rgb & 0xFFFFFF);
    }

    // HTML body coming from HA (e.g. "<h1>Club</h1><p class='big'>12 spots free</p>")
    public void setCardHtml(String html) {
        bodyHtml = (html == null) ? "" : html;
        render();
    }

    private void render() {
        String doc = "<!doctype html><html><head>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>" + css() + "</style></head><body>" + bodyHtml + "</body></html>";
        // Skip loading if the document hasn't changed. A reload blanks the WebView
        // and causes a visible blink. Comparing the full document (body + accent)
        // catches all changes in one check.
        if (doc.equals(lastDoc)) return;
        lastDoc = doc;
        // an https baseURL (non-null) gives the document an origin — without it
        // the WebView treats it as an "opaque" origin and blocks the remote image
        loadDataWithBaseURL("https://console.drivemem/", doc, "text/html", "utf-8", null);
    }

    // System CSS: colors from the active theme, large fonts, accent in cabin light.
    // Transparent layout, natural height. The containing View paints the card
    // background, so don't duplicate it here.
    private String css() {
        String fg = hex(Style.TEXT);
        String strong = hex(Style.LIGHT ? 0xFF000000 : 0xFFFFFFFF);
        String dim = hex(Style.TEXT_DIM), rail = hex(Style.CARD_HI);
        return ":root{--accent:" + accentHex + ";}"
            + "html,body{margin:0;padding:0;background:transparent;color:" + fg + ";"
            + "font-family:sans-serif;-webkit-text-size-adjust:100%;}"
            + "body{padding:32px 36px;box-sizing:border-box;}"
            + "h1{font-size:40px;margin:0 0 16px;color:" + strong
            + ";font-weight:700;line-height:1.1;}"
            + "h2{font-size:30px;margin:0 0 12px;color:" + strong + ";}"
            + "p{font-size:25px;margin:10px 0;line-height:1.35;}"
            + "ul{padding-left:26px;margin:10px 0;} li{font-size:25px;margin:12px 0;}"
            + "strong,b,.accent{color:var(--accent);}"
            + ".big{font-size:56px;font-weight:800;color:var(--accent);"
            + "margin:8px 0;line-height:1;}"
            + "hr{border:none;border-top:2px solid var(--accent);opacity:.5;margin:20px 0;}"
            + "img{max-width:100%;height:auto;display:block;border-radius:"
            + Style.RADIUS_DP + "px;margin:12px 0;}"
            + ".icon{font-size:48px;line-height:1;}"
            + ".dot{display:inline-block;width:22px;height:22px;border-radius:50%;"
            + "background:" + rail + ";vertical-align:middle;margin-right:10px;}"
            + ".dot.on{background:#2ecc71;} .dot.off{background:#e74c3c;}"
            + "small,.dim{color:" + dim + ";font-size:19px;}";
    }

    private static String hex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }
}
