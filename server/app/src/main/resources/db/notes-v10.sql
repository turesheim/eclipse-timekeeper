-- Logical compatibility gate: Notes bindings, guarded directory generations,
-- quotas and portable vault archives require stopping all older server replicas.
-- Business records use the existing owner-scoped resource/relation tables.
SELECT 1;
