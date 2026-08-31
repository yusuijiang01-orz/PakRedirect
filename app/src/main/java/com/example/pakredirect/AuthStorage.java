package com.example.pakredirect;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AuthStorage {
    private static final String PREFS = "rylux_auth_secure";
    private static final String KEY_ALIAS = "rylux_auth_token";
    private static final String PREF_IV = "token_iv";
    private static final String PREF_DATA = "token_data";
    private static final String PREF_USERNAME = "username";

    private final SharedPreferences prefs;

    public AuthStorage(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean saveSession(String token, String username) {
        if (token == null || token.trim().isEmpty()) return false;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return prefs.edit()
                    .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .putString(PREF_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(PREF_USERNAME, username == null ? "" : username)
                    .commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String loadToken() {
        String ivText = prefs.getString(PREF_IV, null);
        String dataText = prefs.getString(PREF_DATA, null);
        if (ivText == null || dataText == null) return null;
        try {
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] data = Base64.decode(dataText, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateSecretKey(),
                    new GCMParameterSpec(128, iv)
            );
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            clear();
            return null;
        }
    }

    public String loadUsername() {
        return prefs.getString(PREF_USERNAME, "");
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private SecretKey getOrCreateSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
        );
        return generator.generateKey();
    }
}
