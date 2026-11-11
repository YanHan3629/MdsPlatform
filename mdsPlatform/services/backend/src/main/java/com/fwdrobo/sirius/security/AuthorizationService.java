package com.fwdrobo.sirius.security;

import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Live DB authority. Capabilities and a single complete resource grant are both required. */
@Service("authorization")
public class AuthorizationService {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<GenericFileOriginService> genericOrigins;
    public static final String REQUEST_OPERATION=AuthorizationService.class.getName()+".operation";
    private final JdbcTemplate jdbc;
    public AuthorizationService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Actor(UUID userId,UUID orgId,String username,int tokenVersion,long permissionVersion,
                        Set<String> operations,boolean platformAdmin,boolean enterpriseManager,boolean readOnly,UUID serviceId) {
        public Actor { operations=Set.copyOf(operations); }
        public Actor(UUID userId,UUID orgId,String username,int tokenVersion,long permissionVersion,Set<String> operations,
                boolean platformAdmin,boolean enterpriseManager,boolean readOnly){
            this(userId,orgId,username,tokenVersion,permissionVersion,operations,platformAdmin,enterpriseManager,readOnly,null);
        }
    }
    public record GrantBinding(UUID grantId,long version){
        public GrantBinding{Objects.requireNonNull(grantId);if(version<1)throw new IllegalArgumentException("Invalid grant version");}
    }
    public record ContractBinding(UUID contractId,long revision){
        public ContractBinding{Objects.requireNonNull(contractId);if(revision<1)throw new IllegalArgumentException("Invalid contract revision");}
    }
    /** One original complete grant or one explicit historical FULL_VERSION contract revision. */
    public record ResourceAuthority(GrantBinding grant,ContractBinding legacyContract){
        public ResourceAuthority{if((grant==null)==(legacyContract==null))throw new IllegalArgumentException("Exactly one resource authority required");}
    }
    private Actor refreshActor(Actor actor){return actor.serviceId()==null?actorFor(actor.userId()):serviceActorFor(actor.serviceId());}
    private static void fail(HttpStatus status,String code) { throw new ResponseStatusException(status,code); }
    private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    private boolean sameAuthenticatedIdentity(Actor old,Actor current) {
        return Objects.equals(old.userId(),current.userId())&&Objects.equals(old.serviceId(),current.serviceId())&&old.orgId().equals(current.orgId())&&
            old.tokenVersion()==current.tokenVersion()&&old.username().equals(current.username());
    }
    private void requireRequestIdentity(Actor current) {
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs &&
                attrs.getRequest().getAttribute("ds.actor") instanceof Actor authenticated &&
                !sameAuthenticatedIdentity(authenticated,current))
            fail(HttpStatus.UNAUTHORIZED,"REQUEST_IDENTITY_REVOKED_OR_CHANGED");
    }
    public Actor actor() {
        UUID service=SecurityUtils.getServiceSubjectId();
        if(service!=null){Actor current=serviceActorFor(service);requireRequestIdentity(current);return current;}
        UUID id=SecurityUtils.getUserId();
        if(id==null) { fail(HttpStatus.UNAUTHORIZED,"AUTHENTICATION_REQUIRED"); }
        Actor current=actorFor(id);requireRequestIdentity(current);return current;
    }
    public Actor actorFor(UUID id) {
        if(id==null) { fail(HttpStatus.UNAUTHORIZED,"AUTHENTICATION_REQUIRED"); }
        var users=jdbc.queryForList("""
            SELECT u.user_id,u.org_id,u.user_name,u.token_version,u.permission_version,
                   u.account_state,u.status_code,o.org_status
            FROM fwd_user u JOIN org o ON o.org_id=u.org_id WHERE u.user_id=?
            """,id);
        if(users.size()!=1) { fail(HttpStatus.UNAUTHORIZED,"IDENTITY_NOT_AVAILABLE"); }
        var row=users.get(0);
        if(!"ACTIVE".equals(row.get("account_state"))||row.get("status_code")==null||((Number)row.get("org_status")).intValue()!=1)
            fail(HttpStatus.UNAUTHORIZED,"IDENTITY_DISABLED_OR_CLOSED");
        UUID org=(UUID)row.get("org_id");
        boolean platform=exists("SELECT EXISTS(SELECT 1 FROM ds_management_membership WHERE user_id=? AND qualification='PLATFORM_ADMIN' AND active)",id);
        boolean member=exists("SELECT EXISTS(SELECT 1 FROM ds_management_membership WHERE user_id=? AND qualification='ENTERPRISE_MANAGER' AND active)",id);
        var codes=new HashSet<String>(jdbc.queryForList("""
            SELECT DISTINCT d.operation_code
            FROM user_role ur JOIN role r ON r.role_id=ur.role_id
            JOIN role_permission rp ON rp.role_id=r.role_id
            JOIN ds_operation d ON d.permission_id=rp.per_id
            WHERE ur.user_id=? AND r.type_state='ACTIVE'
              AND (r.type_scope='GLOBAL' OR r.org_id=?)
              AND (cardinality(r.applicable_org_ids)=0 OR ?=ANY(r.applicable_org_ids))
              AND (jsonb_array_length(r.industry_tags)=0 OR EXISTS(
                  SELECT 1 FROM ds_enterprise_identity e WHERE e.org_id=? AND e.state='CONFIRMED'
                    AND jsonb_exists(r.industry_tags,e.industry_kind)))
            """,String.class,id,org,org,org));
        boolean readOnly=exists("""
            SELECT EXISTS(SELECT 1 FROM user_role ur JOIN role r ON r.role_id=ur.role_id
             WHERE ur.user_id=? AND ur.is_primary AND r.type_state='ACTIVE' AND r.read_only_profile)
            """,id);
        if(readOnly) codes.removeIf(c->OperationCatalog.isBusiness(c)&&!OperationCatalog.isReadOnlyBusinessOperation(c));
        var denied=jdbc.queryForList("""
            SELECT DISTINCT operation_code FROM ds_operation_deny d
            WHERE (d.subject_kind='USER' AND d.subject_id=?) OR (d.subject_kind='ROLE' AND EXISTS(
              SELECT 1 FROM user_role ur WHERE ur.user_id=? AND ur.role_id=d.subject_id))
            """,String.class,id,id);
        codes.removeAll(denied);
        // A capability does not confer any resource. Custom business profiles may
        // create a new own-enterprise dataset even before its first resource
        // grant exists. All existing data still requires a complete scoped grant.
        if(exists("SELECT EXISTS(SELECT 1 FROM ds_enterprise_identity WHERE org_id=? AND state<>'CONFIRMED')",org))
            codes.removeIf(OperationCatalog::isBusiness);
        codes.addAll(OperationCatalog.SELF);
        // These qualifications are stored in protected rows. Editable type codes
        // and legacy ADMIN-like names cannot manufacture management authority.
        if(member) codes.addAll(OperationCatalog.MEMBER_MANAGEMENT);
        if(platform) codes.addAll(OperationCatalog.PLATFORM_MANAGEMENT);
        codes.removeIf(c->!OperationCatalog.isKnown(c));
        return new Actor(id,org,(String)row.get("user_name"),((Number)row.get("token_version")).intValue(),
            ((Number)row.get("permission_version")).longValue(),codes,platform,member,readOnly);
    }
    public Actor serviceActorFor(UUID id){
        if(id==null)fail(HttpStatus.UNAUTHORIZED,"SERVICE_SUBJECT_REQUIRED");
        var rows=jdbc.queryForList("""
            SELECT s.*,o.org_status FROM ds_service_subject s JOIN org o ON o.org_id=s.org_id
            WHERE s.service_subject_id=? AND s.active AND s.closed_at IS NULL AND o.org_status=1
              AND (s.valid_until IS NULL OR clock_timestamp()<s.valid_until)
              AND NOT EXISTS(SELECT 1 FROM ds_enterprise_identity e WHERE e.org_id=s.org_id AND e.state<>'CONFIRMED')
            """,id);
        if(rows.size()!=1)fail(HttpStatus.UNAUTHORIZED,"SERVICE_SUBJECT_DISABLED_OR_CLOSED");
        var row=rows.get(0);String purpose=(String)row.get("purpose");
        Set<String> operations=switch(purpose){
            case "VIEW_BUILD"->Set.of("dataset.index.build");
            case "QUALITY_CHECK"->Set.of("dataset.quality.run");
            case "SCHEDULED_DELIVERY"->Set.of("delivery.execute","data.view.export","data.raw.download");
            default->throw new ResponseStatusException(HttpStatus.FORBIDDEN,"UNKNOWN_SERVICE_PURPOSE");
        };
        long version=((Number)row.get("version")).longValue();
        return new Actor(null,(UUID)row.get("org_id"),"service:"+id,Math.toIntExact(version),version,operations,false,false,false,id);
    }
    public boolean hasOperation(String code) {
        if(!OperationCatalog.isKnown(code)) return false;
        try { return actor().operations().contains(code); } catch(ResponseStatusException denied) { return false; }
    }
    public Actor requireOperation(String code) {
        Actor a=actor(); requireOperation(a,code); return a;
    }
    public void requireOperation(Actor a,String code) {
        Actor current=refreshActor(a);
        if(!sameAuthenticatedIdentity(a,current)) fail(HttpStatus.UNAUTHORIZED,"IDENTITY_REVOKED_OR_CHANGED");
        requireRequestIdentity(current);a=current;
        if(!OperationCatalog.isKnown(code)) fail(HttpStatus.FORBIDDEN,"UNKNOWN_OPERATION");
        if(!a.operations().contains(code)) fail(HttpStatus.FORBIDDEN,"OPERATION_DENIED");
        var mode=jdbc.queryForMap("SELECT initialized,maintenance_mode FROM ds_auth_initialization WHERE singleton");
        if(!Boolean.TRUE.equals(mode.get("initialized"))) fail(HttpStatus.SERVICE_UNAVAILABLE,"ADMIN_INITIALIZATION_REQUIRED");
        if(Boolean.TRUE.equals(mode.get("maintenance_mode"))&&!OperationCatalog.SELF.contains(code))
            fail(HttpStatus.SERVICE_UNAVAILABLE,"AUTHORIZATION_MAINTENANCE_MODE");
    }
    public void requirePlatformManagement(String code) {
        Actor a=requireOperation(code);
        if(!a.platformAdmin()) fail(HttpStatus.FORBIDDEN,"PLATFORM_ADMIN_REQUIRED");
    }
    public void requireMemberManagement(UUID targetOrg,String code) {
        Actor a=requireOperation(code);
        if(!a.platformAdmin()&&(!a.enterpriseManager()||!a.orgId().equals(targetOrg)))
            fail(HttpStatus.NOT_FOUND,"MEMBER_SCOPE_NOT_AVAILABLE");
    }
    private record FixedOperation(String operation,String purpose) {}
    private final ThreadLocal<Deque<FixedOperation>> fixedOperations=new ThreadLocal<>();
    /** Only trusted service code enters a fixed operation; request scope is restored on exit. */
    public final class OperationScope implements AutoCloseable {
        private final FixedOperation binding;private final Thread thread=Thread.currentThread();private boolean closed;
        private OperationScope(FixedOperation binding){this.binding=binding;}
        @Override public void close(){
            if(closed)return;
            Deque<FixedOperation> stack=fixedOperations.get();
            if(thread!=Thread.currentThread()||stack==null||stack.peek()!=binding)throw new IllegalStateException("OPERATION_SCOPE_ORDER");
            stack.pop();if(stack.isEmpty())fixedOperations.remove();closed=true;
        }
    }
    public OperationScope enterOperation(String operation,String purpose){
        requireOperation(operation);
        if(!Set.of("GENERAL_READ","CONTROLLED_DELIVERY","VIEW_BUILD").contains(purpose))fail(HttpStatus.FORBIDDEN,"UNKNOWN_PURPOSE");
        Deque<FixedOperation> stack=fixedOperations.get();
        if(stack==null){stack=new ArrayDeque<>();fixedOperations.set(stack);}
        FixedOperation binding=new FixedOperation(operation,purpose);stack.push(binding);return new OperationScope(binding);
    }
    private FixedOperation fixedOperation(){
        Deque<FixedOperation> stack=fixedOperations.get();return stack==null?null:stack.peek();
    }
    public String currentOperation(String fallback) {
        if(fixedOperation()!=null)return fixedOperation().operation();
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            Object code=attrs.getRequest().getAttribute(REQUEST_OPERATION);
            if(code instanceof String value&&OperationCatalog.isKnown(value)) return value;
        }
        if(!OperationCatalog.isKnown(fallback)) fail(HttpStatus.FORBIDDEN,"UNKNOWN_OPERATION");
        return fallback;
    }
    public String currentPurpose(String fallback) {
        if(fixedOperation()!=null)return fixedOperation().purpose();
        String value=fallback;
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs &&
                attrs.getRequest().getAttribute(AuthorizationService.class.getName()+".purpose") instanceof String purpose) value=purpose;
        if(!Set.of("GENERAL_READ","CONTROLLED_DELIVERY","VIEW_BUILD").contains(value)) fail(HttpStatus.FORBIDDEN,"UNKNOWN_PURPOSE");
        return value;
    }
    public void requireDataset(UUID dataset,UUID version,UUID index,String operation) {
        requireDataset(actor(),dataset,version,index,operation,"GENERAL_READ");
    }
    public void requireDataset(Actor a,UUID dataset,UUID version,UUID index,String operation,String purpose) {
        requireOperation(a,operation);
        if(!canUseDataset(a,dataset,version,index,operation,purpose)) fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
    }
    public boolean canUseDataset(Actor a,UUID dataset,UUID version,UUID index,String operation,String purpose) {
        return canUseDataset(a,dataset,version,index,Set.of(operation),purpose);
    }
    /** One grant must cover the entire operation set, including delivery's export. */
    public void requireDataset(Actor a,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose) {
        for(String code:operations) requireOperation(a,code);
        if(!canUseDataset(a,dataset,version,index,operations,purpose)) fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
    }
    public boolean canUseDataset(Actor original,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose) {
        return canUseDatasetBoundInternal(original,dataset,version,index,operations,purpose,null);
    }
    public boolean canUseDatasetBound(Actor original,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose,GrantBinding binding){
        Objects.requireNonNull(binding);return canUseDatasetBoundInternal(original,dataset,version,index,operations,purpose,binding);
    }
    /** Grant configuration returns metadata only; it never authorizes a content read.
     * Package scope keeps this path inside the reviewed grant-management service.
     * The exact complete grant and authenticated identity are still pinned and
     * rechecked before response output. Ordinary consumption retains source checks.
     */
    boolean canConfigureGrantBound(Actor original,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose,GrantBinding binding){
        Objects.requireNonNull(binding);
        Actor current=refreshActor(original);
        if(!sameAuthenticatedIdentity(original,current))return false;
        requireRequestIdentity(current);
        if(dataset==null||purpose==null||operations==null||operations.isEmpty()||!current.operations().containsAll(operations))return false;
        Set<String> frozenOperations=Set.copyOf(operations);
        if(findCurrentAuthority(current,dataset,version,index,frozenOperations,purpose,new ResourceAuthority(binding,null))==null)return false;
        rememberSourceConstraint("grant-configuration:"+leaseKey(current,dataset,version,index,frozenOperations,purpose)+":"+binding.grantId()+":"+binding.version(),()->{
            if(!canConfigureGrantBound(original,dataset,version,index,frozenOperations,purpose,binding))fail(HttpStatus.NOT_FOUND,"GRANT_CONFIGURATION_AUTHORITY_CHANGED");
        });
        return true;
    }
    private boolean canUseDatasetBoundInternal(Actor original,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose,GrantBinding binding){
        return selectLease(original,dataset,version,index,operations,purpose,binding==null?null:new ResourceAuthority(binding,null))!=null;
    }
    private ResourceLease selectLease(Actor original,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose,ResourceAuthority authority){
        Actor a=refreshActor(original);
        if(!sameAuthenticatedIdentity(original,a)) return null;
        requireRequestIdentity(a);
        if(dataset==null||purpose==null||operations==null||operations.isEmpty())return null;
        ResourceLease prior=requestLease(a,dataset,version,index,operations,purpose);
        if(prior!=null){
            if(authority!=null&&!authority.equals(prior.authority()))return null;
            authority=prior.authority();
        }
        ResourceAuthority selected=findCurrentAuthority(a,dataset,version,index,operations,purpose,authority);
        if(selected==null)return null;
        if(operations.stream().anyMatch(Set.of("data.search","data.qa","data.view.export","dataset.index.build","dataset.quality.run","dataset.quality.read")::contains)
                &&!genericOrigins.getObject().datasetInputsCurrent(dataset,version,index,operations,purpose,a))return null;
        return remember(new ResourceLease(a,dataset,version,index,operations,purpose,selected));
    }
    // Source constraints are evaluated only against persisted frozen coordinates.
    // They never authorize the current caller or record a request resource lease.
    boolean persistedSourceGrantCurrent(UUID user,UUID org,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose,GrantBinding binding){
        try {
            Actor a=actorFor(user);if(!a.orgId().equals(org))return false;
            return findCurrentAuthority(a,dataset,version,index,operations,purpose,new ResourceAuthority(binding,null))!=null;
        }catch(ResponseStatusException denied){if(denied.getStatusCode().value()==401||denied.getStatusCode().value()==403||denied.getStatusCode().value()==404)return false;throw denied;}
    }
    // Internal service delegation checks never impersonate the human configuring it.
    public boolean persistedServiceGrantCurrent(UUID service,UUID org,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose,GrantBinding binding){
        try{var a=serviceActorFor(service);return a.orgId().equals(org)&&findCurrentAuthority(a,dataset,version,index,operations,purpose,new ResourceAuthority(binding,null))!=null;}
        catch(ResponseStatusException denied){if(Set.of(401,403,404).contains(denied.getStatusCode().value()))return false;throw denied;}
    }
    private ResourceAuthority findCurrentAuthority(Actor a,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose,ResourceAuthority authority){
        if(dataset==null||purpose==null||operations==null||operations.isEmpty()||
                operations.stream().anyMatch(code->!OperationCatalog.isKnown(code)||!a.operations().contains(code))) return null;
        if(!Set.of("GENERAL_READ","CONTROLLED_DELIVERY","VIEW_BUILD").contains(purpose)) return null;
        if(!exists("SELECT EXISTS(SELECT 1 FROM ds_auth_initialization WHERE singleton AND initialized AND NOT maintenance_mode)")) return null;
        if(!exists("SELECT EXISTS(SELECT 1 FROM mm_dataset d JOIN org o ON o.org_id=d.org_id WHERE d.dataset_id=? AND d.status='ACTIVE' AND o.org_status=1 AND NOT EXISTS(SELECT 1 FROM ds_enterprise_identity e WHERE e.org_id=o.org_id AND e.state<>'CONFIRMED'))",dataset)) return null;
        if(version!=null&&!exists("SELECT EXISTS(SELECT 1 FROM mm_dataset_version WHERE dataset_id=? AND version_id=?)",dataset,version)) return null;
        if(index!=null&&(version==null||!exists("""
             SELECT EXISTS(SELECT 1 FROM mm_index_version i JOIN artifact_commit c
               ON c.commit_id=i.index_commit_id AND c.repo_id=i.index_repo_id
              WHERE i.dataset_id=? AND i.dataset_version_id=? AND i.index_version_id=?
               AND i.index_status='READY' AND c.commit_status='PUBLISHED')
             """,dataset,version,index))) return null;
        GrantBinding binding=authority==null?null:authority.grant();
        ContractBinding contract=authority==null?null:authority.legacyContract();
        // All array values come from the fixed operation registry, never client SQL.
        String actions="{"+String.join(",",new TreeSet<>(operations))+"}";
        var grants=contract!=null?List.<Map<String,Object>>of():jdbc.queryForList("""
            SELECT g.grant_id,g.version FROM ds_resource_grant g
             JOIN org owner ON owner.org_id=g.owner_org_id
             JOIN mm_dataset d ON d.dataset_id=g.dataset_id AND d.org_id=g.owner_org_id
             WHERE g.dataset_id=? AND ds_human_grant_origin_current(g.grant_id,?::varchar[],ARRAY[]::uuid[]) AND (?::uuid IS NULL OR (g.grant_id=? AND g.version=?)) AND owner.org_status=1 AND g.state='ACTIVE'
               AND g.valid_from<=clock_timestamp() AND (g.valid_to IS NULL OR clock_timestamp()<g.valid_to)
               AND g.operation_codes @> ?::varchar[] AND ?=ANY(g.purpose_codes) AND g.grantee_org_id=?
               AND ((?::uuid IS NULL AND ((g.subject_kind='USER' AND g.subject_id=?) OR (g.subject_kind='ORG' AND g.subject_id=?)))
                 OR (g.subject_kind='SERVICE' AND g.subject_id=?))
               AND ((g.scope_kind='DATASET_CUSTODY' AND g.grantee_org_id=d.org_id
                        AND g.version_id IS NULL AND g.index_version_id IS NULL)
                 OR (g.version_id=? AND (?::uuid IS NULL OR g.index_version_id=?) AND EXISTS(
                     SELECT 1 FROM mm_dataset_version v
                     JOIN artifact_commit raw ON raw.commit_id=v.raw_commit_id AND raw.repo_id=d.raw_repo_id
                     JOIN mm_index_version i ON i.index_version_id=g.index_version_id
                       AND i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id
                     JOIN artifact_commit idx ON idx.commit_id=i.index_commit_id AND idx.repo_id=i.index_repo_id
                     WHERE v.dataset_id=d.dataset_id AND v.version_id=g.version_id
                       AND raw.commit_status='PUBLISHED' AND idx.commit_status='PUBLISHED' AND i.index_status='READY')))
               AND (g.scope_kind<>'CONTROLLED_VIEW' OR EXISTS(SELECT 1 FROM ds_controlled_view v
                   WHERE v.dataset_id=g.dataset_id AND v.version_id=g.version_id
                     AND v.index_version_id=g.index_version_id AND v.owner_org_id=g.owner_org_id
                     AND v.state='READY' AND v.authorization_version=g.view_authorization_version AND controlled_view_publication_policy_current(g.dataset_id)))
               AND (g.scope_kind='CONTROLLED_VIEW' OR NOT EXISTS(
                   SELECT 1 FROM ds_controlled_view cv WHERE cv.dataset_id=g.dataset_id))
               AND (g.contract_id IS NULL OR EXISTS(
                   SELECT 1 FROM ds_contract c JOIN ds_product_version p ON p.product_version_id=c.product_version_id
                    WHERE c.contract_id=g.contract_id AND c.revision=g.contract_revision
                      AND c.provider_org_id=g.owner_org_id AND c.consumer_org_id=g.grantee_org_id
                      AND (c.scope_type='FULL_VERSION' OR (g.scope_kind='CONTROLLED_VIEW'
                           AND ds_controlled_contract_scope_current(c.contract_id,c.revision)))
                      AND p.dataset_id=g.dataset_id AND p.version_id=g.version_id AND p.index_version_id=g.index_version_id
                      AND c.status='CONFIRMED' AND c.revoked_at IS NULL AND c.provider_confirmed_at IS NOT NULL
                      AND c.consumer_confirmed_at IS NOT NULL AND c.valid_from<=clock_timestamp() AND clock_timestamp()<c.valid_to
                      AND NOT EXISTS(SELECT 1 FROM unnest(?::varchar[]) requested(code)
                        WHERE NOT EXISTS(SELECT 1 FROM ds_contract_operation action
                          WHERE action.contract_id=c.contract_id AND action.contract_revision=c.revision
                            AND action.operation_code=requested.code AND ?=ANY(action.purpose_codes)))))
            ORDER BY g.grant_id LIMIT 1
            """,dataset,actions,binding==null?null:binding.grantId(),binding==null?null:binding.grantId(),binding==null?0L:binding.version(),
                actions,purpose,a.orgId(),a.serviceId(),a.userId(),a.orgId(),a.serviceId(),version,index,index,actions,purpose);
        if(!grants.isEmpty()){
            var row=grants.get(0);
            return new ResourceAuthority(new GrantBinding((UUID)row.get("grant_id"),((Number)row.get("version")).longValue()),null);
        }
        // Historical FULL_VERSION contracts preserve their reviewed frozen scope.
        // Every requested action belongs to this same current contract revision.
        if(version==null||a.serviceId()!=null||binding!=null) return null;
        var contracts=jdbc.queryForList("""
            SELECT c.contract_id,c.revision FROM ds_contract c
              JOIN ds_product_version p ON p.product_version_id=c.product_version_id
              JOIN mm_dataset d ON d.dataset_id=p.dataset_id AND d.org_id=c.provider_org_id
              JOIN mm_dataset_version v ON v.version_id=p.version_id AND v.dataset_id=d.dataset_id
              JOIN artifact_commit raw ON raw.commit_id=v.raw_commit_id AND raw.repo_id=d.raw_repo_id
              JOIN mm_index_version i ON i.index_version_id=p.index_version_id AND i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id
              JOIN artifact_commit idx ON idx.commit_id=i.index_commit_id AND idx.repo_id=i.index_repo_id
              JOIN org provider ON provider.org_id=c.provider_org_id
            WHERE c.consumer_org_id=? AND (?::uuid IS NULL OR (c.contract_id=? AND c.revision=?))
              AND c.status='CONFIRMED' AND c.scope_type='FULL_VERSION' AND c.revoked_at IS NULL
              AND c.provider_confirmed_at IS NOT NULL AND c.consumer_confirmed_at IS NOT NULL
              AND c.valid_from<=clock_timestamp() AND clock_timestamp()<c.valid_to AND provider.org_status=1
              AND d.status='ACTIVE' AND raw.commit_status='PUBLISHED' AND idx.commit_status='PUBLISHED'
              AND NOT EXISTS(SELECT 1 FROM ds_controlled_view cv WHERE cv.dataset_id=d.dataset_id)
              AND i.index_status='READY' AND p.raw_commit_id=v.raw_commit_id
              AND p.dataset_id=? AND p.version_id=? AND (?::uuid IS NULL OR p.index_version_id=?)
              AND NOT EXISTS(SELECT 1 FROM unnest(?::varchar[]) requested(code)
                WHERE NOT EXISTS(SELECT 1 FROM ds_contract_operation action
                  WHERE action.contract_id=c.contract_id AND action.contract_revision=c.revision
                    AND action.operation_code=requested.code AND ?=ANY(action.purpose_codes)))
            ORDER BY c.contract_id LIMIT 1
            """,a.orgId(),contract==null?null:contract.contractId(),contract==null?null:contract.contractId(),
                contract==null?0L:contract.revision(),dataset,version,index,index,actions,purpose);
        if(contracts.isEmpty())return null;
        var row=contracts.get(0);
        return new ResourceAuthority(null,new ContractBinding((UUID)row.get("contract_id"),((Number)row.get("revision")).longValue()));
    }
    /** Capture the selected original authority before crossing threads or beginning a stream. */
    public record ResourceLease(Actor actor,UUID datasetId,UUID versionId,UUID indexVersionId,Set<String> operations,String purpose,
                                ResourceAuthority authority) {
        public ResourceLease { operations=Set.copyOf(operations);Objects.requireNonNull(authority); }
    }
    public ResourceLease lease(UUID dataset,UUID version,UUID index,Set<String> operations,String purpose) {
        return lease(actor(),dataset,version,index,operations,purpose,null);
    }
    public ResourceLease lease(Actor actor,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose,ResourceAuthority authority){
        for(String code:operations)requireOperation(actor,code);
        ResourceLease result=selectLease(actor,dataset,version,index,operations,purpose,authority);
        if(result==null)fail(HttpStatus.NOT_FOUND,"RESOURCE_SCOPE_NOT_AVAILABLE");
        return result;
    }
    public void revalidate(ResourceLease lease) {
        lease(lease.actor(),lease.datasetId(),lease.versionId(),lease.indexVersionId(),lease.operations(),lease.purpose(),lease.authority());
    }
    public static final String REQUEST_LEASES=AuthorizationService.class.getName()+".leases";
    private static final String SKIP_LEASE_RECORDING=AuthorizationService.class.getName()+".validating";
    private static String leaseKey(Actor actor,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose){
        return (actor.serviceId()==null?"USER:"+actor.userId():"SERVICE:"+actor.serviceId())+":"+actor.tokenVersion()+":"+
            dataset+":"+version+":"+index+":"+new TreeSet<>(operations)+":"+purpose;
    }
    private ResourceLease requestLease(Actor actor,UUID dataset,UUID version,UUID index,Set<String> operations,String purpose){
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs&&
                attrs.getRequest().getAttribute(REQUEST_LEASES) instanceof Map<?,?> leases){
            Object prior=leases.get(leaseKey(actor,dataset,version,index,operations,purpose));
            if(prior instanceof ResourceLease lease)return lease;
            if(prior!=null)fail(HttpStatus.FORBIDDEN,"INVALID_RESOURCE_LEASE");
        }
        return null;
    }
    @SuppressWarnings("unchecked")
    private ResourceLease remember(ResourceLease lease) {
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            var request=attrs.getRequest();
            if(Boolean.TRUE.equals(request.getAttribute(SKIP_LEASE_RECORDING)))return lease;
            Object prior=request.getAttribute(REQUEST_LEASES);
            Map<String,ResourceLease> leases;
            if(prior==null){leases=new java.util.concurrent.ConcurrentHashMap<>();request.setAttribute(REQUEST_LEASES,leases);}
            else leases=(Map<String,ResourceLease>)prior;
            String key=leaseKey(lease.actor(),lease.datasetId(),lease.versionId(),lease.indexVersionId(),lease.operations(),lease.purpose());
            if(leases.size()>=4096&&!leases.containsKey(key))fail(HttpStatus.PAYLOAD_TOO_LARGE,"REQUEST_SCOPE_LIMIT");
            ResourceLease first=leases.putIfAbsent(key,lease);
            if(first!=null&&!first.authority().equals(lease.authority()))fail(HttpStatus.NOT_FOUND,"RESOURCE_AUTHORITY_CHANGED");
        }
        return lease;
    }
    public void rememberSourceConstraint(String key,Runnable check) {
        if(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs){
            var request=attrs.getRequest();if(Boolean.TRUE.equals(request.getAttribute(SKIP_LEASE_RECORDING)))return;
            Object prior=request.getAttribute("ds.persistedSourceChecks");
            if(prior==null){prior=new java.util.concurrent.ConcurrentHashMap<String,Runnable>();request.setAttribute("ds.persistedSourceChecks",prior);}
            if(!(prior instanceof Map<?,?>))fail(HttpStatus.FORBIDDEN,"INVALID_SOURCE_CONSTRAINTS");
            @SuppressWarnings("unchecked") var checks=(Map<String,Runnable>)prior;checks.putIfAbsent(key,check);
        }
    }
    public void revalidateRequest(jakarta.servlet.http.HttpServletRequest request) {
        Object prior=request.getAttribute(SKIP_LEASE_RECORDING);request.setAttribute(SKIP_LEASE_RECORDING,true);
        try {
            if(request.getAttribute(REQUEST_LEASES) instanceof Map<?,?> leases)
                for(Object value:List.copyOf(leases.values())) {
                    if(!(value instanceof ResourceLease lease))fail(HttpStatus.FORBIDDEN,"INVALID_RESOURCE_LEASE");
                    else revalidate(lease);
                }
            if(request.getAttribute("ds.persistedSourceChecks") instanceof Map<?,?> checks)
                for(Object value:List.copyOf(checks.values())) {
                    if(!(value instanceof Runnable check))fail(HttpStatus.FORBIDDEN,"INVALID_SOURCE_CONSTRAINT");
                    else check.run();
                }
        } finally {request.setAttribute(SKIP_LEASE_RECORDING,prior);}
    }
    public long currentPolicyVersion() {
        return jdbc.queryForObject("SELECT policy_version FROM ds_auth_initialization WHERE singleton",Long.class);
    }
    public void lockIdentityMutation() {
        jdbc.queryForObject("SELECT policy_version FROM ds_auth_initialization WHERE singleton FOR UPDATE",Long.class);
    }
    public void requireNoManagementEscalation(Actor operator,UUID targetUser) {
        if(operator.platformAdmin()) return;
        if(exists("SELECT EXISTS(SELECT 1 FROM ds_management_membership WHERE user_id=? AND active)",targetUser))
            fail(HttpStatus.NOT_FOUND,"MEMBER_SCOPE_NOT_AVAILABLE");
    }
    public Map<String,Object> currentIdentity() {
        Actor a=actor();var result=new LinkedHashMap<String,Object>();
        result.put("userId",a.userId());result.put("orgId",a.orgId());result.put("username",a.username());
        result.put("orgName",jdbc.queryForObject("SELECT org_name FROM org WHERE org_id=?",String.class,a.orgId()));
        var enterprise=jdbc.queryForList("SELECT entity_id AS \"entityId\",industry_kind AS \"industryKind\",state,identity_kind AS \"identityKind\" FROM ds_enterprise_identity WHERE org_id=?",a.orgId());
        result.put("enterpriseIdentity",enterprise.isEmpty()?null:enterprise.get(0));
        result.put("operations",a.operations().stream().sorted().toList());result.put("platformAdmin",a.platformAdmin());
        result.put("enterpriseMemberManager",a.enterpriseManager());result.put("permissionVersion",a.permissionVersion());
        result.put("policyVersion",currentPolicyVersion());result.put("readOnly",a.readOnly());return result;
    }
}
