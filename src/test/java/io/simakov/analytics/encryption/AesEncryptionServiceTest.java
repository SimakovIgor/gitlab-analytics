package io.simakov.analytics.encryption;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesEncryptionServiceTest {

    // 32 zero-bytes encoded as base64 — valid 256-bit AES key for testing
    private static final String TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String PLAINTEXT = "glpat-supersecrettoken123";

    private AesEncryptionService service;

    @BeforeEach
    void setUp() {
        service = new AesEncryptionService(TEST_KEY);
    }

    @Test
    void encryptThenDecryptReturnsOriginal() {
        String encrypted = service.encrypt(PLAINTEXT);
        assertThat(service.decrypt(encrypted)).isEqualTo(PLAINTEXT);
    }

    @Test
    void encryptProducesEncPrefixedBase64Output() {
        String encrypted = service.encrypt(PLAINTEXT);
        assertThat(encrypted).startsWith("ENC:");
        String payload = encrypted.substring(4);
        assertThat(payload).matches("[A-Za-z0-9+/]+=*");
    }

    @Test
    void decryptLegacyPlaintextTokenReturnsAsIs() {
        // Tokens stored before encryption was enabled have no ENC: prefix.
        // decrypt() must return them unchanged (backward compatibility).
        assertThat(service.decrypt("glpat-legacytoken")).isEqualTo("glpat-legacytoken");
    }

    @Test
    void twoEncryptionsOfSamePlaintextProduceDifferentCiphertexts() {
        String first = service.encrypt(PLAINTEXT);
        String second = service.encrypt(PLAINTEXT);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void encryptEmptyStringRoundTrips() {
        String encrypted = service.encrypt("");
        assertThat(service.decrypt(encrypted)).isEmpty();
    }

    @Test
    void encryptLongTokenRoundTrips() {
        String longToken = "a".repeat(500);
        assertThat(service.decrypt(service.encrypt(longToken))).isEqualTo(longToken);
    }

    @Test
    void constructorRejectsKeyTooShort() {
        byte[] shortKey = new byte[16];
        String base64 = Base64.getEncoder().encodeToString(shortKey);
        assertThatThrownBy(() -> new AesEncryptionService(base64))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32 bytes");
    }

    @Test
    void constructorRejectsKeyTooLong() {
        byte[] longKey = new byte[64];
        String base64 = Base64.getEncoder().encodeToString(longKey);
        assertThatThrownBy(() -> new AesEncryptionService(base64))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32 bytes");
    }

    @Test
    void decryptTamperedCiphertextThrows() {
        String encrypted = service.encrypt(PLAINTEXT);
        // Flip a character in the ciphertext portion (after IV = first 12 bytes = 16 base64 chars)
        char[] chars = encrypted.toCharArray();
        chars[20] = chars[20] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);
        assertThatThrownBy(() -> service.decrypt(tampered))
            .isInstanceOf(IllegalStateException.class);
    }
}
