package io.simakov.analytics.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption service. Activated when app.encryption.enabled=true.
 * Each call to encrypt() generates a fresh 12-byte random IV; the output is
 * {@code ENC:} + base64(iv || ciphertext+tag).
 *
 * <p>The {@code ENC:} prefix enables transparent backward-compatibility: if
 * a stored value does not start with the prefix it was written as plaintext
 * (e.g. before encryption was enabled) and is returned as-is by {@link #decrypt}.
 * This means existing tokens continue to work after enabling encryption; they
 * will be re-encrypted the next time they are written through the service.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.encryption.enabled",
                       havingValue = "true")
public final class AesEncryptionService implements EncryptionService {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BYTES = 32;
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String ENC_PREFIX = "ENC:";

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesEncryptionService(@Value("${app.encryption.key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != AES_KEY_BYTES) {
            throw new IllegalArgumentException(
                "Encryption key must be 32 bytes (256-bit) when base64-decoded, got: "
                    + keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("AesEncryptionService initialized — AES-256-GCM encryption is active");
    }

    @Override
    public String encrypt(String plaintext) {
        byte[] iv = new byte[GCM_IV_LENGTH];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, combined, GCM_IV_LENGTH, ciphertext.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException
                 | InvalidKeyException | InvalidAlgorithmParameterException
                 | IllegalBlockSizeException | BadPaddingException e) {
            throw new IllegalStateException("AES-256-GCM encryption failed", e);
        }
    }

    @Override
    public String decrypt(String base64Ciphertext) {
        if (!base64Ciphertext.startsWith(ENC_PREFIX)) {
            // Plaintext token written before encryption was enabled — return as-is.
            return base64Ciphertext;
        }
        byte[] combined = Base64.getDecoder().decode(base64Ciphertext.substring(ENC_PREFIX.length()));
        byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException
                 | InvalidKeyException | InvalidAlgorithmParameterException
                 | IllegalBlockSizeException | BadPaddingException e) {
            throw new IllegalStateException("AES-256-GCM decryption failed", e);
        }
    }
}
