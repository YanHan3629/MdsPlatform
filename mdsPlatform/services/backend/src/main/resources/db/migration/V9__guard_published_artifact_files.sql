SET search_path TO public;

-- Serialize every file metadata mutation with commit publication. A prepared
-- upload can never be inserted after the snapshot's DRAFT -> PUBLISHED switch.
CREATE OR REPLACE FUNCTION public.guard_published_artifact_file()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_commit UUID;
    current_status VARCHAR(16);
BEGIN
    IF TG_OP = 'DELETE' THEN
        target_commit := OLD.commit_id;
    ELSE
        target_commit := NEW.commit_id;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.commit_id <> OLD.commit_id THEN
        RAISE EXCEPTION 'artifact file cannot move between commits';
    END IF;

    SELECT commit_status INTO current_status
    FROM public.artifact_commit WHERE commit_id = target_commit FOR SHARE;
    IF current_status = 'PUBLISHED' THEN
        -- Existing pre-SHA snapshots may acquire a verified content identity
        -- without changing their logical path or underlying object key.
        IF TG_OP = 'UPDATE'
           AND OLD.upload_state = 'READY' AND NEW.upload_state = 'READY'
           AND OLD.logical_path = NEW.logical_path
           AND OLD.bucket_name = NEW.bucket_name
           AND OLD.file_key = NEW.file_key
           AND OLD.device_id IS NOT DISTINCT FROM NEW.device_id
           AND (OLD.sha256 IS NULL OR btrim(OLD.sha256) = '')
           AND NEW.sha256 ~ '^[a-fA-F0-9]{64}$' THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'published artifact file is immutable: %', target_commit;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_guard_published_artifact_file ON public.artifact_file;
CREATE TRIGGER trg_guard_published_artifact_file
BEFORE INSERT OR UPDATE OR DELETE ON public.artifact_file
FOR EACH ROW EXECUTE FUNCTION public.guard_published_artifact_file();
