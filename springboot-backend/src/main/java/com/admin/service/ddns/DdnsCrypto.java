package com.admin.service.ddns;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Credentials follow the existing deployment secret; include .env when restoring backups. */
@Component
public class DdnsCrypto {
    private final SecretKeySpec key;
    public DdnsCrypto(@Value("${jwt-secret}") String secret) {
        try {
            if (secret == null || secret.isBlank()) throw new IllegalArgumentException("面板密钥为空，无法保存 DNS 凭证");
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(
                    ("flux-ddns-v1:" + secret).getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception ex) { throw new IllegalStateException("无法初始化 DNS 凭证加密", ex); }
    }
    public String encrypt(String value) {
        try {
            byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] data = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = Arrays.copyOf(iv, iv.length + data.length);
            System.arraycopy(data, 0, result, iv.length, data.length);
            return Base64.getEncoder().encodeToString(result);
        } catch (Exception ex) { throw new IllegalStateException("凭证加密失败"); }
    }
    public String decrypt(String value) {
        try {
            byte[] data = Base64.getDecoder().decode(value);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Arrays.copyOf(data, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(data, 12, data.length)), StandardCharsets.UTF_8);
        } catch (Exception ex) { throw new IllegalStateException("DNS 凭证无法解密，请重新保存 API Token"); }
    }
}
