-- V4: Add last_seen_at to users table for presence tracking
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users(last_seen_at DESC NULLS LAST);
