package app.template.extension.gboard;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Advanced voice typing: setup status, English (US) download, a test field and the voice log.
 * Styled like Gboard's own settings (Material You): large title with a round back button,
 * rounded preference cards, wallpaper-based colors.
 */
public final class VoiceSetupActivity extends Activity {
    private static final String TTS = "com.google.android.tts";
    private static final String TTS_DETAILS =
            "com.google.android.libraries.speech.modelmanager.languagepack.legacy.LanguagePackDetailsReceiver_Receiver";
    private static final String TTS_DOWNLOAD =
            "com.google.android.libraries.speech.modelmanager.languagepack.legacy.DownloadActivity";
    private static final String LANG = "en-US";

    private int nightMode;
    private boolean dark;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed;
    private boolean polling;

    // Colors (Material You palette when available).
    private int cBg, cCard, cText, cSub, cAccent, cOnAccent, cRipple;

    private TextView appStatus;
    private TextView packStatus;
    private ProgressBar packProgress;
    private View packRow;
    private TextView filesStatus;
    private ProgressBar filesProgress;
    private boolean filesBusy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        int theme = getResources().getIdentifier("Theme.DeviceDefault.DayNight.NoActionBar", "style", "android");
        if (theme == 0) theme = android.R.style.Theme_DeviceDefault_NoActionBar;
        setTheme(theme);
        nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = nightMode == Configuration.UI_MODE_NIGHT_YES;
        super.onCreate(savedInstanceState);
        palette();
        Window w = getWindow();
        w.setStatusBarColor(cBg);
        w.setNavigationBarColor(cBg);
        if (!dark) w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(cBg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(root);

        root.addView(titleBar("Advanced voice typing"));

        root.addView(category("Setup"));
        root.addView(note("Tap each step to do it."));
        View app = row("1. Speech Services by Google", "", v -> open(new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + TTS))));
        appStatus = (TextView) app.getTag();
        root.addView(app);

        packProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        packProgress.setMax(100);
        packProgress.setVisibility(View.GONE);
        packProgress.setIndeterminateTintList(ColorStateList.valueOf(cAccent));
        packRow = row("2. English (US) offline speech", "", v -> download(), packProgress);
        packStatus = (TextView) packRow.getTag();
        root.addView(packRow);

        // Builds without Google's formatter files bundled get them from Google once.
        boolean bundled = SmartDictationData.bundled(this);
        if (!bundled) {
            filesProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            filesProgress.setIndeterminate(true);
            filesProgress.setVisibility(View.GONE);
            filesProgress.setIndeterminateTintList(ColorStateList.valueOf(cAccent));
            View files = row("3. Punctuation & emoji files", "", v -> downloadFiles(), filesProgress);
            filesStatus = (TextView) files.getTag();
            root.addView(files);
        }
        root.addView(row((bundled ? "3" : "4") + ". Restart Gboard", "Tap to restart Gboard now. Needed once after setup. "
                + "The colorful mic means advanced voice typing is on.", v -> {
            finishAffinity();
            Process.killProcess(Process.myPid());
        }));
        root.addView(note("Can't download? Settings \u2192 System \u2192 Languages \u2192 Voice input \u2192 "
                + "\u2699 Speech Recognition and Synthesis from Google \u2192 Add a language. "
                + "Names may differ on your phone."));

        root.addView(category("Test"));
        LinearLayout testCard = card();
        TextView hint = new TextView(this);
        hint.setText("Tap the box, then the mic on the keyboard.");
        hint.setTextColor(cSub);
        hint.setTextSize(14);
        testCard.addView(hint);
        EditText test = new EditText(this);
        test.setHint("Type or dictate here");
        test.setHintTextColor(cSub);
        test.setTextColor(cText);
        test.setTextSize(16);
        test.setMinLines(4);
        test.setGravity(Gravity.TOP | Gravity.START);
        test.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        GradientDrawable field = new GradientDrawable();
        field.setCornerRadius(dp(16));
        field.setColor(cBg);
        test.setBackground(field);
        test.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = dp(12);
        testCard.addView(test, fl);
        root.addView(testCard, cardParams());

        root.addView(category("Troubleshooting"));
        root.addView(row("Copy voice log", "Tap to copy the log. If the mic doesn't work, tap it once in the box "
                + "above first, then copy this and share it.", v -> {
            try {
                String log = VoiceDiagnostics.dump();
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("Gboard voice log", log == null ? "" : log));
                android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                android.widget.Toast.makeText(this, "Copy failed", android.widget.Toast.LENGTH_SHORT).show();
            }
        }));

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if ((newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK) != nightMode) recreate();
    }

    // ------------------------------------------------------------------ status

    private boolean appInstalled() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(TTS, 0);
            appStatus.setText("Installed \u00b7 " + pi.versionName + "\nTap to open it in the Play Store");
            return true;
        } catch (Throwable t) {
            appStatus.setText("Not installed \u00b7 tap to get it from the Play Store");
            return false;
        }
    }

    private void showFiles() {
        if (filesStatus == null || filesBusy) return;
        if (SmartDictationData.ready(this)) {
            filesStatus.setText("Downloaded \u00b7 ready");
        } else {
            filesStatus.setText("Not downloaded \u00b7 tap to get them from Google (about 5 MB, one time). "
                    + "Gboard's network must be allowed in Android settings while it downloads.");
        }
    }

    private void downloadFiles() {
        if (filesBusy) return;
        filesBusy = true;
        filesStatus.setText("Downloading\u2026");
        filesProgress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String err = null;
            try {
                SmartDictationData.download(getApplicationContext());
            } catch (Throwable t) {
                err = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                VoiceDiagnostics.note("formatter download FAILED: " + t);
            }
            final String e = err;
            main.post(() -> {
                filesBusy = false;
                filesProgress.setVisibility(View.GONE);
                if (e == null) showFiles();
                else filesStatus.setText("Download failed \u00b7 " + e + "\nCheck Gboard's network access, then tap to retry.");
            });
        }, "mp-sd-download").start();
    }

    private void refresh() {
        showFiles();
        boolean app = appInstalled();
        packRow.setEnabled(app);
        packRow.setAlpha(app ? 1f : 0.5f);
        if (!app) {
            packStatus.setText("Install Speech Services first.");
            packProgress.setVisibility(View.GONE);
            return;
        }
        queryPack();
    }

    /** Asks Speech Services' language-pack details receiver (public broadcast) what's installed. */
    private void queryPack() {
        Intent i = new Intent("android.speech.action.GET_LANGUAGE_DETAILS");
        i.setComponent(new ComponentName(TTS, TTS_DETAILS));
        try {
            sendOrderedBroadcast(i, null, new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    Bundle b = getResultExtras(true);
                    showPack(has(b, "com.google.recognition.extra.OFFLINE_AVAILABLE_LANGUAGES"),
                            has(b, "com.google.recognition.extra.OFFLINE_PENDING_LANGUAGES"), b.isEmpty());
                }
            }, main, Activity.RESULT_OK, null, null);
        } catch (Throwable t) {
            packStatus.setText("Couldn't check: " + t.getClass().getSimpleName());
        }
    }

    private void showPack(boolean installed, boolean pending, boolean noAnswer) {
        if (installed) {
            packStatus.setText("Installed \u00b7 ready for offline voice typing\nTap to check it in Speech Services");
            packProgress.setVisibility(View.GONE);
            polling = false;
            return;
        }
        if (pending) {
            packStatus.setText("Downloading in Speech Services\u2026\nTap to open the download");
            packProgress.setIndeterminate(true);
            packProgress.setVisibility(View.VISIBLE);
        } else {
            packStatus.setText(noAnswer ? "Unknown \u00b7 Speech Services didn't answer"
                    : "Not installed \u00b7 tap to download");
            packProgress.setVisibility(View.GONE);
        }
        // Keep checking while the screen is open, so the status updates when the download finishes.
        if (!polling) {
            polling = true;
            main.postDelayed(this::poll, 2000);
        }
    }

    private void poll() {
        polling = false;
        if (resumed && !isFinishing()) queryPack();
    }

    /** The language lists come back as protobuf bytes; the tags are plain ASCII inside. */
    private static boolean has(Bundle b, String key) {
        byte[] bytes = b.getByteArray(key);
        if (bytes == null) {
            java.util.ArrayList<String> list = b.getStringArrayList(key);
            if (list == null) return false;
            for (String l : list) if (l != null && l.replace('_', '-').equalsIgnoreCase(LANG)) return true;
            return false;
        }
        String s = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        return s.contains("en-US") || s.contains("en_US");
    }

    private void download() {
        // Speech Services' own download prompt; the status above follows the download.
        Intent i = new Intent("com.google.recognition.action.DOWNLOAD_LANGUAGE");
        i.setComponent(new ComponentName(TTS, TTS_DOWNLOAD));
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANG);
        i.putExtra("com.google.recognition.extra.DISPLAY_NAME", "English (US)");
        open(i);
    }

    // ------------------------------------------------------------------ views

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "Couldn't open it", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** Material You colors from the wallpaper (Android 12+), with neutral fallbacks. */
    private void palette() {
        if (Build.VERSION.SDK_INT >= 31) {
            cBg = sys(dark ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_10);
            cCard = sys(dark ? android.R.color.system_neutral2_800 : android.R.color.system_neutral2_50);
            cText = sys(dark ? android.R.color.system_neutral1_50 : android.R.color.system_neutral1_900);
            cSub = sys(dark ? android.R.color.system_neutral2_200 : android.R.color.system_neutral2_700);
            cAccent = sys(dark ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
            cOnAccent = sys(dark ? android.R.color.system_accent1_800 : android.R.color.system_accent1_0);
        } else {
            cBg = dark ? 0xFF131316 : 0xFFFBF8FD;
            cCard = dark ? 0xFF2A2A2F : 0xFFEFEDF4;
            cText = dark ? 0xFFE4E1E6 : 0xFF1B1B1F;
            cSub = dark ? 0xFFC7C5D0 : 0xFF46464F;
            cAccent = dark ? 0xFFBEC2FF : 0xFF4A55A8;
            cOnAccent = dark ? 0xFF1A2178 : 0xFFFFFFFF;
        }
        cRipple = (cText & 0x00FFFFFF) | 0x1F000000;
    }

    private int sys(int id) {
        return getResources().getColor(id, getTheme());
    }

    private View titleBar(String title) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(16), 0, dp(24));
        ImageView back = new ImageView(this);
        back.setImageDrawable(new BackArrow(cText, dp(2)));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(cCard);
        back.setBackground(new RippleDrawable(ColorStateList.valueOf(cRipple), circle, null));
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(cText);
        t.setTextSize(28);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f);
        tl.leftMargin = dp(20);
        bar.addView(t, tl);
        return bar;
    }

    private TextView category(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(cAccent);
        t.setTextSize(14);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(20), dp(16), dp(20), dp(8));
        return t;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(cSub);
        t.setTextSize(13);
        t.setPadding(dp(20), dp(4), dp(20), dp(8));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(24), dp(18), dp(24), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(cCard);
        c.setBackground(bg);
        return c;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        return lp;
    }

    /** A Gboard-style preference row: title + summary in a rounded card. The summary view is the tag. */
    private View row(String title, String summary, View.OnClickListener click, View... extra) {
        LinearLayout c = card();
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(28));
        mask.setColor(Color.WHITE);
        c.setForeground(new RippleDrawable(ColorStateList.valueOf(cRipple), null, mask));
        c.setClickable(true);
        c.setOnClickListener(click);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(cText);
        t.setTextSize(18);
        c.addView(t);
        TextView s = new TextView(this);
        s.setText(summary);
        s.setTextColor(cSub);
        s.setTextSize(14);
        s.setPadding(0, dp(2), 0, 0);
        c.addView(s);
        for (View e : extra) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            c.addView(e, lp);
        }
        c.setTag(s);
        c.setLayoutParams(cardParams());
        return c;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Simple "←" arrow drawable (no resources needed). */
    private static final class BackArrow extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        BackArrow(int color, float stroke) {
            paint.setColor(color);
            paint.setStrokeWidth(stroke);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height(), cy = b.top + h / 2f;
            float l = b.left + w * 0.15f, r = b.right - w * 0.15f, a = w * 0.3f;
            c.drawLine(l, cy, r, cy, paint);
            c.drawLine(l, cy, l + a, cy - a, paint);
            c.drawLine(l, cy, l + a, cy + a, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
