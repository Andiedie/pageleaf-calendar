package io.github.pageleaf.calendar;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;

import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import static io.github.pageleaf.calendar.CalendarUi.*;

final class CalendarSourcesPanel {
    private final CalendarScreen ui;
    private final Dialog dialog;
    private ScrollView listScroll;

    CalendarSourcesPanel(CalendarScreen ui) {
        this.ui = ui;
        dialog = new Dialog(ui.activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
    }

    boolean isShowing() { return dialog.isShowing(); }

    void show() {
        update();
        dialog.show();
        fitWindow();
    }

    private void fitWindow() {
        Window w = dialog.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(PAPER));
        w.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setDimAmount(.20f);
        int width = ui.wide ? ui.u(445) : Math.min(ui.u(365), ui.root.getWidth() - ui.u(18));
        w.setLayout(width, ui.root.getHeight());
    }

    void update() {
        int savedY = listScroll == null ? 0 : listScroll.getScrollY();
        LinearLayout panel = ui.column();
        panel.setBackgroundColor(PAPER);
        ui.pad(panel, ui.choice(31, 19), ui.choice(24, 16), ui.choice(30, 19), ui.choice(17, 9));
        LinearLayout head = ui.row();
        panel.addView(head, lp(MATCH, ui.u(ui.choice(51, 45))));
        head.addView(ui.text("显示日历", ui.choice(35, 27), INK, true), growX());
        TextView done = ui.button("完成", ui.choice(20, 16), -1, true, "完成", dialog::dismiss);
        done.setBackground(ui.shape(GREEN, 7, 0));
        head.addView(done, lp(ui.u(ui.choice(69, 58)), ui.u(ui.choice(47, 40))));
        TextView hint = ui.text("选择在此设备上显示的飞书日历", ui.choice(20, 15), MUTED, false);
        panel.addView(hint, lp(MATCH, ui.u(ui.choice(63, 49))));
        ui.rule(panel, MUTED, ui.hair());
        LinearLayout heading = ui.row();
        panel.addView(heading, lp(MATCH, ui.u(ui.choice(61, 46))));
        heading.addView(ui.text("已订阅", ui.choice(20, 16), MUTED, true), growX());
        int count = 0;
        for (Data.Source source : ui.sources) if (source.readable && source.enabled) count++;
        heading.addView(ui.text("显示 " + count + " / " + ui.sources.size(), ui.choice(18, 14), MUTED, false));
        listScroll = new ScrollView(ui.activity);
        panel.addView(listScroll, growY());
        LinearLayout items = ui.column();
        listScroll.addView(items);
        for (Data.Source src : ui.sources) if (src.readable) sourceRow(items, src);
        for (Data.Source src : ui.sources) if (!src.readable) sourceRow(items, src);
        if (ui.sources.isEmpty()) {
            String message = ui.connected ? "暂无已订阅的日历" : "连接飞书后，可在这里选择日历。";
            TextView empty = ui.text(message, ui.choice(19, 15), MUTED, false);
            empty.setMaxLines(2);
            items.addView(empty, lp(MATCH, ui.u(ui.choice(84, 63))));
        }
        LinearLayout controls = ui.row();
        panel.addView(controls, lp(MATCH, ui.u(ui.choice(44, 44))));
        String label = ui.connected ? "飞书账户" : "连接飞书";
        TextView account = ui.button(label, ui.choice(17, 15), ui.connected ? GREEN : -1, true, label, () -> {
            dialog.dismiss();
            ui.actions.connect();
        });
        account.setBackground(ui.shape(ui.connected ? PALE : GREEN, 6, 0));
        controls.addView(account, lp(ui.u(ui.choice(99, 92)), ui.u(ui.choice(37, 36))));
        if (ui.connected) {
            controls.addView(ui.space(), growX());
            controls.addView(ui.button("立即同步", ui.choice(17, 15), GREEN, true, "立即同步", ui.actions::refresh), lp(ui.u(96), MATCH));
        }
        String status = CalendarScreen.blank(ui.status) ? (ui.connected ? "已连接飞书" : "尚未连接飞书") : ui.status;
        TextView state = ui.text(status, ui.choice(17, 13), MUTED, false);
        state.setMaxLines(2);
        panel.addView(state, lp(MATCH, WRAP));
        dialog.setContentView(panel);
        final ScrollView current = listScroll;
        current.post(() -> current.scrollTo(0, savedY));
        fitWindow();
    }

    private void sourceRow(LinearLayout items, Data.Source src) {
        LinearLayout row = ui.row();
        int rowHeight = src.readable ? ui.choice(82, 63) : ui.choice(100, 78);
        items.addView(row, lp(MATCH, ui.u(rowHeight)));
        row.addView(src.readable ? ui.dot(src.color) : ui.space(), lp(ui.u(15), ui.u(15)));
        row.addView(ui.space(), lp(ui.u(ui.choice(19, 12)), 1));
        LinearLayout names = ui.column();
        names.setGravity(Gravity.CENTER_VERTICAL);
        names.addView(ui.text(src.name, ui.choice(25, 19), src.readable ? INK : MUTED, true));
        row.addView(names, growX());
        if (!src.readable) {
            TextView reason = ui.text(CalendarScreen.blank(src.reason) ? "当前无法读取日程" : src.reason, ui.choice(16, 13), MUTED, false);
            reason.setMaxLines(2);
            names.addView(reason);
        } else {
            Switch toggle = new SourceSwitch();
            toggle.setContentDescription(src.name);
            toggle.setChecked(src.enabled);
            toggle.setEnabled(ui.connected);
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (ui.sources.contains(src)) ui.actions.toggle(src.id, checked);
            });
            row.addView(toggle, lp(ui.u(ui.choice(58, 52)), MATCH));
            if (ui.connected) row.setOnClickListener(v -> toggle.toggle());
        }
        ui.rule(items, LINE, ui.hair());
    }

    /** Keep native touch, keyboard and accessibility behavior with the panel's indicator. */
    private final class SourceSwitch extends Switch {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        SourceSwitch() {
            super(ui.activity);
            setShowText(false);
            setSwitchMinWidth(ui.u(ui.choice(55, 49)));
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = ui.u(ui.choice(55, 49));
            float radius = ui.u(ui.choice(31, 28)) / 2f;
            float left = (getWidth() - width) / 2f;
            float right = left + width;
            float center = getHeight() / 2f;
            paint.setColor(isChecked() ? GREEN : 0xffd8ddd3);
            canvas.drawRoundRect(left, center - radius, right, center + radius, radius, radius, paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(isChecked() ? right - radius : left + radius, center, radius - ui.u(4), paint);
        }
    }
}
