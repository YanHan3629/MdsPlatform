package com.fwdrobo.sirius.dataspace;

import com.fwdrobo.sirius.security.AuthorizationService;
import com.fwdrobo.sirius.security.GenericFileOriginService;
import com.fwdrobo.sirius.service.ManifestService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** In-process read authority for one leased contract delivery. */
@Service
public class ControlledDeliveryReadScope {
    private final JdbcTemplate jdbc;
    private final AuthorizationService auth;
    private final ManifestService manifests;
    private final ObjectProvider<GenericFileOriginService> origins;
    private final ThreadLocal<Lease> current=new ThreadLocal<>();

    public ControlledDeliveryReadScope(JdbcTemplate jdbc,AuthorizationService auth,ManifestService manifests,
                                      ObjectProvider<GenericFileOriginService> origins) {
        this.jdbc=jdbc;
        this.auth=auth;
        this.manifests=manifests;
        this.origins=origins;
    }

    public final class Lease implements AutoCloseable {
        private final Thread thread=Thread.currentThread();
        private final UUID repo,commit,dataset,version,index,service,consumerOrg;
        private final Runnable authority;
        private final Set<UUID> files;
        private boolean closed;

        private Lease(UUID repo,UUID commit,UUID dataset,UUID version,UUID index,UUID service,UUID consumerOrg,
                      Runnable authority,Set<UUID> files) {
            this.repo=repo;
            this.commit=commit;
            this.dataset=dataset;
            this.version=version;
            this.index=index;
            this.service=service;
            this.consumerOrg=consumerOrg;
            this.authority=authority;
            this.files=Set.copyOf(files);
        }

        private void validate() {
            if(closed||thread!=Thread.currentThread()||current.get()!=this)deny();
            authority.run();
            var lineage=origins.getObject();
            boolean currentInputs=service==null
                ?lineage.legacyDeliveryInputsCurrent(dataset,version,consumerOrg)
                :lineage.datasetInputsCurrent(dataset,version,index,Set.of("data.view.export"),
                    "CONTROLLED_DELIVERY",auth.serviceActorFor(service));
            if(!currentInputs)deny();
        }

        @Override public void close() {
            if(thread!=Thread.currentThread()||current.get()!=this)
                throw new IllegalStateException("DELIVERY_READ_SCOPE_ORDER");
            closed=true;
            current.remove();
        }
    }

    // This factory is package-private and has no credential or HTTP entry point.
    Lease open(UUID contract,int revision,Runnable authority) throws Exception {
        if(current.get()!=null||RequestContextHolder.getRequestAttributes()!=null)deny();
        authority.run();
        var rows=jdbc.queryForList("""
            SELECT d.raw_repo_id,p.raw_commit_id,p.dataset_id,p.version_id,p.index_version_id,plan.delivery_service_id,c.consumer_org_id
            FROM ds_contract c JOIN ds_product_version p ON p.product_version_id=c.product_version_id
            JOIN mm_dataset d ON d.dataset_id=p.dataset_id AND d.org_id=c.provider_org_id
            JOIN mm_dataset_version v ON v.dataset_id=d.dataset_id AND v.version_id=p.version_id AND v.raw_commit_id=p.raw_commit_id
            JOIN artifact_commit raw ON raw.repo_id=d.raw_repo_id AND raw.commit_id=p.raw_commit_id AND raw.commit_status='PUBLISHED'
            LEFT JOIN ds_contract_controlled_revision plan ON plan.contract_id=c.contract_id AND plan.revision=c.revision
            WHERE c.contract_id=? AND c.revision=?
              AND ((c.scope_type='CONTROLLED_VIEW' AND plan.delivery_service_id IS NOT NULL)
                   OR (c.scope_type='FULL_VERSION' AND plan.contract_id IS NULL))
            """,contract,revision);
        if(rows.size()!=1)deny();
        var row=rows.get(0);
        UUID repo=(UUID)row.get("raw_repo_id"),commit=(UUID)row.get("raw_commit_id");
        Set<UUID> fileIds=manifests.getManifest(repo,commit).getFiles().stream()
            .map(file->file.getFileId()).collect(Collectors.toUnmodifiableSet());
        var lease=new Lease(repo,commit,(UUID)row.get("dataset_id"),(UUID)row.get("version_id"),
            (UUID)row.get("index_version_id"),(UUID)row.get("delivery_service_id"),
            (UUID)row.get("consumer_org_id"),authority,fileIds);
        current.set(lease);
        try {
            lease.validate();
            return lease;
        } catch(RuntimeException denied) {
            lease.close();
            throw denied;
        }
    }

    public boolean active() {return current.get()!=null;}

    public void requireArtifact(UUID repo,UUID commit,boolean write) {
        var lease=current.get();
        if(lease==null||write||!lease.repo.equals(repo)||!lease.commit.equals(commit))deny();
        lease.validate();
    }

    public void requireFile(UUID file) {
        var lease=current.get();
        if(lease==null||!lease.files.contains(file))deny();
        lease.validate();
    }

    private static void deny() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"DELIVERY_FROZEN_READ_SCOPE_REQUIRED");
    }
}
