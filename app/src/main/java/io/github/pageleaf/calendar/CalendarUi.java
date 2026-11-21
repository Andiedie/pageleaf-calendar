package io.github.pageleaf.calendar;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared typography, spacing and colors from the frame display design. */
class CalendarUi {
    static final int PAPER = 0xfffcfbf7, INK = 0xff23332e, MUTED = 0xff59665e;
    static final int GREEN = 0xff315747, LINE = 0xffe2e4db, PALE = 0xfff0f2eb;
    static final int SAGE = 0xffe9efe5, TODAY = 0xfff3f6ed, RED = 0xffbf352e;
    static final float PAST_EVENT_ALPHA = .82f;
    static final int MATCH = -1, WRAP = -2;
    final Activity activity;
    boolean wide;
    float unit;

    CalendarUi(Activity activity) {
        this.activity = activity;
        unit = activity.getResources().getDisplayMetrics().density;
    }

    void size(int width, int height) {
        float density = activity.getResources().getDisplayMetrics().density;
        float dpWidth = width / density;
        wide = dpWidth >= 640 || (dpWidth >= 530 && width > height * 1.42f);
        unit = wide ? Math.min(width / 1280f, Math.max(width / 1600f, height / 800f))
                : density * Math.min(1.12f, Math.max(.8f, dpWidth / 400f));
    }

    int u(float value) { return Math.round(value * unit); }
    int choice(int frame, int phone) { return wide ? frame : phone; }
    int hair() { return Math.max(1, u(1)); }

    LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setBaselineAligned(false);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    static LinearLayout.LayoutParams growX() { return new LinearLayout.LayoutParams(0, MATCH, 1f); }
    static LinearLayout.LayoutParams growY() { return new LinearLayout.LayoutParams(MATCH, 0, 1f); }

    static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.LEFT);
        p.leftMargin = x;
        p.topMargin = y;
        return p;
    }

    void pad(View view, int l, int t, int r, int b) { view.setPadding(u(l), u(t), u(r), u(b)); }

    TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(activity);
        v.setIncludeFontPadding(false);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit * activity.getResources().getConfiguration().fontScale);
        v.setTextColor(color);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        v.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        v.setMaxLines(1);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setText(value);
        return v;
    }

    GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(u(radius));
        bg.setColor(color);
        if (stroke != 0) bg.setStroke(hair(), stroke);
        return bg;
    }

    void clickable(View v, String description, Runnable click) {
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x25315747), null, shape(Color.WHITE, 5, 0)));
        v.setContentDescription(description);
        v.setFocusable(true);
        v.setOnClickListener(unused -> click.run());
    }

    TextView button(String label, int size, int color, boolean bold, String description, Runnable click) {
        TextView b = text(label, size, color, bold);
        b.setGravity(Gravity.CENTER);
        clickable(b, description, click);
        return b;
    }

    void rule(LinearLayout parent, int color, int pixels) {
        View line = new View(activity);
        line.setBackgroundColor(color);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(line, lp(MATCH, pixels));
    }

    View space() { return new View(activity); }
    View dot(int color) {
        View dot = new View(activity);
        dot.setBackground(shape(color, 20, 0));
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return dot;
    }

    LinearLayout tag(String name, int color, int size, int dotSize, int textColor, boolean bold) {
        LinearLayout row = row();
        row.addView(dot(color), lp(u(dotSize), u(dotSize)));
        row.addView(space(), lp(u(choice(8, 4)), 1));
        row.addView(text(name, size, textColor, bold), growX());
        return row;
    }

    static int eventFill(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] *= .27f;
        hsv[2] = .95f;
        return Color.HSVToColor(hsv);
    }

    static int eventInk(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.min(.38f, hsv[2]);
        return Color.HSVToColor(hsv);
    }
}
