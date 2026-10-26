-- New approved rules remove statistical versions. V16 remains immutable.
ALTER TABLE template_stage DROP COLUMN statistics_version;
ALTER TABLE template_stage RENAME COLUMN instructions_html TO instructions;
ALTER TABLE template_stage ADD CONSTRAINT stage_interval_whole_seconds
    CHECK (penalty_interval_ms IS NULL OR penalty_interval_ms % 1000 = 0);
ALTER TABLE template_stage ADD CONSTRAINT stage_code_hash_not_blank
    CHECK (code_hash IS NULL OR length(btrim(code_hash)) > 0);

-- Old READY rows were never validated by 4b (no READY API existed in 4a).
-- Do not certify them under the new AND/readiness rules during migration.
INSERT INTO audit_event(id,occurred_at,actor_kind,actor_id,event_type,target_id,target_type,metadata)
SELECT gen_random_uuid(),CURRENT_TIMESTAMP,'SYSTEM',NULL,'TEMPLATE_DRAFTED',id,'TEMPLATE',
       '{"reason":"READINESS_RULES_4B"}'::jsonb
FROM race_template WHERE status='READY';
UPDATE race_template SET status='DRAFT',version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE status='READY';
