ALTER TABLE ds_contract DROP CONSTRAINT ds_contract_scope_type_check;
ALTER TABLE ds_contract ADD CONSTRAINT ds_contract_scope_type_check
    CHECK (scope_type IN ('FULL_VERSION', 'CONTROLLED_VIEW'));

CREATE TABLE ds_contract_controlled_revision (
    contract_id uuid NOT NULL,
    revision integer NOT NULL,
    view_id uuid NOT NULL REFERENCES ds_controlled_view(view_id),
    view_authorization_version bigint NOT NULL CHECK (view_authorization_version > 0),
    delivery_service_id uuid REFERENCES ds_service_subject(service_subject_id),
    delivery_service_version bigint CHECK (delivery_service_version > 0),
    delivery_grant_id uuid REFERENCES ds_resource_grant(grant_id),
    delivery_grant_version bigint CHECK (delivery_grant_version > 0),
    PRIMARY KEY (contract_id, revision),
    FOREIGN KEY (contract_id, revision) REFERENCES ds_contract_change(contract_id, revision),
    CHECK (num_nonnulls(delivery_service_id, delivery_service_version,
                        delivery_grant_id, delivery_grant_version) IN (0, 4))
);

CREATE FUNCTION ds_controlled_contract_scope_current(target_contract uuid, target_revision integer)
RETURNS boolean LANGUAGE sql AS $$
    SELECT EXISTS (
        SELECT 1 FROM ds_contract c
        JOIN ds_contract_controlled_revision plan ON plan.contract_id=c.contract_id AND plan.revision=c.revision
        JOIN ds_product_version p ON p.product_version_id=c.product_version_id
        JOIN ds_controlled_view cv ON cv.view_id=plan.view_id AND cv.dataset_id=p.dataset_id
            AND cv.version_id=p.version_id AND cv.index_version_id=p.index_version_id
            AND cv.owner_org_id=c.provider_org_id AND cv.authorization_version=plan.view_authorization_version
        JOIN org provider ON provider.org_id=c.provider_org_id AND provider.org_status=1
        JOIN org consumer ON consumer.org_id=c.consumer_org_id AND consumer.org_status=1
        WHERE c.contract_id=target_contract AND c.revision=target_revision AND c.scope_type='CONTROLLED_VIEW'
            AND cv.state='READY' AND controlled_view_publication_policy_current(p.dataset_id)
            AND NOT EXISTS (SELECT 1 FROM ds_enterprise_identity e
                WHERE e.org_id IN (c.provider_org_id, c.consumer_org_id) AND e.state<>'CONFIRMED')
    );
$$;
