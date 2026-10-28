ALTER TABLE ds_receiver_endpoint ADD COLUMN receiver_public_key TEXT NOT NULL;
CREATE UNIQUE INDEX ds_receiver_org_name ON ds_receiver_endpoint(consumer_org_id,endpoint_name);
ALTER TABLE ds_delivery_task ADD COLUMN package_manifest JSONB;
ALTER TABLE ds_delivery_task ADD COLUMN package_manifest_digest CHAR(64);
CREATE FUNCTION ds_delivery_keep_manifest() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.package_manifest IS NOT NULL AND (NEW.package_manifest IS DISTINCT FROM OLD.package_manifest OR NEW.package_manifest_digest IS DISTINCT FROM OLD.package_manifest_digest) THEN
        RAISE EXCEPTION 'Delivery manifests are immutable within a contract revision';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ds_delivery_manifest_frozen BEFORE UPDATE ON ds_delivery_task
    FOR EACH ROW EXECUTE FUNCTION ds_delivery_keep_manifest();
