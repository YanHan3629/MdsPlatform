package com.fwdrobo.sirius.service;

import com.fwdrobo.sirius.util.SecurityUtils;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** One live authority for owner access and confirmed full-version consumption. */
@Service
public class DatasetAccessService {
    private final JdbcTemplate jdbc;
    public DatasetAccessService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public UUID actorOrg() {
        if (SecurityUtils.getUserId()==null) fail(HttpStatus.UNAUTHORIZED,"请先登录数据空间");
        UUID org=SecurityUtils.getUserOrgId();
        if (org==null) fail(HttpStatus.FORBIDDEN,"账号未绑定组织");
        return org;
    }
    private static void fail(HttpStatus status,String text) { throw new ResponseStatusException(status,text); }
    private boolean exists(String sql,Object... args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    public boolean ownsDataset(UUID dataset) {
        return exists("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE dataset_id=? AND org_id=?)",dataset,actorOrg());
    }
    public void requireDatasetOwner(UUID dataset) {
        if (!ownsDataset(dataset)) fail(HttpStatus.FORBIDDEN,"只有数据集所属组织可以管理该数据集");
    }
    public void requireDatasetWrite(UUID dataset) {
        requireDatasetOwner(dataset);
        boolean creator=exists("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE dataset_id=? AND owner_user_id=?)",dataset,SecurityUtils.getUserId());
        List<String> roles=SecurityUtils.getUserRoles();
        if (!creator && (roles==null || roles.stream().noneMatch(r->Set.of("ROLE_SUPER_ADMIN","ROLE_ADMIN","ROLE_ORG_ADMIN","ROLE_DATA_MANAGER").contains(r))))
            fail(HttpStatus.FORBIDDEN,"需要数据集所有者或组织数据管理角色");
    }

    // Product delisting is deliberately absent: an existing frozen contract
    // survives delisting. Delivery success is not an access prerequisite.
    private static final String GRANT="""
        SELECT EXISTS(SELECT 1 FROM ds_contract c
          JOIN ds_product_version p ON p.product_version_id=c.product_version_id
          JOIN mm_dataset d ON d.dataset_id=p.dataset_id
          JOIN mm_dataset_version v ON v.version_id=p.version_id AND v.dataset_id=d.dataset_id
          JOIN artifact_commit raw ON raw.commit_id=v.raw_commit_id AND raw.repo_id=d.raw_repo_id
          JOIN mm_index_version i ON i.index_version_id=p.index_version_id AND i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id
          JOIN artifact_commit idx ON idx.commit_id=i.index_commit_id AND idx.repo_id=i.index_repo_id
        WHERE c.consumer_org_id=? AND c.status='CONFIRMED' AND c.scope_type='FULL_VERSION'
          AND c.provider_confirmed_at IS NOT NULL AND c.consumer_confirmed_at IS NOT NULL AND c.revoked_at IS NULL
          AND c.valid_from<=now() AND now()<c.valid_to AND d.status='ACTIVE'
          AND raw.commit_status='PUBLISHED' AND idx.commit_status='PUBLISHED' AND i.index_status='READY'
          AND p.raw_commit_id=v.raw_commit_id AND p.dataset_id=?
        """;
    public boolean canReadVersion(UUID dataset,UUID version,UUID index) {
        UUID org=actorOrg();
        if (ownsDataset(dataset)) return true;
        String extra=version==null?"":" AND p.version_id=?";
        List<Object> args=new ArrayList<>(List.of(org,dataset));
        if (version!=null) args.add(version);
        if (index!=null) {extra+=" AND p.index_version_id=?";args.add(index);}
        return exists(GRANT+extra+")",args.toArray());
    }
    public void requireDatasetRead(UUID dataset) {
        if (!canReadVersion(dataset,null,null)) fail(HttpStatus.FORBIDDEN,"该数据集不在当前组织的有效读取授权内");
    }
    public void requireVersionRead(UUID dataset,UUID version,UUID index) {
        actorOrg();
        if (!exists("SELECT EXISTS(SELECT 1 FROM mm_dataset_version WHERE dataset_id=? AND version_id=?)",dataset,version))
            fail(HttpStatus.FORBIDDEN,"数据集与版本范围不匹配或不可访问");
        if (index!=null && !exists("""
            SELECT EXISTS(SELECT 1 FROM mm_index_version i JOIN artifact_commit c ON c.commit_id=i.index_commit_id AND c.repo_id=i.index_repo_id
            WHERE i.dataset_id=? AND i.dataset_version_id=? AND i.index_version_id=? AND i.index_status='READY' AND c.commit_status='PUBLISHED')
            """,dataset,version,index)) fail(HttpStatus.FORBIDDEN,"指定索引不属于该版本的已发布READY快照");
        if (!canReadVersion(dataset,version,index)) fail(HttpStatus.FORBIDDEN,"该冻结版本尚未授权、已撤销或已到期");
    }
    public List<UUID> readableVersionIds(UUID dataset) {
        requireDatasetRead(dataset);
        if (ownsDataset(dataset)) return null;
        return jdbc.queryForList("SELECT version_id FROM mm_dataset_version WHERE dataset_id=?",UUID.class,dataset).stream()
            .filter(version->canReadVersion(dataset,version,null)).toList();
    }
    public void requireArtifact(UUID repo,UUID commit,boolean write) {
        UUID org=actorOrg();
        List<Map<String,Object>> repos=jdbc.queryForList("SELECT org_id,owner_user_id,visibility FROM artifact_repo WHERE repo_id=?",repo);
        if (repos.isEmpty()) fail(HttpStatus.NOT_FOUND,"仓库不存在");
        if (commit!=null && !exists("SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=?)",repo,commit))
            fail(HttpStatus.FORBIDDEN,"仓库与快照不匹配");
        if (org.equals(repos.get(0).get("org_id"))) {
            if(write)for(UUID dataset:jdbc.queryForList("SELECT dataset_id FROM mm_dataset WHERE raw_repo_id=? OR index_repo_id=?",UUID.class,repo,repo))requireDatasetWrite(dataset);
            return;
        }
        if (write) fail(HttpStatus.FORBIDDEN,"消费读取授权不允许修改供给方仓库");
        List<Map<String,Object>> scopes=jdbc.queryForList("""
            SELECT d.dataset_id,v.version_id FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
            WHERE d.raw_repo_id=? AND v.raw_commit_id=?
            """,repo,commit);
        if (!scopes.isEmpty()) {
            var row=scopes.get(0);requireVersionRead((UUID)row.get("dataset_id"),(UUID)row.get("version_id"),null);return;
        }
        boolean multimodal=exists("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE raw_repo_id=? OR index_repo_id=?)",repo,repo);
        if (!multimodal && "PUBLIC".equals(repos.get(0).get("visibility")) && (commit==null || exists(
            "SELECT EXISTS(SELECT 1 FROM artifact_commit WHERE repo_id=? AND commit_id=? AND commit_status='PUBLISHED')",repo,commit))) return;
        fail(HttpStatus.FORBIDDEN,"该仓库或快照未授权；索引文件不能以消费授权直接下载");
    }
    public boolean isForeignArtifact(UUID repo) {
        return !exists("SELECT EXISTS(SELECT 1 FROM artifact_repo WHERE repo_id=? AND org_id=?)",repo,actorOrg());
    }
    public void requireBusinessFile(UUID repo,String path){
        if(com.fwdrobo.sirius.util.DataSpaceBusinessFilePolicy.isDescription(path)&&exists("SELECT EXISTS(SELECT 1 FROM mm_dataset WHERE raw_repo_id=?)",repo))
            fail(HttpStatus.BAD_REQUEST,"说明文件不作为业务资产入库，请在数据空间外保留README/数据集说明.md");
    }
    public List<Map<String,Object>> readableReadyScopes() {
        return readableReadyScopes(false);
    }
    public List<Map<String,Object>> readableReadyScopes(boolean includeOwnerCandidates) {
        UUID org=actorOrg();
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT d.dataset_id AS "datasetId",d.dataset_name AS "datasetName",d.modality_type AS "modalityType",
                   v.version_id AS "versionId",v.version_name AS "versionName",v.sample_count AS "sampleCount",
                   i.index_version_id AS "indexVersionId",i.index_status AS "indexStatus",d.org_id AS "ownerOrgId",
                   COALESCE(i.index_version_id=v.active_index_version_id,false) AS "isActiveIndex",
                   EXISTS(SELECT 1 FROM ds_product_version pv JOIN ds_product p ON p.product_id=pv.product_id
                       WHERE pv.dataset_id=d.dataset_id AND pv.version_id=v.version_id AND pv.index_version_id=i.index_version_id
                       AND p.provider_org_id=d.org_id) AS "hasProductScope"
            FROM mm_dataset d JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id
            JOIN artifact_commit raw ON raw.commit_id=v.raw_commit_id AND raw.repo_id=d.raw_repo_id AND raw.commit_status='PUBLISHED'
            JOIN mm_index_version i ON i.dataset_id=d.dataset_id AND i.dataset_version_id=v.version_id AND i.index_status='READY'
            JOIN artifact_commit idx ON idx.commit_id=i.index_commit_id AND idx.repo_id=i.index_repo_id AND idx.commit_status='PUBLISHED'
            WHERE d.status='ACTIVE' AND (d.org_id<>? OR i.index_version_id=v.active_index_version_id OR EXISTS(
                SELECT 1 FROM ds_product_version pv JOIN ds_product p ON p.product_id=pv.product_id
                WHERE pv.dataset_id=d.dataset_id AND pv.version_id=v.version_id AND pv.index_version_id=i.index_version_id AND p.provider_org_id=d.org_id)
                OR (? AND d.org_id=?))
            ORDER BY d.dataset_name,v.created_at DESC,i.created_at DESC
            """,org,includeOwnerCandidates,org);
        return rows.stream().filter(row->canReadVersion((UUID)row.get("datasetId"),(UUID)row.get("versionId"),(UUID)row.get("indexVersionId")))
            .peek(row->{row.put("isOwnerCandidate",org.equals(row.get("ownerOrgId")) && !Boolean.TRUE.equals(row.get("isActiveIndex"))
                && !Boolean.TRUE.equals(row.get("hasProductScope")));row.remove("hasProductScope");}).toList();
    }
}
