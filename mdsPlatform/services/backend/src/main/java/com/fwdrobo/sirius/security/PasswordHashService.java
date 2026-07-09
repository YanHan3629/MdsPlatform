package com.fwdrobo.sirius.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 管理密码编码器的统一入口：对外暴露一个 DelegatingPasswordEncoder 供 matches，
 * encode 时统一使用 BCrypt 算法。
 */
@Component
public class PasswordHashService {
    private final DelegatingPasswordEncoder delegatingPasswordEncoder;
    private final Map<String, PasswordEncoder> encoders = new HashMap<>();

    public PasswordHashService() {
        encoders.put("bcrypt", new BCryptPasswordEncoder(10));

        delegatingPasswordEncoder = new DelegatingPasswordEncoder("bcrypt", encoders);
        delegatingPasswordEncoder.setDefaultPasswordEncoderForMatches(encoders.get("bcrypt"));
    }

    /**
     * 获取用于密码匹配的 DelegatingPasswordEncoder
     */
    public PasswordEncoder delegatingPasswordEncoder() {
        return delegatingPasswordEncoder;
    }

    /**
     * 对密码进行编码
     */
    public String encode(String rawPassword) {
        return delegatingPasswordEncoder.encode(rawPassword);
    }
}
