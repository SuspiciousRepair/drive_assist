package com.geely.drivemem.ui;

import android.app.Activity;
import android.content.Context;

import com.geely.drivemem.util.AppLanguage;

/** Apply the language before views are created, including after process death. */
public abstract class LocalizedActivity extends Activity {
    private String languageAtCreation;

    @Override protected void attachBaseContext(Context base) {
        languageAtCreation = AppLanguage.selected(base);
        super.attachBaseContext(AppLanguage.wrap(base));
    }

    @Override protected void onResume() {
        super.onResume();
        // The dashboard can remain alive behind Settings in its singleInstance task.
        if (!languageAtCreation.equals(AppLanguage.selected(this))) {
            recreate();
        }
    }
}
