package com.example.pakredirect;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

/** Device-bound RSA key used only to unwrap the protected content key. */
public final class DeviceKeyManager {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "rylux_content_wrap_v1";

    private DeviceKeyManager() {}

    public static synchronized String publicKeyBase64() throws Exception {
        ensureKey();
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        PublicKey publicKey = store.getCertificate(ALIAS).getPublicKey();
        return Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP);
    }

    public static synchronized byte[] unwrapKey(String wrappedBase64) throws Exception {
        ensureKey();
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        PrivateKey privateKey = (PrivateKey) store.getKey(ALIAS, null);
        if (privateKey == null) throw new IllegalStateException("设备私钥不可用");

        byte[] wrapped = Base64.decode(wrappedBase64, Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA1,
                PSource.PSpecified.DEFAULT
        );
        cipher.init(Cipher.DECRYPT_MODE, privateKey, oaep);
        byte[] key = cipher.doFinal(wrapped);
        if (key.length != 32) throw new IllegalStateException("内容密钥长度无效");
        return key;
    }

    private static void ensureKey() throws Exception {
        KeyStore store = KeyStore.getInstance(STORE);
        store.load(null);
        if (store.containsAlias(ALIAS)) return;

        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                STORE
        );
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_ENCRYPT
        )
                .setKeySize(2048)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setUserAuthenticationRequired(false)
                .build();
        generator.initialize(spec);
        generator.generateKeyPair();
    }
}
