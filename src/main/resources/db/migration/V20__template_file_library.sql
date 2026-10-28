-- Extend the existing shared library without moving existing image files or IDs.
ALTER TABLE template_image ALTER COLUMN content_type TYPE varchar(64);
ALTER TABLE template_image DROP CONSTRAINT template_image_content_type_check;
ALTER TABLE template_image DROP CONSTRAINT template_image_byte_size_check;
ALTER TABLE template_image DROP CONSTRAINT template_image_width_check;
ALTER TABLE template_image DROP CONSTRAINT template_image_height_check;
ALTER TABLE template_image ALTER COLUMN width DROP NOT NULL;
ALTER TABLE template_image ALTER COLUMN height DROP NOT NULL;
-- Immutable UTF-8 Markdown snapshot enables reference checks under the parent DB lock,
-- without taking a filesystem lock after database locks or recursive document loading.
ALTER TABLE template_image ADD COLUMN markdown_source text;
ALTER TABLE template_image ADD CONSTRAINT template_file_content CHECK (
 (content_type IN ('image/jpeg','image/png') AND byte_size BETWEEN 1 AND 5242880
  AND width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096
  AND width IS NOT NULL AND height IS NOT NULL AND markdown_source IS NULL)
 OR (content_type='text/markdown' AND byte_size BETWEEN 0 AND 1048576
  AND width IS NULL AND height IS NULL AND markdown_source IS NOT NULL
  AND octet_length(markdown_source)=byte_size)
 OR (content_type='application/pdf' AND byte_size BETWEEN 1 AND 52428800
  AND width IS NULL AND height IS NULL AND markdown_source IS NULL)
);

-- Re-check previously READY templates against newly active ordinary local links.
-- SQL must not guess Markdown AST semantics; preserve all content/configuration and
-- require explicit readiness confirmation under the new complete-reference rules.
INSERT INTO audit_event(id,occurred_at,actor_kind,event_type,target_id,target_type,metadata)
SELECT gen_random_uuid(),CURRENT_TIMESTAMP,'SYSTEM','TEMPLATE_DRAFTED',id,'TEMPLATE',
       '{"reason":"FILE_LIBRARY_REFERENCE_VALIDATION"}'::jsonb
FROM race_template WHERE status='READY';
UPDATE race_template SET status='DRAFT',version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE status='READY';
