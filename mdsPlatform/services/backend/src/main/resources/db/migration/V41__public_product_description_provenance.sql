-- Older products do not record whether their description came from the private catalogue.
-- Preserve the stored text for authorized publishers and require an explicit public publication before exposing it.
ALTER TABLE ds_product
    ADD COLUMN public_description_explicit BOOLEAN NOT NULL DEFAULT FALSE;
