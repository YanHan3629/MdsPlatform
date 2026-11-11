CREATE INDEX ds_authorization_audit_time ON ds_authorization_audit(created_at DESC,event_id DESC);
CREATE INDEX ds_authorization_audit_target_time ON ds_authorization_audit(target_id,created_at DESC,event_id DESC);
