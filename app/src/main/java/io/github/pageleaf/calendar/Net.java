package io.github.pageleaf.calendar;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import javax.net.ssl.HttpsURLConnection;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Synchronous Feishu HTTPS calls. Call on a worker thread. */
public final class Net {
    private Net() { }

    public static final class HttpError extends IOException {
        public final int status;
        public final int code;
        final String oauth;

        HttpError(int status, int code, String oauth) {
            super("飞书请求失败（HTTP " + status + (code == 0 ? "" : "，错误码 " + code) + "）。");
            this.status = status;
            this.code = code;
            this.oauth = oauth;
        }

        public boolean isAccessTokenRejected() {
            return status == 401 || code == 99991663 || code == 99991668 || code == 99991671 || code == 99991677;
        }

        boolean grantRejected() {
            if (status >= 500 || status == 429 || code == 20050 || code == 21000 || code == 21001) return false;
            return code == 20026 || code == 20037 || code == 20064 || code == 20073
                    || "invalid_grant".equals(oauth) || "expired_token".equals(oauth);
        }
    }

    public static JSONObject get(String url, String bearer) throws IOException {
        URI uri;
        try { uri = new URI(url); }
        catch (URISyntaxException e) { throw untrusted(); }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"open.feishu.cn".equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getRawFragment() != null || !uri.normalize().getRawPath().startsWith("/open-apis/")) {
            throw untrusted();
        }
        if (bearer == null || bearer.isEmpty() || bearer.indexOf('\r') >= 0 || bearer.indexOf('\n') >= 0) {
            throw new IOException("无效的飞书访问凭证。");
        }
        return send((HttpsURLConnection) uri.toURL().openConnection(), "Bearer " + bearer, null, null);
    }

    static JSONObject post(String url, String authorization, String type, String body) throws IOException {
        if (!FeishuProtocol.DEVICE_URL.equals(url) && !FeishuProtocol.TOKEN_URL.equals(url)) throw untrusted();
        return send((HttpsURLConnection) URI.create(url).toURL().openConnection(), authorization, type, body);
    }

    private static IOException untrusted() { return new IOException("不受信任的飞书地址。"); }

    static JSONObject send(HttpsURLConnection conn, String authorization, String type, String body) throws IOException {
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("Accept", "application/json");
            if (authorization != null) conn.setRequestProperty("Authorization", authorization);
            if (body != null) {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", type);
                byte[] data = body.getBytes(UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(data); }
            }
            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) throw new HttpError(status, 0, "");
            boolean ok = status >= 200 && status < 300;
            int limit = ok && body == null ? 16 * 1024 * 1024 : 64 * 1024;
            JSONObject json;
            try (InputStream stream = ok ? conn.getInputStream() : conn.getErrorStream()) {
                json = readJson(stream, limit);
            } catch (IOException e) {
                if (!ok) throw new HttpError(status, 0, "");
                throw e;
            }
            int code = json.optInt("code", 0);
            String oauth = FeishuProtocol.text(json, "error");
            if (!ok || code != 0 || !oauth.isEmpty()) {
                HttpError failure = new HttpError(status, code, oauth);
                // Calendar callers consume other successful HTTP envelopes themselves.
                if (!ok || body != null || failure.isAccessTokenRejected()) throw failure;
            }
            return json;
        } finally { conn.disconnect(); }
    }

    private static JSONObject readJson(InputStream in, int limit) throws IOException {
        if (in == null) throw new IOException("飞书响应为空。");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (n > limit - out.size()) throw new IOException("飞书响应过大。");
            out.write(buf, 0, n);
        }
        try { return new JSONObject(out.toString("UTF-8")); }
        catch (JSONException e) { throw new IOException("飞书返回了无效的 JSON 响应。"); }
    }
}
