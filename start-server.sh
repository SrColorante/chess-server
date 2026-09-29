#!/bin/bash
# Load Neon environment variables if they exist
if [ -f .env.local ]; then
    export $(grep -v '^#' .env.local | xargs)
    # Neon provides DATABASE_URL, map it to DB_URL so ServerAccountHandler works
    export DB_URL=$DATABASE_URL
fi

# Fallbacks if .env.local is missing
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5432/chess}"
export DB_USER="${DB_USER:-postgres}"
export DB_PASS="${DB_PASS:-postgres}"

java -cp "bin:lib/postgresql.jar" server.ServerMain
