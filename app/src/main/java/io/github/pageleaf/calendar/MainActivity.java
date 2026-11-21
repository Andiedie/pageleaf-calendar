package io.github.pageleaf.calendar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import android.view.View;

import android.widget.FrameLayout;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements CalendarScreen.Actions {
    private static final long SYNC_PERIOD_MS = 40_000;
    // A rotation or a new Activity must never overlap two network synchronizations.
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final ExecutorService CACHE = Executors.newSingleThreadExecutor();
    private static CalendarStore appStore;
    private static FeishuAuth appAuth;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CalendarStore store;
    private FeishuAuth auth;
    private CalendarScreen screen;
    private ConnectivityManager connectivity;
    private PowerManager power;
    private String uiSession = "";
    private final Runnable sessionChanged = this::onSessionChanged;
    private final Runnable credentialsChanged = () -> main.post(sessionChanged);
    private final Runnable expiry = this::onSessionChanged;
    private final Runnable reload = this::loadCache;
    private volatile LocalDate from, until;
    private volatile boolean active;
    private boolean inFlight, refreshAgain, signingOut;
    private int snapshotVersion;
    private String notice = "";
    private List<Data.Source> sources = Collections.emptyList();
    private List<Data.Entry> entries = Collections.emptyList();
    private long lastSync;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active || !power.isInteractive()) return;
            startSync(false);
            main.postDelayed(this, SYNC_PERIOD_MS);
        }
    };
    private final Runnable minute = new Runnable() {
        @Override public void run() {
            if (!active || !power.isInteractive()) return;
            screen.tick();
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };
    private final ConnectivityManager.NetworkCallback networkChanges = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) {
            main.post(() -> { if (active) startSync(false); });
        }
        @Override public void onLost(Network network) {
            main.post(() -> {
                if (active && connectivity.getActiveNetwork() == null && !currentSession().isEmpty()) {
                    notice = "离线";
                    render();
                }
            });
        }
    };
    private final BroadcastReceiver clockOrPower = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) stopTimers();
            else if (active && power.isInteractive()) restartTimers();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Context app = getApplicationContext();
        if (appStore == null) appStore = new CalendarStore(app);
        if (appAuth == null) appAuth = new FeishuAuth(app);
        store = appStore;
        auth = appAuth;
        connectivity = getSystemService(ConnectivityManager.class);
        power = getSystemService(PowerManager.class);
        screen = new CalendarScreen(this, this);
        FrameLayout root = new FrameLayout(this);
        root.addView(screen.view());
        // Draw only inside the system bars, including under Android 15 edge-to-edge.
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        screen.start();
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        auth.observe(credentialsChanged);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        registerReceiver(clockOrPower, filter);
        connectivity.registerDefaultNetworkCallback(networkChanges);
        loadCache();
        restartTimers();
    }

    @Override protected void onPause() {
        active = false;
        auth.stopObserving(credentialsChanged);
        main.removeCallbacks(expiry);
        main.removeCallbacks(reload);
        snapshotVersion++;
        refreshAgain = false;
        stopTimers();
        connectivity.unregisterNetworkCallback(networkChanges);
        unregisterReceiver(clockOrPower);
        super.onPause();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void stopTimers() {
        main.removeCallbacks(poll);
        main.removeCallbacks(minute);
    }

    private void restartTimers() {
        stopTimers();
        if (!power.isInteractive()) return;
        minute.run();
        poll.run();
    }

    @Override public void rangeChanged(LocalDate start, LocalDate end) {
        from = start;
        until = end;
        if (active) {
            loadCache();
            startSync(true);
        }
    }

    @Override public void refresh() {
        if (currentSession().isEmpty()) { connect(); return; }
        startSync(true);
    }

    @Override public void toggle(String id, boolean enabled) {
        String displayed = uiSession;
        String session = currentSession();
        if (signingOut || session.isEmpty() || !displayed.equals(session)) return;
        ArrayList<Data.Source> changed = new ArrayList<>(sources.size());
        for (Data.Source source : sources) {
            changed.add(source.id.equals(id) ? new Data.Source(source.id, source.name, source.color,
                    enabled, source.readable, source.reason) : source);
        }
        sources = changed;
        if (!enabled) {
            ArrayList<Data.Entry> shown = new ArrayList<>(entries);
            shown.removeIf(entry -> entry.calendarId.equals(id));
            entries = shown;
        }
        snapshotVersion++;
        render();
        CACHE.execute(() -> {
            try {
                store.session(auth, session).toggle(id, enabled);
            } catch (FeishuAuth.SignInRequired stale) {
                return;
            } catch (RuntimeException storageFailure) {
                main.post(() -> {
                    if (active && !isDestroyed() && session.equals(currentSession())) { notice = "无法保存显示选择"; loadCache(); }
                });
                return;
            }
            main.post(() -> {
                if (active && !signingOut && session.equals(currentSession())) {
                    loadCache();
                    if (enabled) startSync(true);
                }
            });
        });
    }

    @Override public void connect() {
        if (signingOut) return;
        String session = currentSession();
        if (!session.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("飞书账户")
                    .setMessage("退出后将清除这台设备上的授权、日历缓存和显示选择。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("退出账户", (dialog, which) -> signOut(session)).show();
            return;
        }
        AuthDialog.show(this, auth, () -> {
            if (isDestroyed()) return;
            currentSession();
            notice = "正在同步";
            loadCache();
            startSync(true);
        });
    }

    private void signOut(String session) {
        if (!session.equals(currentSession())) return;
        signingOut = true;
        refreshAgain = false;
        snapshotVersion++;
        clearRows();
        notice = "正在退出";
        render();
        CACHE.execute(() -> {
            String outcome = "";
            try {
                auth.signOut(session, store::clear);
            } catch (FeishuAuth.SignInRequired stale) {
                // A newer grant owns the device now.
            } catch (IOException | RuntimeException storageFailure) {
                outcome = "本机存储异常，请检查账户状态";
            }
            String status = outcome;
            main.post(() -> {
                if (isDestroyed()) return;
                signingOut = false;
                currentSession();
                notice = status;
                loadCache();
            });
        });
    }

    private void clearRows() {
        sources = Collections.emptyList();
        entries = Collections.emptyList();
        lastSync = 0;
        screen.dismissEventDetails();
    }

    private String currentSession() {
        String current = auth.sessionId();
        if (!current.equals(uiSession)) {
            uiSession = current;
            snapshotVersion++;
            clearRows();
            notice = "";
            render();
            if (active && !signingOut && !current.isEmpty()) main.post(reload);
        }
        return current;
    }

    private void onSessionChanged() {
        if (!active || isDestroyed() || signingOut) return;
        String previous = uiSession;
        loadCache();
        if (!previous.equals(uiSession)) startSync(true);
    }

    private void loadCache() {
        if (from == null || signingOut || isDestroyed()) return;
        String session = currentSession();
        main.removeCallbacks(reload);
        main.removeCallbacks(expiry);
        if (active && !session.isEmpty()) {
            long remaining = auth.expiresAt(session) - System.currentTimeMillis();
            main.postDelayed(expiry, Math.max(1, remaining));
        }
        final int version = ++snapshotVersion;
        LocalDate first = from, end = until;
        CACHE.execute(() -> {
            try {
                CalendarStore.Snapshot snapshot = store.session(auth, session).snapshot(first, end);
                main.post(() -> {
                    if (!active || isDestroyed() || version != snapshotVersion || !session.equals(currentSession())) return;
                    if (session.isEmpty()) { render(); return; }
                    try {
                        auth.withSession(session, () -> {
                            sources = snapshot.sources;
                            entries = snapshot.events;
                            lastSync = snapshot.lastSync;
                            render();
                            return null;
                        });
                    } catch (FeishuAuth.SignInRequired stale) { onSessionChanged(); }
                });
            } catch (FeishuAuth.SignInRequired stale) {
                main.post(() -> { if (version == snapshotVersion) onSessionChanged(); });
            } catch (RuntimeException failure) {
                main.post(() -> {
                    if (active && !isDestroyed() && version == snapshotVersion && session.equals(currentSession())) {
                        clearRows();
                        notice = "无法读取本机缓存";
                        render();
                    }
                });
            }
        });
    }

    private void startSync(boolean queueIfBusy) {
        if (!active || !power.isInteractive() || from == null || signingOut) return;
        String session = currentSession();
        if (session.isEmpty()) return;
        if (inFlight) { refreshAgain |= queueIfBusy; return; }
        if (connectivity.getActiveNetwork() == null) { notice = "离线"; render(); return; }
        inFlight = true;
        LocalDate first = from, end = until;
        NETWORK.execute(() -> {
            String result = "";
            try {
                if (active && power.isInteractive() && first.equals(from) && end.equals(until) && session.equals(auth.sessionId())) {
                    CalendarSync sync = new CalendarSync(store.session(auth, session));
                    String token = auth.token(session, null);
                    try {
                        sync.sync(token, first, end);
                    } catch (Net.HttpError rejected) {
                        if (!rejected.isAccessTokenRejected()) throw rejected;
                        sync.sync(auth.token(session, token), first, end);
                    }
                }
            } catch (FeishuAuth.SignInRequired expired) {
                result = "请重新连接飞书";
            } catch (Net.HttpError error) {
                if (error.status == 403) result = "缺少飞书日历权限";
                else if (error.status == 429) result = "飞书限流，稍后重试";
                else if (error.isAccessTokenRejected()) result = "授权失效，请重新连接";
                else result = "同步失败，稍后重试";
            } catch (Exception error) {
                result = connectivity.getActiveNetwork() == null ? "离线" : "同步失败，请检查网络或授权";
            }
            String status = result;
            main.post(() -> {
                inFlight = false;
                if (!active || isDestroyed() || signingOut) return;
                boolean sameGrant = session.equals(currentSession());
                boolean sameWindow = first.equals(from) && end.equals(until);
                if (sameGrant && sameWindow) notice = status;
                loadCache();
                boolean retry = refreshAgain || !sameGrant || !sameWindow;
                refreshAgain = false;
                if (retry) startSync(false);
            });
        });
    }

    private void render() {
        String status = notice;
        if (lastSync > 0) {
            ZoneId zone = ZoneId.systemDefault();
            Instant instant = Instant.ofEpochMilli(lastSync);
            String format = instant.atZone(zone).toLocalDate().equals(LocalDate.now(zone)) ? "HH:mm" : "MM-dd HH:mm";
            String time = DateTimeFormatter.ofPattern(format).withZone(zone).format(instant);
            status += (status.isEmpty() ? "" : " · ") + "上次同步 " + time;
        }
        screen.setData(sources, entries, status, !uiSession.isEmpty() && !signingOut);
    }
}
