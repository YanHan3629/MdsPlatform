package com.fwdrobo.sirius.security;

import org.springframework.stereotype.Component;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Recognize the immutable historical seed, without copying or logging its secret. */
@Component
public final class SeedCredentialPolicy {
    private final Set<String> historicalHashes;
    private final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder();
    public SeedCredentialPolicy() {
        try(var stream=SeedCredentialPolicy.class.getResourceAsStream("/db/migration/V1__init_schema.sql")) {
            if(stream==null) throw new IllegalStateException("Historical credential policy resource unavailable");
            String text=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            var matcher=Pattern.compile("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}").matcher(text);
            var hashes=new HashSet<String>();while(matcher.find())hashes.add(matcher.group());
            if(hashes.isEmpty())throw new IllegalStateException("Historical credential policy not established");
            historicalHashes=Set.copyOf(hashes);
        } catch(java.io.IOException exception) { throw new IllegalStateException("Credential policy read failed",exception); }
    }
    public boolean isKnownSeedHash(String hash) {
        return hash!=null&&historicalHashes.contains(hash.startsWith("{bcrypt}")?hash.substring(8):hash);
    }
    public boolean isKnownSeedPassword(String raw) {
        if(raw==null) return false;
        for(String hash:historicalHashes) if(bcrypt.matches(raw,hash))return true;
        return false;
    }
}
