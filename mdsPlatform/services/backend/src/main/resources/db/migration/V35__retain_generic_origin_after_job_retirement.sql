-- Source snapshots carry their exact immutable definition hash and bindings.
-- Retiring a job must not delete source audit or make its published bytes public.
ALTER TABLE ds_generic_execution_origin
 DROP CONSTRAINT ds_generic_execution_origin_run_id_fkey;
