-- Keep all existing simulations, runs, snapshots, command facts and file references.
-- expires_at is a deprecated compatibility column; NULL means no expiration.
ALTER TABLE race_simulation ALTER COLUMN expires_at DROP NOT NULL;
UPDATE race_simulation SET expires_at=NULL;
DROP INDEX simulation_expiration;
CREATE INDEX simulation_owner_activity ON race_simulation(owner_account_id,last_activity_at DESC,id DESC);
COMMENT ON COLUMN race_simulation.expires_at IS 'Deprecated: simulations have no automatic expiration; leave NULL';
