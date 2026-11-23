package com.geely.drivemem.ui;

import android.app.Activity;
import android.widget.LinearLayout;

import com.geely.drivemem.R;
import com.geely.drivemem.util.Prefs;
import com.geely.drivemem.util.Style;

/** The "Music" integration page: the home screen's music card and its
 * lyrics.
 *
 *  - Phone music: the card shows what the phone plays through the car
 *    (Bluetooth, CarPlay, Android Auto), read from those car apps' own
 *    media browser services, no permission (CarMediaSessions). Nothing
 *    leaves the car.
 *  - Large card: the big card style. ComfortActivity reads it once at
 *    onCreate and recreates itself when it changed.
 *  - Lyrics search: the consent to send the song, artist and album names
 *    to lrclib.net (LyricsClient). The one part that uses the internet.
 *    Read by ComfortActivity on every onResume. */
public final class TelemetryMusicSection extends LinearLayout {

    public TelemetryMusicSection(Activity activity) {
        super(activity);
        setOrientation(VERTICAL);

        LinearLayout col = ConfigCards.column(activity);
        addView(ConfigCards.columns(activity, col));
        LinearLayout card = ConfigCards.card(activity, activity.getString(R.string.int_music_name));
        ConfigCards.add(col, card);

        card.addView(Style.toggleRow(activity, activity.getString(R.string.cfg_bt_music),
            Prefs.getBtMusic(activity), on -> Prefs.setBtMusic(activity, on)));
        card.addView(ConfigCards.hint(activity, activity.getString(R.string.cfg_bt_music_hint)));

        card.addView(Style.toggleRow(activity, activity.getString(R.string.cfg_music_large_card),
            Prefs.getMusicLargeCard(activity), on -> Prefs.setMusicLargeCard(activity, on)));
        card.addView(ConfigCards.hint(activity, activity.getString(R.string.cfg_music_large_card_hint)));

        card.addView(Style.toggleRow(activity, activity.getString(R.string.cfg_lyrics_search),
            Prefs.getLyricsSearch(activity), on -> Prefs.setLyricsSearch(activity, on)));
        card.addView(ConfigCards.hint(activity, activity.getString(R.string.cfg_lyrics_search_hint)));
    }
}
