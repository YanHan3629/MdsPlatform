package com.fwdrobo.sirius.entity.permission;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
public class PermissionTree {
    private UUID perId;
    private String perCode;
    private String perName;
    private String category; // feature/operation
    private String description;

    private boolean checked;        // 是否选中
    private boolean indeterminate;  // 是否半选（feature 才用）

    private List<PermissionTree> children = new ArrayList<>();
}