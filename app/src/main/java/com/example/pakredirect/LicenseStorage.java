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

public final class LicenseStorage {
    private static final String PREFS = "license_secure";
    private static final String KEY_ALIAS = "pakredirect_license_key";
    private static final String PREF_IV = "iv";
    private static final String PREF_DATA = "data";

    private final SharedPreferences prefs;

    public LicenseStorage(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean saveKey(String licenseKey) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey());

            byte[] encrypted = cipher.doFinal(licenseKey.getBytes(StandardCharsets.UTF_8));
            String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
            String data = Base64.encodeToString(encrypted, Base64.NO_WRAP);

            return prefs.edit()
                    .putString(PREF_IV, iv)
                    .putString(PREF_DATA, data)
                    .commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public String loadKey() {
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
