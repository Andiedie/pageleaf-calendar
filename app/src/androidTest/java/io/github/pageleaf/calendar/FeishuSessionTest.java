package io.github.pageleaf.calendar;

import junit.framework.TestCase;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline grant lifecycle with a disposable in-memory credential store. */
public final class FeishuSessionTest extends TestCase {
    private static final class MemoryStore implements FeishuAuth.Store {
        JSONObject value;
        public JSONObject read() { return value; }
        public void save(JSONObject state) { value = state; }
        public void clear() { value = null; }
    }

    private static JSONObject grant(String access, String refresh) throws Exception {
        return new JSONObject().put("access_token", access).put("refresh_token", refresh).put("expires_in", 3600);
    }

    private static void login(FeishuAuth auth) throws Exception {
        auth.install(auth.newLogin(), "cli_fixture", "fixture", grant("a1", "r1"));
    }

    public void testRotationRetainsSessionAndUsesLatestRefreshToken() throws Exception {
        MemoryStore store = new MemoryStore();
        AtomicInteger calls = new AtomicInteger();
        FeishuProtocol wire = new FeishuProtocol((url, header, type, body) -> {
            String previous = new JSONObject(body).getString("refresh_token");
            switch (calls.incrementAndGet()) {
                case 1: assertEquals("r1", previous); return grant("a2", "r2");
                case 2: assertEquals("r2", previous); return grant("a3", null);
                case 3: assertEquals("r2", previous); return grant("a4", "r4");
                default: throw new AssertionError("Unexpected refresh");
            }
        });
        FeishuAuth auth = new FeishuAuth(store, wire);
        assertEquals("", auth.sessionId());
        login(auth);
        String session = UUID.fromString(auth.sessionId()).toString();
        assertEquals("a1", auth.accessToken());
        assertEquals("a2", auth.accessTokenAfterRejection("a1"));
        assertEquals("a2", auth.accessTokenAfterRejection("a1"));
        FeishuAuth reopened = new FeishuAuth(store, wire);
        assertEquals(session, reopened.sessionId());
        assertEquals("a3", reopened.accessTokenAfterRejection("a2"));
        assertEquals("a4", reopened.accessTokenAfterRejection("a3"));
        assertEquals(session, reopened.sessionId());
        reopened.clear();
        login(reopened);
        assertFalse(session.equals(reopened.sessionId()));
    }

    public void testTransientRefreshFailureRetainsGrantAndRevocationClearsIt() throws Exception {
        AtomicInteger count = new AtomicInteger();
        FeishuAuth auth = new FeishuAuth(new MemoryStore(), new FeishuProtocol((url, h, type, body) -> {
            if (count.getAndIncrement() == 0) throw new Net.HttpError(503, 0, "");
            throw new Net.HttpError(400, 20064, "invalid_grant");
        }));
        login(auth);
        try { auth.accessTokenAfterRejection("a1"); fail(); } catch (Net.HttpError expected) { }
        assertTrue(auth.connected());
        try { auth.accessTokenAfterRejection("a1"); fail(); } catch (FeishuAuth.SignInRequired expected) { }
        assertEquals("", auth.sessionId());
        assertFalse(auth.connected());
    }

    public void testLogoutWhileRefreshingNeverRestoresOldGrant() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        FeishuAuth auth = new FeishuAuth(new MemoryStore(), new FeishuProtocol((url, h, type, body) -> {
            started.countDown();
            if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("Test timed out");
            return grant("stale-access", "stale-refresh");
        }));
        login(auth);
        FutureTask<String> refresh = new FutureTask<>(() -> auth.accessTokenAfterRejection("a1"));
        new Thread(refresh).start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        auth.clear();
        login(auth);
        release.countDown();
        try { refresh.get(2, TimeUnit.SECONDS); fail(); }
        catch (ExecutionException e) { assertTrue(e.getCause() instanceof FeishuAuth.SignInRequired); }
        assertEquals("a1", auth.accessToken());
    }

    public void testFailedRotationWriteRetriesStorageBeforeNextExchange() throws Exception {
        AtomicInteger writes = new AtomicInteger(), exchanges = new AtomicInteger();
        FeishuAuth.Store flaky = new FeishuAuth.Store() {
            JSONObject saved;
            public JSONObject read() { return saved; }
            public void save(JSONObject state) throws IOException {
                if (writes.incrementAndGet() == 2) throw new IOException("Temporary write failure");
                saved = state;
            }
            public void clear() { saved = null; }
        };
        FeishuAuth auth = new FeishuAuth(flaky, new FeishuProtocol((u, h, t, b) -> {
            assertEquals(1, exchanges.incrementAndGet());
            return grant("a2", "r2");
        }));
        login(auth);
        try { auth.accessTokenAfterRejection("a1"); fail(); } catch (IOException expected) { }
        assertEquals("a2", auth.accessTokenAfterRejection("a1"));
        assertEquals(3, writes.get());
        assertEquals("r2", flaky.read().getString("refresh"));
    }

    public void testCancelledLoginAndMissingOfflineGrantNeverConnect() throws Exception {
        FeishuAuth auth = new FeishuAuth(new MemoryStore(), null);
        FeishuAuth.Login cancelled = auth.newLogin();
        auth.cancelLogin(cancelled);
        try { auth.install(cancelled, "id", "s", grant("a", "r")); fail(); }
        catch (FeishuAuth.SignInRequired expected) { }
        try { auth.install(auth.newLogin(), "id", "s", grant("a", null)); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("refresh")); }
        assertFalse(auth.connected());
        FeishuAuth.Login late = auth.newLogin();
        auth.install(late, "id", "s", grant("a", "r"));
        auth.cancelLogin(late);
        assertFalse(auth.connected());
    }
}
