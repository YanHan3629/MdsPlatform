-- Ensure historical databases keep non-null count columns with sane defaults.
UPDATE public.mm_index_version
SET image_count = 0
WHERE image_count IS NULL;

UPDATE public.mm_index_version
SET text_count = 0
WHERE text_count IS NULL;

ALTER TABLE public.mm_index_version
    ALTER COLUMN image_count SET DEFAULT 0,
    ALTER COLUMN text_count SET DEFAULT 0,
    ALTER COLUMN image_count SET NOT NULL,
    ALTER COLUMN text_count SET NOT NULL;
