CREATE TABLE race_template (
    id uuid PRIMARY KEY,
    name varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
    description varchar(2000),
    skip_penalty_points bigint NOT NULL,
    status varchar(8) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','READY')),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz(3) NOT NULL,
    updated_at timestamptz(3) NOT NULL
);
CREATE INDEX race_template_listing ON race_template(created_at DESC,id DESC);
CREATE TABLE template_stage (
    id uuid PRIMARY KEY,
    race_template_id uuid NOT NULL REFERENCES race_template(id) ON DELETE CASCADE,
    sequence_no integer NOT NULL CHECK (sequence_no >= 1),
    name varchar(100) CHECK (name IS NULL OR length(btrim(name)) > 0),
    expected_duration_ms bigint CHECK (expected_duration_ms >= 0),
    max_penalty_points bigint CHECK (max_penalty_points >= 0),
    statistics_version bigint NOT NULL DEFAULT 1 CHECK (statistics_version >= 1),
    -- Reserved configuration: no public configuration/HTML/code endpoint in stage 4a.
    instructions_html text,
    gps_enabled boolean NOT NULL DEFAULT false,
    latitude double precision CHECK (latitude BETWEEN -90 AND 90),
    longitude double precision CHECK (longitude BETWEEN -180 AND 180),
    radius_m double precision CHECK (radius_m > 0 AND radius_m < 'Infinity'::float8),
    outside_penalty_points bigint,
    spoofing_penalty_points bigint,
    code_enabled boolean NOT NULL DEFAULT false,
    code_hash text,
    wrong_code_penalty_points bigint,
    time_penalty_enabled boolean NOT NULL DEFAULT false,
    penalty_interval_ms bigint CHECK (penalty_interval_ms > 0),
    points_per_interval bigint,
    delayed_enabled boolean NOT NULL DEFAULT false,
    minimum_duration_ms bigint CHECK (minimum_duration_ms >= 0),
    early_penalty_per_second bigint,
    CONSTRAINT template_stage_order UNIQUE(race_template_id,sequence_no) DEFERRABLE INITIALLY DEFERRED
);
-- The parent lock serializes direct child writes as well as application writes.
CREATE FUNCTION lock_stage_template() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner uuid;
BEGIN
    IF TG_OP='UPDATE' AND (NEW.id IS DISTINCT FROM OLD.id OR NEW.race_template_id IS DISTINCT FROM OLD.race_template_id) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Stage identity is immutable';
    END IF;
    IF TG_OP='DELETE' THEN owner:=OLD.race_template_id; ELSE owner:=NEW.race_template_id; END IF;
    EXECUTE format('SELECT id FROM %I.race_template WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA) USING owner;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END $$;
CREATE TRIGGER template_stage_parent_lock BEFORE INSERT OR UPDATE OR DELETE ON template_stage
FOR EACH ROW EXECUTE FUNCTION lock_stage_template();
CREATE FUNCTION check_stage_sequence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner uuid; total bigint; low integer; high integer;
BEGIN
    IF TG_OP='DELETE' THEN owner:=OLD.race_template_id; ELSE owner:=NEW.race_template_id; END IF;
    EXECUTE format('SELECT count(*),min(sequence_no),max(sequence_no) FROM %I.template_stage WHERE race_template_id=$1',TG_TABLE_SCHEMA)
        INTO total,low,high USING owner;
    IF total>0 AND (low<>1 OR high<>total) THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Stage sequence must be contiguous', CONSTRAINT='template_stage_contiguous';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER template_stage_contiguous AFTER INSERT OR UPDATE OR DELETE ON template_stage
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_stage_sequence();
