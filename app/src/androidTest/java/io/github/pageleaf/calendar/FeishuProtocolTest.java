package io.github.pageleaf.calendar;

import junit.framework.TestCase;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class FeishuProtocolTest extends TestCase {
    private static Map<String, String> form(String body) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], "UTF-8"), URLDecoder.decode(parts[1], "UTF-8"));
        }
        return result;
    }

    public void testDeviceExchangeAndRefreshUseDistinctBodyFormats() throws Exception {
        FeishuProtocol protocol = new FeishuProtocol((url, header, type, body) -> {
            assertEquals("https://accounts.feishu.cn/oauth/v3/token", url);
            assertNull(header);
            if (type.equals("application/x-www-form-urlencoded")) {
                Map<String, String> fields = form(body);
                assertEquals(4, fields.size());
                assertEquals("urn:ietf:params:oauth:grant-type:device_code", fields.get("grant_type"));
                assertEquals("a+b", fields.get("device_code"));
                assertEquals("cli_fixture", fields.get("client_id"));
                assertEquals("s&e", fields.get("client_secret"));
            } else {
                assertEquals("application/json; charset=utf-8", type);
                JSONObject data = new JSONObject(body);
                assertEquals(4, data.length());
                assertEquals("refresh_token", data.getString("grant_type"));
                assertEquals("r1", data.getString("refresh_token"));
                assertEquals("cli_fixture", data.getString("client_id"));
                assertEquals("s&e", data.getString("client_secret"));
            }
            return new JSONObject();
        });
        protocol.poll("cli_fixture", "s&e", "a+b");
        protocol.refresh("cli_fixture", "s&e", "r1");
    }

    public void testOnlyTrustedHttpsOriginsReceiveCredentials() throws Exception {
        String[] bad = {"http://open.feishu.cn/open-apis/test", "https://open.feishu.cn@elsewhere.invalid/open-apis/test",
                "https://open.feishu.cn.elsewhere.invalid/open-apis/test", "https://open.feishu.cn:444/open-apis/test",
                "https://accounts.feishu.cn/oauth/v3/token", "https://open.feishu.cn\\@elsewhere.invalid/open-apis/test"};
        for (String url : bad) {
            try { Net.get(url, "fixture"); fail("Untrusted URL accepted"); }
            catch (java.io.IOException expected) { assertEquals("不受信任的飞书地址。", expected.getMessage()); }
        }
        for (String url : new String[]{"javascript:fake", "https://accounts.feishu.cn@example.org/", "https://example.org/"}) {
            try { FeishuProtocol.trustedVerificationUri(url); fail("Untrusted verification link accepted"); }
            catch (java.io.IOException expected) { }
        }
    }

    public void testRedirectIsRejectedBeforeFollowingLocationOrReadingBody() throws Exception {
        javax.net.ssl.HttpsURLConnection redirect = new javax.net.ssl.HttpsURLConnection(
                new java.net.URL("https://open.feishu.cn/open-apis/test")) {
            @Override public int getResponseCode() {
                assertFalse(getInstanceFollowRedirects());
                assertFalse(getUseCaches());
                assertTrue(getConnectTimeout() > 0 && getReadTimeout() > 0);
                assertEquals("Bearer fixture", getRequestProperty("Authorization"));
                return 307;
            }
            @Override public String getHeaderField(String name) { throw new AssertionError("Unexpected redirect lookup"); }
            @Override public java.io.InputStream getInputStream() { throw new AssertionError("Unexpected response read"); }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { throw new AssertionError("Unexpected network call"); }
            @Override public String getCipherSuite() { return null; }
            @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
            @Override public java.security.cert.Certificate[] getServerCertificates() { return null; }
        };
        try { Net.send(redirect, "Bearer fixture", null, null); fail(); }
        catch (Net.HttpError e) { assertEquals(307, e.status); }
    }

    public void testRefreshDispositionUsesOauthAndNumericCodes() {
        for (int code : new int[]{20026, 20037, 20064, 20073}) {
            assertTrue(new Net.HttpError(400, code, "").grantRejected());
        }
        assertTrue(new Net.HttpError(400, 0, "invalid_grant").grantRejected());
        assertFalse(new Net.HttpError(400, 20050, "invalid_grant").grantRejected());
        assertFalse(new Net.HttpError(500, 0, "invalid_grant").grantRejected());
        assertFalse(new Net.HttpError(429, 0, "").grantRejected());
        assertTrue(new Net.HttpError(400, 99991668, "").isAccessTokenRejected());
        assertFalse(new Net.HttpError(403, 99991672, "").isAccessTokenRejected());
    }

    public void testDeviceAuthorizationWireFormat() throws Exception {
        String verify = "https://accounts.feishu.cn/oauth/v1/device/verify";
        JSONObject reply = new JSONObject().put("device_code", "device-fixture").put("user_code", "ABCD")
                .put("verification_uri", verify).put("verification_uri_complete", verify + "?user_code=ABCD");
        FeishuProtocol protocol = new FeishuProtocol((url, header, type, body) -> {
            assertEquals("https://accounts.feishu.cn/oauth/v1/device_authorization", url);
            assertEquals("Basic " + Base64.getEncoder().encodeToString("cli_demo:demo+&秘密".getBytes(UTF_8)), header);
            assertEquals("application/x-www-form-urlencoded", type);
            Map<String, String> fields = form(body);
            assertEquals(2, fields.size());
            assertEquals("cli_demo", fields.get("client_id"));
            assertEquals("calendar:calendar:readonly offline_access", fields.get("scope"));
            return reply;
        });
        FeishuProtocol.Challenge result = protocol.begin("cli_demo", "demo+&秘密");
        assertEquals("device-fixture", result.device);
        assertEquals("ABCD", result.userCode);
        assertEquals(verify + "?user_code=ABCD", result.link);
        assertEquals(5, result.interval);
        assertEquals(240, result.expires);
        reply.remove("verification_uri_complete");
        assertEquals(verify, protocol.begin("cli_demo", "demo+&秘密").link);
    }
}
