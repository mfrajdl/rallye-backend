-- Transitional infrastructure only. Existing V9 writers remain unchanged until V14 / 3c.5a.2.
CREATE TABLE apple_authorization_case (
 id uuid PRIMARY KEY,
 issuer varchar(255) NOT NULL CHECK(issuer='https://appleid.apple.com'),
 subject varchar(255) NOT NULL CHECK(length(btrim(subject))>0),
 identity_digest char(64) NOT NULL CHECK(identity_digest=encode(sha256(convert_to(issuer || chr(10) || subject,'UTF8')),'hex')),
 client_id varchar(255) NOT NULL CHECK(length(btrim(client_id))>0),
 generation uuid NOT NULL,
 origin_flow_id uuid NOT NULL UNIQUE REFERENCES external_auth_flow(id),
 account_id uuid REFERENCES user_account(id),
 registration_id uuid REFERENCES external_registration(id),
 origin_account_id uuid,
 origin_registration_id uuid,
 status varchar(32) NOT NULL CHECK(status IN ('ACCOUNT_BOUND','REGISTRATION_PENDING','REVOCATION_REQUIRED','BLOCKED','COMPLETED')),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz(3) NOT NULL,
 updated_at timestamptz(3) NOT NULL CHECK(updated_at>=created_at),
 UNIQUE(issuer,subject,client_id,generation),
 CHECK((status='ACCOUNT_BOUND' AND account_id IS NOT NULL AND registration_id IS NULL)
    OR (status='REGISTRATION_PENDING' AND account_id IS NULL AND registration_id IS NOT NULL)
    OR (status IN ('REVOCATION_REQUIRED','BLOCKED','COMPLETED') AND account_id IS NULL AND registration_id IS NULL)),
 CHECK(origin_account_id IS NOT NULL OR origin_registration_id IS NOT NULL)
);
CREATE INDEX apple_case_identity ON apple_authorization_case(identity_digest);
ALTER TABLE apple_revocation_credential ADD COLUMN case_id uuid REFERENCES apple_authorization_case(id);
CREATE INDEX apple_credential_case ON apple_revocation_credential(case_id);
CREATE TABLE apple_revocation_job (
 id uuid PRIMARY KEY,
 case_id uuid NOT NULL REFERENCES apple_authorization_case(id),
 credential_id uuid NOT NULL UNIQUE,
 reason varchar(32) NOT NULL CHECK(reason IN ('ACCOUNT_CANCELLATION','REGISTRATION_ABANDONED')),
 status varchar(20) NOT NULL CHECK(status IN ('PENDING','PROCESSING','RETRY_WAIT','BLOCKED','SUCCEEDED')),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
 next_attempt_at timestamptz(3) NOT NULL,
 lease_id uuid,
 lease_until timestamptz(3),
 last_error varchar(24) CHECK(last_error IN ('NETWORK','RATE_LIMIT','PROVIDER_SERVER','CLIENT_CONFIG','MATERIAL','KEY','INTEGRITY','RELATION','PROVIDER_REJECTED','UNEXPECTED')),
 last_http_status integer CHECK(last_http_status BETWEEN 100 AND 599),
 completed_at timestamptz(3),
 version bigint NOT NULL DEFAULT 0 CHECK(version>=0),
 created_at timestamptz(3) NOT NULL,
 updated_at timestamptz(3) NOT NULL CHECK(updated_at>=created_at),
 CHECK((status='PROCESSING' AND lease_id IS NOT NULL AND lease_until IS NOT NULL AND lease_until>updated_at)
    OR (status<>'PROCESSING' AND lease_id IS NULL AND lease_until IS NULL)),
 CHECK((status='SUCCEEDED')=(completed_at IS NOT NULL)),
 CHECK((status IN ('RETRY_WAIT','BLOCKED'))=(last_error IS NOT NULL))
);
CREATE INDEX apple_job_due ON apple_revocation_job(status,next_attempt_at);
CREATE INDEX apple_job_lease ON apple_revocation_job(lease_until) WHERE status='PROCESSING';
CREATE TABLE apple_revocation_barrier (
 identity_digest char(64) PRIMARY KEY CHECK(identity_digest ~ '^[0-9a-f]{64}$'),
 created_at timestamptz(3) NOT NULL
);

CREATE FUNCTION guard_apple_case() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.status<>'COMPLETED' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unfinished Apple case cannot be deleted'; END IF;
  RETURN OLD;
 END IF;
 IF NEW.status='COMPLETED' THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Verified Apple completion is not implemented in this stage';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status NOT IN ('ACCOUNT_BOUND','REGISTRATION_PENDING') OR NEW.version<>0
    OR NEW.origin_account_id IS DISTINCT FROM NEW.account_id OR NEW.origin_registration_id IS DISTINCT FROM NEW.registration_id THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple case requires an initial owner';
  END IF;
 ELSE
  IF ROW(NEW.id,NEW.issuer,NEW.subject,NEW.identity_digest,NEW.client_id,NEW.generation,NEW.origin_flow_id,NEW.origin_registration_id,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.id,OLD.issuer,OLD.subject,OLD.identity_digest,OLD.client_id,OLD.generation,OLD.origin_flow_id,OLD.origin_registration_id,OLD.created_at)
    OR NEW.version<>OLD.version+1 THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple case binding is immutable';
  END IF;
  IF NEW.origin_account_id IS DISTINCT FROM OLD.origin_account_id
    AND NOT(OLD.status='REGISTRATION_PENDING' AND NEW.status='ACCOUNT_BOUND' AND OLD.origin_account_id IS NULL AND NEW.origin_account_id=NEW.account_id) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple case origin cannot be reassigned';
  END IF;
  IF NOT ((OLD.status='REGISTRATION_PENDING' AND NEW.status IN ('ACCOUNT_BOUND','REVOCATION_REQUIRED'))
    OR (OLD.status='ACCOUNT_BOUND' AND NEW.status='REVOCATION_REQUIRED')
    OR (OLD.status='REVOCATION_REQUIRED' AND NEW.status='BLOCKED')
    OR (OLD.status='BLOCKED' AND NEW.status='REVOCATION_REQUIRED')) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple case transition is not allowed';
  END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM external_auth_flow WHERE id=NEW.origin_flow_id AND provider='APPLE' AND purpose='LOGIN') THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple case requires an Apple login flow';
 END IF;
 IF NEW.account_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM user_identity WHERE account_id=NEW.account_id AND provider='APPLE' AND issuer=NEW.issuer AND subject=NEW.subject) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple account owner does not match';
 END IF;
 IF NEW.registration_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM external_registration WHERE id=NEW.registration_id AND provider='APPLE' AND issuer=NEW.issuer AND subject=NEW.subject) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple registration owner does not match';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER apple_case_guard BEFORE INSERT OR UPDATE OR DELETE ON apple_authorization_case FOR EACH ROW EXECUTE FUNCTION guard_apple_case();

CREATE FUNCTION guard_apple_credential() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE c apple_authorization_case;
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.case_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM apple_revocation_job WHERE credential_id=OLD.id AND status='SUCCEEDED') THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unfinished Apple material cannot be deleted';
  END IF;
  RETURN OLD;
 END IF;
 IF TG_OP='UPDATE' AND OLD.case_id IS NOT NULL AND
  ROW(NEW.id,NEW.case_id,NEW.client_id,NEW.key_id,NEW.iv,NEW.ciphertext,NEW.created_at)
   IS DISTINCT FROM ROW(OLD.id,OLD.case_id,OLD.client_id,OLD.key_id,OLD.iv,OLD.ciphertext,OLD.created_at) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple material binding is immutable';
 END IF;
 -- Explicit approved staging boundary: unbound V9 writes still work until V14.
 IF NEW.case_id IS NULL THEN RETURN NEW; END IF;
 SELECT * INTO c FROM apple_authorization_case WHERE id=NEW.case_id FOR UPDATE;
 IF NOT FOUND OR c.client_id<>NEW.client_id OR c.status NOT IN ('ACCOUNT_BOUND','REGISTRATION_PENDING') AND TG_OP='INSERT' THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple material requires an open bound case';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER apple_material_guard BEFORE INSERT OR UPDATE OR DELETE ON apple_revocation_credential FOR EACH ROW EXECUTE FUNCTION guard_apple_credential();

CREATE FUNCTION check_apple_material_owner() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
DECLARE bad boolean;
BEGIN
 SELECT EXISTS(SELECT 1 FROM apple_revocation_credential m JOIN apple_authorization_case c ON c.id=m.case_id
   WHERE c.id=NEW.id AND (m.client_id<>c.client_id OR m.account_id IS DISTINCT FROM c.account_id OR m.registration_id IS DISTINCT FROM c.registration_id)) INTO bad;
 IF TG_TABLE_NAME='apple_revocation_credential' THEN
  SELECT EXISTS(SELECT 1 FROM apple_revocation_credential m JOIN apple_authorization_case c ON c.id=m.case_id
    WHERE m.id=NEW.id AND (m.client_id<>c.client_id OR m.account_id IS DISTINCT FROM c.account_id OR m.registration_id IS DISTINCT FROM c.registration_id)) INTO bad;
 END IF;
 IF bad THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple material owner is inconsistent'; END IF;
 IF TG_TABLE_NAME='apple_authorization_case' AND EXISTS(SELECT 1 FROM apple_authorization_case c WHERE c.id=NEW.id AND c.status IN ('REVOCATION_REQUIRED','BLOCKED')
   AND (NOT EXISTS(SELECT 1 FROM apple_revocation_barrier b WHERE b.identity_digest=c.identity_digest)
    OR NOT EXISTS(SELECT 1 FROM apple_revocation_credential m WHERE m.case_id=c.id)
    OR EXISTS(SELECT 1 FROM apple_revocation_credential m WHERE m.case_id=c.id AND NOT EXISTS(SELECT 1 FROM apple_revocation_job j WHERE j.credential_id=m.id AND j.case_id=c.id)))) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple revocation requires barrier and complete work';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER apple_case_owner_check AFTER INSERT OR UPDATE ON apple_authorization_case DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_apple_material_owner();
CREATE CONSTRAINT TRIGGER apple_material_owner_check AFTER INSERT OR UPDATE ON apple_revocation_credential DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_apple_material_owner();

CREATE FUNCTION guard_apple_job() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.status<>'SUCCEEDED' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unfinished Apple job cannot be deleted'; END IF;
  RETURN OLD;
 END IF;
 IF NEW.status='SUCCEEDED' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Verified Apple completion is not implemented in this stage'; END IF;
 IF NOT EXISTS(SELECT 1 FROM apple_revocation_credential m JOIN apple_authorization_case c ON c.id=m.case_id
   WHERE m.id=NEW.credential_id AND c.id=NEW.case_id AND c.status IN ('REVOCATION_REQUIRED','BLOCKED')) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple job requires bound revocation material';
 END IF;
 IF NOT EXISTS(SELECT 1 FROM apple_authorization_case c WHERE c.id=NEW.case_id AND
   (NEW.reason='ACCOUNT_CANCELLATION' AND c.origin_account_id IS NOT NULL OR NEW.reason='REGISTRATION_ABANDONED' AND c.origin_account_id IS NULL)) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple revocation reason conflicts with origin';
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'PENDING'  OR NEW.attempts<>0 OR NEW.version<>0 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid initial Apple job'; END IF;
 ELSE
  IF ROW(NEW.id,NEW.case_id,NEW.credential_id,NEW.reason,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.case_id,OLD.credential_id,OLD.reason,OLD.created_at)
    OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple job binding is immutable'; END IF;
  IF NEW.status='PROCESSING' THEN
   IF NOT(OLD.status IN ('PENDING','RETRY_WAIT') AND OLD.next_attempt_at<=NEW.updated_at OR OLD.status='PROCESSING' AND OLD.lease_until<=NEW.updated_at)
     OR NEW.attempts<>OLD.attempts+1 OR NEW.lease_id IS NOT DISTINCT FROM OLD.lease_id THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid Apple lease claim';
   END IF;
  ELSIF NOT((OLD.status='PROCESSING' AND NEW.status IN ('RETRY_WAIT','BLOCKED') AND NEW.attempts=OLD.attempts)
      OR (OLD.status='BLOCKED' AND NEW.status='PENDING' AND NEW.attempts=OLD.attempts)) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple job transition is not allowed';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER apple_job_guard BEFORE INSERT OR UPDATE OR DELETE ON apple_revocation_job FOR EACH ROW EXECUTE FUNCTION guard_apple_job();
CREATE FUNCTION guard_apple_barrier() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple barrier cannot be reassigned'; END IF;
 IF EXISTS(SELECT 1 FROM apple_authorization_case WHERE identity_digest=OLD.identity_digest AND status IN ('REVOCATION_REQUIRED','BLOCKED')) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Pending Apple barrier cannot be removed';
 END IF;
 RETURN OLD;
END $$;
CREATE TRIGGER apple_barrier_guard BEFORE UPDATE OR DELETE ON apple_revocation_barrier FOR EACH ROW EXECUTE FUNCTION guard_apple_barrier();
