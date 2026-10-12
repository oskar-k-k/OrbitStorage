package com.orbitstorage.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import com.orbitstorage.config.OrbitConfig;
import org.springframework.stereotype.Component;

@Component
public class Crypto {
    private final byte[] key;
    private static final SecureRandom RANDOM = new SecureRandom();
    public Crypto(OrbitConfig config) { key = config.encryptionKey.clone(); }
    public static String randomKey() { byte[] b = new byte[32]; RANDOM.nextBytes(b); return "orb_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
    public String encrypt(String value) {
        try {
            byte[] nonce = new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[nonce.length + data.length];
            System.arraycopy(nonce, 0, result, 0, nonce.length); System.arraycopy(data, 0, result, nonce.length, data.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception ex) { throw new IllegalStateException("Encryption failed"); }
    }
    public String decrypt(String value) {
        try {
            byte[] data = Base64.getDecoder().decode(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, data, 0, 12));
            return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("Provider secret could not be decrypted"); }
    }
}
