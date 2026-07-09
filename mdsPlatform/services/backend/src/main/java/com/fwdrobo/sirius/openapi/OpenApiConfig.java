package com.fwdrobo.sirius.openapi;

import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sirius-Platform")  // 设置文档的标题
                        .version("v1.0")  // 设置版本
                        .description("""
                                    1、登录拿 Token
                                        调用 POST /api/auth/login，从返回结果里复制 token。
                                    2、在页面里绑定 Token
                                        点击页面右上角 Authorize。
                                    3、粘贴并授权
                                        在弹窗里把刚复制的 token 粘贴进去，点击 Authorize（或确认按钮）。
                                    完成后，这个 Token 会自动带到后续所有 API 请求里（等同于已登录状态）。
                                """)
                )
                // 认证 Header 配置
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
