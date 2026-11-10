package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.exception.ApiException;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.Function;

@Service
public class AiAssistantService {
    private final AiConversationService conversations;
    private final AiAnalyticsToolService tools;
    private final LocalAiProvider local;
    private final LlmAiProvider hosted;
    private final AiConfiguration config;
    private final AuditService audit;
    private final ObjectMapper json;
    private final DashboardService dashboard;
    private final ForecastService forecast;
    private final SensitiveDataService sensitive;
    public AiAssistantService(AiConversationService conversations,AiAnalyticsToolService tools,LocalAiProvider local,
        LlmAiProvider hosted,AiConfiguration config,AuditService audit,ObjectMapper json,DashboardService dashboard,
        ForecastService forecast,SensitiveDataService sensitive){this.conversations=conversations;this.tools=tools;this.local=local;
        this.hosted=hosted;this.config=config;this.audit=audit;this.json=json;this.dashboard=dashboard;this.forecast=forecast;this.sensitive=sensitive;}

    /** Deliberately not transactional: outbound provider work must never hold a database connection or tenant lock. */
    public ApiDtos.ChatResponse chat(ApiDtos.ChatRequest request){
        var begun=conversations.begin(request);if(begun.replay()!=null)return begun.replay();
        var run=begun.run();long started=System.nanoTime();
        var evidence=new LinkedHashMap<String,Object>();var limitations=new ArrayList<String>();var budget=new int[]{0};
        var deadline=Instant.now().plusSeconds(50);
        try{
            var history=conversations.context(run.conversationId,run.requestId);
            Function<AiProvider.Call,Map<String,Object>> execute=call->{
                if(++budget[0]>5||Instant.now().isAfter(deadline)||Thread.currentThread().isInterrupted())throw new IllegalStateException("Assistant budget exceeded");
                conversations.authorize();var value=tools.execute(call);
                if(json.valueToTree(value).toString().length()>60000)throw new IllegalStateException("Assistant evidence limit exceeded");
                // Multiple calls to the same tool get distinct citation IDs and retain their filters/periods.
                String id=call.name();for(int suffix=2;evidence.containsKey(id);suffix++)id=call.name()+"_"+suffix;
                var card=new LinkedHashMap<>(value);card.put("id",id);evidence.put(id,card);return card;
            };
            AiProvider.Completion completion;String status;
            if(config.enabled){
                try{completion=hosted.complete(history,request.message(),tools.definitions(),execute);
                    if(evidence.isEmpty())throw new IllegalStateException("Provider returned an ungrounded answer");
                    validateCitations(completion.answer(), evidence);
                    status="COMPLETED";
                }catch(ApiException ex){throw ex;}
                catch(RuntimeException ex){
                    if(Thread.currentThread().isInterrupted())throw ex;
                    if(!evidence.isEmpty()) {
                        var first=(Map<?,?>)evidence.values().iterator().next();
                        completion=new AiProvider.Completion(String.valueOf(first.get("summary")),"local",null,0,0);
                    } else completion=local.complete(history,request.message(),tools.definitions(),execute);
                    status="FALLBACK";
                    limitations.add("Hosted AI could not complete this request. A local, data-backed answer is shown; no business records were changed.");
                }
            }else{completion=local.complete(history,request.message(),tools.definitions(),execute);status="FALLBACK";
                limitations.add("Hosted AI is not enabled. This answer uses local analytics rules.");}
            if("You do not have permission to access this information.".equals(completion.answer()))
                audit.logCurrent("AI_ACCESS_DENIED","AiConversation",run.conversationId,Map.of("intent","restricted_analytics"));
            for(var card:evidence.values())if(card instanceof Map<?,?> map&&map.get("limitations") instanceof List<?> list)list.forEach(v->limitations.add(String.valueOf(v)));
            return conversations.complete(run.requestId,completion,evidence,status,limitations.stream().distinct().toList(),(System.nanoTime()-started)/1_000_000);
        }catch(RuntimeException ex){try{conversations.failed(run.requestId);}catch(RuntimeException ignored){/* Workspace may have been deleted while the provider was running. */}throw ex;}
    }
    private void validateCitations(String answer, Map<String,Object> evidence) {
        var citations=java.util.regex.Pattern.compile("\\[([a-z][a-z0-9_]*+)\\]").matcher(answer);
        boolean found=false;
        while(citations.find()) { found=true; if(!evidence.containsKey(citations.group(1))) throw new IllegalStateException("Unsupported evidence citation"); }
        if(!found) throw new IllegalStateException("An analytics answer must cite its evidence");
        var supported=numbers(json.valueToTree(evidence).toString());
        if(!supported.containsAll(numbers(answer))) throw new IllegalStateException("An analytics answer used a number absent from its evidence");
    }
    private Set<String> numbers(String value) {
        var values=new HashSet<String>();
        var matcher=java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}_])[-+]?\\d[\\d,]*(?:\\.\\d+)?(?![\\p{L}\\p{N}_])").matcher(value);
        while(matcher.find()) try { values.add(new java.math.BigDecimal(matcher.group().replace(",", "")).stripTrailingZeros().toPlainString()); } catch(NumberFormatException ignored) { }
        return values;
    }
    public Map<String,Object> insights(){return Map.of("summary",sensitive.summary(dashboard.summary()),"lowStock",dashboard.lowStock(),
        "deadStock",dashboard.deadStock().stream().map(sensitive::deadStock).toList(),"reorderSuggestions",forecast.suggestions());}
    /** Existing explicit generation endpoint; never exposed as a chatbot tool. */
    public Map<String,Object> generateInsights(){forecast.run();return insights();}
}
