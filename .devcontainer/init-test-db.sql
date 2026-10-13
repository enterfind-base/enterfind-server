-- Initialize Enterfind Test Database
-- This script creates the test database and user for integration tests

-- Create test database
CREATE DATABASE enterfind_test
    ENCODING 'UTF8'
    LC_COLLATE 'C'
    LC_CTYPE 'C'
    TEMPLATE template0;

-- Create test user
CREATE USER enterfind_test WITH PASSWORD 'enterfind_test_password';

-- Grant privileges on test database
GRANT ALL PRIVILEGES ON DATABASE enterfind_test TO enterfind_test;

-- Connect to test database and grant schema privileges
\c enterfind_test

-- Grant schema privileges
GRANT ALL ON SCHEMA public TO enterfind_test;

-- Set default privileges for future objects
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO enterfind_test;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO enterfind_test;

COMMIT;
