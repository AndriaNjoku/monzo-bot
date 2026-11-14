package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed-origin BYOK protocol adapters. No retries, storage, logs, arbitrary endpoints or server tools. */
public final class AiProviders {
 public static final int MAX_BODY_BYTES=256000,MAX_RESPONSE_BYTES=128000;
 public static final String ANTHROPIC_VERSION="2026-10-21";
 private static final String OPENAI_ENDPOINT="https://api.openai.com/v1/chat/completions";
 private static final String ANTHROPIC_ENDPOINT="https://api.anthropic.com/v1/messages";
 public enum Provider {
  OPENAI("openai","OpenAI","gpt-4.1-mini","Legacy default (deprecated); verify account availability or choose an account-supported Chat Completions tools/JSON model.","api.openai.com"),
  ANTHROPIC("anthropic","Anthropic","claude-haiku-4-5-20251001","Pinned Haiku example; verify account availability. A chosen model must support Messages tools and final JSON.","api.anthropic.com");
  public final String id,displayName,defaultModel,modelNote,host;
  Provider(String id,String display,String model,String note,String host){this.id=id;displayName=display;defaultModel=model;modelNote=note;this.host=host;}
 }
 public interface Transport {JSONObject post(String endpoint,Map<String,String> headers,JSONObject body)throws Exception;}
 private AiProviders(){}
 public static Provider fromId(String id){for(Provider p:Provider.values())if(p.id.equals(id))return p;throw new IllegalArgumentException("Unsupported AI provider");}
 public static CompanionAgent.ModelGateway gateway(Provider provider){return gateway(provider,new HttpTransport());}
 public static CompanionAgent.ModelGateway gateway(Provider provider,Transport transport){
  Objects.requireNonNull(provider);Objects.requireNonNull(transport);
  return (request,key)->{
   try{
    validateKey(key);JSONObject copy=new JSONObject(request.toString());validateRequest(copy);
    JSONObject body=provider==Provider.OPENAI?copy:anthropicRequest(copy);bounded(body,MAX_BODY_BYTES,"Provider context exceeds the request limit");
    Map<String,String> headers=new LinkedHashMap<>();headers.put("Content-Type","application/json");
    String endpoint;
    if(provider==Provider.OPENAI){endpoint=OPENAI_ENDPOINT;headers.put("Authorization","Bearer "+key.trim());}
    else{endpoint=ANTHROPIC_ENDPOINT;headers.put("x-api-key",key.trim());headers.put("anthropic-version",ANTHROPIC_VERSION);}
    JSONObject response=transport.post(endpoint,Collections.unmodifiableMap(headers),body);
    if(response==null)throw new IOException("Provider returned no response");bounded(response,MAX_RESPONSE_BYTES,"Provider response exceeds the limit");
    return provider==Provider.OPENAI?openAiResponse(response):anthropicResponse(response);
   }catch(JSONException malformed){throw new IOException("Provider protocol data was invalid");}
  };
 }
 private static void validateRequest(JSONObject r)throws Exception{
  only(r,"model","messages","tools","tool_choice","max_completion_tokens","response_format","store");
  if(!r.getString("model").matches("[A-Za-z0-9._:-]{1,100}"))throw new IOException("Invalid model ID");
  Object tokens=r.get("max_completion_tokens");if(!(tokens instanceof Number)||new java.math.BigDecimal(tokens.toString()).intValueExact()<1||new java.math.BigDecimal(tokens.toString()).intValueExact()>4096)throw new IOException("Unsupported output token bound");
  if(r.has("store")&&!Boolean.FALSE.equals(r.get("store")))throw new IOException("Unsupported storage setting");
  if(r.has("response_format")){JSONObject f=r.getJSONObject("response_format");only(f,"type");if(!"json_object".equals(f.getString("type")))throw new IOException("Unsupported final-answer format");}
  if(r.has("tool_choice")&&!Arrays.asList("required","auto","none").contains(r.getString("tool_choice")))throw new IOException("Unsupported tool choice");
  JSONArray messages=r.getJSONArray("messages");if(messages.length()<1||messages.length()>40)throw new IOException("Unsupported message count");
  JSONArray tools=r.getJSONArray("tools");if(tools.length()>32)throw new IOException("Too many local tools");
  Set<String> names=new HashSet<>();for(int i=0;i<tools.length();i++){JSONObject t=tools.getJSONObject(i);only(t,"type","function");if(!"function".equals(t.getString("type")))throw new IOException("Only local function tools are supported");JSONObject fn=t.getJSONObject("function");only(fn,"name","description","parameters","strict");if(!fn.getString("name").matches("[A-Za-z0-9_-]{1,128}")||!names.add(fn.getString("name")))throw new IOException("Invalid local tool name");fn.getJSONObject("parameters");}
  // Apply the same strict role/tool-result validation for both protocols.
  convertMessages(messages);
 }
 private static JSONObject anthropicRequest(JSONObject r)throws Exception{
  JSONObject translated=convertMessages(r.getJSONArray("messages"));
  JSONObject body=new JSONObject().put("model",r.getString("model")).put("max_tokens",r.getInt("max_completion_tokens")).put("messages",translated.getJSONArray("messages"));
  String system=translated.optString("system");if(!system.isEmpty())body.put("system",system);
  JSONArray tools=new JSONArray();JSONArray original=r.getJSONArray("tools");
  for(int i=0;i<original.length();i++){JSONObject fn=original.getJSONObject(i).getJSONObject("function");JSONObject t=new JSONObject().put("name",fn.getString("name")).put("description",fn.optString("description")).put("input_schema",fn.getJSONObject("parameters"));if(fn.has("strict"))t.put("strict",fn.getBoolean("strict"));tools.put(t);}
  body.put("tools",tools);
  if(r.has("tool_choice")){String choice=r.getString("tool_choice");body.put("tool_choice",new JSONObject().put("type",choice.equals("required")?"any":choice));}
  // Legacy Haiku supports tool use; the agent system prompt demands final JSON. No unsupported OpenAI JSON-mode field is forwarded.
  return body;
 }
 private static JSONObject convertMessages(JSONArray input)throws Exception{
  JSONArray messages=new JSONArray();StringBuilder system=new StringBuilder();Set<String> pending=new LinkedHashSet<>(),seenIds=new HashSet<>();boolean started=false;
  for(int i=0;i<input.length();i++){
   JSONObject m=input.getJSONObject(i);only(m,"role","content","tool_calls","tool_call_id");String role=m.getString("role");
   if(role.equals("system")){if(started||m.has("tool_calls")||m.has("tool_call_id"))throw new IOException("Invalid system message order");String text=m.getString("content");if(system.length()>0)system.append('\n');system.append(text);continue;}
   started=true;
   if(role.equals("tool")){
    if(m.has("tool_calls")||!pending.remove(m.getString("tool_call_id")))throw new IOException("Tool result does not match a pending local call");
    JSONObject block=new JSONObject().put("type","tool_result").put("tool_use_id",m.getString("tool_call_id")).put("content",m.getString("content"));
    try{if(new JSONObject(m.getString("content")).has("error"))block.put("is_error",true);}catch(JSONException nonJson){/* Text local results are valid. */}
    append(messages,"user",new JSONArray().put(block));continue;
   }
   if(!pending.isEmpty())throw new IOException("Missing local tool results");
   if(m.has("tool_call_id")||!(role.equals("user")||role.equals("assistant")))throw new IOException("Unsupported conversation role");
   JSONArray blocks=new JSONArray();Object content=m.opt("content");
   if(content!=null&&content!=JSONObject.NULL){if(!(content instanceof String))throw new IOException("Only text conversation content is supported");if(!((String)content).isEmpty())blocks.put(new JSONObject().put("type","text").put("text",content));}
   if(m.has("tool_calls")){
    if(!role.equals("assistant"))throw new IOException("Only assistant messages may request local tools");JSONArray calls=m.getJSONArray("tool_calls");if(calls.length()<1||calls.length()>8)throw new IOException("Unsupported local tool call count");
    for(int j=0;j<calls.length();j++){JSONObject call=calls.getJSONObject(j);only(call,"id","type","function");String id=call.getString("id");if(!id.matches("[A-Za-z0-9_-]{1,200}")||!seenIds.add(id)||!"function".equals(call.getString("type")))throw new IOException("Invalid local tool call");JSONObject fn=call.getJSONObject("function");only(fn,"name","arguments");if(!fn.getString("name").matches("[A-Za-z0-9_-]{1,128}"))throw new IOException("Invalid function name");blocks.put(new JSONObject().put("type","tool_use").put("id",id).put("name",fn.getString("name")).put("input",new JSONObject(fn.getString("arguments"))));pending.add(id);}
   }
   if(blocks.length()==0)throw new IOException("Empty conversation message");
   if(messages.length()==0&&!role.equals("user"))throw new IOException("Conversation must begin with a user question");append(messages,role,blocks);
  }
  if(!pending.isEmpty()||messages.length()==0||!"user".equals(messages.getJSONObject(messages.length()-1).getString("role")))throw new IOException("Request must end with user input or completed local tool results");
  return new JSONObject().put("system",system.toString()).put("messages",messages);
 }
 private static void append(JSONArray messages,String role,JSONArray blocks)throws Exception{
  if(messages.length()>0&&messages.getJSONObject(messages.length()-1).getString("role").equals(role)){JSONArray previous=messages.getJSONObject(messages.length()-1).getJSONArray("content");for(int i=0;i<blocks.length();i++)previous.put(blocks.get(i));}
  else messages.put(new JSONObject().put("role",role).put("content",blocks));
 }
 private static JSONObject openAiResponse(JSONObject response)throws Exception{
  JSONArray choices=response.getJSONArray("choices");if(choices.length()!=1)throw new IOException("Provider must return one assistant response");JSONObject choice=choices.getJSONObject(0);String stop=choice.optString("finish_reason");
  if(Arrays.asList("length","content_filter").contains(stop))throw new IOException("Provider response was incomplete or refused");JSONObject message=choice.getJSONObject("message");if(message.has("role")&&!"assistant".equals(message.getString("role")))throw new IOException("Unexpected provider response role");
  if(message.opt("refusal")!=null&&message.opt("refusal")!=JSONObject.NULL&&!message.optString("refusal").isEmpty())throw new IOException("Provider refused the request");
  JSONArray calls=message.optJSONArray("tool_calls");if(calls!=null&&calls.length()>8)throw new IOException("Too many returned local tool calls");
  if(!Arrays.asList("stop","tool_calls").contains(stop)||stop.equals("tool_calls")!=(calls!=null&&calls.length()>0))throw new IOException("Provider stop reason does not match returned tools");
  if(calls==null||calls.length()==0){if(!(message.opt("content") instanceof String)||message.getString("content").trim().isEmpty())throw new IOException("Provider returned no final text");}
  else {Set<String> ids=new HashSet<>();for(int i=0;i<calls.length();i++){JSONObject call=calls.getJSONObject(i);if(!"function".equals(call.getString("type"))||!call.getString("id").matches("[A-Za-z0-9_-]{1,200}")||!ids.add(call.getString("id")))throw new IOException("Invalid returned local tool call");JSONObject fn=call.getJSONObject("function");if(!fn.getString("name").matches("[A-Za-z0-9_-]{1,128}"))throw new IOException("Invalid returned local function name");new JSONObject(fn.getString("arguments"));}}
  return new JSONObject(response.toString());
 }
 private static JSONObject anthropicResponse(JSONObject response)throws Exception{
  if(!"message".equals(response.getString("type"))||!"assistant".equals(response.getString("role")))throw new IOException("Unexpected provider response type");String stop=response.getString("stop_reason");
  if(!Arrays.asList("tool_use","end_turn","stop_sequence").contains(stop))throw new IOException("Provider response was incomplete or refused");
  JSONArray content=response.getJSONArray("content"),calls=new JSONArray();StringBuilder text=new StringBuilder();Set<String> ids=new HashSet<>();
  for(int i=0;i<content.length();i++){JSONObject block=content.getJSONObject(i);String type=block.getString("type");if(type.equals("text")){if(text.length()>0)text.append('\n');text.append(block.getString("text"));}else if(type.equals("tool_use")){String id=block.getString("id"),name=block.getString("name");if(!id.matches("[A-Za-z0-9_-]{1,200}")||!ids.add(id)||!name.matches("[A-Za-z0-9_-]{1,128}")||calls.length()>=8)throw new IOException("Invalid returned local tool call");calls.put(new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",block.getJSONObject("input").toString())));}else throw new IOException("Unsupported provider content block");}
  if(stop.equals("tool_use")!=(calls.length()>0))throw new IOException("Provider stop reason does not match returned tools");if(calls.length()==0&&text.toString().trim().isEmpty())throw new IOException("Provider returned no final text");
  JSONObject message=new JSONObject().put("role","assistant").put("content",text.length()==0?JSONObject.NULL:text.toString());if(calls.length()>0)message.put("tool_calls",calls);
  return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",message).put("finish_reason",calls.length()>0?"tool_calls":"stop")));
 }
 private static void validateKey(String key)throws IOException{if(key==null||key.trim().isEmpty()||key.length()>8192)throw new IOException("A valid selected-provider API key is required");for(char c:key.trim().toCharArray())if(c<33||c>126)throw new IOException("Invalid API key characters");}
 private static void only(JSONObject object,String...allowed)throws IOException{Set<String> keys=new HashSet<>(Arrays.asList(allowed));Iterator<String> it=object.keys();while(it.hasNext())if(!keys.contains(it.next()))throw new IOException("Unsupported provider protocol field");}
 private static void bounded(JSONObject object,int limit,String message)throws IOException{if(object.toString().getBytes(StandardCharsets.UTF_8).length>limit)throw new IOException(message);}
 private static final class HttpTransport implements Transport {
  public JSONObject post(String endpoint,Map<String,String> headers,JSONObject body)throws Exception{
   if(!OPENAI_ENDPOINT.equals(endpoint)&&!ANTHROPIC_ENDPOINT.equals(endpoint))throw new IOException("Unsupported provider endpoint");
   byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);if(data.length>MAX_BODY_BYTES)throw new IOException("Provider context exceeds the request limit");
   HttpsURLConnection connection=(HttpsURLConnection)new URL(endpoint).openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setFixedLengthStreamingMode(data.length);
   for(Map.Entry<String,String> header:headers.entrySet())connection.setRequestProperty(header.getKey(),header.getValue());
   try{try(OutputStream out=connection.getOutputStream()){out.write(data);}int code=connection.getResponseCode();if(code!=200)throw new IOException("Selected provider request failed (HTTP "+code+"). No automatic retry was made.");if(connection.getContentLengthLong()>MAX_RESPONSE_BYTES)throw new IOException("Provider response exceeds the limit");ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=connection.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>MAX_RESPONSE_BYTES)throw new IOException("Provider response exceeds the limit");bytes.write(buffer,0,n);}}try{return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));}catch(JSONException malformed){throw new IOException("Selected provider returned invalid JSON");}}finally{connection.disconnect();}
  }
 }
}
