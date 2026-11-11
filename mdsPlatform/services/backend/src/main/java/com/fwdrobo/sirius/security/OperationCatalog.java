package com.fwdrobo.sirius.security;

import java.util.*;

/** Implemented operation registry. Type names and client paths are never authorities. */
public final class OperationCatalog {
    private OperationCatalog() {}
    public record Operation(String code,String group,String label,boolean mutation) {}
    public static final Map<String,Operation> OPERATIONS;
    static {
        var entries=new LinkedHashMap<String,Operation>();
        entries.put("data.catalog.read",new Operation("data.catalog.read","data","View authorized catalog",false));
        entries.put("data.preview",new Operation("data.preview","data","Preview authorized content",false));
        entries.put("data.search",new Operation("data.search","data","Search authorized content",false));
        entries.put("data.qa",new Operation("data.qa","data","Answer with authorized evidence",false));
        entries.put("data.raw.download",new Operation("data.raw.download","data","Download complete authorized originals",false));
        entries.put("data.view.export",new Operation("data.view.export","data","Export an authorized materialized view",false));
        entries.put("dataset.share.manage",new Operation("dataset.share.manage","supply","Review explicit own-enterprise resource sharing",true));
        entries.put("identity.resource.manage",new Operation("identity.resource.manage","platform","Manage explicit human resource grants",true));
        entries.put("dataset.create",new Operation("dataset.create","supply","Create datasets",true));
        entries.put("dataset.upload",new Operation("dataset.upload","supply","Initiate resume and complete uploads",true));
        entries.put("dataset.draft.manage",new Operation("dataset.draft.manage","supply","Manage draft metadata",true));
        entries.put("dataset.publish",new Operation("dataset.publish","supply","Publish immutable raw versions",true));
        entries.put("dataset.quality.run",new Operation("dataset.quality.run","supply","Run quality checks",true));
        entries.put("dataset.quality.read",new Operation("dataset.quality.read","supply","Read scoped quality details",false));
        entries.put("dataset.index.build",new Operation("dataset.index.build","supply","Build indexes",true));
        entries.put("dataset.index.activate",new Operation("dataset.index.activate","supply","Activate a ready index",true));
        entries.put("product.publish",new Operation("product.publish","product","Publish data products",true));
        entries.put("product.unpublish",new Operation("product.unpublish","product","Delist data products",true));
        entries.put("product.summary.manage",new Operation("product.summary.manage","product","Edit explicit public summaries",true));
        entries.put("application.submit",new Operation("application.submit","product","Submit own-enterprise consumption requests",true));
        entries.put("application.review",new Operation("application.review","product","Review own supply requests",true));
        entries.put("contract.read",new Operation("contract.read","contract","Read own authorized contracts",false));
        entries.put("contract.edit",new Operation("contract.edit","contract","Draft own contract terms",true));
        entries.put("contract.confirm",new Operation("contract.confirm","contract","Confirm on behalf of own enterprise",true));
        entries.put("contract.revoke",new Operation("contract.revoke","contract","Revoke own contract grants",true));
        entries.put("delivery.endpoint.manage",new Operation("delivery.endpoint.manage","delivery","Manage own receiver endpoints",true));
        entries.put("delivery.execute",new Operation("delivery.execute","delivery","Execute or retry explicitly authorized delivery",true));
        entries.put("delivery.receipt.read",new Operation("delivery.receipt.read","delivery","Read authorized receipts",false));
        entries.put("delivery.package.download",new Operation("delivery.package.download","delivery","Download authorized delivery packages",false));
        entries.put("job.read",new Operation("job.read","jobs","Read own scoped tasks",false));
        entries.put("job.log.read",new Operation("job.log.read","jobs","Read own scoped task logs",false));
        entries.put("job.cancel",new Operation("job.cancel","jobs","Cancel own authorized tasks",true));
        entries.put("identity.self.read",new Operation("identity.self.read","identity","Read own identity",false));
        entries.put("identity.self.password",new Operation("identity.self.password","identity","Change own password",true));
        entries.put("identity.self.logout",new Operation("identity.self.logout","identity","Revoke own sessions",true));
        entries.put("identity.self.close",new Operation("identity.self.close","identity","Close own identity",true));
        entries.put("identity.member.read",new Operation("identity.member.read","identity","Read permitted enterprise members",false));
        entries.put("identity.member.create",new Operation("identity.member.create","identity","Create permitted enterprise members",true));
        entries.put("identity.member.update",new Operation("identity.member.update","identity","Edit permitted enterprise members",true));
        entries.put("identity.member.disable",new Operation("identity.member.disable","identity","Disable or restore permitted members",true));
        entries.put("identity.member.reset",new Operation("identity.member.reset","identity","Reset permitted member credentials",true));
        entries.put("identity.member.close",new Operation("identity.member.close","identity","Close permitted member identities",true));
        entries.put("identity.member.type.assign",new Operation("identity.member.type.assign","identity","Assign permitted business types",true));
        entries.put("identity.type.manage",new Operation("identity.type.manage","platform","Manage global business type definitions",true));
        entries.put("identity.service.manage",new Operation("identity.service.manage","platform","Manage institutional service subjects",true));
        entries.put("identity.platform-admin.manage",new Operation("identity.platform-admin.manage","platform","Manage platform administrator qualifications",true));
        entries.put("identity.enterprise.manage",new Operation("identity.enterprise.manage","platform","Manage confirmed enterprise mappings",true));
        entries.put("identity.audit.read",new Operation("identity.audit.read","platform","Read permitted authorization audit",false));
        entries.put("platform.runtime.manage",new Operation("platform.runtime.manage","platform","Manage platform runtime without implied business reads",true));
        OPERATIONS=Collections.unmodifiableMap(entries);
    }
    public static final Set<String> SELF=Set.of("identity.self.read","identity.self.password","identity.self.logout","identity.self.close");
    public static final Set<String> MEMBER_MANAGEMENT=Set.of("identity.member.read","identity.member.create","identity.member.update","identity.member.disable","identity.member.reset","identity.member.close","identity.member.type.assign");
    public static final Set<String> PLATFORM_MANAGEMENT;
    static {
        var codes=new HashSet<String>(MEMBER_MANAGEMENT);
        codes.addAll(Set.of("identity.resource.manage","identity.type.manage","identity.service.manage","identity.platform-admin.manage","identity.enterprise.manage","identity.audit.read","platform.runtime.manage"));
        PLATFORM_MANAGEMENT=Set.copyOf(codes);
    }
    public static Map<String,Operation> all() { return OPERATIONS; }
    public static boolean isKnown(String code) { return OPERATIONS.containsKey(code); }
    public static boolean isBusiness(String code) {
        Operation op=OPERATIONS.get(code);
        return op!=null&&!Set.of("identity","platform").contains(op.group());
    }
    public static boolean isReadOnlyBusinessOperation(String code) {
        Operation op=OPERATIONS.get(code);
        return op!=null&&!op.mutation()&&!code.equals("data.raw.download")&&!code.equals("delivery.package.download")&&!code.equals("data.view.export");
    }
}
