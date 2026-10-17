CREATE TABLE tk_recovery_control (id INTEGER PRIMARY KEY CHECK(id=1), origin VARCHAR(2048), epoch BIGINT NOT NULL CHECK(epoch>=0));
INSERT INTO tk_recovery_control VALUES(1,NULL,0);
CREATE TABLE tk_recovery_address (owner_id VARCHAR(400) NOT NULL REFERENCES tk_account(owner_id), slot VARCHAR(8) NOT NULL CHECK(slot='PRIMARY' OR slot='BACKUP'), verified_address VARCHAR(320), pending_address VARCHAR(320), version BIGINT NOT NULL CHECK(version>=0), PRIMARY KEY(owner_id,slot));
CREATE TABLE tk_recovery_link (digest VARCHAR(64) PRIMARY KEY, owner_id VARCHAR(400) NOT NULL REFERENCES tk_account(owner_id), slot VARCHAR(8) NOT NULL CHECK(slot='PRIMARY' OR slot='BACKUP'), address VARCHAR(320) NOT NULL, purpose VARCHAR(8) NOT NULL CHECK(purpose='VERIFY' OR purpose='RESET'), origin_epoch BIGINT NOT NULL CHECK(origin_epoch>0), created_at VARCHAR(40) NOT NULL, expires_at VARCHAR(40) NOT NULL, consumed BOOLEAN NOT NULL);
CREATE INDEX tk_recovery_link_owner ON tk_recovery_link(owner_id);
