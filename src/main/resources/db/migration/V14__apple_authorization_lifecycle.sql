-- No historic production Apple authorizations exist. Never guess bindings or discard ciphertext.
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM apple_revocation_credential WHERE case_id IS NULL) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Unbound Apple material prevents V14; investigate test/preproduction data without discarding material';
 END IF;
 IF EXISTS(SELECT 1 FROM apple_authorization_case c LEFT JOIN user_account a ON a.id=c.account_id
   WHERE c.account_id IS NOT NULL AND (a.id IS NULL OR a.status='ANONYMIZED'
     OR NOT EXISTS(SELECT 1 FROM user_identity i WHERE i.account_id=c.account_id AND i.provider='APPLE' AND i.issuer=c.issuer AND i.subject=c.subject))) THEN
  RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='Inconsistent Apple account binding prevents V14';
 END IF;
END $$;
CREATE INDEX apple_case_account ON apple_authorization_case(account_id) WHERE account_id IS NOT NULL;
CREATE INDEX apple_case_registration ON apple_authorization_case(registration_id) WHERE registration_id IS NOT NULL;
ALTER TABLE apple_revocation_credential ALTER COLUMN case_id SET NOT NULL;

-- Reject committing an account cancellation that did not detach/enqueue its cases.
CREATE FUNCTION check_apple_account_lifecycle() RETURNS trigger LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM apple_authorization_case c LEFT JOIN user_account a ON a.id=c.account_id
   WHERE c.account_id=OLD.id AND (a.id IS NULL OR a.status='ANONYMIZED')) THEN
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Apple account cancellation requires durable revocation work';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER apple_account_lifecycle AFTER UPDATE OR DELETE ON user_account
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_apple_account_lifecycle();
