ALTER TABLE idempotency_request
    ADD COLUMN request_hash TEXT NOT NULL DEFAULT '';
