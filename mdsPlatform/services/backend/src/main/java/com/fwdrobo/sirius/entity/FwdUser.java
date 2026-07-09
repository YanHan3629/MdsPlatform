package com.fwdrobo.sirius.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class FwdUser {

    /**
     * 用户唯一标识符
     */
    UUID userId;

    /**
     * 用户名，现在作为唯一登录标识符
     */
    String userName;

    /**
     * 密码哈希值
     */
    @JsonIgnore
    String passwordHash;

    /**
     * 用户组织
     */
    UUID orgId;

    /**
     * 用户状态码
     * - 0：离线
     * - 1：在线
     * - null：已删除
     */
    Integer statusCode;

    /**
     * token版本号
     */
    Integer tokenVersion;

    @JsonIgnore
    List<String> roles;
}
