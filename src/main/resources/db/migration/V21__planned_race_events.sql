-- Planned events only: no registrations, run state machine or scheduler in this migration.
CREATE TABLE race_event (
 id uuid PRIMARY KEY,
 source_template_id uuid NOT NULL REFERENCES race_template(id) ON DELETE RESTRICT,
 source_template_version bigint NOT NULL CHECK(source_template_version>=0),
 name varchar(100) NOT NULL CHECK(length(btrim(name))>0),
 description varchar(2000),
 skip_penalty_points bigint NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PLANNED' CHECK(status='PLANNED'),
 scheduled_start_at timestamptz(3),
 end_at timestamptz(3),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz(3) NOT NULL,
 updated_at timestamptz(3) NOT NULL,
 CHECK(scheduled_start_at IS NULL OR end_at IS NULL OR scheduled_start_at<end_at)
);
CREATE INDEX race_event_listing ON race_event(created_at DESC,id DESC);
CREATE INDEX race_event_source ON race_event(source_template_id);
-- LIKE copies NOT NULL/CHECK/defaults, but deliberately not source FKs, triggers or indexes.
CREATE TABLE event_stage (LIKE template_stage INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
ALTER TABLE event_stage RENAME COLUMN race_template_id TO race_event_id;
ALTER TABLE event_stage ADD COLUMN source_template_stage_id uuid NOT NULL;
ALTER TABLE event_stage ADD PRIMARY KEY(id);
ALTER TABLE event_stage ADD FOREIGN KEY(race_event_id) REFERENCES race_event(id) ON DELETE CASCADE;
ALTER TABLE event_stage ADD CONSTRAINT event_stage_order UNIQUE(race_event_id,sequence_no) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE event_file (LIKE template_image INCLUDING DEFAULTS INCLUDING CONSTRAINTS);
ALTER TABLE event_file RENAME COLUMN race_template_id TO race_event_id;
ALTER TABLE event_file ADD PRIMARY KEY(id);
ALTER TABLE event_file ADD FOREIGN KEY(race_event_id) REFERENCES race_event(id) ON DELETE CASCADE;
ALTER TABLE event_file ADD CONSTRAINT event_file_name UNIQUE(race_event_id,name_key);
-- Source child IDs are historical provenance, not FKs: source stages may subsequently be edited/deleted.
