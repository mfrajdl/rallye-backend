-- Infrastructure only. No code is issued, consumed, or participation started by this migration.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM crew_registration WHERE individual_started_at IS NOT NULL) THEN
  RAISE EXCEPTION 'Existing individual start has no verifiable real run; migration requires review';
 END IF;
END; $$;
ALTER TABLE crew_registration
 ADD COLUMN current_run_id uuid,
 ADD COLUMN device_generation bigint NOT NULL DEFAULT 0 CHECK(device_generation>=0),
 ADD CONSTRAINT registration_event_identity UNIQUE(id,race_event_id);
ALTER TABLE event_stage ADD CONSTRAINT event_stage_event_identity UNIQUE(id,race_event_id);

CREATE TABLE race_run (
 id uuid PRIMARY KEY,
 registration_id uuid NOT NULL,
 race_event_id uuid NOT NULL,
 ordinal bigint NOT NULL CHECK(ordinal>0),
 status varchar(16) NOT NULL DEFAULT 'RUNNING' CHECK(status IN ('RUNNING','COMPLETED')),
 activation_generation bigint NOT NULL DEFAULT 1 CHECK(activation_generation>0),
 started_at timestamptz(3) NOT NULL,
 completed_at timestamptz(3),
 participation_ended_at timestamptz(3),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(registration_id,ordinal), UNIQUE(registration_id,id), UNIQUE(id,race_event_id),
 FOREIGN KEY(registration_id,race_event_id) REFERENCES crew_registration(id,race_event_id) ON DELETE RESTRICT,
 CHECK((status='COMPLETED')=(completed_at IS NOT NULL)),
 CHECK(completed_at IS NULL OR completed_at>=started_at),
 CHECK(participation_ended_at IS NULL OR participation_ended_at>=started_at)
);
ALTER TABLE crew_registration ADD CONSTRAINT registration_current_run
 FOREIGN KEY(id,current_run_id) REFERENCES race_run(registration_id,id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE race_stage_progress (
 run_id uuid NOT NULL,
 race_event_id uuid NOT NULL,
 event_stage_id uuid NOT NULL,
 sequence_no integer NOT NULL CHECK(sequence_no>0),
 status varchar(16) NOT NULL CHECK(status IN ('PENDING','ACTIVE','COMPLETED','SKIPPED')),
 activated_at timestamptz(3), completed_at timestamptz(3), ended_at timestamptz(3),
 code_satisfied boolean NOT NULL DEFAULT false,
 gps_satisfied boolean NOT NULL DEFAULT false,
 PRIMARY KEY(run_id,event_stage_id),
 FOREIGN KEY(run_id,race_event_id) REFERENCES race_run(id,race_event_id) ON DELETE RESTRICT,
 FOREIGN KEY(event_stage_id,race_event_id) REFERENCES event_stage(id,race_event_id) ON DELETE RESTRICT,
 UNIQUE(run_id,sequence_no) DEFERRABLE INITIALLY DEFERRED,
 CHECK((status='PENDING' AND activated_at IS NULL AND completed_at IS NULL AND ended_at IS NULL AND NOT code_satisfied AND NOT gps_satisfied)
 OR (status='ACTIVE' AND activated_at IS NOT NULL AND completed_at IS NULL AND ended_at IS NULL)
 OR (status='COMPLETED' AND activated_at IS NOT NULL AND completed_at IS NOT NULL AND ended_at IS NOT NULL AND ended_at=completed_at)
 OR (status='SKIPPED' AND completed_at IS NULL AND ended_at IS NOT NULL)),
 CHECK(completed_at IS NULL OR completed_at>=activated_at),
 CHECK(ended_at IS NULL OR activated_at IS NULL OR ended_at>=activated_at)
);
CREATE UNIQUE INDEX race_progress_one_active ON race_stage_progress(run_id) WHERE status='ACTIVE';
CREATE INDEX race_progress_event_stage ON race_stage_progress(event_stage_id);

CREATE TABLE race_start_code (
 id uuid PRIMARY KEY,
 registration_id uuid NOT NULL REFERENCES crew_registration(id) ON DELETE RESTRICT,
 kind varchar(16) NOT NULL CHECK(kind IN ('ORIGINAL','REPLACEMENT')),
 generation bigint NOT NULL CHECK(generation>0),
 state varchar(16) NOT NULL DEFAULT 'AVAILABLE' CHECK(state IN ('AVAILABLE','USED')),
 code varchar(6) NOT NULL CHECK(code ~ '^[A-Z0-9]{6}$'),
 issued_at timestamptz(3) NOT NULL,
 issued_by_account_id uuid NOT NULL,
 used_at timestamptz(3), used_device_id uuid,
 points_adjustment bigint NOT NULL DEFAULT 0,
 reason varchar(2000),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 UNIQUE(registration_id,id), UNIQUE(registration_id,generation), UNIQUE(registration_id,code),
 CHECK((kind='ORIGINAL' AND generation IN (1,2)) OR (kind='REPLACEMENT' AND generation>=3)),
 CHECK(kind='REPLACEMENT' OR points_adjustment=0),
 CHECK((state='AVAILABLE' AND used_at IS NULL AND used_device_id IS NULL)
 OR (state='USED' AND used_at IS NOT NULL AND used_device_id IS NOT NULL)),
 CHECK(used_at IS NULL OR used_at>=issued_at)
);
CREATE INDEX race_code_listing ON race_start_code(registration_id,issued_at DESC,id DESC);

CREATE TABLE race_device_authorization (
 id uuid PRIMARY KEY,
 registration_id uuid NOT NULL REFERENCES crew_registration(id) ON DELETE RESTRICT,
 generation bigint NOT NULL CHECK(generation>0),
 credential_hash varchar(64) NOT NULL UNIQUE CHECK(credential_hash ~ '^[0-9a-f]{64}$'),
 state varchar(16) NOT NULL CHECK(state IN ('ACTIVE','REVOKED')),
 created_at timestamptz(3) NOT NULL, revoked_at timestamptz(3),
 created_by_code_id uuid NOT NULL UNIQUE,
 label varchar(100), revocation_reason varchar(2000), revoked_by_account_id uuid,
 UNIQUE(registration_id,id), UNIQUE(registration_id,generation),
 FOREIGN KEY(registration_id,created_by_code_id) REFERENCES race_start_code(registration_id,id) ON DELETE RESTRICT,
 CHECK((state='ACTIVE' AND revoked_at IS NULL AND revocation_reason IS NULL AND revoked_by_account_id IS NULL)
 OR (state='REVOKED' AND revoked_at IS NOT NULL)),
 CHECK(revoked_at IS NULL OR revoked_at>=created_at)
);
CREATE UNIQUE INDEX race_device_one_active ON race_device_authorization(registration_id) WHERE state='ACTIVE';
ALTER TABLE race_start_code ADD CONSTRAINT race_code_used_device
 FOREIGN KEY(registration_id,used_device_id) REFERENCES race_device_authorization(registration_id,id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE race_start_command (
 id uuid PRIMARY KEY,
 registration_id uuid NOT NULL REFERENCES crew_registration(id) ON DELETE RESTRICT,
 actor_account_id uuid NOT NULL,
 command_key uuid NOT NULL,
 operation varchar(24) NOT NULL CHECK(operation IN ('ORIGINAL_ISSUE','REPLACEMENT_ISSUE','CODE_REDEEM','DEVICE_REVOKE')),
 fingerprint_version integer NOT NULL CHECK(fingerprint_version>0),
 request_fingerprint varchar(64) NOT NULL CHECK(request_fingerprint ~ '^[0-9a-f]{64}$'),
 code_id uuid, second_code_id uuid, device_id uuid,
 executed_at timestamptz(3) NOT NULL,
 response_status integer NOT NULL CHECK(response_status IN (200,201)),
 result jsonb NOT NULL CHECK(jsonb_typeof(result)='object'),
 UNIQUE(actor_account_id,command_key),
 FOREIGN KEY(registration_id,code_id) REFERENCES race_start_code(registration_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(registration_id,second_code_id) REFERENCES race_start_code(registration_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(registration_id,device_id) REFERENCES race_device_authorization(registration_id,id) ON DELETE RESTRICT,
 CHECK((operation='ORIGINAL_ISSUE' AND code_id IS NOT NULL AND second_code_id IS NOT NULL AND code_id<>second_code_id AND device_id IS NULL AND response_status=201)
 OR (operation='REPLACEMENT_ISSUE' AND code_id IS NOT NULL AND second_code_id IS NULL AND device_id IS NULL AND response_status=201)
 OR (operation='CODE_REDEEM' AND code_id IS NOT NULL AND second_code_id IS NULL AND device_id IS NOT NULL AND response_status=200)
 OR (operation='DEVICE_REVOKE' AND code_id IS NULL AND second_code_id IS NULL AND device_id IS NOT NULL AND response_status=200))
);
CREATE UNIQUE INDEX race_original_pair_one_issue ON race_start_command(registration_id) WHERE operation='ORIGINAL_ISSUE';
CREATE UNIQUE INDEX race_code_one_redemption ON race_start_command(code_id) WHERE operation='CODE_REDEEM';
CREATE INDEX race_start_command_registration ON race_start_command(registration_id,executed_at,id);
CREATE TABLE race_points_adjustment (
 id uuid PRIMARY KEY,
 registration_id uuid NOT NULL REFERENCES crew_registration(id) ON DELETE RESTRICT,
 source_code_id uuid NOT NULL UNIQUE,
 points bigint NOT NULL,
 applied_at timestamptz(3) NOT NULL,
 reason varchar(2000),
 FOREIGN KEY(registration_id,source_code_id) REFERENCES race_start_code(registration_id,id) ON DELETE RESTRICT
);

-- Immediate guards protect provenance, monotonic versions and terminal history.
CREATE FUNCTION guard_race_start_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Race start history cannot be deleted'; END IF;
 IF TG_TABLE_NAME IN ('race_start_command','race_points_adjustment') THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Race start evidence is immutable';
 ELSIF TG_TABLE_NAME='race_start_code' THEN
  IF OLD.state<>'AVAILABLE' OR NEW.state<>'USED' OR NEW.version<>OLD.version+1
   OR ROW(NEW.id,NEW.registration_id,NEW.kind,NEW.generation,NEW.code,NEW.issued_at,NEW.issued_by_account_id,NEW.points_adjustment,NEW.reason)
   IS DISTINCT FROM ROW(OLD.id,OLD.registration_id,OLD.kind,OLD.generation,OLD.code,OLD.issued_at,OLD.issued_by_account_id,OLD.points_adjustment,OLD.reason) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid code history transition'; END IF;
 ELSIF TG_TABLE_NAME='race_device_authorization' THEN
  IF OLD.state<>'ACTIVE' OR NEW.state<>'REVOKED'
   OR ROW(NEW.id,NEW.registration_id,NEW.generation,NEW.credential_hash,NEW.created_at,NEW.created_by_code_id,NEW.label)
   IS DISTINCT FROM ROW(OLD.id,OLD.registration_id,OLD.generation,OLD.credential_hash,OLD.created_at,OLD.created_by_code_id,OLD.label) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid device history transition'; END IF;
 ELSIF TG_TABLE_NAME='race_run' THEN
  IF NEW.version<>OLD.version+1 OR NEW.activation_generation<OLD.activation_generation
   OR ROW(NEW.id,NEW.registration_id,NEW.race_event_id,NEW.ordinal,NEW.started_at) IS DISTINCT FROM ROW(OLD.id,OLD.registration_id,OLD.race_event_id,OLD.ordinal,OLD.started_at)
   OR (OLD.status='COMPLETED' AND ROW(NEW.status,NEW.completed_at) IS DISTINCT FROM ROW(OLD.status,OLD.completed_at)) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid run history transition'; END IF;
 ELSIF TG_TABLE_NAME='crew_registration' THEN
  IF NEW.device_generation<OLD.device_generation
   OR ((NEW.device_generation<>OLD.device_generation OR NEW.current_run_id IS DISTINCT FROM OLD.current_run_id OR NEW.individual_started_at IS DISTINCT FROM OLD.individual_started_at) AND NEW.version<>OLD.version+1) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid registration generation or version'; END IF;
 ELSIF TG_TABLE_NAME='race_stage_progress' THEN
  IF ROW(NEW.run_id,NEW.race_event_id,NEW.event_stage_id) IS DISTINCT FROM ROW(OLD.run_id,OLD.race_event_id,OLD.event_stage_id)
   OR (OLD.status IN ('COMPLETED','SKIPPED') AND NEW IS DISTINCT FROM OLD) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid stage history transition'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER race_code_history BEFORE UPDATE OR DELETE ON race_start_code FOR EACH ROW EXECUTE FUNCTION guard_race_start_history();
CREATE TRIGGER race_device_history BEFORE UPDATE OR DELETE ON race_device_authorization FOR EACH ROW EXECUTE FUNCTION guard_race_start_history();
CREATE TRIGGER race_command_history BEFORE UPDATE OR DELETE ON race_start_command FOR EACH ROW EXECUTE FUNCTION guard_race_start_history();
CREATE TRIGGER race_adjustment_history BEFORE UPDATE OR DELETE ON race_points_adjustment FOR EACH ROW EXECUTE FUNCTION guard_race_start_history();
CREATE TRIGGER race_run_history BEFORE UPDATE OR DELETE ON race_run FOR EACH ROW EXECUTE FUNCTION guard_race_start_history();
CREATE TRIGGER race_progress_history BEFORE UPDATE OR DELETE ON race_stage_progress FOR EACH ROW EXECUTE FUNCTION guard_race_start_history();
CREATE TRIGGER registration_race_generation BEFORE UPDATE ON crew_registration FOR EACH ROW EXECUTE FUNCTION guard_race_start_history();

-- Deferred validation permits atomic construction of cyclic code/device and registration/run graphs.
CREATE FUNCTION check_race_start_graph() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE reg_id uuid; r crew_registration%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='crew_registration' THEN reg_id:=NEW.id;
 ELSIF TG_TABLE_NAME='race_stage_progress' THEN SELECT registration_id INTO reg_id FROM race_run WHERE id=NEW.run_id;
 ELSE reg_id:=NEW.registration_id; END IF;
 SELECT * INTO r FROM crew_registration WHERE id=reg_id FOR UPDATE;
 IF NOT FOUND THEN RETURN NULL; END IF;
 IF (r.individual_started_at IS NULL AND (r.current_run_id IS NOT NULL OR r.device_generation<>0 OR EXISTS(SELECT 1 FROM race_run WHERE registration_id=r.id) OR EXISTS(SELECT 1 FROM race_device_authorization WHERE registration_id=r.id)))
 OR (r.individual_started_at IS NOT NULL AND (r.current_run_id IS NULL OR r.device_generation=0 OR NOT EXISTS(SELECT 1 FROM race_device_authorization WHERE registration_id=r.id))) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Incomplete registration run graph'; END IF;
 IF EXISTS(SELECT 1 FROM race_device_authorization d WHERE d.registration_id=r.id AND (d.generation>r.device_generation OR (d.state='ACTIVE' AND d.generation<>r.device_generation))) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Device generation mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM race_device_authorization d JOIN race_start_code c ON c.id=d.created_by_code_id WHERE d.registration_id=r.id AND (c.state<>'USED' OR c.used_device_id IS DISTINCT FROM d.id))
 OR EXISTS(SELECT 1 FROM race_start_code c LEFT JOIN race_device_authorization d ON d.id=c.used_device_id WHERE c.registration_id=r.id AND c.state='USED' AND (d.created_by_code_id IS DISTINCT FROM c.id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Code device evidence mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM race_points_adjustment a JOIN race_start_code c ON c.id=a.source_code_id WHERE a.registration_id=r.id AND (c.kind<>'REPLACEMENT' OR c.state<>'USED' OR c.points_adjustment<>a.points OR c.used_at<>a.applied_at)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid replacement adjustment'; END IF;
 IF EXISTS(SELECT 1 FROM race_start_command x WHERE x.registration_id=r.id AND x.operation='CODE_REDEEM' AND NOT EXISTS(SELECT 1 FROM race_start_code c WHERE c.id=x.code_id AND c.state='USED' AND c.used_device_id=x.device_id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid redemption evidence'; END IF;
 IF EXISTS(SELECT 1 FROM race_start_code c WHERE c.registration_id=r.id AND c.state='USED' AND NOT EXISTS(SELECT 1 FROM race_start_command x WHERE x.code_id=c.id AND x.operation='CODE_REDEEM' AND x.device_id=c.used_device_id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing redemption receipt'; END IF;
 IF r.individual_started_at IS NOT NULL AND NOT EXISTS(
  SELECT 1 FROM race_device_authorization d JOIN race_start_code c ON c.id=d.created_by_code_id
  WHERE d.registration_id=r.id AND d.generation=1 AND c.kind='ORIGINAL' AND c.state='USED' AND c.used_at=r.individual_started_at AND d.created_at=r.individual_started_at) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing original start evidence'; END IF;
 IF EXISTS(SELECT 1 FROM race_start_code c WHERE c.registration_id=r.id AND c.kind='ORIGINAL') AND
  ((SELECT count(*) FROM race_start_code c WHERE c.registration_id=r.id AND c.kind='ORIGINAL')<>2
   OR EXISTS(SELECT 1 FROM race_start_code a JOIN race_start_code b ON b.registration_id=a.registration_id AND b.kind='ORIGINAL' AND b.generation=2
     WHERE a.registration_id=r.id AND a.kind='ORIGINAL' AND a.generation=1 AND (a.issued_at<>b.issued_at OR a.issued_by_account_id<>b.issued_by_account_id))) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Original codes must form one complete pair'; END IF;
 IF r.individual_started_at IS NULL AND EXISTS(SELECT 1 FROM race_start_code c WHERE c.registration_id=r.id AND c.kind='REPLACEMENT') THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Replacement requires existing participation'; END IF;
 IF EXISTS(SELECT 1 FROM race_start_code c WHERE c.registration_id=r.id AND c.kind='REPLACEMENT' AND c.state='USED' AND c.points_adjustment<>0 AND NOT EXISTS(SELECT 1 FROM race_points_adjustment a WHERE a.source_code_id=c.id)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Missing replacement adjustment'; END IF;
 IF EXISTS(SELECT 1 FROM race_start_command x JOIN race_start_code c ON c.id=x.code_id WHERE x.registration_id=r.id AND
  ((x.operation='ORIGINAL_ISSUE' AND (c.kind<>'ORIGINAL' OR c.generation<>1 OR NOT EXISTS(
    SELECT 1 FROM race_start_code second_code WHERE second_code.id=x.second_code_id AND second_code.kind='ORIGINAL' AND second_code.generation=2)))
   OR (x.operation='REPLACEMENT_ISSUE' AND c.kind<>'REPLACEMENT')))
 OR EXISTS(SELECT 1 FROM race_start_command x JOIN race_device_authorization d ON d.id=x.device_id WHERE x.registration_id=r.id AND x.operation='DEVICE_REVOKE' AND d.state<>'REVOKED') THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid command provenance'; END IF;
 IF EXISTS(SELECT 1 FROM race_stage_progress p JOIN race_run u ON u.id=p.run_id JOIN event_stage e ON e.id=p.event_stage_id WHERE u.registration_id=r.id AND p.sequence_no<>e.sequence_no) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Progress order does not match event'; END IF;
 IF EXISTS(SELECT 1 FROM race_run u WHERE u.registration_id=r.id AND u.ordinal=1 AND u.started_at<>r.individual_started_at) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Original run start mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM race_run u WHERE u.registration_id=r.id AND (
  NOT EXISTS(SELECT 1 FROM race_stage_progress p WHERE p.run_id=u.id)
  OR (SELECT count(*) FROM race_stage_progress p WHERE p.run_id=u.id)<>(SELECT count(*) FROM event_stage e WHERE e.race_event_id=u.race_event_id)
  OR (u.status='RUNNING' AND u.participation_ended_at IS NULL AND NOT EXISTS(SELECT 1 FROM race_stage_progress p WHERE p.run_id=u.id AND p.status='ACTIVE'))
  OR (u.status='COMPLETED' AND EXISTS(SELECT 1 FROM race_stage_progress p WHERE p.run_id=u.id AND p.status IN ('PENDING','ACTIVE')))
 )) THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Incomplete run progress'; END IF;
 RETURN NULL;
END; $$;
CREATE CONSTRAINT TRIGGER registration_race_graph AFTER INSERT OR UPDATE ON crew_registration DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_start_graph();
CREATE CONSTRAINT TRIGGER run_race_graph AFTER INSERT OR UPDATE ON race_run DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_start_graph();
CREATE CONSTRAINT TRIGGER code_race_graph AFTER INSERT OR UPDATE ON race_start_code DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_start_graph();
CREATE CONSTRAINT TRIGGER device_race_graph AFTER INSERT OR UPDATE ON race_device_authorization DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_start_graph();
CREATE CONSTRAINT TRIGGER progress_race_graph AFTER INSERT OR UPDATE ON race_stage_progress DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_start_graph();
CREATE CONSTRAINT TRIGGER adjustment_race_graph AFTER INSERT ON race_points_adjustment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_start_graph();
CREATE CONSTRAINT TRIGGER command_race_graph AFTER INSERT ON race_start_command DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_start_graph();
