package io.github.pageleaf.calendar;

import android.content.Context;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Single-process Feishu user session shared by the activity and background jobs. */
public final class FeishuAuth {
    public static final class SignInRequired extends IOException {
        SignInRequired() { super("请重新连接飞书。"); }
    }

    interface Store {
        JSONObject read() throws Exception;
        void save(JSONObject state) throws Exception;
        void clear() throws IOException;
    }

    static final class Login {
        final String id = UUID.randomUUID().toString();
    }

    private static final class Shared {
        final Store store;
        final Object refreshLock = new Object();
        final Set<Runnable> listeners = new HashSet<>();
        boolean loaded;
        JSONObject current;
        JSONObject pendingRotation;
        Login login;
        Shared(Store store) { this.store = store; }

        JSONObject current() throws Exception {
            if (!loaded) {
                current = store.read();
                loaded = true;
            }
            return current;
        }

        void save(JSONObject next) throws Exception {
            store.save(next);
            boolean changed = current == null || !next.optString("session").equals(current.optString("session"));
            current = next;
            pendingRotation = null;
            loaded = true;
            if (changed) listeners.forEach(Runnable::run);
        }

        void clear() throws IOException {
            store.clear();
            current = null;
            pendingRotation = null;
            loaded = true;
            login = null;
            listeners.forEach(Runnable::run);
        }
    }

    private static Shared process;
    private final Shared shared;
    final FeishuProtocol protocol;

    public FeishuAuth(Context context) {
        synchronized (FeishuAuth.class) {
            if (process == null) process = new Shared(new FeishuSealedStore(context.getApplicationContext()));
            shared = process;
        }
        protocol = new FeishuProtocol(Net::post);
    }

    FeishuAuth(Store store, FeishuProtocol protocol) {
        shared = new Shared(store);
        this.protocol = protocol;
    }

    /** A usable grant, stable across token refresh. Empty means no verified owner for cache access. */
    public String sessionId() {
        synchronized (shared) {
            try {
                JSONObject s = shared.current();
                if (s != null && Math.max(s.optLong("accessUntil"), s.optLong("refreshUntil")) > System.currentTimeMillis()) {
                    return FeishuProtocol.text(s, "session");
                }
            } catch (Exception ignored) { /* Unreadable credentials cannot identify a cache owner. */ }
            return "";
        }
    }

    void observe(Runnable listener) { synchronized (shared) { shared.listeners.add(listener); } }
    void stopObserving(Runnable listener) { synchronized (shared) { shared.listeners.remove(listener); } }

    long expiresAt(String session) {
        synchronized (shared) {
            JSONObject s = shared.current;
            return s == null || !session.equals(s.optString("session")) ? 0
                    : Math.max(s.optLong("accessUntil"), s.optLong("refreshUntil"));
        }
    }

    // Credential installation, revocation and local cache operations share this monitor.
    // Only short local operations belong here. Token exchange and calendar HTTP run outside it.
    interface LocalWork<T> { T run() throws SignInRequired; }

    <T> T withSession(String session, LocalWork<T> operation) throws SignInRequired {
        synchronized (shared) {
            if (session.isEmpty() || !session.equals(sessionId())) throw new SignInRequired();
            return operation.run();
        }
    }

    void signOut(String session, Runnable eraseCache) throws IOException {
        synchronized (shared) {
            if (shared.current == null || session.isEmpty() || !session.equals(shared.current.optString("session")))
                throw new SignInRequired();
            eraseCache.run();
            shared.clear();
        }
    }

    public boolean connected() { return !sessionId().isEmpty(); }

    public void clear() {
        synchronized (shared) {
            try { shared.clear(); }
            catch (IOException e) { throw new IllegalStateException("无法删除本机的飞书凭证。", e); }
        }
    }

    Login newLogin() {
        synchronized (shared) { return shared.login = new Login(); }
    }

    void cancelLogin(Login login) {
        synchronized (shared) {
            if (login == null || shared.login != login) return;
            shared.login = null;
            if (shared.current != null && login.id.equals(shared.current.optString("session"))) clear();
        }
    }

    boolean acceptLogin(Login login) {
        synchronized (shared) {
            if (shared.login != login || !login.id.equals(sessionId())) return false;
            shared.login = null;
            return true;
        }
    }

    void install(Login login, String id, String secret, JSONObject token) throws Exception {
        JSONObject next = credentials(login.id, id, secret, token, null);
        synchronized (shared) {
            if (shared.login != login) throw new SignInRequired();
            shared.save(next);
        }
    }

    public String accessToken() throws Exception { return token(null, null); }

    /** Retry a rejected request once. A concurrent refresh can already have replaced oldToken. */
    public String accessTokenAfterRejection(String oldToken) throws Exception {
        if (oldToken == null || oldToken.isEmpty()) throw new IllegalArgumentException("缺少被拒绝的访问凭证。");
        return token(null, oldToken);
    }

    String token(String session, String rejected) throws Exception {
        synchronized (shared.refreshLock) {
            JSONObject s;
            synchronized (shared) {
                if (session != null && (session.isEmpty() || !session.equals(sessionId()))) throw new SignInRequired();
                if (shared.pendingRotation != null) shared.save(shared.pendingRotation);
                s = shared.current();
                if (s == null) throw new SignInRequired();
                long now = System.currentTimeMillis();
                String access = s.getString("access");
                boolean renewable = now < s.getLong("refreshUntil");
                long margin = renewable ? 60_000L : 0L;
                if (!access.equals(rejected) && now + margin < s.getLong("accessUntil")) return access;
                if (!renewable) {
                    shared.clear();
                    throw new SignInRequired();
                }
            }
            JSONObject response;
            try { response = protocol.refresh(s.getString("client"), s.getString("secret"), s.getString("refresh")); }
            catch (Net.HttpError e) {
                if (!e.grantRejected()) throw e;
                synchronized (shared) { if (shared.current == s) shared.clear(); }
                throw new SignInRequired();
            }
            JSONObject next = credentials(s.getString("session"), s.getString("client"), s.getString("secret"), response, s);
            synchronized (shared) {
                // Logout or a different sign-in may have completed during the network request.
                if (shared.current != s) throw new SignInRequired();
                // The provider may already have consumed the old refresh token. Keep the
                // replacement until a local storage retry can commit it, or the user signs out.
                shared.pendingRotation = next;
                shared.save(next);
                return next.getString("access");
            }
        }
    }

    private static JSONObject credentials(String session, String id, String secret, JSONObject token, JSONObject old) throws Exception {
        String type = FeishuProtocol.text(token, "token_type");
        if (!type.isEmpty() && !"Bearer".equalsIgnoreCase(type)) throw new FeishuProtocol.Issue("飞书返回了不支持的凭证类型。");
        String scopes = FeishuProtocol.text(token, "scope").trim();
        if (!scopes.isEmpty() && !Arrays.asList(scopes.split("\\s+")).contains("calendar:calendar:readonly")) {
            throw new FeishuProtocol.Issue("请为应用开通并授权日历只读权限 calendar:calendar:readonly。");
        }
        String access = FeishuProtocol.required(token, "access_token");
        String refresh = FeishuProtocol.text(token, "refresh_token");
        if (refresh.isEmpty() && old != null) refresh = old.getString("refresh");
        if (refresh.isEmpty()) throw new FeishuProtocol.Issue("未获得 refresh_token。请在飞书应用安全设置中开启刷新 user_access_token，发布新版本后再连接。");
        long now = System.currentTimeMillis();
        int ttl = token.optInt("expires_in", 7200);
        int refreshTtl = token.optInt("refresh_token_expires_in", 0);
        long refreshUntil = refreshTtl > 0 ? now + refreshTtl * 1000L
                : old == null ? now + 604_800_000L : old.getLong("refreshUntil");
        return new JSONObject().put("session", session).put("client", id).put("secret", secret)
                .put("access", access).put("refresh", refresh).put("accessUntil", now + (ttl > 0 ? ttl : 7200) * 1000L)
                .put("refreshUntil", refreshUntil);
    }
}
