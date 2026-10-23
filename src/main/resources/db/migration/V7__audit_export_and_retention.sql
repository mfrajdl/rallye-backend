ALTER TABLE audit_event ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}'::jsonb
    CHECK (jsonb_typeof(metadata) = 'object');

-- Only successful daily runs are remembered. Failure rolls this update back.
CREATE TABLE audit_cleanup_control (
    id integer PRIMARY KEY CHECK (id = 1),
    last_success_local_date date
);
INSERT INTO audit_cleanup_control(id) VALUES (1);
