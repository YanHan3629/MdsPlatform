-- Older tasks have no verifiable session and may be requeued by an authorized request.
ALTER TABLE public.mm_ingest_task ADD COLUMN origin_session_id uuid;
