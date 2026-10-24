-- Transactional preflight: never choose, merge or delete conflicting identities/contexts.
LOCK TABLE user_account, user_identity, external_auth_flow, external_auth_grant IN ACCESS EXCLUSIVE MODE;
DO $$
BEGIN
 IF EXISTS(SELECT 1 FROM user_identity GROUP BY account_id HAVING count(*)>1)
 OR EXISTS(SELECT 1 FROM user_account a WHERE a.status='ACTIVE'
           AND NOT EXISTS(SELECT 1 FROM user_identity i WHERE i.account_id=a.id))
 OR EXISTS(SELECT 1 FROM user_account a JOIN user_identity i ON i.account_id=a.id WHERE a.status='ANONYMIZED') THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V10 identity preflight failed', CONSTRAINT='account_single_identity';
 END IF;
 IF EXISTS(SELECT 1 FROM external_auth_flow WHERE purpose<>'LOGIN' OR account_id IS NOT NULL OR session_id IS NOT NULL)
 OR EXISTS(SELECT 1 FROM external_auth_grant WHERE kind<>'MOBILE' OR purpose<>'LOGIN') THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='V10 unsupported authentication contexts exist', CONSTRAINT='login_only_contexts';
 END IF;
END $$;
ALTER TABLE user_identity ADD CONSTRAINT user_identity_single_account UNIQUE(account_id);
-- Keep V8 checks, but narrow their allowed values without relying on generated constraint names.
ALTER TABLE external_auth_flow ADD CONSTRAINT external_flow_login_only
 CHECK(purpose='LOGIN' AND account_id IS NULL AND session_id IS NULL);
ALTER TABLE external_auth_grant ADD CONSTRAINT external_grant_mobile_only CHECK(kind='MOBILE' AND purpose='LOGIN');

CREATE FUNCTION guard_identity_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_id uuid; owner_status text;
BEGIN
 IF TG_OP='UPDATE' AND (NEW.id,NEW.account_id,NEW.provider,NEW.issuer,NEW.subject)
   IS DISTINCT FROM (OLD.id,OLD.account_id,OLD.provider,OLD.issuer,OLD.subject) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Login identity is immutable', CONSTRAINT='identity_immutable';
 END IF;
 IF TG_OP='DELETE' THEN owner_id:=OLD.account_id; ELSE owner_id:=NEW.account_id; END IF;
 EXECUTE format('SELECT status FROM %I.user_account WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA)
   INTO owner_status USING owner_id;
 IF TG_OP<>'DELETE' AND owner_status='ANONYMIZED' THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Cancelled account cannot receive an identity', CONSTRAINT='account_single_identity';
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER identity_mutation_guard BEFORE INSERT OR UPDATE OR DELETE ON user_identity
 FOR EACH ROW EXECUTE FUNCTION guard_identity_mutation();

CREATE FUNCTION guard_terminal_account() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.id IS DISTINCT FROM OLD.id OR (OLD.status='ANONYMIZED' AND NEW.status<>'ANONYMIZED') THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Account identity and terminal status are immutable', CONSTRAINT='account_terminal_identity';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER account_terminal_guard BEFORE UPDATE ON user_account
 FOR EACH ROW EXECUTE FUNCTION guard_terminal_account();

CREATE FUNCTION check_account_identity() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE owner_id uuid; owner_status text; identity_count bigint;
BEGIN
 IF TG_TABLE_NAME='user_account' THEN owner_id:=NEW.id;
 ELSIF TG_OP='DELETE' THEN owner_id:=OLD.account_id;
 ELSE owner_id:=NEW.account_id; END IF;
 EXECUTE format('SELECT status FROM %I.user_account WHERE id=$1 FOR UPDATE',TG_TABLE_SCHEMA)
   INTO owner_status USING owner_id;
 IF owner_status IS NULL THEN RETURN NULL; END IF;
 -- A DELETE event may only finish as real cancellation, even when followed by INSERT.
 IF TG_TABLE_NAME='user_identity' AND TG_OP='DELETE' AND owner_status<>'ANONYMIZED' THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Identity removal requires account cancellation', CONSTRAINT='identity_delete_requires_cancellation';
 END IF;
 EXECUTE format('SELECT count(*) FROM %I.user_identity WHERE account_id=$1',TG_TABLE_SCHEMA)
   INTO identity_count USING owner_id;
 IF (owner_status='ACTIVE' AND identity_count<>1) OR (owner_status='ANONYMIZED' AND identity_count<>0) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Account identity cardinality violated', CONSTRAINT='account_single_identity';
 END IF;
 RETURN NULL;
END $$;
-- Account-first registration and identity-first cancellation are legal within one transaction.
CREATE CONSTRAINT TRIGGER account_identity_cardinality AFTER INSERT OR UPDATE ON user_account
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_identity();
CREATE CONSTRAINT TRIGGER identity_account_cardinality AFTER INSERT OR UPDATE OR DELETE ON user_identity
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_account_identity();
