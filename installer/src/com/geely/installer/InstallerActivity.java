package com.geely.installer;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

/**
 * Driver-facing setup wizard for Drive Assist on Geely EX2 (IHU629G).
 *
 * Provides a two-phase flow:
 * 1. Changelog & Update Confirmation: Displays version comparison and release notes,
 *    allowing the driver to review and Accept or Decline.
 * 2. Progress & Execution: Extracts bundled APKs, installs ModeHelper and DriveMem,
 *    starts background services, launches Drive Assist, and self-uninstalls.
 *
 * CRITICAL ARCHITECTURAL CONSTRAINTS:
 * - Shared UID system (android.uid.system): NEVER load or reference android.webkit.WebView.
 * - Display metrics: 1920x1080 px at density 1.00 (1 dp = 1 px = 1 sp).
 */
public class InstallerActivity extends Activity implements InstallerCore.Listener {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // Version metadata
    private String newVersionName = "v1.2.0";
    private int newVersionCode = 1;
    private String newGitSha = "";
    private String newBuildDate = "";
    private String installedVersionName = null;
    private int installedVersionCode = 0;
    private boolean isUpdate = false;
    private String changelogText = "";

    // UI containers
    private TextView titleView;
    private TextView subtitleView;
    private LinearLayout reviewLayout;
    private LinearLayout progressLayout;

    // Progress widgets
    private TextView statusTextView;
    private ProgressBar progressBar;
    private TextView logTextView;
    private ScrollView logScrollView;
    private LinearLayout failureButtonBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadMetadata();
        buildUi();

        boolean autoStart = getIntent().getBooleanExtra("auto", false);
        if (autoStart) {
            startInstallation();
        }
    }

    private void loadMetadata() {
        // 1. Read version.properties from bundled assets
        Properties props = new Properties();
        try (InputStream in = getAssets().open("version.properties")) {
            props.load(in);
            newVersionName = props.getProperty("versionName", "v1.2.0");
            newVersionCode = Integer.parseInt(props.getProperty("versionCode", "1"));
            newGitSha = props.getProperty("gitSha", "");
            newBuildDate = props.getProperty("buildDate", "");
        } catch (Throwable ignored) {}

        // 2. Read changelog.txt from bundled assets
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getAssets().open("changelog.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            changelogText = sb.toString().trim();
        } catch (Throwable e) {
            changelogText = "• Bug fixes, performance improvements, and telemetry updates.";
        }

        // 3. Inspect currently installed Drive Assist (com.geely.drivemem)
        PackageManager pm = getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(InstallerCore.PKG_DRIVEMEM, 0);
            installedVersionName = pi.versionName;
            installedVersionCode = pi.versionCode;
            isUpdate = true;
        } catch (PackageManager.NameNotFoundException e) {
            installedVersionName = null;
            installedVersionCode = 0;
            isUpdate = false;
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(Color.parseColor("#0C0D12"));

        // Main Card Container (1100px wide, centered)
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(1100, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        card.setLayoutParams(cardLp);
        card.setPadding(38, 32, 38, 32);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#17181F"));
        cardBg.setCornerRadius(18f);
        cardBg.setStroke(2, Color.parseColor("#262833"));
        card.setBackground(cardBg);

        // Header Section
        TextView badge = new TextView(this);
        badge.setText("GEELY EX2 · IHU629G PLATFORM");
        badge.setTextColor(Color.parseColor("#00D2FF"));
        badge.setTextSize(13f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setLetterSpacing(0.08f);
        card.addView(badge);

        titleView = new TextView(this);
        titleView.setText(isUpdate ? "Drive Assist Update" : "Drive Assist Setup");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(32f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(0, 6, 0, 4);
        titleView.setLayoutParams(titleLp);
        card.addView(titleView);

        subtitleView = new TextView(this);
        subtitleView.setText(isUpdate
            ? "An update is ready for Drive Assist. Review the changes below before installing."
            : "Install Drive Assist and background companion service on your center screen.");
        subtitleView.setTextColor(Color.parseColor("#8E8E9A"));
        subtitleView.setTextSize(16f);
        card.addView(subtitleView);

        // Divider
        View sep = new View(this);
        LinearLayout.LayoutParams sepLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 2);
        sepLp.setMargins(0, 18, 0, 18);
        sep.setLayoutParams(sepLp);
        sep.setBackgroundColor(Color.parseColor("#232530"));
        card.addView(sep);

        // -------------------------------------------------------------
        // PHASE 1: Changelog & Confirmation View
        // -------------------------------------------------------------
        reviewLayout = new LinearLayout(this);
        reviewLayout.setOrientation(LinearLayout.VERTICAL);
        reviewLayout.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Version Comparison Card
        LinearLayout verCard = new LinearLayout(this);
        verCard.setOrientation(LinearLayout.HORIZONTAL);
        verCard.setGravity(Gravity.CENTER_VERTICAL);
        verCard.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        verCard.setPadding(20, 14, 20, 14);
        GradientDrawable verBg = new GradientDrawable();
        verBg.setColor(Color.parseColor("#111218"));
        verBg.setCornerRadius(10f);
        verBg.setStroke(1, Color.parseColor("#232532"));
        verCard.setBackground(verBg);

        // Col 1: Current
        LinearLayout colCur = new LinearLayout(this);
        colCur.setOrientation(LinearLayout.VERTICAL);
        colCur.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView curLbl = new TextView(this);
        curLbl.setText("CURRENT VERSION");
        curLbl.setTextColor(Color.parseColor("#7A7D8E"));
        curLbl.setTextSize(12f);
        curLbl.setTypeface(Typeface.DEFAULT_BOLD);
        colCur.addView(curLbl);

        TextView curVal = new TextView(this);
        curVal.setText(isUpdate ? installedVersionName : "Not installed");
        curVal.setTextColor(isUpdate ? Color.parseColor("#D0D2DE") : Color.parseColor("#FFA200"));
        curVal.setTextSize(16f);
        curVal.setTypeface(Typeface.DEFAULT_BOLD);
        colCur.addView(curVal);
        verCard.addView(colCur);

        // Arrow / Status Tag
        TextView statusBadge = new TextView(this);
        if (!isUpdate) {
            statusBadge.setText("NEW INSTALL");
            statusBadge.setTextColor(Color.parseColor("#00D2FF"));
        } else if (newVersionCode > installedVersionCode) {
            statusBadge.setText("UPDATE AVAILABLE");
            statusBadge.setTextColor(Color.parseColor("#30D158"));
        } else if (newVersionCode == installedVersionCode) {
            statusBadge.setText("SAME VERSION (REPAIR)");
            statusBadge.setTextColor(Color.parseColor("#FFD60A"));
        } else {
            statusBadge.setText("DOWNGRADE");
            statusBadge.setTextColor(Color.parseColor("#FF453A"));
        }
        statusBadge.setTextSize(12f);
        statusBadge.setTypeface(Typeface.DEFAULT_BOLD);
        statusBadge.setPadding(16, 6, 16, 6);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(Color.parseColor("#1B1E29"));
        badgeBg.setCornerRadius(6f);
        badgeBg.setStroke(1, Color.parseColor("#2F3346"));
        statusBadge.setBackground(badgeBg);
        verCard.addView(statusBadge);

        // Col 2: New Update
        LinearLayout colNew = new LinearLayout(this);
        colNew.setOrientation(LinearLayout.VERTICAL);
        colNew.setGravity(Gravity.RIGHT);
        colNew.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView newLbl = new TextView(this);
        newLbl.setText("NEW PACKAGE");
        newLbl.setTextColor(Color.parseColor("#7A7D8E"));
        newLbl.setTextSize(12f);
        newLbl.setTypeface(Typeface.DEFAULT_BOLD);
        colNew.addView(newLbl);

        TextView newVal = new TextView(this);
        newVal.setText(newVersionName);
        newVal.setTextColor(Color.WHITE);
        newVal.setTextSize(16f);
        newVal.setTypeface(Typeface.DEFAULT_BOLD);
        colNew.addView(newVal);
        verCard.addView(colNew);

        reviewLayout.addView(verCard);

        // Changelog Label
        TextView clLabel = new TextView(this);
        clLabel.setText("RELEASE NOTES & WHAT'S NEW:");
        clLabel.setTextColor(Color.parseColor("#8E8E9A"));
        clLabel.setTextSize(13f);
        clLabel.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams clLblLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clLblLp.setMargins(0, 16, 0, 8);
        clLabel.setLayoutParams(clLblLp);
        reviewLayout.addView(clLabel);

        // Changelog Scroll Box
        ScrollView clScrollView = new ScrollView(this);
        LinearLayout.LayoutParams clScrollLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 230);
        clScrollView.setLayoutParams(clScrollLp);

        GradientDrawable clBg = new GradientDrawable();
        clBg.setColor(Color.parseColor("#0F1015"));
        clBg.setCornerRadius(10f);
        clBg.setStroke(1, Color.parseColor("#22242F"));
        clScrollView.setBackground(clBg);
        clScrollView.setPadding(20, 16, 20, 16);

        TextView clContent = new TextView(this);
        clContent.setText(changelogText);
        clContent.setTextColor(Color.parseColor("#D2D4E4"));
        clContent.setTextSize(15f);
        clContent.setLineSpacing(5f, 1f);
        clScrollView.addView(clContent);
        reviewLayout.addView(clScrollView);

        // Review Action Buttons Bar (Decline / Accept)
        LinearLayout actionButtonsBar = new LinearLayout(this);
        actionButtonsBar.setOrientation(LinearLayout.HORIZONTAL);
        actionButtonsBar.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams abbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abbLp.setMargins(0, 20, 0, 0);
        actionButtonsBar.setLayoutParams(abbLp);

        Button declineButton = new Button(this);
        declineButton.setText("Decline / Cancel");
        declineButton.setTextColor(Color.parseColor("#D0D0D5"));
        declineButton.setTextSize(16f);
        LinearLayout.LayoutParams decLp = new LinearLayout.LayoutParams(180, 56);
        decLp.setMargins(0, 0, 16, 0);
        declineButton.setLayoutParams(decLp);
        GradientDrawable decBg = new GradientDrawable();
        decBg.setColor(Color.parseColor("#292A34"));
        decBg.setCornerRadius(8f);
        declineButton.setBackground(decBg);
        declineButton.setOnClickListener(v -> finish());
        actionButtonsBar.addView(declineButton);

        Button acceptButton = new Button(this);
        acceptButton.setText(isUpdate ? "Accept & Install Update" : "Accept & Install");
        acceptButton.setTextColor(Color.WHITE);
        acceptButton.setTextSize(16f);
        acceptButton.setTypeface(Typeface.DEFAULT_BOLD);
        acceptButton.setLayoutParams(new LinearLayout.LayoutParams(250, 56));
        GradientDrawable accBg = new GradientDrawable();
        accBg.setColor(Color.parseColor("#007AFF"));
        accBg.setCornerRadius(8f);
        acceptButton.setBackground(accBg);
        acceptButton.setOnClickListener(v -> startInstallation());
        actionButtonsBar.addView(acceptButton);

        reviewLayout.addView(actionButtonsBar);
        card.addView(reviewLayout);

        // -------------------------------------------------------------
        // PHASE 2: Installation Progress View (initially GONE)
        // -------------------------------------------------------------
        progressLayout = new LinearLayout(this);
        progressLayout.setOrientation(LinearLayout.VERTICAL);
        progressLayout.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progressLayout.setVisibility(View.GONE);

        // Status Text
        statusTextView = new TextView(this);
        statusTextView.setText("Preparing components...");
        statusTextView.setTextColor(Color.parseColor("#00D2FF"));
        statusTextView.setTextSize(20f);
        statusTextView.setTypeface(Typeface.DEFAULT_BOLD);
        progressLayout.addView(statusTextView);

        // Progress Bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 16);
        pbLp.setMargins(0, 12, 0, 18);
        progressBar.setLayoutParams(pbLp);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressLayout.addView(progressBar);

        // Live Log Console Box
        logScrollView = new ScrollView(this);
        LinearLayout.LayoutParams logScrollLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 240);
        logScrollView.setLayoutParams(logScrollLp);

        GradientDrawable logBg = new GradientDrawable();
        logBg.setColor(Color.parseColor("#0F1015"));
        logBg.setCornerRadius(10f);
        logBg.setStroke(1, Color.parseColor("#22242F"));
        logScrollView.setBackground(logBg);
        logScrollView.setPadding(20, 16, 20, 16);

        logTextView = new TextView(this);
        logTextView.setTextColor(Color.parseColor("#C2C4D4"));
        logTextView.setTextSize(14f);
        logTextView.setTypeface(Typeface.MONOSPACE);
        logTextView.setLineSpacing(4f, 1f);
        logScrollView.addView(logTextView);
        progressLayout.addView(logScrollView);

        // Failure Button Bar
        failureButtonBar = new LinearLayout(this);
        failureButtonBar.setOrientation(LinearLayout.HORIZONTAL);
        failureButtonBar.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fbLp.setMargins(0, 20, 0, 0);
        failureButtonBar.setLayoutParams(fbLp);
        failureButtonBar.setVisibility(View.GONE);

        Button exitBtn = new Button(this);
        exitBtn.setText("Exit");
        exitBtn.setTextColor(Color.WHITE);
        exitBtn.setTextSize(16f);
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(160, 54);
        exitLp.setMargins(0, 0, 16, 0);
        exitBtn.setLayoutParams(exitLp);
        GradientDrawable exitBg = new GradientDrawable();
        exitBg.setColor(Color.parseColor("#2C2D38"));
        exitBg.setCornerRadius(8f);
        exitBtn.setBackground(exitBg);
        exitBtn.setOnClickListener(v -> finish());
        failureButtonBar.addView(exitBtn);

        Button retryBtn = new Button(this);
        retryBtn.setText("Retry Installation");
        retryBtn.setTextColor(Color.WHITE);
        retryBtn.setTextSize(16f);
        retryBtn.setTypeface(Typeface.DEFAULT_BOLD);
        retryBtn.setLayoutParams(new LinearLayout.LayoutParams(220, 54));
        GradientDrawable retryBg = new GradientDrawable();
        retryBg.setColor(Color.parseColor("#007AFF"));
        retryBg.setCornerRadius(8f);
        retryBtn.setBackground(retryBg);
        retryBtn.setOnClickListener(v -> startInstallation());
        failureButtonBar.addView(retryBtn);

        progressLayout.addView(failureButtonBar);
        card.addView(progressLayout);

        root.addView(card);
        setContentView(root);
    }

    private void startInstallation() {
        reviewLayout.setVisibility(View.GONE);
        progressLayout.setVisibility(View.VISIBLE);
        failureButtonBar.setVisibility(View.GONE);
        statusTextView.setText("Installing Drive Assist components...");
        statusTextView.setTextColor(Color.parseColor("#00D2FF"));
        progressBar.setProgress(5);
        logTextView.setText("");

        mainHandler.postDelayed(() -> InstallerCore.run(getApplicationContext(), this), 300);
    }

    @Override
    public void onLog(String message) {
        mainHandler.post(() -> {
            String timestamp = timeFormat.format(new Date());
            logTextView.append("[" + timestamp + "] " + message + "\n");
            logScrollView.post(() -> logScrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    public void onProgress(String statusText, int progressPercent) {
        mainHandler.post(() -> {
            statusTextView.setText(statusText);
            progressBar.setProgress(progressPercent);
        });
    }

    @Override
    public void onError(String errorMessage) {
        mainHandler.post(() -> {
            statusTextView.setText("Installation Failed");
            statusTextView.setTextColor(Color.parseColor("#FF453A"));
            progressBar.setProgress(100);
            failureButtonBar.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onSuccess() {
        mainHandler.post(() -> {
            statusTextView.setText("Installation Successful!");
            statusTextView.setTextColor(Color.parseColor("#30D158"));
            progressBar.setProgress(100);
            mainHandler.postDelayed(this::finish, 3500);
        });
    }
}
