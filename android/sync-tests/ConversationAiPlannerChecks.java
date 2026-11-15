package org.monzobot.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

/** Synthetic gateways only. No credentials, private transactions, provider network or charges. */
public final class ConversationAiPlannerChecks {
    private static int checks;
    private static final String KEY="synthetic-planner-placeholder";
    private static final String MESSAGE="  Next month I might buy a bicycle for £120 in Shopping  ";
    private static final LocalDate TODAY=LocalDate.of(2026,11,14);
    private interface Action { void run() throws Exception; }
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void rejects(Action action,String reason)throws Exception {
        checks++;
        try{action.run();}catch(IOException|IllegalArgumentException|org.json.JSONException expected){return;}
        throw new AssertionError("Accepted: "+reason);
    }
    private static JSONObject proposal()throws Exception {
        return new JSONObject().put("tentative",true).put("name","bicycle").put("category","Shopping")
                .put("amountPence",12000).put("from","2026-12-01").put("to","2026-12-31");
    }
    private static JSONObject empty()throws Exception {
        return new JSONObject().put("tentative",true).put("name","").put("category","")
                .put("amountPence",JSONObject.NULL).put("from",JSONObject.NULL).put("to",JSONObject.NULL);
    }
    private static JSONObject canonical(String content)throws Exception {
        return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content",content))));
    }
    private static JSONObject nativeReply(AiProviders.Provider provider,String content)throws Exception {
        if(provider==AiProviders.Provider.OPENAI)return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("finish_reason","stop").put("message",new JSONObject().put("role","assistant").put("content",content))));
        return new JSONObject().put("type","message").put("role","assistant").put("stop_reason","end_turn")
                .put("content",new JSONArray().put(new JSONObject().put("type","text").put("text",content)));
    }
    private static ConversationAiPlanner fake(AiProviders.Provider provider,JSONObject value){
        return new ConversationAiPlanner(provider,(request,key)->canonical(value.toString()));
    }
    private static CompanionAgent.Consent consent(AiProviders.Provider provider){return new CompanionAgent.Consent(provider,true,false);}
    public static void main(String[] args)throws Exception {
        checks=0;
        for(AiProviders.Provider provider:AiProviders.Provider.values()) {
            final int[] calls={0};
            ConversationAiPlanner nativePlanner=new ConversationAiPlanner(provider,AiProviders.gateway(provider,(endpoint,headers,body)->{
                calls[0]++;
                check(endpoint.equals(provider==AiProviders.Provider.OPENAI?"https://api.openai.com/v1/chat/completions":"https://api.anthropic.com/v1/messages"),"fixed selected endpoint");
                check(provider==AiProviders.Provider.OPENAI?headers.get("Authorization").equals("Bearer "+KEY):headers.get("x-api-key").equals(KEY),"selected auth header");
                check(!body.toString().contains(KEY),"credential excluded from prompt");
                check(!body.toString().contains("synthetic-private-transaction"),"no financial data API input");
                check(body.getJSONArray("tools").length()==0,"no tools or write capabilities");
                check(body.getString("model").equals("synthetic-model"),"model passed explicitly");
                check(provider==AiProviders.Provider.OPENAI?body.getInt("max_completion_tokens")==800:body.getInt("max_tokens")==800,"output bounded");
                JSONArray messages=body.getJSONArray("messages");
                String text=provider==AiProviders.Provider.OPENAI?messages.getJSONObject(1).getString("content"):messages.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text");
                JSONObject user=new JSONObject(text);
                check(user.length()==2&&user.getString("message").equals(MESSAGE)&&user.getString("referenceDate").equals(TODAY.toString()),"only exact message and reference date shared");
                return nativeReply(provider,proposal().toString());
            }));
            ConversationAiPlanner.Result result=nativePlanner.propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider));
            check(calls[0]==1&&result.modelRequests==1,"one request no retry");
            check(result.provider==provider&&result.model.equals("synthetic-model"),"accepted response identity");
            check(result.draft.originalText.equals(MESSAGE)&&result.draft.tentative,"verbatim tentative wording retained");
            check(result.draft.amountPence==12000L,"integer pence evidence retained");
            check(result.draft.from.equals("2026-12-01")&&result.draft.to.equals("2026-12-31"),"exact local date scope");
            check(result.suggestedName.equals("bicycle")&&result.suggestedCategory.equals("Shopping"),"source-excerpt suggestions");
            check(result.draft.issues.isEmpty(),"clean extraction has no hidden issues");

            int[] deniedCalls={0};ConversationAiPlanner blocked=new ConversationAiPlanner(provider,(request,key)->{deniedCalls[0]++;return canonical(proposal().toString());});
            AiProviders.Provider other=provider==AiProviders.Provider.OPENAI?AiProviders.Provider.ANTHROPIC:AiProviders.Provider.OPENAI;
            rejects(()->blocked.propose(KEY,"synthetic-model",MESSAGE,TODAY,new CompanionAgent.Consent(other,true,false)),"cross-provider consent");
            rejects(()->blocked.propose(KEY,"synthetic-model",MESSAGE,TODAY,new CompanionAgent.Consent(provider,false,false)),"processing declined");
            for(String key:new String[]{null,""," ","synthetic\nsecret","synthetic secret","x".repeat(4001)})
                rejects(()->blocked.propose(key,"synthetic-model",MESSAGE,TODAY,consent(provider)),"invalid key");
            for(String model:new String[]{null,"","https://arbitrary.example","bad model","x".repeat(101)})
                rejects(()->blocked.propose(KEY,model,MESSAGE,TODAY,consent(provider)),"invalid model");
            for(String original:new String[]{null,""," ","x".repeat(4001)})
                rejects(()->blocked.propose(KEY,"synthetic-model",original,TODAY,consent(provider)),"invalid input");
            check(deniedCalls[0]==0,"all denied inputs make zero calls");

            for(String field:new String[]{"tentative","name","category","amountPence","from","to"}) {
                JSONObject missing=proposal();missing.remove(field);
                rejects(()->fake(provider,missing).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"missing field "+field);
            }
            for(String field:new String[]{"originalText","actions","affordable","payment","obligation","mode","status","explanation","evidenceRefs"}) {
                JSONObject extra=proposal().put(field,"invented");
                rejects(()->fake(provider,extra).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"extra field "+field);
            }
            for(Object value:new Object[]{false,"true",1,JSONObject.NULL}) {
                JSONObject wrong=proposal().put("tentative",value);
                rejects(()->fake(provider,wrong).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"non-tentative");
            }
            for(Object value:new Object[]{12000.5,"12000",-12000,0,12001,Long.MAX_VALUE,true,new JSONArray(),new JSONObject()}) {
                JSONObject wrong=proposal().put("amountPence",value);
                rejects(()->fake(provider,wrong).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"non-source or noninteger amount");
            }
            String decimalAmount=proposal().toString().replace("12000","12000.0");
            rejects(()->new ConversationAiPlanner(provider,(r,k)->canonical(decimalAmount)).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"decimal JSON amount");
            for(String field:new String[]{"from","to"})for(Object value:new Object[]{"2026-11-01","2026-12-99","next month",20261201,true,JSONObject.NULL}) {
                JSONObject wrong=proposal().put(field,value);
                rejects(()->fake(provider,wrong).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"invented or partial date");
            }
            for(String field:new String[]{"name","category"})for(Object value:new Object[]{"Invented purpose"," Shopping","Shopping ","Shopping\n","x".repeat(121),12,JSONObject.NULL}) {
                JSONObject wrong=proposal().put(field,value);
                rejects(()->fake(provider,wrong).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"unsupported label");
            }
            ConversationAiPlanner.Result unresolved=fake(provider,empty()).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider));
            check(unresolved.draft.amountPence==null&&unresolved.draft.from==null&&unresolved.draft.to==null,"model may leave known fields unresolved");
            check(unresolved.draft.issues.size()==2,"unresolved fields have explicit review notices");
            for(String original:new String[]{"I might buy a bicycle", "Next month I owe £120", "Next month refund £120", "Next month salary £120", "Next month transfer £120", "Next month do not buy £120", "Next month buy around £120", "Next month buy £120 or £150", "Next month buy €120", "Next month buy £120 and $5", "Next month buy £120.005"}) {
                JSONObject forged=empty().put("amountPence",12000);
                rejects(()->fake(provider,forged).propose(KEY,"synthetic-model",original,TODAY,consent(provider)),"unknown/ambiguous amount "+original);
                ConversationAiPlanner.Result unknown=fake(provider,empty()).propose(KEY,"synthetic-model",original,TODAY,consent(provider));
                check(unknown.draft.amountPence==null,"ambiguous amount stays unresolved");
                check(!unknown.draft.issues.isEmpty(),"parser ambiguity retained");
                check(unknown.draft.originalText.equals(original),"ambiguous tentative wording retained");
            }
            for(String original:new String[]{"Buy £120 someday", "Next month or this month buy £120", "Buy £120 next Monday", "Buy £120 on 01/02/2027", "Do not buy £120 next month", "Buy £120 on 2026-12-01 or 2026-12-31", "Buy £120 on 2026-02-30"}) {
                JSONObject forged=empty().put("from","2026-12-01").put("to","2026-12-31");
                rejects(()->fake(provider,forged).propose(KEY,"synthetic-model",original,TODAY,consent(provider)),"unknown/ambiguous date "+original);
                ConversationAiPlanner.Result unknown=fake(provider,empty()).propose(KEY,"synthetic-model",original,TODAY,consent(provider));
                check(unknown.draft.from==null&&unknown.draft.to==null,"unresolved date stays unset");
            }
            for(String original:new String[]{"Buy £120 on 2026-12-24", "Buy £120 from 2026-12-01 to 2026-12-08", "This month buy £120", "Next month buy £120"}) {
                ConversationBudget.Draft local=ConversationBudget.parse(original,TODAY);
                JSONObject valid=empty().put("amountPence",local.amountPence).put("from",local.from).put("to",local.to);
                ConversationAiPlanner.Result exact=fake(provider,valid).propose(KEY,"synthetic-model",original,TODAY,consent(provider));
                check(exact.draft.from.equals(local.from)&&exact.draft.to.equals(local.to),"supported exact date grounded");
            }
            for(String malformed:new String[]{"", "[]", "null", "```json\n"+proposal()+"\n```",proposal()+" trailing", "{", "x".repeat(6001)}) {
                ConversationAiPlanner planner=new ConversationAiPlanner(provider,(request,key)->canonical(malformed));
                rejects(()->planner.propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"malformed final JSON");
            }
            JSONObject tool=canonical(proposal().toString());tool.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("tool_calls",new JSONArray().put(new JSONObject().put("id","write").put("type","function").put("function",new JSONObject().put("name","apply_budget").put("arguments","{}"))));
            rejects(()->new ConversationAiPlanner(provider,(r,k)->tool).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"any model tool call");
            for(Object wrong:new Object[]{"apply_budget",new JSONObject(),true}) {
                JSONObject malformedTools=canonical(proposal().toString());malformedTools.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("tool_calls",wrong);
                rejects(()->new ConversationAiPlanner(provider,(r,k)->malformedTools).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"malformed tool_calls field");
            }
            for(String stop:new String[]{"length","content_filter","tool_calls"}) {
                JSONObject stopped=canonical(proposal().toString());stopped.getJSONArray("choices").getJSONObject(0).put("finish_reason",stop);
                rejects(()->new ConversationAiPlanner(provider,(r,k)->stopped).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"incomplete response");
            }
            JSONObject refusal=canonical(proposal().toString());refusal.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("refusal","refused");
            rejects(()->new ConversationAiPlanner(provider,(r,k)->refusal).propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"refusal");
            int[] failures={0};ConversationAiPlanner offline=new ConversationAiPlanner(provider,(r,k)->{failures[0]++;throw new IOException("synthetic network failure");});
            rejects(()->offline.propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider)),"gateway failure");
            check(failures[0]==1,"failure is not retried");
            Thread.currentThread().interrupt();
            try {blocked.propose(KEY,"synthetic-model",MESSAGE,TODAY,consent(provider));throw new AssertionError("interrupted request accepted");}
            catch(InterruptedException expected){checks++;}finally{Thread.interrupted();}
            check(deniedCalls[0]==0,"interrupted request makes zero calls");
        }
        System.out.println("Conversation AI planner: "+checks+" checks passed; synthetic transports only, no network or provider costs");
    }
}
