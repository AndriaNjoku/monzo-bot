package org.monzobot.app;
import javax.net.ssl.HttpsURLConnection;
import java.net.URL;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Explicit user-triggered aggregate-only request. Never invoked by sync or notifications. */
final class AiCoach {
 static String explain(String key,String model,JSONObject aggregates)throws Exception{
  if(key.trim().isEmpty()||key.indexOf('\n')>=0||key.indexOf('\r')>=0||!model.matches("[A-Za-z0-9._:-]{1,100}"))throw new IllegalArgumentException("Enter your API key and a valid model ID.");
  JSONObject body=new JSONObject().put("model",model).put("max_completion_tokens",350).put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content","You are a concise budgeting coach. Use only supplied GBP integer-pence aggregates. Never invent transactions, source freshness, available balances, debts or commitments. Explain uncertainty and offer three small practical optional budgeting actions. Do not claim affordability or provide investment, legal or credit advice. No tools or financial actions.")).put(new JSONObject().put("role","user").put("content",aggregates.toString())));
  HttpsURLConnection c=(HttpsURLConnection)new URL("https://api.openai.com/v1/chat/completions").openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer "+key);c.setRequestProperty("Content-Type","application/json");
  try{try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}if(c.getResponseCode()!=200)throw new IOException("Provider request failed. Check model/key/provider usage.");ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>100000)throw new IOException("Provider response too large.");out.write(b,0,n);}}return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");}finally{c.disconnect();}
 }
}
