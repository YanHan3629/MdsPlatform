package com.fwdrobo.sirius.dto.permission;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrgReq {
    private String orgName;
    private Integer orgStatus;
    private String licenseType;
}
