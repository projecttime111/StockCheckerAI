package com.stockpilot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockpilot.ai.config.TenantContext;
import com.stockpilot.ai.domain.*;
import com.stockpilot.ai.exception.*;
import com.stockpilot.ai.repo.AiChatRepositories;
import com.stockpilot.ai.web.dto.ApiDtos;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Short database transactions only. No provider request may execute inside these methods. */
@Service
public class AiConversationService {
    private final AiChatRepositories.Conversations conversations;
    private final AiChatRepositories.Messages messages;
    private final AiChatRepositories.Runs runs;
    private final AiChatRepositories.Access access;
    private final TenantWriteLockService locks;
    private final PermissionService permissions;
    private final AiConfiguration config;
    private final ObjectMapper json;
    public AiConversationService(AiChatRepositories.Conversations conversations,AiChatRepositories.Messages messages,
        AiChatRepositories.Runs runs,AiChatRepositories.Access access,TenantWriteLockService locks,PermissionService permissions,
        AiConfiguration config,ObjectMapper json){
        this.conversations=conversations;this.messages=messages;this.runs=runs;this.access=access;this.locks=locks;
        this.permissions=permissions;this.config=config;this.json=json;
    }
    public record Begun(AiChatRun run,ApiDtos.ChatResponse replay) { }
    @Transactional(readOnly=true)
    public void authorize(){
        var tenant=TenantContext.tenantIdOrNull();var user=TenantContext.userId();
        if(tenant==null||user==null) throw ApiErrors.forbidden("An active business workspace is required");
        var role=access.findCurrentRole(tenant,user).orElseThrow(()->ApiErrors.forbidden("Workspace access is no longer available"));
        if(AuthRoles.isPlatform(role)) throw ApiErrors.forbidden("A business role is required");
        TenantContext.set(tenant,user,role);
        if(!permissions.has("ai.assistant.use")) throw ApiErrors.forbidden("Assistant access is not available for your role");
    }
    @Transactional
    public Begun begin(ApiDtos.ChatRequest request){
        authorize();var tenant=TenantContext.tenantId();var user=TenantContext.userId();locks.lock(tenant);authorize();
        var requestId=request.requestId()==null?UUID.randomUUID():request.requestId();
        var fingerprint=fingerprint(request.conversationId()+"\n"+request.message());
        var existing=runs.findByTenantIdAndUserIdAndRequestId(tenant,user,requestId);
        if(existing.isPresent()) {
            var run=existing.get(); owned(run.conversationId);
            boolean initialConversationRetry=run.conversationId.equals(request.conversationId())
                &&run.fingerprint.equals(fingerprint("null\n"+request.message()));
            if(!run.fingerprint.equals(fingerprint)&&!initialConversationRetry) throw ApiErrors.conflict("This request ID was already used for another message");
            if(!permissions.currentPermissions().containsAll(run.requiredPermissions)) throw ApiErrors.forbidden("This response is restricted by your current role");
            if("COMPLETED".equals(run.status)||"FALLBACK".equals(run.status)) return new Begun(run,json.convertValue(run.responseJson,ApiDtos.ChatResponse.class));
            if("RUNNING".equals(run.status)&&run.updatedAt.isAfter(Instant.now().minusSeconds(120))) throw ApiErrors.conflict("This answer is still being prepared. Retry the same request shortly");
            for(var pending:runs.findByTenantIdAndConversationIdAndStatus(tenant,run.conversationId,"RUNNING"))
                if(!pending.requestId.equals(requestId)&&pending.updatedAt.isAfter(Instant.now().minusSeconds(120)))
                    throw ApiErrors.conflict("Another answer is still being prepared in this conversation");
            if(run.attemptCount>=3) throw ApiErrors.conflict("This request has reached its retry limit. Start a new message");
            enforceRate(tenant,user);
            run.attemptCount++;run.lastAttemptAt=Instant.now();
            run.status="RUNNING";run.updatedAt=Instant.now();runs.save(run);return new Begun(run,null);
        }
        enforceRate(tenant,user);
        AiConversation conversation;
        if(request.conversationId()==null){conversation=new AiConversation();conversation.tenantId=tenant;conversation.userId=user;conversation.requiredPermissions=new HashSet<>(permissions.currentPermissions());conversation.title=request.message().substring(0,Math.min(80,request.message().length()));conversations.save(conversation);}
        else conversation=owned(request.conversationId());
        for(var pending:runs.findByTenantIdAndConversationIdAndStatus(tenant,conversation.id,"RUNNING")) {
            if(pending.updatedAt.isAfter(Instant.now().minusSeconds(120))) throw ApiErrors.conflict("Another answer is still being prepared in this conversation");
            pending.status="FAILED";runs.save(pending);
        }
        var run=new AiChatRun();run.tenantId=tenant;run.userId=user;run.conversationId=conversation.id;run.requestId=requestId;
        run.fingerprint=fingerprint;run.status="RUNNING";run.requiredPermissions=new HashSet<>(permissions.currentPermissions());runs.save(run);
        var message=new AiMessage();message.tenantId=tenant;message.conversationId=conversation.id;message.role=DomainEnums.AiRole.USER;
        message.content=request.message();message.requestId=requestId;message.status="RUNNING";message.requiredPermissions=new HashSet<>(run.requiredPermissions);messages.save(message);
        conversation.updatedAt=Instant.now();conversations.save(conversation);return new Begun(run,null);
    }
    private void enforceRate(UUID tenant,UUID user){
        var since=Instant.now().minusSeconds(60);
        if(runs.countByTenantIdAndUserIdAndLastAttemptAtAfter(tenant,user,since)>=config.userRequestsPerMinute
            ||runs.countByTenantIdAndLastAttemptAtAfter(tenant,since)>=config.tenantRequestsPerMinute)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"AI_RATE_LIMIT","Too many assistant requests. Wait a minute and retry");
    }
    @Transactional(readOnly=true)
    public List<AiProvider.Turn> context(UUID conversationId,UUID requestId){
        authorize();owned(conversationId);var rows=messages.findTop12ByTenantIdAndConversationIdOrderByCreatedAtDescIdDesc(TenantContext.tenantId(),conversationId);
        var result=new ArrayList<AiProvider.Turn>();int length=0;
        for(var row:rows){if(Objects.equals(requestId,row.requestId)||restricted(row)||!("COMPLETED".equals(row.status)||"FALLBACK".equals(row.status)))continue;
            if(length+row.content.length()>16000)break;result.add(new AiProvider.Turn(row.role.name(),row.content));length+=row.content.length();}
        Collections.reverse(result);return result;
    }
    @Transactional
    public ApiDtos.ChatResponse complete(UUID requestId,AiProvider.Completion result,Map<String,Object> evidence,String status,List<String> limitations,long duration){
        var tenant=TenantContext.tenantId();locks.lock(tenant);authorize();
        var run=runs.findByTenantIdAndUserIdAndRequestId(tenant,TenantContext.userId(),requestId).orElseThrow(()->ApiErrors.notFound("Assistant request not found"));
        var conversation=owned(run.conversationId);
        if(!permissions.currentPermissions().containsAll(run.requiredPermissions)) throw ApiErrors.forbidden("Your permissions changed while preparing this answer. Ask again with your current role");
        if(!"RUNNING".equals(run.status))throw ApiErrors.conflict("Assistant request is no longer running");
        var message=new AiMessage();message.tenantId=tenant;message.conversationId=conversation.id;message.role=DomainEnums.AiRole.ASSISTANT;
        message.content=result.answer();message.evidenceJson=evidence;message.requestId=requestId;message.status=status;message.requiredPermissions=new HashSet<>(run.requiredPermissions);messages.save(message);
        var response=new ApiDtos.ChatResponse(conversation.id,result.answer(),evidence,message.id,requestId,status,result.provider(),result.model(),Instant.now(),limitations);
        run.responseJson=json.convertValue(response,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        run.status=status;run.provider=result.provider();run.model=result.model();run.inputTokens=result.inputTokens();run.outputTokens=result.outputTokens();run.durationMs=duration;runs.save(run);
        for(var userMessage:messages.findByTenantIdAndConversationIdAndRequestId(tenant,conversation.id,requestId)){userMessage.status=status;messages.save(userMessage);}
        conversation.updatedAt=Instant.now();conversations.save(conversation);return response;
    }
    @Transactional
    public void failed(UUID requestId){
        var tenant=TenantContext.tenantId();locks.lock(tenant);
        runs.findByTenantIdAndUserIdAndRequestId(tenant,TenantContext.userId(),requestId).ifPresent(run->{if("RUNNING".equals(run.status)){run.status="FAILED";runs.save(run);
            messages.findByTenantIdAndConversationIdAndRequestId(tenant,run.conversationId,requestId).forEach(m->{m.status="FAILED";messages.save(m);});}});
    }
    @Transactional(readOnly=true)
    public Page<ApiDtos.AiConversationResponse> list(int page,int size){authorize();return conversations.findByTenantIdAndUserId(TenantContext.tenantId(),TenantContext.userId(),page(page,size,"updatedAt"))
        .map(c->new ApiDtos.AiConversationResponse(c.id,!c.requiredPermissions.isEmpty()&&permissions.currentPermissions().containsAll(c.requiredPermissions)?c.title:"Saved conversation",c.createdAt,c.updatedAt));}
    @Transactional(readOnly=true)
    public Page<ApiDtos.AiMessageResponse> history(UUID id,int page,int size){authorize();owned(id);return messages.findByTenantIdAndConversationId(TenantContext.tenantId(),id,page(page,size,"createdAt"))
        .map(m->{boolean hidden=restricted(m);return new ApiDtos.AiMessageResponse(m.id,m.role.name(),hidden?"This message is restricted by your current permissions.":m.content,
            hidden?Map.of():m.evidenceJson,m.createdAt,hidden,m.status,m.requestId);});}
    @Transactional
    public void delete(UUID id){var tenant=TenantContext.tenantId();locks.lock(tenant);authorize();var c=owned(id);
        for(var run:runs.findByTenantIdAndConversationIdAndStatus(tenant,id,"RUNNING"))if(run.updatedAt.isAfter(Instant.now().minusSeconds(120)))throw ApiErrors.conflict("Wait for the current answer to finish before deleting this conversation");
        messages.deleteByTenantIdAndConversationId(tenant,id);runs.deleteByTenantIdAndConversationId(tenant,id);conversations.delete(c);}
    private boolean restricted(AiMessage m){return m.status==null||!permissions.currentPermissions().containsAll(m.requiredPermissions);}
    private AiConversation owned(UUID id){return conversations.findByTenantIdAndUserIdAndId(TenantContext.tenantId(),TenantContext.userId(),id).orElseThrow(()->ApiErrors.notFound("Conversation not found"));}
    private Pageable page(int page,int size,String field){if(page<0||size<1||size>100)throw ApiErrors.badRequest("Invalid page or size");return PageRequest.of(page,size,Sort.by(Sort.Order.desc(field),Sort.Order.desc("id")));}
    private String fingerprint(String input){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
