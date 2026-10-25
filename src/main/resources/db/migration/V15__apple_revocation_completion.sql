-- Permit verified completion without weakening immutable bindings or unfinished-work protection.
CREATE OR REPLACE FUNCTION guard_apple_case() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.status<>'COMPLETED' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unfinished Apple case cannot be deleted'; END IF;
  RETURN OLD;
 END IF;
 IF TG_OP='INSERT' AND NEW.status='COMPLETED' THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Completed Apple work cannot be inserted directly';
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
    OR (OLD.status='BLOCKED' AND NEW.status='REVOCATION_REQUIRED')
    OR (OLD.status IN ('REVOCATION_REQUIRED','BLOCKED') AND NEW.status='COMPLETED'
      AND EXISTS(SELECT 1 FROM apple_revocation_job WHERE case_id=OLD.id)
      AND NOT EXISTS(SELECT 1 FROM apple_revocation_job WHERE case_id=OLD.id AND status<>'SUCCEEDED')
      AND NOT EXISTS(SELECT 1 FROM apple_revocation_credential WHERE case_id=OLD.id))) THEN
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

CREATE OR REPLACE FUNCTION guard_apple_job() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.status<>'SUCCEEDED' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Unfinished Apple job cannot be deleted'; END IF;
  RETURN OLD;
 END IF;
 IF TG_OP='INSERT' AND NEW.status='SUCCEEDED' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Completed Apple work cannot be inserted directly'; END IF;
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
      OR (OLD.status='BLOCKED' AND NEW.status='PENDING' AND NEW.attempts=OLD.attempts)
      OR (OLD.status='PROCESSING' AND NEW.status='SUCCEEDED' AND NEW.attempts=OLD.attempts
        AND OLD.lease_until>NEW.updated_at AND NEW.completed_at=NEW.updated_at)) THEN
   RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple job transition is not allowed';
  END IF;
 END IF;
 RETURN NEW;
END $$;


CREATE OR REPLACE FUNCTION guard_apple_barrier() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple barrier cannot be reassigned'; END IF;
 IF EXISTS(SELECT 1 FROM apple_authorization_case WHERE identity_digest=OLD.identity_digest AND status<>'COMPLETED') THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Pending Apple barrier cannot be removed';
 END IF;
 RETURN OLD;
END $$;
