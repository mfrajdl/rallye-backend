-- Existing PLANNED events remain untouched; only a real transition creates history.
ALTER TABLE race_event DROP CONSTRAINT race_event_status_check;
ALTER TABLE race_event DROP CONSTRAINT race_event_check;
ALTER TABLE race_event ADD CONSTRAINT race_event_status_check CHECK(status IN ('PLANNED','RUNNING','ENDED'));
ALTER TABLE race_event ADD COLUMN started_at timestamptz(3),
 ADD COLUMN last_ended_at timestamptz(3),
 ADD COLUMN active_period_id uuid,
 ADD COLUMN lifecycle_order bigint NOT NULL DEFAULT 0 CHECK(lifecycle_order>=0),
 ADD COLUMN period_number bigint NOT NULL DEFAULT 0 CHECK(period_number>=0),
 ADD COLUMN closure_revision bigint NOT NULL DEFAULT 0 CHECK(closure_revision>=0),
 ADD COLUMN current_closure_revision bigint,
 ADD COLUMN results_finalized_at timestamptz(3);
CREATE TABLE race_event_lifecycle_history (
 id uuid PRIMARY KEY,
 race_event_id uuid NOT NULL REFERENCES race_event(id) ON DELETE RESTRICT,
 sequence bigint NOT NULL CHECK(sequence>0),
 kind text NOT NULL CHECK(kind IN ('CONFIGURATION','TRANSITION')),
 type text NOT NULL CHECK(type IN ('TIMES_UPDATED','STARTED','ENDED','RESUMED')),
 from_status text NOT NULL CHECK(from_status IN ('PLANNED','RUNNING','ENDED')),
 to_status text NOT NULL CHECK(to_status IN ('PLANNED','RUNNING','ENDED')),
 effective_at timestamptz(3) NOT NULL,
 processed_at timestamptz(3) NOT NULL,
 "trigger" text NOT NULL CHECK("trigger" IN ('ADMIN','SCHEDULER','API_RECONCILIATION')),
 cause text NOT NULL CHECK(cause IN ('SETTINGS_UPDATE','MANUAL','SCHEDULED_START','END_AT')),
 actor_kind text NOT NULL CHECK(actor_kind IN ('USER','SYSTEM')),
 actor_account_id uuid,
 version_before bigint NOT NULL CHECK(version_before>=0),
 version_after bigint NOT NULL CHECK(version_after=version_before+1),
 scheduled_start_at_before timestamptz(3), scheduled_start_at_after timestamptz(3),
 end_at_before timestamptz(3), end_at_after timestamptz(3),
 period_id uuid, closure_revision bigint, invalidated_closure_revision bigint,
 CHECK((actor_kind='USER')=(actor_account_id IS NOT NULL)),
 CHECK((type='TIMES_UPDATED' AND kind='CONFIGURATION' AND from_status=to_status)
 OR (kind='TRANSITION' AND ((type='STARTED' AND from_status='PLANNED' AND to_status='RUNNING')
 OR (type='ENDED' AND from_status IN ('PLANNED','RUNNING') AND to_status='ENDED')
 OR (type='RESUMED' AND from_status='ENDED' AND to_status='RUNNING')))),
 CHECK((type='ENDED')=(closure_revision IS NOT NULL)),
 CHECK(closure_revision IS NULL OR closure_revision>0), CHECK(invalidated_closure_revision IS NULL OR invalidated_closure_revision>0),
 CHECK((type='RESUMED')=(invalidated_closure_revision IS NOT NULL)),
 UNIQUE(race_event_id,id), UNIQUE(race_event_id,sequence), UNIQUE(race_event_id,version_after), CHECK(effective_at<=processed_at)
);
CREATE INDEX race_event_history_page ON race_event_lifecycle_history(race_event_id,sequence DESC,id DESC);
CREATE TABLE race_event_running_period (
 id uuid PRIMARY KEY,
 race_event_id uuid NOT NULL REFERENCES race_event(id) ON DELETE RESTRICT,
 number bigint NOT NULL CHECK(number>0),
 started_at timestamptz(3) NOT NULL, ended_at timestamptz(3),
 end_boundary text CHECK(end_boundary IN ('INCLUSIVE','SERVER_ORDER')),
 start_transition_id uuid NOT NULL, end_transition_id uuid,
 start_sequence bigint NOT NULL, end_sequence bigint,
 UNIQUE(race_event_id,number), UNIQUE(race_event_id,id),
 FOREIGN KEY(race_event_id,start_transition_id) REFERENCES race_event_lifecycle_history(race_event_id,id) DEFERRABLE INITIALLY DEFERRED,
 FOREIGN KEY(race_event_id,end_transition_id) REFERENCES race_event_lifecycle_history(race_event_id,id) DEFERRABLE INITIALLY DEFERRED,
 CHECK(ended_at IS NULL OR ended_at>=started_at),
 CHECK((ended_at IS NULL AND end_boundary IS NULL AND end_transition_id IS NULL AND end_sequence IS NULL)
 OR (ended_at IS NOT NULL AND end_boundary IS NOT NULL AND end_transition_id IS NOT NULL AND end_sequence IS NOT NULL))
);
CREATE UNIQUE INDEX race_event_one_open_period ON race_event_running_period(race_event_id) WHERE ended_at IS NULL;
ALTER TABLE race_event ADD CONSTRAINT race_event_active_period_fk FOREIGN KEY(id,active_period_id)
 REFERENCES race_event_running_period(race_event_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE race_event_lifecycle_history ADD CONSTRAINT race_event_history_period_fk FOREIGN KEY(race_event_id,period_id)
 REFERENCES race_event_running_period(race_event_id,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE race_event ADD CONSTRAINT race_event_lifecycle_shape CHECK(
 (status='PLANNED' AND started_at IS NULL AND last_ended_at IS NULL AND active_period_id IS NULL AND period_number=0 AND closure_revision=0 AND current_closure_revision IS NULL)
 OR (status='RUNNING' AND started_at IS NOT NULL AND active_period_id IS NOT NULL AND period_number>0 AND current_closure_revision IS NULL)
 OR (status='ENDED' AND last_ended_at IS NOT NULL AND active_period_id IS NULL AND current_closure_revision IS NOT NULL AND current_closure_revision=closure_revision AND closure_revision>0));
CREATE INDEX race_event_due_start ON race_event(scheduled_start_at,id) WHERE status='PLANNED' AND scheduled_start_at IS NOT NULL;
CREATE INDEX race_event_due_end ON race_event(end_at,id) WHERE status IN ('PLANNED','RUNNING') AND end_at IS NOT NULL;
-- Validate the final transaction state, including circular transition/period provenance.
CREATE FUNCTION check_race_event_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE event_id uuid; e race_event%ROWTYPE; period_row race_event_running_period%ROWTYPE; h race_event_lifecycle_history%ROWTYPE;
BEGIN
 IF TG_TABLE_NAME='race_event' THEN event_id:=COALESCE(NEW.id,OLD.id);
 ELSE event_id:=COALESCE(NEW.race_event_id,OLD.race_event_id); END IF;
 SELECT * INTO e FROM race_event WHERE id=event_id;
 IF NOT FOUND THEN RETURN NULL; END IF;
 SELECT * INTO h FROM race_event_lifecycle_history WHERE race_event_id=event_id AND kind='TRANSITION' ORDER BY sequence DESC LIMIT 1;
 IF e.status='PLANNED' THEN
  IF FOUND THEN RAISE EXCEPTION 'Planned event has lifecycle transitions' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT FOUND OR h.to_status<>e.status OR h.version_after>e.version THEN
   RAISE EXCEPTION 'Event lifecycle history mismatch' USING ERRCODE='23514';
  END IF;
 END IF;
 IF e.closure_revision<>(SELECT count(*) FROM race_event_lifecycle_history WHERE race_event_id=event_id AND type='ENDED')
 OR e.period_number<>(SELECT count(*) FROM race_event_running_period WHERE race_event_id=event_id)
 OR e.period_number<>COALESCE((SELECT max(number) FROM race_event_running_period WHERE race_event_id=event_id),0)
 OR e.started_at IS DISTINCT FROM (SELECT started_at FROM race_event_running_period WHERE race_event_id=event_id ORDER BY number LIMIT 1)
 OR e.last_ended_at IS DISTINCT FROM (SELECT effective_at FROM race_event_lifecycle_history WHERE race_event_id=event_id AND type='ENDED' ORDER BY sequence DESC LIMIT 1) THEN
  RAISE EXCEPTION 'Event lifecycle metadata mismatch' USING ERRCODE='23514';
 END IF;
 IF e.status='ENDED' AND h.closure_revision IS DISTINCT FROM e.current_closure_revision THEN
  RAISE EXCEPTION 'Event closure mismatch' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM (SELECT from_status,lag(to_status,1,'PLANNED') OVER(ORDER BY sequence) AS previous_status
   FROM race_event_lifecycle_history WHERE race_event_id=event_id AND kind='TRANSITION') chain WHERE from_status<>previous_status) THEN
  RAISE EXCEPTION 'Event lifecycle transition chain mismatch' USING ERRCODE='23514';
 END IF;
 IF e.status='RUNNING' THEN
  IF h.period_id IS DISTINCT FROM e.active_period_id THEN
   RAISE EXCEPTION 'Event transition period mismatch' USING ERRCODE='23514';
  END IF;
  SELECT * INTO period_row FROM race_event_running_period WHERE race_event_id=event_id AND id=e.active_period_id;
  IF NOT FOUND OR period_row.ended_at IS NOT NULL OR period_row.number<>e.period_number THEN
   RAISE EXCEPTION 'Event active period mismatch' USING ERRCODE='23514';
  END IF;
 ELSE
  IF EXISTS(SELECT 1 FROM race_event_running_period WHERE race_event_id=event_id AND ended_at IS NULL) THEN
   RAISE EXCEPTION 'Event has unexpected open period' USING ERRCODE='23514';
  END IF;
 END IF;
 IF EXISTS(SELECT 1 FROM race_event_lifecycle_history WHERE race_event_id=event_id AND (version_after>e.version OR sequence>e.lifecycle_order)) THEN
  RAISE EXCEPTION 'Event history version mismatch' USING ERRCODE='23514';
 END IF;
 IF EXISTS(SELECT 1 FROM race_event_running_period p JOIN race_event_lifecycle_history s ON s.id=p.start_transition_id
   LEFT JOIN race_event_lifecycle_history t ON t.id=p.end_transition_id
   WHERE p.race_event_id=event_id AND (s.type NOT IN ('STARTED','RESUMED') OR s.period_id<>p.id OR s.sequence<>p.start_sequence OR s.effective_at<>p.started_at
   OR (p.ended_at IS NOT NULL AND (t.type<>'ENDED' OR t.period_id<>p.id OR t.sequence<>p.end_sequence OR t.effective_at<>p.ended_at OR p.end_boundary<>CASE WHEN t.cause='END_AT' THEN 'INCLUSIVE' ELSE 'SERVER_ORDER' END)))) THEN
  RAISE EXCEPTION 'Event period provenance mismatch' USING ERRCODE='23514';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER race_event_lifecycle_consistency AFTER INSERT OR UPDATE ON race_event DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_event_lifecycle();
CREATE CONSTRAINT TRIGGER race_event_history_consistency AFTER INSERT OR UPDATE OR DELETE ON race_event_lifecycle_history DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_event_lifecycle();
CREATE CONSTRAINT TRIGGER race_event_period_consistency AFTER INSERT OR UPDATE OR DELETE ON race_event_running_period DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_race_event_lifecycle();
CREATE FUNCTION guard_race_event_lifecycle_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' OR EXISTS(SELECT 1 FROM race_event WHERE id=OLD.race_event_id) THEN
  RAISE EXCEPTION 'Event lifecycle history is immutable' USING ERRCODE='23514';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER race_event_history_immutable BEFORE UPDATE OR DELETE ON race_event_lifecycle_history FOR EACH ROW EXECUTE FUNCTION guard_race_event_lifecycle_history();
