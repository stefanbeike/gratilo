\set ON_ERROR_STOP on
BEGIN;
SELECT 'CREATE ROLE gratilo_grafana LOGIN' WHERE NOT EXISTS (
 SELECT FROM pg_catalog.pg_roles WHERE rolname='gratilo_grafana') \gexec
SELECT 'CREATE ROLE gratilo_pg_metrics LOGIN' WHERE NOT EXISTS (
 SELECT FROM pg_catalog.pg_roles WHERE rolname='gratilo_pg_metrics') \gexec
ALTER ROLE gratilo_grafana LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'grafana_password';
ALTER ROLE gratilo_grafana SET default_transaction_read_only = on;
ALTER ROLE gratilo_grafana SET statement_timeout = '8s';
ALTER ROLE gratilo_grafana SET gratilo_monitor.environment = :'monitor_environment';
ALTER ROLE gratilo_pg_metrics LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'exporter_password';
ALTER ROLE gratilo_pg_metrics SET default_transaction_read_only = on;
ALTER ROLE gratilo_pg_metrics SET statement_timeout = '5s';
GRANT pg_monitor TO gratilo_pg_metrics;
-- Fail closed if a legacy template grants either login persistent schema writes.
DO $$
BEGIN
  IF EXISTS (SELECT FROM pg_catalog.pg_namespace n
             WHERE has_schema_privilege('gratilo_grafana', n.oid, 'CREATE')
                OR has_schema_privilege('gratilo_pg_metrics', n.oid, 'CREATE')) THEN
    RAISE EXCEPTION 'Monitoring logins must not have CREATE on persistent schemas';
  END IF;
END $$;
COMMIT;
