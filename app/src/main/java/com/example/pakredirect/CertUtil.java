package com.example.pakredirect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class CertUtil {
    private CertUtil() {}

    public static final class GeneratedIdentity {
        public final PrivateKey privateKey;
        public final X509Certificate leaf;
        public final X509Certificate ca;
        GeneratedIdentity(PrivateKey privateKey, X509Certificate leaf, X509Certificate ca) {
            this.privateKey = privateKey;
            this.leaf = leaf;
            this.ca = ca;
        }
    }

    public static GeneratedIdentity issueServerIdentity(String[] hosts, byte[] caPem, byte[] caKeyPem) throws Exception {
        if (hosts == null || hosts.length == 0) throw new IllegalArgumentException("hosts empty");
        X509Certificate ca = parseCertificate(caPem);
        PrivateKey caKey = parsePrivateKey(caKeyPem);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair leafPair = kpg.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 5L * 60_000L);
        Date notAfter = new Date(now + 365L * 24L * 60L * 60L * 1000L);

        byte[] sigAlg = seq(oid("1.2.840.113549.1.1.11"), derNull());
        byte[] version = contextConstructed(0, integer(new byte[]{0x02}));
        byte[] serial = positiveInteger(new SecureRandom().generateSeed(16));
        byte[] issuer = ca.getSubjectX500Principal().getEncoded();
        byte[] validity = seq(utcTime(notBefore), utcTime(notAfter));
        byte[] subject = new javax.security.auth.x500.X500Principal("CN=" + hosts[0]).getEncoded();
        byte[] spki = leafPair.getPublic().getEncoded();

        List<byte[]> extensions = new ArrayList<>();
        extensions.add(extension("2.5.29.19", true, seq()));
        extensions.add(extension("2.5.29.15", true, bitString(new byte[]{(byte) 0xA0}, 5)));
        extensions.add(extension("2.5.29.37", false, seq(oid("1.3.6.1.5.5.7.3.1"))));

        List<byte[]> sanNames = new ArrayList<>();
        for (String host : hosts) {
            if (host != null && !host.isEmpty()) sanNames.add(contextPrimitive(2, host.getBytes(StandardCharsets.US_ASCII)));
        }
        extensions.add(extension("2.5.29.17", false, seq(sanNames.toArray(new byte[0][]))));

        byte[] extSeq = seq(extensions.toArray(new byte[0][]));
        byte[] extWrapper = contextConstructed(3, extSeq);

        byte[] tbs = seq(version, serial, sigAlg, issuer, validity, subject, spki, extWrapper);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(caKey);
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] certDer = seq(tbs, sigAlg, bitString(signature, 0));

        X509Certificate leaf = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certDer));
        leaf.verify(ca.getPublicKey());
        leaf.checkValidity(new Date());
        return new GeneratedIdentity(leafPair.getPrivate(), leaf, ca);
    }

    public static X509Certificate parseCertificate(byte[] pemOrDer) throws Exception {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pemOrDer));
    }

    public static PrivateKey parsePrivateKey(byte[] pem) throws Exception {
        String s = new String(pem, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(s);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static byte[] extension(String oid, boolean critical, byte[] inner) {
        if (critical) return seq(oid(oid), bool(true), octetString(inner));
        return seq(oid(oid), octetString(inner));
    }

    private static byte[] seq(byte[]... parts) { return tagged(0x30, concat(parts)); }
    private static byte[] integer(byte[] content) { return tagged(0x02, content); }
    private static byte[] positiveInteger(byte[] raw) {
        int firstNonZero = 0;
        while (firstNonZero < raw.length - 1 && raw[firstNonZero] == 0) firstNonZero++;
        byte[] v = new byte[raw.length - firstNonZero];
        System.arraycopy(raw, firstNonZero, v, 0, v.length);
        if ((v[0] & 0x80) != 0) {
            byte[] p = new byte[v.length + 1];
            System.arraycopy(v, 0, p, 1, v.length);
            v = p;
        }
        return integer(v);
    }
    private static byte[] bool(boolean v) { return tagged(0x01, new byte[]{(byte)(v ? 0xFF : 0x00)}); }
    private static byte[] derNull() { return new byte[]{0x05, 0x00}; }
    private static byte[] octetString(byte[] v) { return tagged(0x04, v); }
    private static byte[] bitString(byte[] v, int unusedBits) {
        byte[] c = new byte[v.length + 1];
        c[0] = (byte) unusedBits;
        System.arraycopy(v, 0, c, 1, v.length);
        return tagged(0x03, c);
    }
    private static byte[] utcTime(Date d) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tagged(0x17, f.format(d).getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] contextConstructed(int n, byte[] v) { return tagged(0xA0 | n, v); }
    private static byte[] contextPrimitive(int n, byte[] v) { return tagged(0x80 | n, v); }

    private static byte[] oid(String dotted) {
        String[] p = dotted.split("\\.");
        long first = Long.parseLong(p[0]);
        long second = Long.parseLong(p[1]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeBase128(out, first * 40 + second);
        for (int i = 2; i < p.length; i++) writeBase128(out, Long.parseLong(p[i]));
        return tagged(0x06, out.toByteArray());
    }
    private static void writeBase128(ByteArrayOutputStream out, long value) {
        int count = 1;
        long t = value;
        while ((t >>= 7) > 0) count++;
        for (int i = count - 1; i >= 0; i--) {
            int b = (int)((value >> (i * 7)) & 0x7F);
            if (i != 0) b |= 0x80;
            out.write(b);
        }
    }
    private static byte[] tagged(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }
    private static void writeLength(ByteArrayOutputStream out, int len) {
        if (len < 128) {
            out.write(len);
            return;
        }
        int n = 0, t = len;
        byte[] buf = new byte[4];
        while (t > 0) { buf[3 - n] = (byte)(t & 0xFF); t >>>= 8; n++; }
        out.write(0x80 | n);
        out.write(buf, 4 - n, n);
    }
    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
        return out;
    }
}
