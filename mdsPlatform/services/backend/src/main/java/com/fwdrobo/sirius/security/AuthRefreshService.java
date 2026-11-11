package com.fwdrobo.sirius.security;
import org.springframework.stereotype.Service;
import java.util.Map;
@Service
public class AuthRefreshService {
    private final IdentitySessionService identity;
    public AuthRefreshService(IdentitySessionService identity){this.identity=identity;}
    public Map<String,Object> login(String username,String password){return identity.login(username,password);}
    public Map<String,Object> refresh(String token){return identity.refresh(token);}
}
