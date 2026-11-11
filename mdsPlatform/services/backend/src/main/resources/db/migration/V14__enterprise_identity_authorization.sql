-- Feature 04: explicit identity, operation and frozen-resource authorization.
-- Schema only; legacy administrator qualification is reviewed by maintenance CLI.
-- Existing accounts, passwords, immutable snapshots and contracts are not rewritten.
ALTER TABLE fwd_user ADD COLUMN account_state varchar(16) NOT NULL DEFAULT 'ACTIVE'
  CHECK (account_state IN ('PENDING','ACTIVE','DISABLED','CLOSED'));
ALTER TABLE fwd_user ADD COLUMN permission_version bigint NOT NULL DEFAULT 1 CHECK(permission_version>0);
ALTER TABLE fwd_user ADD COLUMN credential_activated_at timestamptz;
ALTER TABLE fwd_user ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;
ALTER TABLE fwd_user ADD COLUMN last_login_at timestamptz;
UPDATE fwd_user SET account_state='CLOSED' WHERE status_code IS NULL;
ALTER TABLE org ADD COLUMN permission_version bigint NOT NULL DEFAULT 1 CHECK(permission_version>0);
ALTER TABLE role ADD COLUMN type_state varchar(16) NOT NULL DEFAULT 'ACTIVE'
  CHECK(type_state IN ('ACTIVE','DISABLED','DELETED'));
ALTER TABLE role ADD COLUMN type_scope varchar(16) NOT NULL DEFAULT 'ENTERPRISE'
  CHECK(type_scope IN ('GLOBAL','ENTERPRISE'));
ALTER TABLE role ADD COLUMN type_version bigint NOT NULL DEFAULT 1 CHECK(type_version>0);
ALTER TABLE role ADD COLUMN description text NOT NULL DEFAULT '';
ALTER TABLE role ADD COLUMN industry_tags jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(industry_tags)='array');
ALTER TABLE role ADD COLUMN applicable_org_ids uuid[] NOT NULL DEFAULT '{}';
ALTER TABLE role ADD COLUMN read_only_profile boolean NOT NULL DEFAULT false;
ALTER TABLE user_role ADD COLUMN is_primary boolean NOT NULL DEFAULT false;
UPDATE user_role ur SET is_primary=true WHERE (ur.user_id,ur.role_id) IN
 (SELECT DISTINCT ON (user_id) user_id,role_id FROM user_role ORDER BY user_id,created_at,role_id);
CREATE UNIQUE INDEX ds_one_primary_business_type ON user_role(user_id) WHERE is_primary;

CREATE TABLE ds_auth_initialization(
 singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
 initialized boolean NOT NULL DEFAULT false,
 maintenance_mode boolean NOT NULL DEFAULT true,
 legacy_review_completed boolean NOT NULL DEFAULT false,
 policy_version bigint NOT NULL DEFAULT 1 CHECK(policy_version>0),
 initialized_at timestamptz,initialized_by uuid REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 updated_at timestamptz NOT NULL DEFAULT now());
INSERT INTO ds_auth_initialization(singleton) VALUES(true);
CREATE TABLE ds_operation(
 operation_code varchar(80) PRIMARY KEY,
 group_code varchar(32) NOT NULL,
 label varchar(160) NOT NULL,
 is_mutation boolean NOT NULL,
 permission_id uuid UNIQUE REFERENCES permission(per_id) ON DELETE RESTRICT,
 CHECK(operation_code ~ '^[a-z][a-z0-9._-]{2,79}$'));
CREATE TABLE ds_management_membership(
 user_id uuid NOT NULL REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 qualification varchar(32) NOT NULL CHECK(qualification IN('PLATFORM_ADMIN','ENTERPRISE_MANAGER')),
 active boolean NOT NULL DEFAULT true,
 delegated_type_ids uuid[] NOT NULL DEFAULT '{}',
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 granted_by uuid REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 granted_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(user_id,qualification));
CREATE TABLE ds_operation_deny(
 subject_kind varchar(8) NOT NULL CHECK(subject_kind IN('USER','ROLE')),
 subject_id uuid NOT NULL,
 operation_code varchar(80) NOT NULL REFERENCES ds_operation(operation_code) ON DELETE RESTRICT,
 reason text NOT NULL,
 created_by uuid NOT NULL REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(subject_kind,subject_id,operation_code));
CREATE TABLE ds_enterprise_identity(
 mapping_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 org_id uuid NOT NULL UNIQUE REFERENCES org(org_id) ON DELETE RESTRICT,
 entity_id varchar(128) NOT NULL,
 enterprise_name varchar(256) NOT NULL,
 industry_kind varchar(32) NOT NULL CHECK(industry_kind IN('MANUFACTURER','SUPPLIER','WAREHOUSE','CARRIER','CUSTOMER','SERVICE')),
 identity_kind varchar(16) NOT NULL CHECK(identity_kind IN('BUSINESS','TEST')),
 state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN('PENDING','CONFIRMED','DISABLED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 source_dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id) ON DELETE RESTRICT,
 source_version_id uuid NOT NULL REFERENCES mm_dataset_version(version_id) ON DELETE RESTRICT,
 source_sha256 char(64) NOT NULL,
 confirmation_reason text,
 confirmed_by uuid REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 confirmed_at timestamptz,
 CHECK(state<>'CONFIRMED' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)));
CREATE TABLE ds_auth_session(
 session_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 token_version integer NOT NULL,
 refresh_id uuid NOT NULL UNIQUE,
 refresh_generation bigint NOT NULL DEFAULT 1 CHECK(refresh_generation>0),
 purpose varchar(16) NOT NULL CHECK(purpose IN('ACCESS','ACTIVATION')),
 issued_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz NOT NULL,
 revoked_at timestamptz,
 revoke_reason varchar(80),
 CHECK(expires_at>issued_at));
CREATE INDEX ds_auth_session_actor ON ds_auth_session(user_id,revoked_at,expires_at);
CREATE TABLE ds_auth_refresh_consumed(
 refresh_id uuid PRIMARY KEY,
 session_id uuid NOT NULL REFERENCES ds_auth_session(session_id) ON DELETE RESTRICT,
 consumed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE ds_service_subject(
 service_subject_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 org_id uuid NOT NULL REFERENCES org(org_id) ON DELETE RESTRICT,
 purpose varchar(32) NOT NULL CHECK(purpose IN('SCHEDULED_DELIVERY','VIEW_BUILD')),
 active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by uuid NOT NULL REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE ds_resource_grant(
 grant_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 subject_kind varchar(16) NOT NULL CHECK(subject_kind IN('USER','ORG','SERVICE')),
 subject_id uuid NOT NULL,
 owner_org_id uuid NOT NULL REFERENCES org(org_id) ON DELETE RESTRICT,
 scope_kind varchar(24) NOT NULL CHECK(scope_kind IN('DATASET_CUSTODY','FROZEN_VERSION','CONTROLLED_VIEW')),
 dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id) ON DELETE RESTRICT,
 version_id uuid REFERENCES mm_dataset_version(version_id) ON DELETE RESTRICT,
 index_version_id uuid REFERENCES mm_index_version(index_version_id) ON DELETE RESTRICT,
 operation_codes varchar(80)[] NOT NULL CHECK(cardinality(operation_codes)>0),
 contract_id uuid REFERENCES ds_contract(contract_id) ON DELETE RESTRICT,
 valid_from timestamptz NOT NULL DEFAULT now(),valid_to timestamptz,
 state varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK(state IN('ACTIVE','REVOKED','EXPIRED')),
 version bigint NOT NULL DEFAULT 1 CHECK(version>0),
 created_by uuid NOT NULL REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now(),
 revoked_at timestamptz,revoke_reason text,
 CHECK(scope_kind='DATASET_CUSTODY' OR (version_id IS NOT NULL AND index_version_id IS NOT NULL)),
 CHECK(valid_to IS NULL OR valid_from<valid_to));
CREATE INDEX ds_resource_grant_lookup ON ds_resource_grant(subject_kind,subject_id,dataset_id,state);
CREATE TABLE ds_controlled_view(
 view_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 owner_org_id uuid NOT NULL REFERENCES org(org_id) ON DELETE RESTRICT,
 source_dataset_id uuid NOT NULL REFERENCES mm_dataset(dataset_id) ON DELETE RESTRICT,
 source_version_id uuid NOT NULL REFERENCES mm_dataset_version(version_id) ON DELETE RESTRICT,
 source_raw_commit_id uuid NOT NULL REFERENCES artifact_commit(commit_id) ON DELETE RESTRICT,
 dataset_id uuid REFERENCES mm_dataset(dataset_id) ON DELETE RESTRICT,
 version_id uuid REFERENCES mm_dataset_version(version_id) ON DELETE RESTRICT,
 index_version_id uuid REFERENCES mm_index_version(index_version_id) ON DELETE RESTRICT,
 rule_version varchar(80) NOT NULL,
 rule_manifest_sha256 char(64) NOT NULL,
 private_rule_manifest jsonb NOT NULL,
 public_scope_summary jsonb NOT NULL,
 state varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(state IN('DRAFT','BUILDING','READY','REVOKED','FAILED')),
 authorization_version bigint NOT NULL DEFAULT 1 CHECK(authorization_version>0),
 created_by uuid NOT NULL REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(state<>'READY' OR (dataset_id IS NOT NULL AND version_id IS NOT NULL AND index_version_id IS NOT NULL)));
CREATE TABLE ds_contract_operation(
 contract_id uuid NOT NULL REFERENCES ds_contract(contract_id) ON DELETE RESTRICT,
 operation_code varchar(80) NOT NULL REFERENCES ds_operation(operation_code) ON DELETE RESTRICT,
 PRIMARY KEY(contract_id,operation_code));
CREATE TABLE ds_authorization_audit(
 event_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 actor_user_id uuid REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 actor_org_id uuid REFERENCES org(org_id) ON DELETE RESTRICT,
 actor_service_id uuid REFERENCES ds_service_subject(service_subject_id) ON DELETE RESTRICT,
 operation_code varchar(80) NOT NULL,
 target_kind varchar(40) NOT NULL,target_id varchar(160),
 outcome varchar(16) NOT NULL CHECK(outcome IN('ALLOW','DENY','SUCCESS','FAILURE')),
 request_id varchar(128) NOT NULL,
 scope jsonb NOT NULL DEFAULT '{}'::jsonb,
 before_value jsonb,after_value jsonb,
 reason_code varchar(80) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX ds_authorization_audit_scope ON ds_authorization_audit(actor_org_id,created_at,event_id);
CREATE FUNCTION ds_admin_change_lock() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
 PERFORM singleton FROM ds_auth_initialization WHERE singleton FOR UPDATE;
 IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $body$;
CREATE FUNCTION ds_check_last_live_admin() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN
 IF (SELECT initialized FROM ds_auth_initialization WHERE singleton) AND NOT EXISTS(
  SELECT 1 FROM ds_management_membership m JOIN fwd_user u ON u.user_id=m.user_id
   JOIN org o ON o.org_id=u.org_id
  WHERE m.qualification='PLATFORM_ADMIN' AND m.active AND u.account_state='ACTIVE'
   AND u.status_code IS NOT NULL AND NOT u.password_change_required
   AND u.credential_activated_at IS NOT NULL AND u.password_hash<>'' AND o.org_status=1
 ) THEN
  RAISE EXCEPTION 'LAST_PLATFORM_ADMIN_REQUIRED' USING ERRCODE='23514',CONSTRAINT='ds_last_live_platform_admin';
 END IF;
 RETURN NULL;
END $body$;
DO $body$
DECLARE target text;
BEGIN
 FOREACH target IN ARRAY ARRAY['fwd_user','org','role','user_role','role_permission','ds_management_membership','ds_enterprise_identity'] LOOP
  EXECUTE format('CREATE TRIGGER ds_admin_lock BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION ds_admin_change_lock()',target);
  EXECUTE format('CREATE CONSTRAINT TRIGGER ds_last_admin AFTER INSERT OR UPDATE OR DELETE ON %I DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ds_check_last_live_admin()',target);
 END LOOP;
END $body$;
CREATE CONSTRAINT TRIGGER ds_initialized_admin AFTER UPDATE ON ds_auth_initialization
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ds_check_last_live_admin();
CREATE FUNCTION ds_audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $body$
BEGIN RAISE EXCEPTION 'AUTHORIZATION_AUDIT_APPEND_ONLY' USING ERRCODE='55000'; END $body$;
CREATE TRIGGER ds_audit_no_mutation BEFORE UPDATE OR DELETE ON ds_authorization_audit
 FOR EACH ROW EXECUTE FUNCTION ds_audit_append_only();
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('data.catalog.read','data','View authorized catalog',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('data.preview','data','Preview authorized content',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('data.search','data','Search authorized content',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('data.qa','data','Answer with authorized evidence',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('data.raw.download','data','Download complete authorized originals',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('data.view.export','data','Export an authorized materialized view',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('dataset.create','supply','Create datasets',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('dataset.upload','supply','Initiate resume and complete uploads',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('dataset.draft.manage','supply','Manage draft metadata',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('dataset.publish','supply','Publish immutable raw versions',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('dataset.quality.run','supply','Run quality checks',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('dataset.quality.read','supply','Read scoped quality details',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('dataset.index.build','supply','Build indexes',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('dataset.index.activate','supply','Activate a ready index',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('product.publish','product','Publish data products',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('product.unpublish','product','Delist data products',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('product.summary.manage','product','Edit explicit public summaries',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('application.submit','product','Submit own-enterprise consumption requests',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('application.review','product','Review own supply requests',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('contract.read','contract','Read own authorized contracts',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('contract.edit','contract','Draft own contract terms',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('contract.confirm','contract','Confirm on behalf of own enterprise',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('contract.revoke','contract','Revoke own contract grants',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('delivery.endpoint.manage','delivery','Manage own receiver endpoints',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('delivery.execute','delivery','Execute or retry explicitly authorized delivery',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('delivery.receipt.read','delivery','Read authorized receipts',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('delivery.package.download','delivery','Download authorized delivery packages',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('job.read','jobs','Read own scoped tasks',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('job.log.read','jobs','Read own scoped task logs',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('job.cancel','jobs','Cancel own authorized tasks',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.self.read','identity','Read own identity',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.self.password','identity','Change own password',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.self.logout','identity','Revoke own sessions',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.self.close','identity','Close own identity',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.member.read','identity','Read permitted enterprise members',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.member.create','identity','Create permitted enterprise members',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.member.update','identity','Edit permitted enterprise members',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.member.disable','identity','Disable or restore permitted members',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.member.reset','identity','Reset permitted member credentials',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.member.close','identity','Close permitted member identities',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.member.type.assign','identity','Assign permitted business types',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.type.manage','platform','Manage global business type definitions',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.platform-admin.manage','platform','Manage platform administrator qualifications',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.enterprise.manage','platform','Manage confirmed enterprise mappings',true);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('identity.audit.read','platform','Read permitted authorization audit',false);
INSERT INTO ds_operation(operation_code,group_code,label,is_mutation) VALUES('platform.runtime.manage','platform','Manage platform runtime without implied business reads',true);
-- System operation definitions are immutable; business type templates are not builtin.
INSERT INTO permission(per_id,per_code,per_name,category,is_builtin,created_by)
 SELECT gen_random_uuid(),operation_code,label,'operation',true,(SELECT user_id FROM fwd_user ORDER BY created_at,user_id LIMIT 1)
 FROM ds_operation ON CONFLICT(per_code) DO NOTHING;
UPDATE ds_operation d SET permission_id=p.per_id FROM permission p WHERE p.per_code=d.operation_code AND p.category='operation';
ALTER TABLE ds_operation ALTER COLUMN permission_id SET NOT NULL;
-- Preserve the explicit historic FULL_VERSION action semantics in a reviewable extension.
INSERT INTO ds_contract_operation(contract_id,operation_code)
 SELECT c.contract_id,o.operation_code FROM ds_contract c CROSS JOIN ds_operation o
 WHERE c.scope_type='FULL_VERSION' AND o.operation_code IN
 ('data.catalog.read','data.preview','data.search','data.qa','data.raw.download','data.view.export','delivery.package.download');

-- Every resource use also binds a server-validated purpose. No model may grant it.
ALTER TABLE ds_resource_grant ADD COLUMN purpose_codes varchar(40)[] NOT NULL DEFAULT ARRAY['GENERAL_READ']::varchar(40)[];
ALTER TABLE ds_resource_grant ADD COLUMN view_authorization_version bigint;
ALTER TABLE ds_contract_operation ADD COLUMN purpose_codes varchar(40)[] NOT NULL DEFAULT ARRAY['GENERAL_READ','CONTROLLED_DELIVERY']::varchar(40)[];
ALTER TABLE ds_resource_grant ADD COLUMN grantee_org_id uuid NOT NULL REFERENCES org(org_id) ON DELETE RESTRICT;
ALTER TABLE ds_auth_session ADD COLUMN org_id uuid NOT NULL REFERENCES org(org_id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX ds_reserved_username_casefold ON fwd_user(lower(user_name));

CREATE UNIQUE INDEX ds_one_confirmed_business_identity ON ds_enterprise_identity(entity_id)
 WHERE identity_kind='BUSINESS' AND state='CONFIRMED';

ALTER TABLE ds_auth_initialization ADD COLUMN initial_templates_installed boolean NOT NULL DEFAULT false;

-- Usernames remain reserved after rename or closure; tokens bind immutable IDs.
CREATE TABLE ds_reserved_username(
 normalized_name varchar(64) PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES fwd_user(user_id) ON DELETE RESTRICT,
 reserved_at timestamptz NOT NULL DEFAULT now(),
 CHECK(normalized_name=lower(normalized_name)));
INSERT INTO ds_reserved_username(normalized_name,user_id) SELECT lower(user_name),user_id FROM fwd_user;
