ALTER TABLE race_event
 ADD COLUMN registration_from timestamptz(3),
 ADD COLUMN registration_to timestamptz(3),
 ADD COLUMN registration_override varchar(6) NOT NULL DEFAULT 'AUTO',
 ADD CONSTRAINT registration_window CHECK (
   (registration_from IS NULL AND registration_to IS NULL) OR
   (registration_from IS NOT NULL AND registration_to IS NOT NULL AND registration_from < registration_to)),
 ADD CONSTRAINT registration_override_value CHECK (registration_override IN ('AUTO','OPEN','CLOSED'));

-- Exact java.lang.Character.isWhitespace set (in particular NOT NBSP, U+2007 or U+202F).
-- One member is a nonblank LF-separated line; no separate member table or fixed member limit.
CREATE FUNCTION registration_member_count(value text) RETURNS integer
 LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
 SELECT count(*)::integer FROM unnest(string_to_array(value,E'\n')) AS lines(line)
 WHERE line !~ U&'^[\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028-\2029\205F\3000]*$'
$$;

CREATE TABLE crew_registration (
 id uuid PRIMARY KEY,
 race_event_id uuid NOT NULL REFERENCES race_event(id) ON DELETE RESTRICT,
 crew_id uuid NOT NULL REFERENCES crew(id) ON DELETE RESTRICT,
 status varchar(16) NOT NULL DEFAULT 'REGISTERED' CHECK(status='REGISTERED'),
 crew_name_at_registration varchar(100) NOT NULL CHECK(length(btrim(crew_name_at_registration))>0),
 members_text text NOT NULL CHECK(char_length(members_text)<=2000 AND strpos(members_text,E'\r')=0),
 children_under_15 integer NOT NULL,
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 registered_at timestamptz(3) NOT NULL,
 updated_at timestamptz(3) NOT NULL,
 -- Reserved for a future real individual start; this migration does not start any participation.
 individual_started_at timestamptz(3),
 historical_captain_account_id uuid REFERENCES user_account(id) ON DELETE RESTRICT,
 started_members_text text,
 started_children_under_15 integer,
 UNIQUE(race_event_id,crew_id),
 CHECK(individual_started_at IS NULL OR (members_text=started_members_text AND children_under_15=started_children_under_15)),
 CHECK(registration_member_count(members_text)>=1 AND children_under_15 BETWEEN 0 AND registration_member_count(members_text)),
 CHECK((individual_started_at IS NULL AND historical_captain_account_id IS NULL AND started_members_text IS NULL AND started_children_under_15 IS NULL)
    OR (individual_started_at IS NOT NULL AND historical_captain_account_id IS NOT NULL AND started_members_text IS NOT NULL AND started_children_under_15 IS NOT NULL
      AND char_length(started_members_text)<=2000 AND strpos(started_members_text,E'\r')=0
      AND registration_member_count(started_members_text)>=1 AND started_children_under_15 BETWEEN 0 AND registration_member_count(started_members_text)))
);
CREATE INDEX registration_event_listing ON crew_registration(race_event_id,registered_at DESC,id DESC);
CREATE INDEX registration_crew_defaults ON crew_registration(crew_id,registered_at DESC,id DESC);
CREATE INDEX registration_historical_captain ON crew_registration(historical_captain_account_id) WHERE historical_captain_account_id IS NOT NULL;

CREATE FUNCTION preserve_registration_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF ROW(NEW.id,NEW.race_event_id,NEW.crew_id,NEW.crew_name_at_registration,NEW.registered_at)
    IS DISTINCT FROM ROW(OLD.id,OLD.race_event_id,OLD.crew_id,OLD.crew_name_at_registration,OLD.registered_at) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Registration provenance is immutable';
 END IF;
 IF OLD.individual_started_at IS NOT NULL AND
    ROW(NEW.individual_started_at,NEW.historical_captain_account_id,NEW.started_members_text,NEW.started_children_under_15,NEW.members_text,NEW.children_under_15)
    IS DISTINCT FROM ROW(OLD.individual_started_at,OLD.historical_captain_account_id,OLD.started_members_text,OLD.started_children_under_15,OLD.members_text,OLD.children_under_15) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Started registration composition is immutable';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER immutable_registration_history BEFORE UPDATE ON crew_registration FOR EACH ROW EXECUTE FUNCTION preserve_registration_history();
