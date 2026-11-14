package com.xtr3.downloader;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

final class Ui {
    private Ui() { }
    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static void insets(Activity activity, View root) {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime();
                android.graphics.Insets bars = insets.getInsets(types);
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }
    static LinearLayout card(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bg_card);
        int pad = dp(c, 16);
        layout.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(c, 12);
        layout.setLayoutParams(params);
        return layout;
    }
    static TextView text(Context c, String value, float size, boolean title) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(c.getColor(title ? R.color.text : R.color.muted));
        view.setLineSpacing(dp(c, 3), 1);
        if (title) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(c, 6);
        view.setLayoutParams(params);
        return view;
    }
    static Button button(Context c, String label, boolean primary) {
        Button button = new Button(c, null, 0, primary ? R.style.Widget_Xtr3_Button : R.style.Widget_Xtr3_Button_Secondary);
        button.setText(label);
        button.setMinHeight(dp(c, 48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(c, 8);
        button.setLayoutParams(params);
        return button;
    }
    static String bytes(Context c, long value) {
        if (value < 0) return c.getString(R.string.size_unknown);
        if (value >= 1_000_000_000) return String.format(Locale.ROOT, "%.2f GB", value / 1_000_000_000.0);
        if (value >= 1_000_000) return String.format(Locale.ROOT, "%.1f MB", value / 1_000_000.0);
        return String.format(Locale.ROOT, "%.0f KB", value / 1000.0);
    }
    static String date(long time) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(time));
    }
    static void toast(Context c, int resource) { Toast.makeText(c, resource, Toast.LENGTH_LONG).show(); }
    static void toast(Context c, String message) { Toast.makeText(c, message, Toast.LENGTH_LONG).show(); }
    static void openVideo(Context c, String uri, String mime) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            c.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) { toast(c, R.string.no_player); }
    }
}
