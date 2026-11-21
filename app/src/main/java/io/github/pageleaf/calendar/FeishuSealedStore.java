package io.github.pageleaf.calendar;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import static java.nio.charset.StandardCharsets.UTF_8;

/** One authenticated ciphertext in noBackupFilesDir. A rename commits the entire grant. */
final class FeishuSealedStore implements FeishuAuth.Store {
    private static final String ALIAS = "pageleaf.feishu.credentials.v1";
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private final File file, pending;

    FeishuSealedStore(Context context) {
        file = new File(context.getNoBackupFilesDir(), "feishu-session.v1");
        pending = new File(file.getParentFile(), file.getName() + ".tmp");
    }

    private SecretKey key(boolean create) throws Exception {
        KeyStore keys = KeyStore.getInstance(PROVIDER);
        keys.load(null);
        SecretKey existing = (SecretKey) keys.getKey(ALIAS, null);
        if (existing != null) return existing;
        if (!create) throw new IOException("本机飞书密钥不可用。");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    public JSONObject read() throws Exception {
        if (!file.exists()) return null;
        if (file.length() < IV_BYTES + 16 || file.length() > 131072) throw new IOException("本机飞书凭证损坏。");
        byte[] bytes = Files.readAllBytes(file.toPath());
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, bytes, 0, IV_BYTES));
        byte[] clear = cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES);
        try { return new JSONObject(new String(clear, UTF_8)); }
        finally { Arrays.fill(clear, (byte) 0); }
    }

    public void save(JSONObject state) throws Exception {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] iv = cipher.getIV();
        if (iv.length != IV_BYTES) throw new IOException("无法加密本机飞书凭证。");
        byte[] clear = state.toString().getBytes(UTF_8);
        try {
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(iv);
                out.write(cipher.doFinal(clear));
                out.getFD().sync();
            }
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Arrays.fill(clear, (byte) 0);
            Files.deleteIfExists(pending.toPath());
        }
    }

    public void clear() throws IOException {
        Files.deleteIfExists(pending.toPath());
        Files.deleteIfExists(file.toPath());
    }
}
