-- Runs once, on an empty volume, against the polaris database (POSTGRES_DB).
-- Polaris does not issue CREATE SCHEMA: it must exist before the admin tool bootstraps the realm.
CREATE SCHEMA polaris_schema;
-- Proposals and audit log, kept apart from the catalog (decision 10 in docs/architecture.md).
CREATE DATABASE ops;
