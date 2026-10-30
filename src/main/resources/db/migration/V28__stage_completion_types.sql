-- Preserve all existing definitions, CODE text and event snapshots.
ALTER TABLE template_stage ADD COLUMN stage_type varchar(16) NOT NULL DEFAULT 'STANDARD',
 ADD COLUMN radar_penalty_points bigint,
 ADD CONSTRAINT template_stage_completion_type CHECK(stage_type IN ('STANDARD','INFORMATION','RADAR')),
 ADD CONSTRAINT template_stage_completion_shape CHECK(
 (stage_type='STANDARD' AND radar_penalty_points IS NULL)
 OR (stage_type='INFORMATION' AND NOT gps_enabled AND NOT code_enabled AND NOT time_penalty_enabled AND NOT delayed_enabled AND radar_penalty_points IS NULL)
 OR (stage_type='RADAR' AND gps_enabled AND NOT code_enabled AND latitude IS NOT NULL AND longitude IS NOT NULL AND radius_m IS NOT NULL AND radar_penalty_points IS NOT NULL AND radar_penalty_points>=0));
ALTER TABLE event_stage ADD COLUMN stage_type varchar(16) NOT NULL DEFAULT 'STANDARD',
 ADD COLUMN radar_penalty_points bigint,
 ADD CONSTRAINT event_stage_completion_type CHECK(stage_type IN ('STANDARD','INFORMATION','RADAR')),
 ADD CONSTRAINT event_stage_completion_shape CHECK(
 (stage_type='STANDARD' AND radar_penalty_points IS NULL)
 OR (stage_type='INFORMATION' AND NOT gps_enabled AND NOT code_enabled AND NOT time_penalty_enabled AND NOT delayed_enabled AND radar_penalty_points IS NULL)
 OR (stage_type='RADAR' AND gps_enabled AND NOT code_enabled AND latitude IS NOT NULL AND longitude IS NOT NULL AND radius_m IS NOT NULL AND radar_penalty_points IS NOT NULL AND radar_penalty_points>=0));
