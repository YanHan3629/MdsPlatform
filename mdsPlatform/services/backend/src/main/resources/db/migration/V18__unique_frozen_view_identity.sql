-- A frozen physical view has one unambiguous authorization identity.
-- Existing duplicate records cause a controlled migration failure; no record is deleted.
CREATE UNIQUE INDEX uq_controlled_view_frozen_identity
 ON ds_controlled_view(dataset_id,version_id,index_version_id)
 WHERE dataset_id IS NOT NULL AND version_id IS NOT NULL AND index_version_id IS NOT NULL;
