package org.monzobot.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials have a separate encrypted file and never enter exportable financial JSON. */
final class CompanionKeyStore {
 private static final Object LOCK=new Object();
 private static final String ALIAS="monzobot-companion-provider-v1";
 private final AtomicFile file;
 CompanionKeyStore(Context context){file=new AtomicFile(new File(context.getNoBackupFilesDir(),"companion-provider.enc"));}
 private javax.crypto.SecretKey key()throws Exception{
  KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
  if(!keys.containsAlias(ALIAS)){KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();}
  return (javax.crypto.SecretKey)keys.getKey(ALIAS,null);
 }
 String read()throws Exception{synchronized(LOCK){byte[] bytes;try{bytes=file.readFully();}catch(FileNotFoundException absent){return "";}if(bytes.length<29||bytes.length>4124)throw new IOException("Saved provider key is unavailable");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));return new String(cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length)),StandardCharsets.UTF_8);}}
 void save(String value)throws Exception{save(value,()->true);}
 void save(String value,java.util.function.BooleanSupplier allowed)throws Exception{synchronized(LOCK){if(!allowed.getAsBoolean())throw new InterruptedException("Credential save cancelled");if(value==null||!value.trim().matches("[\\x21-\\x7e]{1,4000}"))throw new IllegalArgumentException("Invalid provider key");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());byte[] ciphertext=cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));FileOutputStream out=null;try{out=file.startWrite();out.write(cipher.getIV());out.write(ciphertext);file.finishWrite(out);}catch(Exception failed){if(out!=null)file.failWrite(out);throw failed;}}}
 void clear()throws Exception{synchronized(LOCK){file.delete();KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry(ALIAS);}}
}
