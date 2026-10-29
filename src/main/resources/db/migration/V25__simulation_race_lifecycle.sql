-- Keep legacy state JSON and journal hashes byte-for-byte logically unchanged.
-- Legacy runs already allowed gameplay: infer an implicit start at virtual_start_at.
ALTER TABLE race_simulation ADD COLUMN race jsonb NOT NULL
 DEFAULT '{"status":"NOT_STARTED","startedAt":null,"endedAt":null}'::jsonb;
ALTER TABLE simulation_run ADD COLUMN initial_race jsonb NOT NULL
 DEFAULT '{"status":"NOT_STARTED","startedAt":null,"endedAt":null}'::jsonb;
UPDATE race_simulation SET race=jsonb_build_object('status','RUNNING','startedAt',coalesce(projection->'virtualStartAt',to_jsonb(virtual_start_at)),'endedAt',NULL);
UPDATE simulation_run SET initial_race=jsonb_build_object('status','RUNNING','startedAt',coalesce(initial_state->'virtualStartAt',to_jsonb(started_virtual_at)),'endedAt',NULL);
-- Flush any deferred FK events before subsequent DDL; do not disable integrity checks.
SET CONSTRAINTS ALL IMMEDIATE;
-- Require an object and all lifecycle keys; missing keys must not pass as SQL NULL.
ALTER TABLE race_simulation ADD CONSTRAINT simulation_race_lifecycle CHECK (
 jsonb_typeof(race)='object' AND race ?& ARRAY['status','startedAt','endedAt'] AND
 (CASE race->>'status'
 WHEN 'NOT_STARTED' THEN race->'startedAt'='null'::jsonb AND race->'endedAt'='null'::jsonb
 WHEN 'RUNNING' THEN jsonb_typeof(race->'startedAt')='string' AND race->'endedAt'='null'::jsonb
 WHEN 'ENDED' THEN jsonb_typeof(race->'endedAt')='string' AND
 (race->'startedAt'='null'::jsonb OR (jsonb_typeof(race->'startedAt')='string' AND (race->>'endedAt')::timestamptz >= (race->>'startedAt')::timestamptz))
 ELSE false END));
ALTER TABLE simulation_run ADD CONSTRAINT simulation_run_race_lifecycle CHECK (
 jsonb_typeof(initial_race)='object' AND initial_race ?& ARRAY['status','startedAt','endedAt'] AND
 (CASE initial_race->>'status'
 WHEN 'NOT_STARTED' THEN initial_race->'startedAt'='null'::jsonb AND initial_race->'endedAt'='null'::jsonb
 WHEN 'RUNNING' THEN jsonb_typeof(initial_race->'startedAt')='string' AND initial_race->'endedAt'='null'::jsonb
 WHEN 'ENDED' THEN jsonb_typeof(initial_race->'endedAt')='string' AND
 (initial_race->'startedAt'='null'::jsonb OR (jsonb_typeof(initial_race->'startedAt')='string' AND (initial_race->>'endedAt')::timestamptz >= (initial_race->>'startedAt')::timestamptz))
 ELSE false END));
