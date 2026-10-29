-- CODE is administrative configuration, not an authentication secret. Old hashes are evidence only.
ALTER TABLE template_stage ADD COLUMN code_value varchar(256);
ALTER TABLE event_stage ADD COLUMN code_value varchar(256);
ALTER TABLE template_stage ADD CONSTRAINT template_code_value_valid CHECK(code_value IS NULL OR (length(code_value)>0 AND length(code_value)<=128));
ALTER TABLE event_stage ADD CONSTRAINT event_code_value_valid CHECK(code_value IS NULL OR (length(code_value)>0 AND length(code_value)<=128));
ALTER TABLE template_stage ALTER COLUMN max_penalty_points SET DEFAULT 0;
ALTER TABLE event_stage ALTER COLUMN max_penalty_points SET DEFAULT 0;
ALTER TABLE race_template ADD COLUMN default_wrong_code_penalty_points bigint NOT NULL DEFAULT 0;
ALTER TABLE race_event ADD COLUMN default_wrong_code_penalty_points bigint NOT NULL DEFAULT 0;
ALTER TABLE race_event ADD COLUMN source_template_name varchar(100);
ALTER TABLE race_event ADD COLUMN source_template_name_is_snapshot boolean NOT NULL DEFAULT true;
-- V21 did not store historical names; label the backfill honestly, never claim it is an original snapshot.
UPDATE race_event e SET source_template_name=t.name,source_template_name_is_snapshot=false FROM race_template t WHERE t.id=e.source_template_id;
ALTER TABLE race_event ALTER COLUMN source_template_name SET NOT NULL;
-- Preserve all original hashes, overrides and event configuration. No guessed CODE values.
INSERT INTO audit_event(id,occurred_at,actor_kind,event_type,target_id,target_type,metadata)
SELECT gen_random_uuid(),CURRENT_TIMESTAMP,'SYSTEM','TEMPLATE_DRAFTED',id,'TEMPLATE','{"reason":"CODE_VALUE_REQUIRED"}'::jsonb
FROM race_template t WHERE status='READY' AND EXISTS(SELECT 1 FROM template_stage s WHERE s.race_template_id=t.id AND s.code_enabled AND s.code_value IS NULL);
UPDATE race_template t SET status='DRAFT',version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE status='READY' AND EXISTS(SELECT 1 FROM template_stage s WHERE s.race_template_id=t.id AND s.code_enabled AND s.code_value IS NULL);
