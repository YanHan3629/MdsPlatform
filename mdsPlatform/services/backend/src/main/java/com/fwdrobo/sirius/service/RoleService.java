package com.fwdrobo.sirius.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import com.fwdrobo.sirius.dto.permission.RoleReq;
import com.fwdrobo.sirius.entity.permission.*;
import com.fwdrobo.sirius.mapper.*;
import com.fwdrobo.sirius.security.*;
import com.fwdrobo.sirius.security.IdentityManagementService.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
@Service
public class RoleService {
    private final RoleMapper roleMapper;private final PermissionMapper permissionMapper;private final JdbcTemplate jdbc;
    private final IdentityManagementService management;private final AuthorizationService auth;private final ObjectMapper mapper;
    public RoleService(RoleMapper roleMapper,PermissionMapper permissionMapper,JdbcTemplate jdbc,
            IdentityManagementService management,AuthorizationService auth,ObjectMapper mapper) {
        this.roleMapper=roleMapper;this.permissionMapper=permissionMapper;this.jdbc=jdbc;this.management=management;this.auth=auth;this.mapper=mapper;
    }
    private void read(UUID id){get(id);}
    private Map<String,Object> metadata(UUID id){read(id);return jdbc.queryForMap("SELECT * FROM role WHERE role_id=? AND type_state<>'DELETED'",id);}
    public Role get(UUID id){
        return management.legacyRoles(id).stream().findFirst()
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"TYPE_NOT_AVAILABLE"));
    }
    public List<Role> selectRoles(){return management.legacyRoles(null);}
    private List<String> operations(List<UUID> ids){
        if(ids==null)return List.of();var unique=new LinkedHashSet<>(ids);var out=new ArrayList<String>();
        for(UUID id:unique){
            var rows=jdbc.queryForList("SELECT operation_code FROM ds_operation WHERE permission_id=?",String.class,id);
            if(rows.size()!=1||!OperationCatalog.isBusiness(rows.get(0)))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"IMPLEMENTED_BUSINESS_PERMISSION_REQUIRED");
            out.add(rows.get(0));
        }
        return out;
    }
    private List<String> tags(Object raw) {
        try{return raw==null?List.of():mapper.readValue(raw.toString(),mapper.getTypeFactory().constructCollectionType(List.class,String.class));}
        catch(Exception e){throw new IllegalStateException("Stored type tags invalid");}
    }
    private List<UUID> orgIds(Object raw){
        try{return raw==null?List.of():Arrays.stream((Object[])((java.sql.Array)raw).getArray()).map(x->UUID.fromString(x.toString())).toList();}
        catch(Exception e){throw new IllegalStateException("Stored type applicability invalid");}
    }
    private TypeChange change(RoleReq req,Map<String,Object> old){
        boolean fresh=old==null;
        List<String> ops=req.getPermissionIds()!=null?operations(req.getPermissionIds()):fresh?List.of():
            jdbc.queryForList("SELECT o.operation_code FROM role_permission rp JOIN ds_operation o ON o.permission_id=rp.per_id WHERE rp.role_id=?",String.class,old.get("role_id"));
        return new TypeChange(fresh?req.getOrgId():(UUID)old.get("org_id"),
            req.getRoleCode()!=null?req.getRoleCode():fresh?null:(String)old.get("role_code"),
            req.getRoleName()!=null?req.getRoleName():fresh?null:(String)old.get("role_name"),
            req.getDescription()!=null?req.getDescription():fresh?"":(String)old.get("description"),
            req.getScope()!=null?req.getScope():fresh?"ENTERPRISE":(String)old.get("type_scope"),
            req.getIndustryTags()!=null?req.getIndustryTags():fresh?List.of():tags(old.get("industry_tags")),
            req.getApplicableOrgIds()!=null?req.getApplicableOrgIds():fresh?List.of():orgIds(old.get("applicable_org_ids")),
            req.getReadOnly()!=null?req.getReadOnly():!fresh&&Boolean.TRUE.equals(old.get("read_only_profile")),ops,
            req.getState()!=null?req.getState():fresh?"ACTIVE":(String)old.get("type_state"),req.getExpectedVersion());
    }
    public UUID create(RoleReq req){auth.requirePlatformManagement("identity.type.manage");return management.createType(change(req,null));}
    public void update(UUID id,RoleReq req){auth.requirePlatformManagement("identity.type.manage");management.updateType(id,change(req,metadata(id)));}
    public void delete(UUID id){delete(id,null,null,null);}
    public void delete(UUID id,Long version,UUID replacement,Long replacementVersion){management.deleteType(id,version,replacement,replacementVersion);}
    public List<Role> getRolesByUserId(UUID uid){
        var a=auth.actor();
        if(!a.userId().equals(uid)){
            var orgs=jdbc.queryForList("SELECT org_id FROM fwd_user WHERE user_id=?",UUID.class,uid);
            if(orgs.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"MEMBER_SCOPE_NOT_AVAILABLE");
            auth.requireMemberManagement(orgs.get(0),"identity.member.read");auth.requireNoManagementEscalation(a,uid);
        }
        return roleMapper.selectRolesByUserId(uid);
    }
    public List<Role> getRolesByIds(List<UUID> ids){if(ids==null)return List.of();return ids.stream().map(this::get).toList();}
    public void insertUserRoles(UUID uid,List<UUID> ids){setUserRoles(uid,ids);}
    public void setUserRoles(UUID uid,List<UUID> ids){management.assignTypes(uid,new TypeAssignment(ids,ids==null||ids.isEmpty()?null:ids.get(0),null));}
    public List<UUID> listRolePermissionIds(UUID id){read(id);return roleMapper.selectPerIdsByRoleId(id);}
    public void setRolePermissions(UUID id,List<UUID> ids){setRolePermissions(id,ids,null);}
    public void setRolePermissions(UUID id,List<UUID> ids,Long version){
        auth.requirePlatformManagement("identity.type.manage");var req=new RoleReq();req.setPermissionIds(ids);req.setExpectedVersion(version);
        management.updateType(id,change(req,metadata(id)));
    }
    public List<PermissionTree> getRolePermissionTree(UUID roleId) {
        read(roleId);

        // Operation definitions are metadata; reading requires management scope.
        List<Permission> all = permissionMapper.selectAll(null);

        // 查角色已绑定的 per_id（推荐模式：仅 operation）
        Set<UUID> selectedOpIds = new HashSet<>(roleMapper.selectPerIdsByRoleId(roleId));

        // feature 节点容器（保持稳定顺序）
        Map<UUID, PermissionTree> featureNodes = new LinkedHashMap<>();
        for (Permission p : all) {
            if ("feature".equals(p.getCategory())) {
                PermissionTree f = toTreeNode(p);
                featureNodes.put(p.getPerId(), f);
            }
        }

        // 挂 operation，并设置 operation.checked
        for (Permission p : all) {
            if (!"operation".equals(p.getCategory())) continue;
            PermissionTree feature = featureNodes.get(p.getParentId());
            if (feature == null) {
                if (!OperationCatalog.isBusiness(p.getPerCode())) continue;
                String group = OperationCatalog.all().get(p.getPerCode()).group();
                UUID groupId = UUID.nameUUIDFromBytes(("sirius:operation-group:" + group)
                    .getBytes(StandardCharsets.UTF_8));
                feature = featureNodes.computeIfAbsent(groupId, id -> {
                    PermissionTree node = new PermissionTree();
                    node.setPerId(id);
                    node.setPerCode(group);
                    node.setPerName(group);
                    node.setCategory("feature");
                    return node;
                });
            }

            PermissionTree op = toTreeNode(p);
            op.setChecked(selectedOpIds.contains(p.getPerId()));
            feature.getChildren().add(op);
        }

        // 计算 feature 的 checked / indeterminate
        List<PermissionTree> result = new ArrayList<>(featureNodes.values());
        for (PermissionTree f : result) {
            int total = f.getChildren().size();
            int checked = 0;
            for (PermissionTree op : f.getChildren()) {
                if (op.isChecked()) checked++;
            }

            if (total == 0) {
                f.setChecked(false);
                f.setIndeterminate(false);
            } else if (checked == 0) {
                f.setChecked(false);
                f.setIndeterminate(false);
            } else if (checked == total) {
                f.setChecked(true);
                f.setIndeterminate(false);
            } else {
                f.setChecked(false);
                f.setIndeterminate(true);
            }

            // 对子节点排序（按 perCode）
            f.getChildren().sort(java.util.Comparator.comparing(PermissionTree::getPerCode));
        }

        // feature 排序
        result.sort(java.util.Comparator.comparing(PermissionTree::getPerCode));

        return result;
    }

    private PermissionTree toTreeNode(Permission p) {
        PermissionTree n = new PermissionTree();
        BeanUtils.copyProperties(p, n);
        return n;
    }

    private List<UUID> distinct(List<UUID> list) {
        if (list == null) return Collections.emptyList();
        return new ArrayList<>(new LinkedHashSet<>(list));
    }

    private boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public Object previewUpdate(UUID id,RoleReq req){return management.preview("TYPE_UPDATE",id,change(req,metadata(id)));}
    public Object previewPermissions(UUID id,List<UUID> ids,Long version){var req=new RoleReq();req.setPermissionIds(ids);req.setExpectedVersion(version);return previewUpdate(id,req);}
    public Object previewDelete(UUID id,Long version,UUID replacement,Long replacementVersion){return management.preview("TYPE_DELETE",id,new com.fwdrobo.sirius.security.IdentityManagementService.TypeDeletion(version,replacement,replacementVersion));}
}
