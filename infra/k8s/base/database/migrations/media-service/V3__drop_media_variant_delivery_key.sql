ALTER TABLE media_variants
    DROP KEY uq_media_variants_delivery_key,
    DROP COLUMN delivery_key;
