package org.monzobot.app;

import android.content.Context;
import java.util.function.BooleanSupplier;

/** Credential boundary, injectable in host UI tests without touching Android Keystore. */
interface ProviderSecrets {
 String read(AiProviders.Provider provider)throws Exception;
 void save(AiProviders.Provider provider,String key,BooleanSupplier allowed)throws Exception;
 void forget(AiProviders.Provider provider)throws Exception;
 void forgetAll()throws Exception;
 static ProviderSecrets android(Context context){Context app=context.getApplicationContext();return new ProviderSecrets(){
  public String read(AiProviders.Provider p)throws Exception{return new CompanionKeyStore(app,p).read();}
  public void save(AiProviders.Provider p,String key,BooleanSupplier allowed)throws Exception{new CompanionKeyStore(app,p).save(key,allowed);}
  public void forget(AiProviders.Provider p)throws Exception{new CompanionKeyStore(app,p).clear();}
  public void forgetAll()throws Exception{CompanionKeyStore.clearAll(app);}
 };}
}
