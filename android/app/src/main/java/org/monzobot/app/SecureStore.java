package org.monzobot.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.LocalDate;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Atomic, app-private AES-GCM state; key never leaves Android Keystore. No backup. */
final class SecureStore {
 static final Object LOCK = new Object();
 private static final String ALIAS="monzobot-state-v1";
 private final AtomicFile file;
 private final Context context;
 SecureStore(Context c) { context=c.getApplicationContext();file=new AtomicFile(new File(c.getNoBackupFilesDir(),"budget-state.enc")); }
 private javax.crypto.SecretKey key() throws Exception {
  KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
  if(!ks.containsAlias(ALIAS)) {
   KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
   g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();
  }
  return (javax.crypto.SecretKey)ks.getKey(ALIAS,null);
 }
 JSONObject load() throws Exception {
  synchronized(LOCK) {
   byte[] data;
   try { data=file.readFully(); } catch(FileNotFoundException absent) { JSONObject initial=defaults();save(initial);return initial; }
   if(data.length<29||data.length>30*1024*1024)throw new IOException("Saved data could not be read safely.");
   Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOfRange(data,0,12)));
   return new JSONObject(new String(c.doFinal(Arrays.copyOfRange(data,12,data.length)),StandardCharsets.UTF_8));
  }
 }
 void save(JSONObject data) throws Exception {
  synchronized(LOCK) {
   Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
   byte[] clear=data.toString().getBytes(StandardCharsets.UTF_8);
   if(clear.length>30*1024*1024-28)throw new IOException("Import is too large for private storage; previous data preserved.");
   byte[] encrypted=c.doFinal(clear);
   FileOutputStream out=null;
   try{out=file.startWrite();out.write(c.getIV());out.write(encrypted);file.finishWrite(out);BudgetWidget.refresh(context);}
   catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
  }
 }
 void clear() throws Exception { synchronized(LOCK){file.delete();KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);ks.deleteEntry(ALIAS);new CompanionKeyStore(context).clear();BudgetWidget.refresh(context);} }
 static JSONObject defaults() throws JSONException {
  LocalDate now=LocalDate.now();return new JSONObject().put("version",1).put("workspaceId",UUID.randomUUID().toString()).put("mode","local").put("status","Choose a source or explore the synthetic demo.").put("start",now.withDayOfMonth(1).toString()).put("end",now.withDayOfMonth(now.lengthOfMonth()).toString()).put("threshold",80).put("quietStart",21).put("quietEnd",8).put("notifications",false).put("transactions",new JSONArray()).put("budgets",new JSONObject()).put("overrides",new JSONObject()).put("sent",new JSONObject());
 }
 static List<Finance.Transaction> transactions(JSONObject data) throws JSONException {
  List<Finance.Transaction> list=new ArrayList<>();JSONArray a=data.optJSONArray("transactions");if(a==null)return list;
  for(int i=0;i<a.length();i++){JSONObject t=a.getJSONObject(i);JSONObject raw=t.getJSONObject("raw");Map<String,String> map=new LinkedHashMap<>();Iterator<String> keys=raw.keys();while(keys.hasNext()){String k=keys.next();map.put(k,raw.getString(k));}list.add(new Finance.Transaction(t.getString("id"),t.getString("date"),t.getString("name"),t.getString("category"),t.optString("type"),"GBP",t.getLong("amount"),map));}return list;
 }
 static JSONArray encode(List<Finance.Transaction> list) throws JSONException {
  JSONArray a=new JSONArray();for(Finance.Transaction t:list)a.put(new JSONObject().put("id",t.id).put("date",t.date).put("name",t.name).put("category",t.category).put("type",t.type).put("amount",t.amount).put("raw",new JSONObject(t.raw)));return a;
 }
 static Map<String,Long> budgets(JSONObject data) throws JSONException {Map<String,Long> m=new LinkedHashMap<>();JSONObject b=data.optJSONObject("budgets");if(b!=null){Iterator<String> k=b.keys();while(k.hasNext()){String s=k.next();m.put(s,b.getLong(s));}}return m;}
 static Map<String,String> overrides(JSONObject data) throws JSONException {Map<String,String> m=new LinkedHashMap<>();JSONObject b=data.optJSONObject("overrides");if(b!=null){Iterator<String> k=b.keys();while(k.hasNext()){String s=k.next();m.put(s,b.getString(s));}}return m;}
 static Finance.Report report(JSONObject data) throws JSONException {return Finance.summarize(transactions(data),data.getString("start"),data.getString("end"),overrides(data));}
}
