SELECT definition FROM (
  SELECT 'SCHEMA|' || nspname AS definition FROM pg_namespace
  WHERE nspname NOT LIKE 'pg_%' AND nspname NOT IN ('public','information_schema')
  UNION ALL
  SELECT concat_ws('|','RELATION',c.relname,c.relkind,c.relrowsecurity,c.relforcerowsecurity)
  FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relkind NOT IN ('i','I')
  UNION ALL
  SELECT concat_ws('|','COLUMN',c.relname,a.attnum,a.attname,format_type(a.atttypid,a.atttypmod),
                   a.attnotnull,COALESCE(pg_get_expr(d.adbin,d.adrelid),''),a.attidentity,a.attgenerated)
  FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace
  LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
  WHERE n.nspname='public' AND a.attnum>0 AND NOT a.attisdropped
    AND c.relkind NOT IN ('i','I')
  UNION ALL
  SELECT concat_ws('|','CONSTRAINT',c.relname,k.contype,pg_get_constraintdef(k.oid))
  FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
  JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'
  UNION ALL
  SELECT concat_ws('|','INDEX',tablename,indexdef) FROM pg_indexes WHERE schemaname='public'
  UNION ALL
  SELECT 'TRIGGER|' || pg_get_triggerdef(t.oid) FROM pg_trigger t
  JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND NOT t.tgisinternal
  UNION ALL
  SELECT 'ROUTINE|' || p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
  WHERE n.nspname='public'
  UNION ALL
  SELECT 'TYPE|' || t.typname FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
  WHERE n.nspname='public' AND t.typtype IN ('d','e')
) shapes ORDER BY definition COLLATE "C"
