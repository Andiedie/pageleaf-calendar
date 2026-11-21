package io.github.pageleaf.calendar;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;

/** Bearer-device flow from larksuite/cli internal/auth/device_flow.go and uat_client.go. */
final class FeishuProtocol {
    private static final String ACCOUNTS = "https://accounts.feishu.cn";
    static final String DEVICE_URL = ACCOUNTS + "/oauth/v1/device_authorization";
    static final String TOKEN_URL = ACCOUNTS + "/oauth/v3/token";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String READ_SCOPE = "calendar:calendar:readonly";

    static final class Issue extends IOException {
        Issue(String message) { super(message); }
    }

    interface Post {
        JSONObject send(String url, String authorization, String type, String body) throws Exception;
    }
    private final Post post;
    FeishuProtocol(Post post) { this.post = post; }

    static final class Challenge {
        final String device, userCode, link;
        final int interval, expires;

        Challenge(JSONObject data) throws IOException {
            device = required(data, "device_code");
            userCode = required(data, "user_code");
            String full = text(data, "verification_uri_complete");
            link = full.isEmpty() ? required(data, "verification_uri") : full;
            trustedVerificationUri(link);
            interval = positive(data, "interval", 5);
            expires = positive(data, "expires_in", 240);
        }
    }

    Challenge begin(String id, String secret) throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(UTF_8));
        return new Challenge(post.send(DEVICE_URL, auth, FORM,
                form("client_id", id, "scope", READ_SCOPE + " offline_access")));
    }

    JSONObject poll(String id, String secret, String device) throws Exception {
        return post.send(TOKEN_URL, null, FORM, form("grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                "device_code", device, "client_id", id, "client_secret", secret));
    }

    JSONObject refresh(String id, String secret, String refresh) throws Exception {
        JSONObject payload = new JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh)
                .put("client_id", id).put("client_secret", secret);
        return post.send(TOKEN_URL, null, "application/json; charset=utf-8", payload.toString());
    }

    static void trustedVerificationUri(String link) throws IOException {
        try {
            URI uri = new URI(link);
            String host = uri.getHost();
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443) && link.length() <= 4096
                    && ("accounts.feishu.cn".equalsIgnoreCase(host) || "applink.feishu.cn".equalsIgnoreCase(host)
                    || "open.feishu.cn".equalsIgnoreCase(host))) return;
        } catch (URISyntaxException ignored) { }
        throw new FeishuProtocol.Issue("飞书返回的验证地址无效。");
    }

    private static String form(String... pairs) throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (i != 0) body.append('&');
            body.append(pairs[i]).append('=').append(URLEncoder.encode(pairs[i + 1], "UTF-8"));
        }
        return body.toString();
    }

    static String text(JSONObject data, String key) {
        Object value = data.opt(key);
        return value instanceof String ? (String) value : "";
    }

    static String required(JSONObject data, String key) throws IOException {
        String value = text(data, key);
        if (value.isEmpty()) throw new FeishuProtocol.Issue("飞书响应缺少必要字段 " + key);
        return value;
    }

    private static int positive(JSONObject data, String key, int fallback) {
        int value = data.optInt(key, fallback);
        return value > 0 ? value : fallback;
    }
}
