package com.fwdrobo.sirius.dto.user;

public record PasswordResetReq(
        
        /**
         * 旧的密码
         */
        String oldPassword,
    
        /**
         * 新的密码
         */
        String newPassword

        // TODO: 未来可能改变为通过邮箱或短信验证码重置密码
    ) {}