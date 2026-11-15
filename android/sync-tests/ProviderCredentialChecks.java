package org.monzobot.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Portable storage-design checks with synthetic bytes. They do NOT exercise AndroidKeyStore,
 * app-private filesystem permissions, AtomicFile crash recovery, device backups or real credentials.
 */
public final class ProviderCredentialChecks {
    private static int checks;
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("Provider credential check " + checks); }
    private static void rejects(Runnable action) {
        checks++;
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsupported provider was accepted");
    }
    private static Cipher syntheticCipher(int mode, byte[] iv, AiProviders.Provider provider) throws Exception {
        // Public, fixed test bytes only: never used by production storage or written to a device.
        byte[] synthetic = new byte[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(synthetic, "AES"), new GCMParameterSpec(128, iv));
        byte[] binding = CompanionKeyStore.associatedData(provider);
        if (binding != null) cipher.updateAAD(binding);
        return cipher;
    }
    public static void main(String[] args) throws Exception {
        check(CompanionKeyStore.storageFileName(AiProviders.Provider.OPENAI).equals("companion-provider.enc"));
        check(CompanionKeyStore.keyAlias(AiProviders.Provider.OPENAI).equals("monzobot-companion-provider-v1"));
        check(CompanionKeyStore.legacyFormat(AiProviders.Provider.OPENAI));
        check(CompanionKeyStore.associatedData(AiProviders.Provider.OPENAI) == null);
        check(!CompanionKeyStore.legacyFormat(AiProviders.Provider.ANTHROPIC));
        Set<String> files = new HashSet<>(), aliases = new HashSet<>();
        for (AiProviders.Provider provider : new AiProviders.Provider[]{AiProviders.Provider.OPENAI, AiProviders.Provider.ANTHROPIC}) {
            String file = CompanionKeyStore.storageFileName(provider), alias = CompanionKeyStore.keyAlias(provider);
            check(files.add(file)); check(aliases.add(alias));
            check(file.endsWith(".enc")); check(!file.contains("/") && !file.contains("\\") && !file.contains(".."));
            check(alias.startsWith("monzobot-companion-"));
        }
        byte[] binding = CompanionKeyStore.associatedData(AiProviders.Provider.ANTHROPIC);
        String description = new String(binding, StandardCharsets.UTF_8);
        check(description.contains("anthropic"));
        check(description.contains(CompanionKeyStore.storageFileName(AiProviders.Provider.ANTHROPIC)));
        check(description.contains(CompanionKeyStore.keyAlias(AiProviders.Provider.ANTHROPIC)));
        byte expected = binding[0]; binding[0] ^= 1;
        check(CompanionKeyStore.associatedData(AiProviders.Provider.ANTHROPIC)[0] == expected);
        rejects(() -> CompanionKeyStore.storageFileName(null));
        rejects(() -> CompanionKeyStore.keyAlias(null));
        rejects(() -> CompanionKeyStore.associatedData(null));
        rejects(() -> CompanionKeyStore.legacyFormat(null));

        byte[] syntheticText = "synthetic-only-placeholder".getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[]{11,10,9,8,7,6,5,4,3,2,1,0};
        // Existing OpenAI layout remains 12-byte IV + GCM ciphertext/tag without associated data.
        Cipher oldWriter = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] synthetic = new byte[]{0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
        oldWriter.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(synthetic,"AES"),new GCMParameterSpec(128,iv));
        byte[] legacyCiphertext = oldWriter.doFinal(syntheticText);
        byte[] envelope = new byte[12 + legacyCiphertext.length];
        System.arraycopy(iv,0,envelope,0,12); System.arraycopy(legacyCiphertext,0,envelope,12,legacyCiphertext.length);
        check(Arrays.equals(syntheticText, syntheticCipher(Cipher.DECRYPT_MODE,Arrays.copyOfRange(envelope,0,12),AiProviders.Provider.OPENAI)
                .doFinal(Arrays.copyOfRange(envelope,12,envelope.length))));
        try {
            syntheticCipher(Cipher.DECRYPT_MODE,iv,AiProviders.Provider.ANTHROPIC).doFinal(legacyCiphertext);
            throw new AssertionError("Legacy envelope accepted in a different provider slot");
        } catch (AEADBadTagException expectedFailure) { checks++; }
        byte[] anthropicCiphertext = syntheticCipher(Cipher.ENCRYPT_MODE,iv,AiProviders.Provider.ANTHROPIC).doFinal(syntheticText);
        check(Arrays.equals(syntheticText,syntheticCipher(Cipher.DECRYPT_MODE,iv,AiProviders.Provider.ANTHROPIC).doFinal(anthropicCiphertext)));
        try {
            syntheticCipher(Cipher.DECRYPT_MODE,iv,AiProviders.Provider.OPENAI).doFinal(anthropicCiphertext);
            throw new AssertionError("Provider-bound envelope accepted as a legacy credential");
        } catch (AEADBadTagException expectedFailure) { checks++; }
        anthropicCiphertext[0] ^= 1;
        try {
            syntheticCipher(Cipher.DECRYPT_MODE,iv,AiProviders.Provider.ANTHROPIC).doFinal(anthropicCiphertext);
            throw new AssertionError("Tampered synthetic envelope accepted");
        } catch (AEADBadTagException expectedFailure) { checks++; }
        System.out.println("Provider credential storage design: " + checks + " checks passed (no AndroidKeyStore/device IO)");
    }
}
