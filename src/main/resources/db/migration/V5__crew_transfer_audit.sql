ALTER TABLE audit_event ADD COLUMN crew_id uuid;
ALTER TABLE audit_event ADD COLUMN transfer_id uuid;
ALTER TABLE audit_event ADD COLUMN resolution_reason varchar(48);
CREATE INDEX audit_event_transfer ON audit_event(transfer_id,occurred_at DESC);
CREATE INDEX audit_event_crew ON audit_event(crew_id,occurred_at DESC);
