-- Installation-wide operation authority and audit; actors intentionally have no
-- owner FK so restore evidence survives replacement of the bootstrap account.
CREATE TABLE tk_instance (
 id UUID PRIMARY KEY,
 kind VARCHAR(16) NOT NULL,
 version BIGINT NOT NULL CHECK (version > 0),
 operation_id UUID,
 actor_owner VARCHAR(400),
 occurred_at VARCHAR(50) NOT NULL,
 payload TEXT NOT NULL,
 CONSTRAINT tk_instance_kind CHECK (
  (kind='CONTROL' AND id='00000000-0000-0000-0000-000000000000' AND operation_id IS NULL AND actor_owner IS NULL)
  OR ((kind='OPERATION' OR kind='AUDIT') AND id<>'00000000-0000-0000-0000-000000000000' AND operation_id IS NOT NULL AND actor_owner IS NOT NULL)
 )
);
INSERT INTO tk_instance VALUES ('00000000-0000-0000-0000-000000000000','CONTROL',1,NULL,NULL,'1970-01-01T00:00:00Z','{}');
