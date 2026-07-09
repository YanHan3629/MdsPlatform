package com.fwdrobo.sirius.dto.permission;

import com.fwdrobo.sirius.dto.user.UserInfo;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 组织详情响应。
 */
@Getter
@Setter
public class OrgResponse {
    private UUID orgId;
    private String orgName;
    private Integer orgStatus;
    private String licenseType;
    private OffsetDateTime createdAt;
    private UserInfo createdBy;
    private OffsetDateTime updatedAt;
    private UserInfo updatedBy;

}
