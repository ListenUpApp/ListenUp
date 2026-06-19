CREATE TABLE libraries (
    id        VARCHAR(36) NOT NULL PRIMARY KEY,
    name      VARCHAR(256) NOT NULL,
    root_path VARCHAR(1024) NOT NULL
);
CREATE UNIQUE INDEX idx_libraries_root_path ON libraries(root_path);
