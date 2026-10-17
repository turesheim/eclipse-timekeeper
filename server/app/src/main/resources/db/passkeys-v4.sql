CREATE TABLE tk_passkey_user (owner_id VARCHAR(400) PRIMARY KEY REFERENCES tk_account(owner_id), user_handle VARCHAR(64) NOT NULL UNIQUE);
CREATE TABLE tk_passkey_credential (id UUID PRIMARY KEY, owner_id VARCHAR(400) NOT NULL REFERENCES tk_passkey_user(owner_id), credential_digest VARCHAR(64) NOT NULL UNIQUE, credential_id VARCHAR(4096) NOT NULL, rp_id VARCHAR(255) NOT NULL, public_key VARCHAR(8192) NOT NULL, signature_count BIGINT NOT NULL CHECK(signature_count>=0 AND signature_count<=4294967295), backup_eligible BOOLEAN NOT NULL, backed_up BOOLEAN NOT NULL, transports VARCHAR(1024) NOT NULL, name VARCHAR(400) NOT NULL, created_at VARCHAR(40) NOT NULL, last_used_at VARCHAR(40), revoked BOOLEAN NOT NULL, version BIGINT NOT NULL CHECK(version>=0), UNIQUE(owner_id,id), CHECK(backup_eligible OR NOT backed_up));
CREATE INDEX tk_passkey_credential_owner ON tk_passkey_credential(owner_id);
CREATE TABLE tk_passkey_ceremony (digest VARCHAR(64) PRIMARY KEY, browser_digest VARCHAR(64) NOT NULL, purpose VARCHAR(16) NOT NULL CHECK(purpose='REGISTER' OR purpose='LOGIN' OR purpose='MANAGE' OR purpose='PROOF'), owner_id VARCHAR(400) REFERENCES tk_account(owner_id), session_digest VARCHAR(64), payload VARCHAR(32768) NOT NULL, expires_at VARCHAR(40) NOT NULL, consumed BOOLEAN NOT NULL);
CREATE INDEX tk_passkey_ceremony_browser ON tk_passkey_ceremony(browser_digest);
CREATE INDEX tk_passkey_ceremony_owner ON tk_passkey_ceremony(owner_id);
-- Earlier sessions lack browser/origin provenance. Accounts, passwords and API tokens remain.
DELETE FROM tk_auth_session;
ALTER TABLE tk_auth_session ADD COLUMN origin_epoch BIGINT DEFAULT 0 NOT NULL CHECK(origin_epoch>=0);
ALTER TABLE tk_auth_session ADD COLUMN browser_digest VARCHAR(64);
ALTER TABLE tk_auth_session ADD COLUMN passkey_id UUID;
ALTER TABLE tk_auth_session ADD CONSTRAINT tk_auth_session_passkey FOREIGN KEY(owner_id,passkey_id) REFERENCES tk_passkey_credential(owner_id,id);
