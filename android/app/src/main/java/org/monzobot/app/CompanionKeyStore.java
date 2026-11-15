package org.monzobot.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Per-provider credentials stay in separate no-backup encrypted files, never financial JSON. */
final class CompanionKeyStore {
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    private final AiProviders.Provider provider;

    /** Preserve the existing OpenAI file, key alias and IV+ciphertext format exactly. */
    CompanionKeyStore(Context context) { this(context, AiProviders.Provider.OPENAI); }

    CompanionKeyStore(Context context, AiProviders.Provider provider) {
        this.provider = supported(provider);
        Objects.requireNonNull(context, "context");
        File directory = Objects.requireNonNull(context.getNoBackupFilesDir(), "private credential directory");
        file = new AtomicFile(new File(directory, storageFileName(this.provider)));
    }

    /** Non-secret storage metadata, exposed package-locally for design/compatibility checks. */
    static String storageFileName(AiProviders.Provider provider) {
        switch (supported(provider)) {
            case OPENAI: return "companion-provider.enc";
            case ANTHROPIC: return "companion-provider-anthropic.enc";
            default: throw new IllegalArgumentException("Unsupported credential provider");
        }
    }

    static String keyAlias(AiProviders.Provider provider) {
        switch (supported(provider)) {
            case OPENAI: return "monzobot-companion-provider-v1";
            case ANTHROPIC: return "monzobot-companion-anthropic-v1";
            default: throw new IllegalArgumentException("Unsupported credential provider");
        }
    }

    static boolean legacyFormat(AiProviders.Provider provider) { return supported(provider) == AiProviders.Provider.OPENAI; }

    /** Legacy OpenAI has no AAD; new provider storage additionally authenticates its slot identity. */
    static byte[] associatedData(AiProviders.Provider provider) {
        AiProviders.Provider selected = supported(provider);
        if (legacyFormat(selected)) return null;
        return ("monzobot-credential-slot-v1\u0000" + selected.id + "\u0000"
                + storageFileName(selected) + "\u0000" + keyAlias(selected)).getBytes(StandardCharsets.UTF_8);
    }

    private SecretKey key(boolean create) throws Exception {
        String alias = keyAlias(provider);
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        if (!keys.containsAlias(alias)) {
            if (!create) throw new IOException("Saved provider key is unavailable");
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        java.security.Key value = keys.getKey(alias, null);
        if (!(value instanceof SecretKey)) throw new IOException("Saved provider key is unavailable");
        return (SecretKey) value;
    }

    String read() throws Exception {
        synchronized (LOCK) {
            final byte[] bytes;
            try { bytes = file.readFully(); } catch (FileNotFoundException absent) { return ""; }
            if (bytes.length < 29 || bytes.length > 4124) throw new IOException("Saved provider key is unavailable");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
            bind(cipher);
            String value = new String(cipher.doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)), StandardCharsets.UTF_8);
            if (!valid(value)) throw new IOException("Saved provider key is unavailable");
            return value;
        }
    }

    void save(String value) throws Exception { save(value, () -> true); }

    /** Guard under the same lock as clear(), including before the atomic write is committed. */
    void save(String value, BooleanSupplier allowed) throws Exception {
        Objects.requireNonNull(allowed, "credential save guard");
        synchronized (LOCK) {
            checkAllowed(allowed);
            if (value == null || !valid(value.trim())) throw new IllegalArgumentException("Invalid provider key");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true)); bind(cipher);
            byte[] ciphertext = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
            checkAllowed(allowed);
            FileOutputStream out = null;
            try {
                out = file.startWrite(); out.write(cipher.getIV()); out.write(ciphertext);
                checkAllowed(allowed);
                file.finishWrite(out);
            } catch (Exception failed) {
                if (out != null) file.failWrite(out);
                throw failed;
            }
        }
    }

    /** Forget only this provider's slot; neither reads nor changes any other provider's credentials. */
    void clear() throws Exception {
        synchronized (LOCK) {
            file.delete();
            KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
            keys.deleteEntry(keyAlias(provider));
        }
    }

    /** Explicit global forget attempts every implemented provider, preserving any failure for caller. */
    static void clearAll(Context context) throws Exception {
        synchronized (LOCK) {
            Exception firstFailure = null;
            for (AiProviders.Provider provider : new AiProviders.Provider[]{AiProviders.Provider.OPENAI, AiProviders.Provider.ANTHROPIC}) {
                try { new CompanionKeyStore(context, provider).clear(); }
                catch (Exception failure) {
                    if (firstFailure == null) firstFailure = failure; else firstFailure.addSuppressed(failure);
                }
            }
            if (firstFailure != null) throw firstFailure;
        }
    }

    private void bind(Cipher cipher) {
        byte[] aad = associatedData(provider); if (aad != null) cipher.updateAAD(aad);
    }
    private static boolean valid(String value) { return value != null && value.matches("[\\x21-\\x7e]{1,4000}"); }
    private static void checkAllowed(BooleanSupplier allowed) throws InterruptedException {
        if (!allowed.getAsBoolean()) throw new InterruptedException("Credential save cancelled");
    }
    private static AiProviders.Provider supported(AiProviders.Provider provider) {
        if (provider == null) throw new IllegalArgumentException("Choose a supported credential provider");
        switch (provider) {
            case OPENAI: case ANTHROPIC: return provider;
            default: throw new IllegalArgumentException("Unsupported credential provider");
        }
    }
}
