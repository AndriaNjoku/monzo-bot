package org.monzobot.app;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Synthetic provider-native orchestration tests. Every provider transport is fake; no network or keys. */
public final class ProviderAgentChecks {
    private static int checks;
    private static final String KEY="synthetic-header-key-only";
    private static final LocalDate TODAY=LocalDate.of(2033,8,14);
    private static final String RETRIEVED="2033-08-14T12:00:00Z";
    private static final List<Finance.Transaction> HISTORY=List.of(new Finance.Transaction(
            "synthetic-private-source-id","2033-08-13","Synthetic Private Merchant","Synthetic Private Category",
            "Card payment","GBP",-4321,Map.of("Notes and #tags","synthetic excluded note",
            "Address","synthetic excluded address","Receipt URL","https://synthetic.invalid/private-receipt",
            "Pot name","synthetic excluded pot")));
    private interface Checked { void run() throws Exception; }
    private static void check(boolean condition,String label) { checks++;if(!condition)throw new AssertionError(label); }
    private static void rejects(Checked operation,String label)throws Exception {
        checks++;try{operation.run();}catch(IOException|IllegalArgumentException expected){return;}
        throw new AssertionError("Expected rejection: "+label);
    }
    private static JSONObject tool(AiProviders.Provider provider,String id,String name)throws Exception {
        if(provider==AiProviders.Provider.ANTHROPIC)
            return new JSONObject().put("type","message").put("role","assistant").put("stop_reason","tool_use")
                    .put("content",new JSONArray().put(new JSONObject().put("type","tool_use").put("id",id).put("name",name).put("input",new JSONObject())));
        JSONObject function=new JSONObject().put("name",name).put("arguments","{}");
        JSONObject call=new JSONObject().put("id",id).put("type","function").put("function",function);
        JSONObject message=new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(call));
        return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","tool_calls").put("message",message)));
    }
    private static JSONObject text(AiProviders.Provider provider,String text)throws Exception {
        if(provider==AiProviders.Provider.ANTHROPIC)
            return new JSONObject().put("type","message").put("role","assistant").put("stop_reason","end_turn")
                    .put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",text)));
        return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop")
                .put("message",new JSONObject().put("role","assistant").put("content",text))));
    }
    private static JSONObject grounded(JSONObject evidence,String interpretation)throws Exception {
        return new JSONObject().put("interpretation",interpretation)
                .put("facts",new JSONArray().put(new JSONObject().put("toolCallId","summary_call").put("path","/amountsPence/moneyOutPence")))
                .put("evidenceRefs",evidence.getJSONArray("evidenceRefs"))
                .put("nextSteps",new JSONArray().put("Review flexible spending while protecting the essentials you have chosen."));
    }
    /** Locate an actual local tool result in either provider's native message sequence. */
    private static JSONObject evidence(Object value)throws Exception {
        if(value instanceof String) {
            String s=(String)value;
            if(!s.trim().startsWith("{"))return null;
            try{return evidence(new JSONObject(s));}catch(org.json.JSONException malformed){return null;}
        }
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;
            if(object.has("amountsPence")&&object.has("evidenceRefs"))return object;
            java.util.Iterator<String> keys=object.keys();
            while(keys.hasNext()){JSONObject found=evidence(object.get(keys.next()));if(found!=null)return found;}
        } else if(value instanceof JSONArray) {
            JSONArray array=(JSONArray)value;
            for(int i=array.length()-1;i>=0;i--){JSONObject found=evidence(array.get(i));if(found!=null)return found;}
        }
        return null;
    }
    private static void privacy(JSONObject body,boolean labels)throws Exception {
        String payload=body.toString();
        for(String excluded:List.of(KEY,"synthetic-private-source-id","synthetic excluded note",
                "synthetic excluded address","synthetic.invalid/private-receipt","synthetic excluded pot"))
            check(!payload.contains(excluded),"private fields or key must not enter provider body");
        if(!labels) {
            check(!payload.contains("Synthetic Private Merchant"),"merchant label consent");
            check(!payload.contains("Synthetic Private Category"),"category label consent");
        }
        for(String field:List.of("user","metadata","safety_identifier","conversation","previous_response_id"))
            check(!body.has(field),"no identity or persistent conversation metadata");
    }
    private static CompanionAgent.Answer ask(CompanionAgent agent,AiProviders.Provider provider,boolean labels,int max)throws Exception {
        return agent.answer(KEY,provider.defaultModel,"Summarize imported spending",HISTORY,Map.of(),TODAY,RETRIEVED,
                new CompanionAgent.Consent(provider,true,labels),max);
    }
    private static void nativeSuccess(AiProviders.Provider provider,boolean labels)throws Exception {
        List<JSONObject> bodies=new ArrayList<>();
        CompanionAgent agent=new CompanionAgent(provider,AiProviders.gateway(provider,(endpoint,headers,body)->{
            bodies.add(new JSONObject(body.toString()));privacy(body,labels);
            check(endpoint.startsWith("https://"+provider.host+"/"),"fixed provider endpoint");
            check(!endpoint.contains(KEY),"key absent URL");
            if(provider==AiProviders.Provider.OPENAI) {
                check(("Bearer "+KEY).equals(headers.get("Authorization")),"OpenAI auth header");
                check(!body.getBoolean("store"),"OpenAI persistence disabled");
            } else {
                check(KEY.equals(headers.get("x-api-key")),"Anthropic auth header");
                check(headers.containsKey("anthropic-version"),"Anthropic API version");
                check(body.opt("system")!=null,"Anthropic native system prompt");
                check(!body.has("response_format")&&!body.has("store"),"unsupported canonical fields removed");
            }
            JSONArray definitions=body.getJSONArray("tools");
            for(int i=0;i<definitions.length();i++) {
                JSONObject definition=definitions.getJSONObject(i);
                String name=provider==AiProviders.Provider.OPENAI?definition.getJSONObject("function").getString("name"):definition.getString("name");
                check(!name.equals("send_payment")&&!name.equals("modify_budget")&&!name.equals("update_sheet"),"only read-only tools advertised");
            }
            if(bodies.size()==1)return tool(provider,"summary_call","summarize_transactions");
            JSONObject fact=evidence(body);
            check(fact!=null,"native tool result reaches provider");
            check(fact.getJSONObject("amountsPence").getLong("moneyOutPence")==4321,"exact local evidence");
            check(fact.getString("sourceCoverage").equals("unknown"),"unknown bank coverage maintained");
            if(labels)check(body.toString().contains("Synthetic Private Category"),"explicit category-label consent honored");
            return text(provider,grounded(fact,"Recorded spending is {{fact:0}}; this export does not verify an available bank balance.").toString());
        }));
        CompanionAgent.Answer answer=ask(agent,provider,labels,3);
        check(answer.complete&&answer.modelRequests==2&&answer.toolCalls==1,"bounded native completed flow");
        check(bodies.size()==2,"no hidden native retries");
        check(answer.text.contains("£43.21"),"host inserts exact source figure");
        check(!answer.interpretationVerified,"AI reasoning remains unverified");
        JSONObject fact=new JSONArray(answer.verifiedFactsJson).getJSONObject(0);
        check(fact.getLong("value")==4321&&!fact.getBoolean("bankDataVerified"),"host-owned facts retain uncertainty");
        check(answer.evidenceRefs.size()==1&&!answer.evidenceRefs.get(0).equals("synthetic-private-source-id"),"opaque local evidence refs");
    }
    public static void main(String[] args)throws Exception {
        for(AiProviders.Provider provider:AiProviders.Provider.values()) {
            AiProviders.Provider other=provider==AiProviders.Provider.OPENAI?AiProviders.Provider.ANTHROPIC:AiProviders.Provider.OPENAI;
            int[] gate={0};CompanionAgent guarded=new CompanionAgent(provider,(body,key)->{gate[0]++;throw new AssertionError("Consent gate allowed transport");});
            rejects(()->guarded.answer(KEY,provider.defaultModel,"Question",HISTORY,Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(other,true,false),2),"provider consent mismatch");
            rejects(()->guarded.answer("",provider.defaultModel,"Question",HISTORY,Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(other,true,false),2),"mismatch regardless key");
            rejects(()->guarded.answer(KEY,provider.defaultModel,"Question",HISTORY,Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(provider,false,true),2),"declined processing");
            check(gate[0]==0,"consent rejects before gateway");
            nativeSuccess(provider,false);nativeSuccess(provider,true);
            int[] count={0};CompanionAgent bounded=new CompanionAgent(provider,AiProviders.gateway(provider,(e,h,b)->{count[0]++;return tool(provider,"summary_call","summarize_transactions");}));
            CompanionAgent.Answer partial=ask(bounded,provider,false,1);
            check(!partial.complete&&partial.modelRequests==1&&partial.toolCalls==1&&count[0]==1,"model bound enforced for native provider");
            int[] failures={0};CompanionAgent failure=new CompanionAgent(provider,AiProviders.gateway(provider,(e,h,b)->{failures[0]++;throw new IOException("Synthetic transport failure");}));
            rejects(()->ask(failure,provider,false,4),"native failure propagated");check(failures[0]==1,"no automatic retry after transport error");
            for(String attack:List.of("numeric_prose","invented_value","invented_ref","write_tool")) {
                int[] stage={0};CompanionAgent attacking=new CompanionAgent(provider,AiProviders.gateway(provider,(endpoint,headers,body)->{
                    privacy(body,false);
                    if(stage[0]++==0)return tool(provider,"summary_call",attack.equals("write_tool")?"send_payment":"summarize_transactions");
                    if(attack.equals("write_tool")) {
                        check(body.toString().contains("Tool request rejected"),"write function rejected locally");
                        return text(provider,"I sent a payment");
                    }
                    JSONObject answer=grounded(evidence(body),"Recorded spending is {{fact:0}}.");
                    if(attack.equals("numeric_prose"))answer.put("interpretation","Spend £999 immediately.");
                    if(attack.equals("invented_value"))answer.getJSONArray("facts").getJSONObject(0).put("value",99900);
                    if(attack.equals("invented_ref"))answer.put("evidenceRefs",new JSONArray().put("invented_provider_ref"));
                    return text(provider,answer.toString());
                }));
                rejects(()->ask(attacking,provider,false,3),"native model attack: "+attack);
                check(stage[0]==2,"attack has no automatic correction retry");
            }
        }
        int[] legacyCalls={0};CompanionAgent legacy=new CompanionAgent((request,key)->{
            if(legacyCalls[0]++==0)return tool(AiProviders.Provider.OPENAI,"summary_call","summarize_transactions");
            return text(AiProviders.Provider.OPENAI,grounded(evidence(request),"Recorded spending is {{fact:0}}.").toString());
        });
        CompanionAgent.Answer old=legacy.answer(KEY,"synthetic-model","Question",HISTORY,Map.of(),TODAY,RETRIEVED,new CompanionAgent.Consent(true,false),3);
        check(old.complete&&legacyCalls[0]==2,"legacy constructors and OpenAI consent remain compatible");
        System.out.println("ProviderAgentChecks: "+checks+" passed; synthetic native transports only");
    }
}
