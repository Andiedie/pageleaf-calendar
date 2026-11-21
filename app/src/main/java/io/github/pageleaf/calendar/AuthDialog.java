package io.github.pageleaf.calendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Activity-owned device authorization. Browser trips can background the Activity while polling. */
public final class AuthDialog implements Application.ActivityLifecycleCallbacks {
    private final Activity activity;
    private final FeishuAuth auth;
    private final Runnable connected;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AlertDialog dialog;
    private final LinearLayout form, panel;
    private final EditText idField, secretField;
    private final TextView status;
    private final ImageView qr;
    private final TextView code;
    private volatile boolean closed;
    private FeishuAuth.Login login;
    private String link;

    public static void show(Activity activity, FeishuAuth auth, Runnable onConnected) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new AuthDialog(activity, auth, onConnected).open();
    }

    private AuthDialog(Activity activity, FeishuAuth auth, Runnable callback) {
        this.activity = activity;
        this.auth = auth;
        connected = callback;
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = column();
        content.setPadding(dp(22), dp(8), dp(22), dp(8));
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        scroll.addView(content);
        form = column();
        form.addView(label("填写你自己的飞书自建应用凭据。开通日历只读权限 calendar:calendar:readonly 和 offline_access，在安全设置中开启刷新 user_access_token，然后发布应用版本。", 15));
        idField = field("App ID", false);
        secretField = field("App Secret", true);
        form.addView(idField);
        form.addView(secretField);
        content.addView(form);
        panel = column();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        qr = new ImageView(activity);
        qr.setContentDescription("飞书授权二维码");
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        panel.addView(qr, new LinearLayout.LayoutParams(-1, dp(208)));
        code = label("", 22);
        code.setGravity(Gravity.CENTER);
        code.setTextIsSelectable(true);
        code.setSaveEnabled(false);
        panel.addView(code);
        panel.setVisibility(View.GONE);
        content.addView(panel);
        status = label("", 15);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        dialog = new AlertDialog.Builder(activity).setTitle("连接飞书").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("获取授权码", null)
                .setNeutralButton("打开验证页", null).create();
    }

    private void open() {
        dialog.setOnDismissListener(d -> close());
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        activity.registerActivityLifecycleCallbacks(this);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> start());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> browse());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
    }

    private void start() {
        String id = idField.getText().toString().trim();
        String secret = secretField.getText().toString().trim();
        if (id.isEmpty() || id.indexOf(':') >= 0) { idField.setError("请输入有效的 App ID"); return; }
        if (secret.isEmpty()) { secretField.setError("请输入 App Secret"); return; }
        login = auth.newLogin();
        FeishuAuth.Login attempt = login;
        form.setVisibility(View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
        status.setText("正在向飞书获取授权码…");
        worker.execute(() -> authorize(attempt, id, secret));
    }

    private void authorize(FeishuAuth.Login attempt, String id, String secret) {
        try {
            if (closed) return;
            FeishuProtocol.Challenge c = auth.protocol.begin(id, secret);
            if (closed) return;
            long deadline = SystemClock.elapsedRealtime() + 1000L * c.expires;
            Bitmap bitmap = qrCode(c.link);
            update(() -> {
                link = c.link;
                qr.setImageBitmap(bitmap);
                qr.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
                panel.setVisibility(View.VISIBLE);
                code.setText("设备码  " + c.userCode);
                status.setText(bitmap == null ? "打开飞书验证页，核对设备码后确认授权。"
                        : "用飞书扫描二维码，或在本机打开验证页。请核对设备码后确认授权。");
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.VISIBLE);
            });
            long delay = c.interval * 1000L;
            while (!closed) {
                long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                Thread.sleep(Math.min(delay, remaining));
                if (closed || SystemClock.elapsedRealtime() >= deadline) break;
                JSONObject token;
                try { token = auth.protocol.poll(id, secret, c.device); }
                catch (Net.HttpError e) {
                    if ("slow_down".equals(e.oauth) || e.status == 429) delay += 5000;
                    else if (e.status >= 500) delay = backoff(delay);
                    else if (!"authorization_pending".equals(e.oauth)) throw e;
                    continue;
                } catch (IOException network) {
                    delay = backoff(delay);
                    continue;
                }
                auth.install(attempt, id, secret, token);
                update(() -> {
                    if (!auth.acceptLogin(attempt)) { dialog.dismiss(); return; }
                    login = null;
                    dialog.dismiss();
                    connected.run();
                });
                return;
            }
            if (!closed) failed("设备码已过期，请重新获取。");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Net.HttpError e) {
            String message;
            switch (e.oauth) {
                case "access_denied": message = "授权未获批准。"; break;
                case "expired_token": case "invalid_grant": message = "设备码已失效，请重新获取。"; break;
                case "invalid_client": message = "请检查应用的 App ID 和 App Secret。"; break;
                case "invalid_scope": message = "请检查应用的日历只读权限和 offline_access，并发布应用版本。"; break;
                default: message = e.getMessage();
            }
            failed(message);
        } catch (FeishuProtocol.Issue e) { failed(e.getMessage()); }
        catch (Exception e) { failed("连接未完成，请检查网络、应用配置和本机凭证存储后重试。"); }
    }

    private static long backoff(long delay) {
        return Math.max(delay, Math.min(60_000L, delay * 2));
    }

    private void failed(String message) {
        update(() -> {
            auth.cancelLogin(login);
            login = null;
            link = null;
            panel.setVisibility(View.GONE);
            qr.setImageDrawable(null);
            code.setText("");
            form.setVisibility(View.VISIBLE);
            status.setText(message);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.VISIBLE);
        });
    }

    private void update(Runnable task) {
        main.post(() -> {
            if (!closed && !activity.isDestroyed() && !activity.isFinishing() && dialog.isShowing()) task.run();
        });
    }

    private void browse() {
        if (link == null) return;
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch (ActivityNotFoundException | SecurityException e) { status.setText("无法打开验证页，请使用另一台设备上的飞书扫码。"); }
    }

    private void close() {
        if (closed) return;
        closed = true;
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        auth.cancelLogin(login);
        activity.unregisterActivityLifecycleCallbacks(this);
        secretField.setText("");
        qr.setImageDrawable(null);
    }

    @Override public void onActivityDestroyed(Activity a) {
        if (a == activity) { close(); dialog.dismiss(); }
    }
    @Override public void onActivityCreated(Activity a, Bundle state) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityResumed(Activity a) { }
    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle state) { }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(activity);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }
    private TextView label(String text, float size) {
        TextView v = new TextView(activity);
        v.setTextSize(size);
        v.setText(text);
        v.setPadding(0, dp(6), 0, dp(6));
        return v;
    }
    private EditText field(String hint, boolean secret) {
        EditText e = new EditText(activity);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        e.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING | (secret ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT));
        e.setSaveEnabled(false);
        e.setHint(hint);
        return e;
    }
    private int dp(int n) { return Math.round(activity.getResources().getDisplayMetrics().density * n); }

    static Bitmap qrCode(String link) {
        try {
            BitMatrix bits = new QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 384, 384);
            int width = bits.getWidth(), height = bits.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) pixels[y * width + x] = bits.get(x, y) ? 0xff000000 : 0xffffffff;
            }
            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (WriterException e) { return null; }
    }
}
