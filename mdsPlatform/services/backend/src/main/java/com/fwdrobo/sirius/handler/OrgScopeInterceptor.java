package com.fwdrobo.sirius.handler;

import com.fwdrobo.sirius.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Connection;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * 只对指定表强制租户隔离：自动给 SQL 注入 org_id 条件（SELECT/UPDATE/DELETE）。
 *
 * 适配：PostgreSQL + 纯 MyBatis + JSqlParser
 *
 * 跳过方式：
 *  - SQL 中包含注释：/*IGNORE_ORG*\/
 *
 * 重要：仅 enforcedTables 中的表会自动注入 org_id。
 */
@Slf4j
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class OrgScopeInterceptor implements Interceptor {

    private static final String ORG_COLUMN = "org_id";
    private static final String IGNORE_MARKER = "/*IGNORE_ORG*/";

    /** 是否尝试改写 INSERT（默认 false，建议先不上） */
    private final boolean rewriteInsert;

    /** 只有这些表才会注入 org_id（小写） */
    private final Set<String> enforcedTables;

    public OrgScopeInterceptor() {
        this(false, Set.of(
                "device",
                "artifact_repo",
                "job",
                "role",
                "async_operation"
        ));
    }

    public OrgScopeInterceptor(boolean rewriteInsert, Set<String> enforcedTables) {
        this.rewriteInsert = rewriteInsert;
        this.enforcedTables = enforcedTables == null ? Set.of() : enforcedTables;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(handler);

        String originalSql = (String) metaObject.getValue("delegate.boundSql.sql");
        if (originalSql == null) return invocation.proceed();

        // 超级管理员跳过
        if (isSuperAdmin()) return invocation.proceed();
        // SQL 显式跳过
        if (originalSql.contains(IGNORE_MARKER)) return invocation.proceed();

        // 仅当“主表属于 enforcedTables”时才注入 org_id
        if (!shouldApplyOrgScope(originalSql)) {
            return invocation.proceed();
        }

        UUID orgId = SecurityUtils.getUserOrgId();
        if (orgId == null) {
            throw new IllegalStateException(
                    "Missing orgId in TenantContext. " +
                            "This SQL requires org scope injection. " +
                            "Make sure TenantContext.setOrgId() is called after auth succeeds and clear() in finally."
            );
        }

        String rewritten = tryRewriteSql(originalSql, orgId.toString());
        if (rewritten != null && !Objects.equals(rewritten, originalSql)) {
            metaObject.setValue("delegate.boundSql.sql", rewritten);
        }

        return invocation.proceed();
    }

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;

        // 你们 JwtAuthFilter 里把所有角色都映射成 ROLE_xxx 了
        for (GrantedAuthority ga : auth.getAuthorities()) {
            if (ga == null) continue;
            String a = ga.getAuthority();
            if ("ROLE_SUPER_ADMIN".equals(a) || "ROLE_SUPERADMIN".equals(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判定是否需要注入 org_id：
     * - 解析 SELECT/UPDATE/DELETE 的“主表名”
     * - 只有主表在 enforcedTables 中才返回 true
     */
    private boolean shouldApplyOrgScope(String sql) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);

            if (stmt instanceof Select select) {
                if (!(select.getSelectBody() instanceof PlainSelect ps)) {
                    throw new IllegalStateException("Complex SELECT not supported for org scope injection");
                }
                FromItem from = ps.getFromItem();
                if (!(from instanceof Table table)) {
                    throw new IllegalStateException("Non-table FROM not supported for org scope injection");
                }
                String tableName = normalizeTableName(table.getName());
                return enforcedTables.contains(tableName);
            }

            if (stmt instanceof Update update) {
                Table table = update.getTable();
                if (table == null) throw new IllegalStateException("UPDATE table missing");
                String tableName = normalizeTableName(table.getName());
                return enforcedTables.contains(tableName);
            }

            if (stmt instanceof Delete delete) {
                Table table = delete.getTable();
                if (table == null) throw new IllegalStateException("DELETE table missing");
                String tableName = normalizeTableName(table.getName());
                return enforcedTables.contains(tableName);
            }

            // INSERT/其他：默认不注入（你要注入的话，需要更完善的 INSERT 改写）
            return false;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse SQL for org scope injection", e);
        }
    }

    private String tryRewriteSql(String sql, String orgIdStr) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);

            if (stmt instanceof Select select) {
                return rewriteSelect(select, orgIdStr);
            }
            if (stmt instanceof Update update) {
                return rewriteUpdate(update, orgIdStr);
            }
            if (stmt instanceof Delete delete) {
                return rewriteDelete(delete, orgIdStr);
            }
            if (rewriteInsert && stmt instanceof Insert insert) {
                // 默认关闭：INSERT AST 版本差异多，建议先不要在这里做自动改写
                return insert.toString();
            }
            return null;
        } catch (Exception e) {
            // 解析失败：保守处理（不改写）
            return null;
        }
    }

    private String rewriteSelect(Select select, String orgIdStr) {
        if (!(select.getSelectBody() instanceof PlainSelect ps)) return select.toString();

        FromItem from = ps.getFromItem();
        if (!(from instanceof Table table)) return select.toString();

        String alias = table.getAlias() == null ? null : table.getAlias().getName();
        Expression orgExpr = equalsOrgExpr(orgIdStr, alias);

        ps.setWhere(and(ps.getWhere(), orgExpr));
        return select.toString();
    }

    private String rewriteUpdate(Update update, String orgIdStr) {
        Table table = update.getTable();
        if (table == null) return update.toString();

        String alias = table.getAlias() == null ? null : table.getAlias().getName();
        Expression orgExpr = equalsOrgExpr(orgIdStr, alias);

        update.setWhere(and(update.getWhere(), orgExpr));
        return update.toString();
    }

    private String rewriteDelete(Delete delete, String orgIdStr) {
        Table table = delete.getTable();
        if (table == null) return delete.toString();

        String alias = table.getAlias() == null ? null : table.getAlias().getName();
        Expression orgExpr = equalsOrgExpr(orgIdStr, alias);

        delete.setWhere(and(delete.getWhere(), orgExpr));
        return delete.toString();
    }

    private String normalizeTableName(String raw) {
        if (raw == null) return "";
        String name = raw.trim().toLowerCase(Locale.ROOT);

        // 去 schema 前缀：public.xxx -> xxx
        int dot = name.lastIndexOf('.');
        name = dot >= 0 ? name.substring(dot + 1) : name;

        // 去掉 PG 双引号： "device" -> device
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() >= 2) {
            name = name.substring(1, name.length() - 1);
        }
        return name;
    }

    private Expression equalsOrgExpr(String orgIdStr, String alias) {
        Column col = (alias == null || alias.isBlank())
                ? new Column(ORG_COLUMN)
                : new Column(alias + "." + ORG_COLUMN);

        EqualsTo eq = new EqualsTo();
        eq.setLeftExpression(col);
        eq.setRightExpression(new StringValue(orgIdStr)); // PG uuid 列通常可接受字符串字面量
        return eq;
    }

    private Expression and(Expression where, Expression extra) {
        return where == null ? extra : new AndExpression(where, extra);
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // 可扩展：从配置注入 enforcedTables / rewriteInsert
    }
}