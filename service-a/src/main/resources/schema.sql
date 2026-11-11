CREATE TABLE IF NOT EXISTS service_a_demo_records (
    record_id VARCHAR(100) PRIMARY KEY,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
