package com.fwdrobo.sirius.security;

import com.fwdrobo.sirius.entity.FwdUser;
import com.fwdrobo.sirius.entity.job.JobRun;
import com.fwdrobo.sirius.entity.job.RunStatus;
import com.fwdrobo.sirius.mapper.JobRunMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Fixed-snapshot worker I/O only; ordinary controller authorization still runs. */
@Component
public class JobIoScope {
    private final JobRunMapper runs;

    public JobIoScope(JobRunMapper runs) { this.runs = runs; }

    public boolean permits(HttpServletRequest request, Claims claims, FwdUser user) {
        try {
            Map<?, ?> scope = claims.get("job_io_scope", Map.class);
            if (scope == null || !Objects.equals(user.getStatusCode(), 1)) return false;
            UUID jobId = id(scope, "jobId"), runId = id(scope, "runId");
            if (!user.getUserId().equals(id(scope, "userId")) || !user.getOrgId().equals(id(scope, "orgId"))) return false;
            JobRun run = runs.selectByRunId(runId).orElse(null);
            if (run == null || !runId.equals(run.getRunId()) || !jobId.equals(run.getJobId()) || !user.getUserId().equals(run.getCreatedBy())
                    || !user.getOrgId().equals(run.getOrgId())
                    || !(run.getRunStatus() == RunStatus.QUEUED || run.getRunStatus() == RunStatus.RUNNING)) return false;
            String input = files(id(scope, "inputRepoId"), id(scope, "inputCommitId"));
            String output = files(id(scope, "outputRepoId"), id(scope, "outputCommitId"));
            String uri = request.getRequestURI();
            String context = request.getContextPath();
            if (context != null && !context.isEmpty()) uri = uri.substring(context.length());
            if ("GET".equals(request.getMethod())) {
                if (uri.equals(input) || uri.equals(input + "/content")
                        || uri.equals("/api/jobs/" + jobId + "/entrypoint/content")
                        || uri.equals("/api/jobs/internal/script/command")) return true;
                Object previous = scope.get("previousIndexCommitId");
                if (previous == null) return false;
                String previousFiles = files(id(scope, "outputRepoId"), id(scope, "previousIndexCommitId"));
                return uri.equals(previousFiles) || uri.equals(previousFiles + "/content");
            }
            return "PUT".equals(request.getMethod()) && uri.equals(output);
        } catch (IllegalArgumentException | NullPointerException | ClassCastException exception) {
            return false;
        }
    }

    private static UUID id(Map<?, ?> scope, String name) { return UUID.fromString((String) scope.get(name)); }
    private static String files(UUID repo, UUID commit) { return "/api/artifacts/" + repo + "/commits/" + commit + "/files"; }
}
