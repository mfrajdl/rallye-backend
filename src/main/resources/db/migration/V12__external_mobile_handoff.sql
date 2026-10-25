-- Earlier stages never issued grants. Do not guess bindings for pre-existing protocol fixtures.
LOCK TABLE external_auth_grant IN ACCESS EXCLUSIVE MODE;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM external_auth_grant) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V12 unbound mobile grants exist', CONSTRAINT='mobile_grant_attempt_required';
 END IF;
END $$;
ALTER TABLE external_auth_grant ADD COLUMN attempt_id uuid NOT NULL
 REFERENCES mobile_login_attempt(id);
ALTER TABLE external_auth_grant ADD CONSTRAINT mobile_grant_one_per_attempt UNIQUE(attempt_id);
