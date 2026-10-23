CREATE TABLE IF NOT EXISTS kb_user (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(40) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS kb_session (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    expires_at BIGINT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES kb_user(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS kb_document (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    file_type VARCHAR(8) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    page_count INT NOT NULL,
    chunk_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    UNIQUE (user_id, sha256),
    FOREIGN KEY (user_id) REFERENCES kb_user(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS kb_chunk (
    id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    ordinal INT NOT NULL,
    page_number INT NOT NULL,
    start_offset INT NOT NULL,
    content TEXT NOT NULL,
    FOREIGN KEY (document_id) REFERENCES kb_document(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES kb_user(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS kb_answer (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    question VARCHAR(1000) NOT NULL,
    answer TEXT NOT NULL,
    answer_mode VARCHAR(24) NOT NULL,
    citations_json TEXT NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    feedback INT,
    created_at TIMESTAMP(6) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES kb_user(id) ON DELETE CASCADE
);
CREATE TABLE IF NOT EXISTS kb_embedding (
    chunk_id VARCHAR(36) PRIMARY KEY,
    model_version VARCHAR(100) NOT NULL,
    vector_json TEXT NOT NULL,
    FOREIGN KEY (chunk_id) REFERENCES kb_chunk(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS kb_exercise (
    id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    anchor_chunk_id VARCHAR(36) NOT NULL,
    question VARCHAR(500) NOT NULL,
    solution_json TEXT NOT NULL,
    student_answer TEXT,
    grade_json TEXT,
    created_at TIMESTAMP(6) NOT NULL,
    graded_at TIMESTAMP(6),
    FOREIGN KEY (user_id) REFERENCES kb_user(id) ON DELETE CASCADE
);
