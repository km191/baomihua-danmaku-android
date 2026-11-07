package com.baomihua.danmaku;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.security.Key;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AppSecret 的本机存储。
 *
 * <p>优先用 Android Keystore 里的 AES/GCM 密钥加密后再写 SharedPreferences：
 * 密钥不可导出，换机/备份也解不开。少数 ROM 的 Keystore 会异常，这时退回到
 * 「应用私有目录 + 明文」并记一条诊断——功能优先，但会在面板里提示。
 *
 * <p>不参与任何上传，也不写日志。
 */
public final class SecretStore {

    private static final String PREFS = "danmu_secure";
    private static final String KEY_DATA = "api_app_secret";
    private static final String KEY_ALIAS = "danmu-api-secret";
    private static final String PREFIX_AES = "v1:";
    private static final String PREFIX_PLAIN = "plain:";

    private SecretStore() {
    }

    public static String read(Context context) {
        String raw = prefs(context).getString(KEY_DATA, "");
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (raw.startsWith(PREFIX_PLAIN)) {
            return raw.substring(PREFIX_PLAIN.length());
        }
        if (!raw.startsWith(PREFIX_AES)) {
            // 早期版本没有前缀，按明文处理
            return raw;
        }
        try {
            byte[] all = Base64.decode(raw.substring(PREFIX_AES.length()), Base64.NO_WRAP);
            if (all.length < 2) {
                return "";
            }
            int ivLength = all[0] & 0xFF;
            if (ivLength <= 0 || ivLength + 1 > all.length) {
                return "";
            }
            byte[] iv = new byte[ivLength];
            System.arraycopy(all, 1, iv, 0, ivLength);
            byte[] cipherText = new byte[all.length - 1 - ivLength];
            System.arraycopy(all, 1 + ivLength, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), "UTF-8");
        } catch (Throwable e) {
            Diag.error("读取 AppSecret 失败（需要重新填写）", e);
            return "";
        }
    }

    /**
     * 保存 AppSecret；空串表示清除。
     *
     * @return true 表示已用 Keystore 加密保存；false 表示用了明文兜底
     */
    public static boolean write(Context context, String value) {
        if (value == null || value.isEmpty()) {
            clear(context);
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(value.getBytes("UTF-8"));

            ByteArrayOutputStream out = new ByteArrayOutputStream(iv.length + cipherText.length + 1);
            out.write(iv.length & 0xFF);
            out.write(iv);
            out.write(cipherText);
            prefs(context).edit().putString(KEY_DATA,
                    PREFIX_AES + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)).apply();
            return true;
        } catch (Throwable e) {
            Diag.error("Keystore 加密失败，AppSecret 改用应用私有明文保存", e);
            prefs(context).edit().putString(KEY_DATA, PREFIX_PLAIN + value).apply();
            return false;
        }
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_DATA).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build());
        return generator.generateKey();
    }
}
