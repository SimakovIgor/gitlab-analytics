package io.simakov.analytics.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * WARNING: Stores tokens as plaintext. For development/testing only.
 * Replace with a Vault/KMS-backed implementation before going to production.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "app.encryption.enabled",
                       havingValue = "false",
                       matchIfMissing = true)
public class NoOpEncryptionService implements EncryptionService {

    public NoOpEncryptionService() {
        log.warn("NoOpEncryptionService is active — tokens are stored as plaintext. "
            + "Replace with a real EncryptionService before production use.");
    }

    @Override
    public String encrypt(String plaintext) {
        return plaintext;
    }

    @Override
    public String decrypt(String ciphertext) {
        return ciphertext;
    }
}
