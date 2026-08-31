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

    private static final String PREF_REMEMBER = "remember_password";
    private static final String PREF_REMEMBER_USERNAME = "remember_username";
    private static final String PREF_PASSWORD_IV = "password_iv";
    private static final String PREF_PASSWORD_DATA = "password_data";

    private final SharedPreferences prefs;

    public AuthStorage(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean saveSession(String token, String username) {
        if (token == null || token.trim().isEmpty()) return false;
        try {
            EncryptedValue encrypted = encrypt(token);
            return prefs.edit()
                    .putString(PREF_IV, encrypted.iv)
                    .putString(PREF_DATA, encrypted.data)
                    .putString(PREF_USERNAME, username == null ? "" : username)
                    .commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String loadToken() {
        return decryptPreference(PREF_IV, PREF_DATA, true);
    }

    public String loadUsername() {
        return prefs.getString(PREF_USERNAME, "");
    }

    public boolean saveRememberedCredentials(String username, String password) {
        if (password == null || password.isEmpty()) return false;
        try {
            EncryptedValue encrypted = encrypt(password);
            return prefs.edit()
                    .putBoolean(PREF_REMEMBER, true)
                    .putString(PREF_REMEMBER_USERNAME, username == null ? "" : username)
                    .putString(PREF_PASSWORD_IV, encrypted.iv)
                    .putString(PREF_PASSWORD_DATA, encrypted.data)
                    .commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isRememberPasswordEnabled() {
        return prefs.getBoolean(PREF_REMEMBER, false);
    }

    public String loadRememberedUsername() {
        return prefs.getString(PREF_REMEMBER_USERNAME, "");
    }

    public String loadRememberedPassword() {
        if (!isRememberPasswordEnabled()) return "";
        String value = decryptPreference(PREF_PASSWORD_IV, PREF_PASSWORD_DATA, false);
        return value == null ? "" : value;
    }

    public void clearRememberedCredentials() {
        prefs.edit()
                .remove(PREF_REMEMBER)
                .remove(PREF_REMEMBER_USERNAME)
                .remove(PREF_PASSWORD_IV)
                .remove(PREF_PASSWORD_DATA)
                .apply();
    }

    public void clearSession() {
        prefs.edit()
                .remove(PREF_IV)
                .remove(PREF_DATA)
                .remove(PREF_USERNAME)
                .apply();
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private EncryptedValue encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return new EncryptedValue(
                Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP),
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        );
    }

    private String decryptPreference(String ivKey, String dataKey, boolean clearSessionOnError) {
        String ivText = prefs.getString(ivKey, null);
        String dataText = prefs.getString(dataKey, null);
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
            if (clearSessionOnError) clearSession();
            else clearRememberedCredentials();
            return null;
        }
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

    private static final class EncryptedValue {
        final String iv;
        final String data;

        EncryptedValue(String iv, String data) {
            this.iv = iv;
            this.data = data;
        }
    }
}
