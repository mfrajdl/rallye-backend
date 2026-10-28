-- No production/user instruction or image content exists. Discard the explicitly disposable test content.
DELETE FROM template_stage_image;
DELETE FROM template_image;
DELETE FROM admin_role_command WHERE operation='TEMPLATE_IMAGE_CREATE';
INSERT INTO audit_event(id,occurred_at,actor_kind,event_type,target_id,target_type,metadata)
SELECT gen_random_uuid(),CURRENT_TIMESTAMP,'SYSTEM','TEMPLATE_DRAFTED',id,'TEMPLATE',
       '{"reason":"MARKDOWN_CONTRACT"}'::jsonb FROM race_template WHERE status='READY';
UPDATE race_template SET status='DRAFT',version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE status='READY' OR EXISTS (SELECT 1 FROM template_stage s WHERE s.race_template_id=race_template.id AND s.instructions_html IS NOT NULL);
UPDATE template_stage SET instructions_html=NULL;
-- DELETE on template_image queues the V18 deferred image FK check, even after
-- all usage rows were deleted. The stage UPDATE also queues its deferred order
-- check. Execute pending constraint checks before ALTER TABLE, in the same atomic
-- Flyway transaction; otherwise PostgreSQL rejects DDL with SQLSTATE 55006.
-- There is no further DML below; no triggers or integrity checks are disabled.
SET CONSTRAINTS ALL IMMEDIATE;
ALTER TABLE template_stage RENAME COLUMN instructions_html TO instructions_markdown;
ALTER TABLE template_stage RENAME CONSTRAINT stage_html_size TO stage_markdown_size;
ALTER TABLE template_image ADD COLUMN file_name varchar(100) NOT NULL;
ALTER TABLE template_image ADD COLUMN name_key text NOT NULL;
ALTER TABLE template_image ADD CONSTRAINT template_image_name_unique UNIQUE(race_template_id,name_key);
ALTER TABLE template_image ADD CONSTRAINT template_image_name_size CHECK(octet_length(file_name)<=200 AND length(file_name)>0 AND length(name_key)>0);
