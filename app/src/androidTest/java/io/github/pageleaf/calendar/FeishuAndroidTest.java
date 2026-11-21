package io.github.pageleaf.calendar;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.test.InstrumentationTestCase;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static java.nio.charset.StandardCharsets.UTF_8;

public final class FeishuAndroidTest extends InstrumentationTestCase {
    public void testSealedCredentialRoundTripAndTamperDetection() throws Exception {
        Context target = getInstrumentation().getTargetContext();
        File dir = new File(target.getCacheDir(), UUID.randomUUID().toString());
        assertTrue(dir.mkdir());
        Context isolated = new ContextWrapper(target) {
            @Override public File getNoBackupFilesDir() { return dir; }
        };
        FeishuSealedStore store = new FeishuSealedStore(isolated);
        try {
            String fixture = "pageleaf-test-credential-" + UUID.randomUUID();
            store.save(new JSONObject().put("fixture", fixture));
            assertEquals(fixture, new FeishuSealedStore(isolated).read().getString("fixture"));
            File[] files = dir.listFiles();
            assertNotNull(files);
            assertEquals(1, files.length);
            byte[] bytes = Files.readAllBytes(files[0].toPath());
            assertFalse(new String(bytes, UTF_8).contains(fixture));
            bytes[bytes.length - 1] ^= 1;
            Files.write(files[0].toPath(), bytes);
            try { store.read(); fail("Modified ciphertext accepted"); }
            catch (javax.crypto.AEADBadTagException expected) { }
        } finally {
            store.clear();
            assertNull(store.read());
            assertTrue(dir.delete());
        }
    }

    public void testQrDecodesToOriginalVerificationUri() throws Exception {
        String link = "https://accounts.feishu.cn/oauth/v1/device/verify?user_code=DEMO";
        android.graphics.Bitmap qr = AuthDialog.qrCode(link);
        assertNotNull(qr);
        int w = qr.getWidth(), h = qr.getHeight();
        int[] pixels = new int[w * h];
        qr.getPixels(pixels, 0, w, 0, 0, w, h);
        com.google.zxing.BinaryBitmap image = new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
                new com.google.zxing.RGBLuminanceSource(w, h, pixels)));
        assertEquals(link, new com.google.zxing.qrcode.QRCodeReader().decode(image).getText());
    }

    public void testDeviceApprovalAcrossRotationRunsCallbackOnce() throws Exception {
        Instrumentation ins = getInstrumentation();
        Context target = ins.getTargetContext();
        Intent launch = target.getPackageManager().getLaunchIntentForPackage(target.getPackageName());
        Activity activity = ins.startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        java.util.concurrent.CountDownLatch allowApproval = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch success = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger callbacks = new java.util.concurrent.atomic.AtomicInteger();
        FeishuAuth.Store memory = new FeishuAuth.Store() {
            JSONObject value;
            public JSONObject read() { return value; }
            public void save(JSONObject s) { value = s; }
            public void clear() { value = null; }
        };
        FeishuAuth auth = new FeishuAuth(memory, new FeishuProtocol((url, header, type, body) -> {
            if (url.equals(FeishuProtocol.DEVICE_URL)) {
                return new JSONObject().put("interval", 1).put("device_code", "fixture-device")
                        .put("user_code", "ABCD-EFGH").put("verification_uri", "https://accounts.feishu.cn/verify");
            }
            if (allowApproval.getCount() > 0) throw new Net.HttpError(400, 0, "authorization_pending");
            return new JSONObject().put("access_token", "access-fixture").put("refresh_token", "refresh-fixture");
        }));
        int orientation = activity.getRequestedOrientation();
        try {
            ins.runOnMainSync(() -> AuthDialog.show(activity, auth, () -> { callbacks.incrementAndGet(); success.countDown(); }));
            setNodeText(ins, "App ID", "cli_fixture");
            setNodeText(ins, "App Secret", "fixture");
            assertTrue(node(ins, "获取授权码").performAction(AccessibilityNodeInfo.ACTION_CLICK));
            assertNotNull(node(ins, "ABCD-EFGH"));
            ins.runOnMainSync(() -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            ins.waitForIdleSync();
            assertNotNull(node(ins, "打开验证页"));
            allowApproval.countDown();
            assertTrue(success.await(8, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
            assertEquals("access-fixture", auth.accessToken());
        } finally {
            ins.runOnMainSync(() -> { activity.setRequestedOrientation(orientation); activity.finish(); });
        }
    }

    private static AccessibilityNodeInfo node(Instrumentation ins, String text) {
        long until = android.os.SystemClock.elapsedRealtime() + 4500;
        do {
            AccessibilityNodeInfo root = ins.getUiAutomation().getRootInActiveWindow();
            AccessibilityNodeInfo match = matchingNode(root, text);
            if (match != null) return match;
            android.os.SystemClock.sleep(80);
        } while (android.os.SystemClock.elapsedRealtime() < until);
        throw new AssertionError("Missing dialog control " + text);
    }

    private static AccessibilityNodeInfo matchingNode(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (text.equals(String.valueOf(node.getHintText())) || String.valueOf(node.getText()).contains(text)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo match = matchingNode(node.getChild(i), text);
            if (match != null) return match;
        }
        return null;
    }

    private static void setNodeText(Instrumentation ins, String label, String value) {
        android.os.Bundle arguments = new android.os.Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        assertTrue(node(ins, label).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
    }

    public void testSetupDialogCancelsWithoutStartingAuthorization() throws Exception {
        Instrumentation ins = getInstrumentation();
        Context target = ins.getTargetContext();
        Intent launch = target.getPackageManager().getLaunchIntentForPackage(target.getPackageName());
        assertNotNull(launch);
        Activity activity = ins.startActivitySync(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicBoolean callback = new AtomicBoolean();
        FeishuAuth auth = new FeishuAuth(target);
        try {
            ins.runOnMainSync(() -> AuthDialog.show(activity, auth, () -> callback.set(true)));
            ins.waitForIdleSync();
            AccessibilityNodeInfo root = ins.getUiAutomation().getRootInActiveWindow();
            assertNotNull(root);
            assertFalse(root.findAccessibilityNodeInfosByText("连接飞书").isEmpty());
            ins.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            ins.waitForIdleSync();
            assertFalse(callback.get());
        } finally { ins.runOnMainSync(activity::finish); }
    }
}
