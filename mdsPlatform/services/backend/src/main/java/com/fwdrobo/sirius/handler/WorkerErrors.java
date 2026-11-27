package com.fwdrobo.sirius.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.regex.Pattern;

/** Retain typed worker failures without exposing exception text or upstream HTML. */
public final class WorkerErrors {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("^([A-Z][A-Z0-9_]{2,95})(?:$|:)");

    private WorkerErrors() {}

    public static ResponseStatusException from(HttpStatusCode status, byte[] body) {
        String reason = "WORKER_HTTP_" + status.value();
        if (body != null && body.length <= 32768) {
            try {
                var payload = JSON.readTree(body);
                if (payload != null) {
                    var detail = payload.path("errorType").asText(payload.path("detail").asText(""));
                    var code = CODE.matcher(detail);
                    if (code.find()) reason = code.group(1);
                }
            } catch (IOException ignored) {
                // A gateway can emit a non-JSON error response.
            }
        }
        return new ResponseStatusException(status, reason);
    }
}
