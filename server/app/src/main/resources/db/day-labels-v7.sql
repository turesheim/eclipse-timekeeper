-- Logical model fence: DAY_LABEL resources now own labels independently.
-- The new server migrates each owner atomically before accepting requests.
-- All older replicas must remain stopped; physical tables are unchanged.
SELECT 1;
