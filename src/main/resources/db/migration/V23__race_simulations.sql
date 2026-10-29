-- Simulations are private snapshots, never real events or historical crew participation.
CREATE TABLE race_simulation (
 id uuid PRIMARY KEY, owner_account_id uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
 source_template_id uuid NOT NULL, source_template_version bigint NOT NULL CHECK(source_template_version>=0), source_template_name varchar(100) NOT NULL,
 snapshot jsonb NOT NULL, snapshot_format_version integer NOT NULL DEFAULT 1 CHECK(snapshot_format_version>0), evaluator_version integer NOT NULL CHECK(evaluator_version>0),
 current_run_id uuid NOT NULL, version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 status varchar(16) NOT NULL CHECK(status IN ('RUNNING','COMPLETED','CLOSED')),
 virtual_start_at timestamptz(3) NOT NULL, virtual_now timestamptz(3) NOT NULL, projection jsonb NOT NULL,
 created_at timestamptz(3) NOT NULL, last_activity_at timestamptz(3) NOT NULL, expires_at timestamptz(3) NOT NULL,
 CHECK(virtual_now>=virtual_start_at),CHECK(expires_at>created_at)
);
CREATE INDEX simulation_owner ON race_simulation(owner_account_id,id);
CREATE INDEX simulation_expiration ON race_simulation(expires_at,id);
CREATE TABLE simulation_run (
 id uuid PRIMARY KEY, simulation_id uuid NOT NULL REFERENCES race_simulation(id) ON DELETE CASCADE,
 number bigint NOT NULL CHECK(number>0), parent_run_id uuid, restored_through_sequence bigint CHECK(restored_through_sequence>=0),
 started_virtual_at timestamptz(3) NOT NULL, initial_state jsonb NOT NULL,
 UNIQUE(simulation_id,number), UNIQUE(simulation_id,id),
 CHECK((parent_run_id IS NULL)=(restored_through_sequence IS NULL)),
 FOREIGN KEY(simulation_id,parent_run_id) REFERENCES simulation_run(simulation_id,id) DEFERRABLE INITIALLY DEFERRED
);
ALTER TABLE race_simulation ADD CONSTRAINT simulation_current_run FOREIGN KEY(id,current_run_id) REFERENCES simulation_run(simulation_id,id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE simulation_command (
 id uuid PRIMARY KEY, simulation_id uuid NOT NULL REFERENCES race_simulation(id) ON DELETE CASCADE,
 run_id uuid NOT NULL, actor_id uuid NOT NULL, command_key uuid NOT NULL, sequence bigint NOT NULL CHECK(sequence>0),
 operation varchar(32) NOT NULL, expected_version bigint NOT NULL CHECK(expected_version>=0),
 request_fingerprint varchar(64), verified_facts jsonb NOT NULL, effects jsonb NOT NULL, response jsonb,
 observed_virtual_at timestamptz(3) NOT NULL, recorded_at timestamptz(3) NOT NULL, replay_expires_at timestamptz(3) NOT NULL,
 UNIQUE(simulation_id,sequence), UNIQUE(simulation_id,actor_id,command_key),
 FOREIGN KEY(simulation_id,run_id) REFERENCES simulation_run(simulation_id,id) ON DELETE CASCADE,
 CHECK(request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$'),
 CHECK((request_fingerprint IS NULL)=(response IS NULL))
);
CREATE INDEX simulation_command_history ON simulation_command(run_id,sequence);
CREATE TABLE simulation_file (
 id uuid PRIMARY KEY, simulation_id uuid NOT NULL REFERENCES race_simulation(id) ON DELETE CASCADE,
 file_name varchar(100) NOT NULL,name_key varchar(200) NOT NULL,
 content_type varchar(100) NOT NULL CHECK(content_type IN ('image/jpeg','image/png','text/markdown','application/pdf')),
 byte_size integer NOT NULL CHECK(byte_size>=0),width integer,height integer,markdown_source text,created_at timestamptz(3) NOT NULL,
 UNIQUE(simulation_id,name_key),
 CHECK((width IS NULL)=(height IS NULL)),CHECK(width IS NULL OR (width>0 AND height>0 AND width<=4096 AND height<=4096)),
 CHECK((content_type='text/markdown')=(markdown_source IS NOT NULL))
);

-- Also handles anonymization; delete FK alone only covers physical removal.
CREATE FUNCTION remove_cancelled_account_simulations() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='ANONYMIZED' THEN
  EXECUTE format('DELETE FROM %I.race_simulation WHERE owner_account_id=$1',TG_TABLE_SCHEMA) USING NEW.id;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER cancelled_account_simulations AFTER UPDATE OF status ON user_account FOR EACH ROW WHEN (NEW.status='ANONYMIZED') EXECUTE FUNCTION remove_cancelled_account_simulations();
