--liquibase formatted sql
-- SPDX-License-Identifier: Apache-2.0
-- Metadata store of Auto-Ontology (FR-DB-01..05). Derived from NVIDIA Auto-Ontology
-- auto_ontology/dal/schema.py and frontend/prisma/schema.prisma (Apache 2.0).
-- ${text} is TEXT on PostgreSQL and CHARACTER VARYING on H2 (no CLOBs).

--changeset ao:001-catalog
CREATE TABLE catalog_database (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    connection ${text},
    dialect ${text},
    description ${text},
    description_certified BOOLEAN DEFAULT FALSE NOT NULL,
    owner_id ${text},
    created TIMESTAMP WITH TIME ZONE,
    modified TIMESTAMP WITH TIME ZONE,
    created_by ${text},
    modified_by ${text},
    pulled TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_catalog_database_name UNIQUE (name)
);
CREATE TABLE catalog_schema (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    database_id VARCHAR(36) NOT NULL REFERENCES catalog_database (id) ON DELETE CASCADE,
    name ${text} NOT NULL,
    description ${text},
    description_certified BOOLEAN DEFAULT FALSE NOT NULL,
    owner_id ${text},
    modified TIMESTAMP WITH TIME ZONE,
    modified_by ${text},
    CONSTRAINT uq_catalog_schema_database_name UNIQUE (database_id, name)
);
CREATE TABLE catalog_table (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    schema_id VARCHAR(36) NOT NULL REFERENCES catalog_schema (id) ON DELETE CASCADE,
    name ${text} NOT NULL,
    description ${text},
    description_certified BOOLEAN DEFAULT FALSE NOT NULL,
    owner_id ${text},
    pk ${text},
    table_type ${text},
    is_junction_table BOOLEAN DEFAULT FALSE NOT NULL,
    modified TIMESTAMP WITH TIME ZONE,
    modified_by ${text},
    CONSTRAINT uq_catalog_table_schema_name UNIQUE (schema_id, name)
);
CREATE TABLE catalog_column (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    table_id VARCHAR(36) NOT NULL REFERENCES catalog_table (id) ON DELETE CASCADE,
    name ${text} NOT NULL,
    description ${text},
    description_certified BOOLEAN DEFAULT FALSE NOT NULL,
    data_type ${text},
    sample_values ${text},
    format ${text},
    is_unique BOOLEAN,
    is_nullable BOOLEAN,
    ordinal_position INTEGER,
    pii_processed BOOLEAN DEFAULT FALSE NOT NULL,
    modified TIMESTAMP WITH TIME ZONE,
    modified_by ${text},
    CONSTRAINT uq_catalog_column_table_name UNIQUE (table_id, name)
);
CREATE TABLE column__foreign_key (
    source_column_id VARCHAR(36) NOT NULL REFERENCES catalog_column (id) ON DELETE CASCADE,
    target_column_id VARCHAR(36) NOT NULL REFERENCES catalog_column (id) ON DELETE CASCADE,
    last_seen TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (source_column_id, target_column_id)
);
CREATE TABLE column__join (
    source_column_id VARCHAR(36) NOT NULL REFERENCES catalog_column (id) ON DELETE CASCADE,
    target_column_id VARCHAR(36) NOT NULL REFERENCES catalog_column (id) ON DELETE CASCADE,
    refs ${text},
    PRIMARY KEY (source_column_id, target_column_id)
);
CREATE TABLE column__union (
    source_column_id VARCHAR(36) NOT NULL REFERENCES catalog_column (id) ON DELETE CASCADE,
    target_column_id VARCHAR(36) NOT NULL REFERENCES catalog_column (id) ON DELETE CASCADE,
    refs ${text},
    PRIMARY KEY (source_column_id, target_column_id)
);
CREATE TABLE table__join (
    source_table_id VARCHAR(36) NOT NULL REFERENCES catalog_table (id) ON DELETE CASCADE,
    target_table_id VARCHAR(36) NOT NULL REFERENCES catalog_table (id) ON DELETE CASCADE,
    join_columns ${text},
    PRIMARY KEY (source_table_id, target_table_id)
);

--changeset ao:002-sql
CREATE TABLE sql_query (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    query_hash VARCHAR(64) NOT NULL,
    database_id VARCHAR(36) REFERENCES catalog_database (id) ON DELETE CASCADE,
    sql_full_query ${text} NOT NULL,
    query_count INTEGER DEFAULT 0 NOT NULL,
    total_counter INTEGER DEFAULT 0 NOT NULL,
    created TIMESTAMP WITH TIME ZONE,
    last_seen TIMESTAMP WITH TIME ZONE,
    last_query_timestamp TIMESTAMP WITH TIME ZONE,
    nodes_count INTEGER,
    join_count INTEGER,
    union_count INTEGER,
    CONSTRAINT uq_sql_query_hash UNIQUE (query_hash)
);
CREATE TABLE sql_query__table (
    sql_query_id VARCHAR(36) NOT NULL REFERENCES sql_query (id) ON DELETE CASCADE,
    table_id VARCHAR(36) NOT NULL REFERENCES catalog_table (id) ON DELETE CASCADE,
    PRIMARY KEY (sql_query_id, table_id)
);
CREATE TABLE sql_query__column (
    sql_query_id VARCHAR(36) NOT NULL REFERENCES sql_query (id) ON DELETE CASCADE,
    column_id VARCHAR(36) NOT NULL REFERENCES catalog_column (id) ON DELETE CASCADE,
    PRIMARY KEY (sql_query_id, column_id)
);

--changeset ao:003-semantic
CREATE TABLE term (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    description ${text},
    source ${text} DEFAULT 'semantic' NOT NULL,
    synonyms ${text},
    name_certified BOOLEAN DEFAULT FALSE NOT NULL,
    description_certified BOOLEAN DEFAULT FALSE NOT NULL,
    owner_id ${text},
    created TIMESTAMP WITH TIME ZONE,
    modified TIMESTAMP WITH TIME ZONE,
    created_by ${text},
    modified_by ${text},
    CONSTRAINT uq_term_name_source UNIQUE (name, source)
);
CREATE TABLE table__term (
    table_id VARCHAR(36) NOT NULL REFERENCES catalog_table (id) ON DELETE CASCADE,
    term_id VARCHAR(36) NOT NULL REFERENCES term (id) ON DELETE CASCADE,
    PRIMARY KEY (table_id, term_id)
);
CREATE TABLE column_attribute (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    description ${text},
    datatype ${text},
    source ${text} DEFAULT 'semantic' NOT NULL,
    source_column ${text} DEFAULT '' NOT NULL,
    term_name ${text} DEFAULT '' NOT NULL,
    table_id VARCHAR(36) DEFAULT '' NOT NULL,
    certified BOOLEAN DEFAULT FALSE NOT NULL,
    description_suggestion ${text},
    pii_processed BOOLEAN DEFAULT FALSE NOT NULL,
    created TIMESTAMP WITH TIME ZONE,
    modified TIMESTAMP WITH TIME ZONE,
    created_by ${text},
    modified_by ${text},
    CONSTRAINT uq_column_attribute_merge_key UNIQUE (name, source_column, term_name, table_id, source)
);
CREATE TABLE column_attribute__term (
    attribute_id VARCHAR(36) NOT NULL REFERENCES column_attribute (id) ON DELETE CASCADE,
    term_id VARCHAR(36) NOT NULL REFERENCES term (id) ON DELETE CASCADE,
    PRIMARY KEY (attribute_id, term_id),
    CONSTRAINT uq_column_attribute__term_attribute UNIQUE (attribute_id)
);
CREATE TABLE column__has_attribute (
    column_id VARCHAR(36) NOT NULL REFERENCES catalog_column (id) ON DELETE CASCADE,
    attribute_id VARCHAR(36) NOT NULL REFERENCES column_attribute (id) ON DELETE CASCADE,
    PRIMARY KEY (column_id, attribute_id)
);
CREATE TABLE column__semantic_fk (
    column_id VARCHAR(36) NOT NULL REFERENCES catalog_column (id) ON DELETE CASCADE,
    attribute_id VARCHAR(36) NOT NULL REFERENCES column_attribute (id) ON DELETE CASCADE,
    use_in_join_paths BOOLEAN DEFAULT TRUE NOT NULL,
    PRIMARY KEY (column_id, attribute_id)
);
-- FR-NLC-02: 'dataset' added to the source values of the original (documented deviation)
CREATE TABLE sql_attribute (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    description ${text},
    expression ${text} NOT NULL,
    source ${text},
    certified BOOLEAN DEFAULT FALSE NOT NULL,
    owner_id ${text},
    description_suggestion ${text},
    pii_processed BOOLEAN DEFAULT FALSE NOT NULL,
    created TIMESTAMP WITH TIME ZONE,
    modified TIMESTAMP WITH TIME ZONE,
    created_by ${text},
    modified_by ${text},
    CONSTRAINT uq_sql_attribute_name UNIQUE (name),
    CONSTRAINT ck_sql_attribute_source CHECK (source IS NULL OR source IN ('manual', 'sql', 'table', 'bridgeTable', 'dataset'))
);
CREATE TABLE sql_attribute__term (
    attribute_id VARCHAR(36) NOT NULL REFERENCES sql_attribute (id) ON DELETE CASCADE,
    term_id VARCHAR(36) NOT NULL REFERENCES term (id) ON DELETE CASCADE,
    PRIMARY KEY (attribute_id, term_id),
    CONSTRAINT uq_sql_attribute__term_attribute UNIQUE (attribute_id)
);
CREATE TABLE sql_attribute__sql (
    attribute_id VARCHAR(36) NOT NULL REFERENCES sql_attribute (id) ON DELETE CASCADE,
    sql_query_id VARCHAR(36) NOT NULL REFERENCES sql_query (id) ON DELETE CASCADE,
    PRIMARY KEY (attribute_id, sql_query_id)
);
CREATE TABLE custom_analysis (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    description ${text},
    sql ${text},
    database_id VARCHAR(36) REFERENCES catalog_database (id) ON DELETE CASCADE,
    created TIMESTAMP WITH TIME ZONE,
    modified TIMESTAMP WITH TIME ZONE,
    created_by ${text},
    modified_by ${text},
    CONSTRAINT uq_custom_analysis_name UNIQUE (name)
);
CREATE TABLE custom_analysis__sql (
    analysis_id VARCHAR(36) NOT NULL REFERENCES custom_analysis (id) ON DELETE CASCADE,
    sql_query_id VARCHAR(36) NOT NULL REFERENCES sql_query (id) ON DELETE CASCADE,
    PRIMARY KEY (analysis_id, sql_query_id)
);
CREATE TABLE pql_analysis (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    description ${text},
    pql ${text},
    created TIMESTAMP WITH TIME ZONE,
    modified TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_pql_analysis_name UNIQUE (name)
);
CREATE TABLE text_attribute (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    description ${text}
);
CREATE TABLE analysis (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    description ${text}
);
CREATE TABLE semantic_compilation_history (
    id VARCHAR(36) PRIMARY KEY,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE,
    status ${text},
    database_name ${text},
    summary ${text},
    CONSTRAINT ck_semantic_compilation_history_status CHECK (status IS NULL OR status IN ('succeeded', 'failed'))
);

--changeset ao:004-governance
CREATE TABLE zone (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    description ${text},
    color ${text},
    enabled BOOLEAN DEFAULT TRUE NOT NULL,
    created TIMESTAMP WITH TIME ZONE,
    modified TIMESTAMP WITH TIME ZONE,
    created_by ${text},
    modified_by ${text}
);
CREATE TABLE zone_target (
    id VARCHAR(36) PRIMARY KEY,
    zone_id VARCHAR(36) NOT NULL REFERENCES zone (id) ON DELETE CASCADE,
    database_id VARCHAR(36) REFERENCES catalog_database (id) ON DELETE CASCADE,
    schema_id VARCHAR(36) REFERENCES catalog_schema (id) ON DELETE CASCADE,
    table_id VARCHAR(36) REFERENCES catalog_table (id) ON DELETE CASCADE,
    CONSTRAINT ck_zone_target_one CHECK (
        (CASE WHEN database_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN schema_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN table_id IS NULL THEN 0 ELSE 1 END) = 1)
);
CREATE TABLE tag (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    name_lower ${text} NOT NULL,
    created TIMESTAMP WITH TIME ZONE,
    modified TIMESTAMP WITH TIME ZONE,
    created_by ${text},
    modified_by ${text},
    CONSTRAINT uq_tag_name_lower UNIQUE (name_lower)
);
CREATE TABLE rule (
    id VARCHAR(36) PRIMARY KEY,
    name ${text} NOT NULL,
    search_term ${text} NOT NULL,
    text_match_option ${text} DEFAULT 'contains' NOT NULL,
    filters ${text},
    created TIMESTAMP WITH TIME ZONE,
    modified TIMESTAMP WITH TIME ZONE,
    created_by ${text} NOT NULL,
    modified_by ${text}
);
CREATE TABLE rule__tag (
    rule_id VARCHAR(36) NOT NULL REFERENCES rule (id) ON DELETE CASCADE,
    tag_id VARCHAR(36) NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    position INTEGER DEFAULT 0 NOT NULL,
    PRIMARY KEY (rule_id, tag_id)
);
CREATE TABLE tag_target (
    id VARCHAR(36) PRIMARY KEY,
    tag_id VARCHAR(36) NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    term_id VARCHAR(36) REFERENCES term (id) ON DELETE CASCADE,
    table_id VARCHAR(36) REFERENCES catalog_table (id) ON DELETE CASCADE,
    column_id VARCHAR(36) REFERENCES catalog_column (id) ON DELETE CASCADE,
    column_attribute_id VARCHAR(36) REFERENCES column_attribute (id) ON DELETE CASCADE,
    sql_attribute_id VARCHAR(36) REFERENCES sql_attribute (id) ON DELETE CASCADE,
    tagged TIMESTAMP WITH TIME ZONE,
    tagged_by ${text},
    rule_id VARCHAR(36) REFERENCES rule (id) ON DELETE CASCADE,
    CONSTRAINT ck_tag_target_one CHECK (
        (CASE WHEN term_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN table_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN column_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN column_attribute_id IS NULL THEN 0 ELSE 1 END)
        + (CASE WHEN sql_attribute_id IS NULL THEN 0 ELSE 1 END) = 1),
    CONSTRAINT uq_tag_target_tag_id_term_id UNIQUE (tag_id, term_id),
    CONSTRAINT uq_tag_target_tag_id_table_id UNIQUE (tag_id, table_id),
    CONSTRAINT uq_tag_target_tag_id_column_id UNIQUE (tag_id, column_id),
    CONSTRAINT uq_tag_target_tag_id_column_attribute_id UNIQUE (tag_id, column_attribute_id),
    CONSTRAINT uq_tag_target_tag_id_sql_attribute_id UNIQUE (tag_id, sql_attribute_id)
);
-- FR-AUTH-08: groups are team names; role and members are only kept for import/export
CREATE TABLE user_group (
    id VARCHAR(36) PRIMARY KEY,
    imported_id ${text},
    name ${text} NOT NULL,
    name_lower ${text} NOT NULL,
    description ${text},
    color ${text},
    role ${text},
    created TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_user_group_name_lower UNIQUE (name_lower)
);
CREATE TABLE user_group__member (
    group_id VARCHAR(36) NOT NULL REFERENCES user_group (id) ON DELETE CASCADE,
    user_id ${text} NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

--changeset ao:005-app
-- Tables the original keeps in the Next.js frontend (prisma), FR-DB-05. User columns hold the fixed user.
CREATE TABLE app_conversations (
    id VARCHAR(36) PRIMARY KEY,
    user_id ${text} NOT NULL,
    title ${text},
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE app_messages (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL REFERENCES app_conversations (id) ON DELETE CASCADE,
    role ${text} NOT NULL,
    content ${text},
    sql_code ${text},
    sql_response ${text},
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_app_messages_conversation ON app_messages (conversation_id, created_at);
CREATE TABLE app_prompts (
    id VARCHAR(36) PRIMARY KEY,
    name ${text},
    content ${text} NOT NULL,
    created_by ${text},
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE app_configurations (
    config_key VARCHAR(128) PRIMARY KEY,
    config_value ${text},
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE app_acronyms (
    id VARCHAR(36) PRIMARY KEY,
    name ${text} NOT NULL,
    description ${text},
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_app_acronyms_name UNIQUE (name)
);
CREATE TABLE app_conversation_analytics (
    id VARCHAR(36) PRIMARY KEY,
    user_id ${text} NOT NULL,
    conversation_id VARCHAR(36),
    source ${text} NOT NULL,
    question ${text},
    answer ${text},
    reasoning ${text},
    sql_code ${text},
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    answered_at TIMESTAMP WITH TIME ZONE
);

--changeset ao:006-vector-and-dataset
-- FR-DEMO-01: optional persistence of the in-memory vector store
CREATE TABLE vector_record (
    collection_name VARCHAR(128) NOT NULL,
    id VARCHAR(255) NOT NULL,
    database_name ${text},
    label ${text},
    text_content ${text},
    metadata ${text},
    vector ${text} NOT NULL,
    PRIMARY KEY (collection_name, id)
);
-- FR-NLC-02: link between quasar-dashboard datasets and the catalog
CREATE TABLE ao_dataset_link (
    dataset_id VARCHAR(128) PRIMARY KEY,
    table_id VARCHAR(36) REFERENCES catalog_table (id) ON DELETE SET NULL,
    synced_at TIMESTAMP WITH TIME ZONE,
    warnings ${text}
);
CREATE TABLE ao_dataset_column_link (
    dataset_id VARCHAR(128) NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    column_id VARCHAR(36) REFERENCES catalog_column (id) ON DELETE SET NULL,
    type_generic INTEGER,
    is_dttm BOOLEAN,
    groupby BOOLEAN,
    filterable BOOLEAN,
    PRIMARY KEY (dataset_id, column_name)
);

--changeset ao:007-search-indexes dbms:postgresql failOnError:false
-- FR-DB-03: trigram indexes for substring search (TRIGRAM_SEARCH_TABLES)
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX ix_catalog_table_name_trgm ON catalog_table USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_catalog_column_name_trgm ON catalog_column USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_term_name_trgm ON term USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_column_attribute_name_trgm ON column_attribute USING gin (lower(name) gin_trgm_ops);
CREATE INDEX ix_sql_attribute_name_trgm ON sql_attribute USING gin (lower(name) gin_trgm_ops);
