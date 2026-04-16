package io.simakov.analytics.encryption;

/**
 * Abstraction for encrypting/decrypting sensitive values (e.g., GitLab tokens).
 * Replace NoOpEncryptionService with a real implementation backed by Vault or KMS in production.
 */
public interface EncryptionService {

    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
