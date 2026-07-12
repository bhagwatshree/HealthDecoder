-- Initial script to set up PostgreSQL database tables for Medical Report Scanner

-- Create database if not exists (Note: this should be run in postgres default database first,
-- or database can be created manually via pgAdmin / psql)
-- CREATE DATABASE medical_db;

-- Connect to medical_db database before running the below commands
-- \c medical_db;

CREATE TABLE IF NOT EXISTS medical_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_name VARCHAR(255),
    report_date DATE,
    report_type VARCHAR(100),
    extracted_text TEXT,
    comments TEXT,
    medications JSONB DEFAULT '[]'::jsonb,
    image_path VARCHAR(512),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    test_results JSONB DEFAULT '[]'::jsonb,
    comparison_result JSONB DEFAULT '{}'::jsonb,
    report_category VARCHAR(100),
    health_insights JSONB DEFAULT '{}'::jsonb,
    detailed_analysis JSONB
);

-- Index for scanning reports by patient name or date for performance
CREATE INDEX IF NOT EXISTS idx_medical_reports_patient_name ON medical_reports(patient_name);
CREATE INDEX IF NOT EXISTS idx_medical_reports_report_date ON medical_reports(report_date DESC);

-- Composite index serving "this patient's reports, newest first" and the
-- previous-report lookup used for comparisons (patient + category + date).
CREATE INDEX IF NOT EXISTS idx_medical_reports_patient_cat_date
    ON medical_reports(patient_name, report_category, report_date DESC);

-- Full-text search index over patient, type, comments, and extracted OCR text.
-- The expression must stay identical to REPORT_SEARCH_VECTOR in server.js.
CREATE INDEX IF NOT EXISTS idx_medical_reports_fts ON medical_reports USING GIN (
    to_tsvector('english',
        coalesce(patient_name, '') || ' ' || coalesce(report_type, '') || ' ' ||
        coalesce(comments, '') || ' ' || coalesce(extracted_text, ''))
);

-- Table for future recommended tests (reminders)
CREATE TABLE IF NOT EXISTS pending_tests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_name VARCHAR(255) NOT NULL,
    test_name VARCHAR(255) NOT NULL,
    due_date DATE,
    status VARCHAR(50) DEFAULT 'Pending', -- 'Pending', 'Completed'
    resolved_report_id UUID REFERENCES medical_reports(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Index for pending tests querying
CREATE INDEX IF NOT EXISTS idx_pending_tests_patient_name ON pending_tests(patient_name);
CREATE INDEX IF NOT EXISTS idx_pending_tests_status ON pending_tests(status);

-- Table for medication logs (intakes and detail updates)
CREATE TABLE IF NOT EXISTS medication_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_name VARCHAR(255) NOT NULL,
    medicine_name VARCHAR(255) NOT NULL,
    action_type VARCHAR(50) NOT NULL, -- 'TAKEN' or 'UPDATE_DETAILS'
    frequency VARCHAR(255),
    notes TEXT,
    taken_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Index for medication logs
CREATE INDEX IF NOT EXISTS idx_medication_logs_patient_med ON medication_logs(patient_name, medicine_name);

-- User accounts: each logged-in user gets their own metered free-tier AI usage lane
-- (see backend/keyPool.js) and can optionally attach their own Gemini/Sarvam API key.
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    date_of_birth DATE NOT NULL,
    gender VARCHAR(20) NOT NULL CHECK (gender IN ('male', 'female', 'other', 'prefer_not_to_say')),
    email VARCHAR(255) UNIQUE NOT NULL,
    -- E.164 format (e.g. +919876543210). One MSISDN maps to exactly one email and vice
    -- versa — enforced by this UNIQUE constraint plus the UNIQUE on email above, since both
    -- live on the same row (see backend/server.js signup for the paired-uniqueness check).
    msisdn VARCHAR(20) UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    plan VARCHAR(20) NOT NULL DEFAULT 'free', -- 'free' | 'premium'
    own_gemini_key TEXT, -- AES-256-GCM encrypted, see backend/auth.js
    own_sarvam_key TEXT, -- AES-256-GCM encrypted, see backend/auth.js
    usage_count INTEGER NOT NULL DEFAULT 0,
    usage_period_start DATE NOT NULL DEFAULT CURRENT_DATE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_msisdn ON users(msisdn);

-- One row per external AI/SMS call, written by backend/usageTracker.js. Feeds the local cost
-- dashboard (D:\Medical_Admin_Dashboard) — cost_usd is an estimate computed at write time from
-- backend/pricing.js, not a real invoice line.
CREATE TABLE IF NOT EXISTS api_usage_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    provider VARCHAR(20) NOT NULL, -- 'gemini' | 'sarvam' | 'firebase'
    operation VARCHAR(50) NOT NULL, -- 'ocr' | 'chat' | 'tts' | 'compare' | 'detailed-analysis' | 'translate' | 'otp-verify' | ...
    model VARCHAR(100),
    input_tokens INTEGER,
    output_tokens INTEGER,
    units INTEGER, -- provider-specific fallback count (chars, pages, verifications) when tokens don't apply
    latency_ms INTEGER,
    cost_usd NUMERIC(12, 6) NOT NULL DEFAULT 0,
    success BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX IF NOT EXISTS idx_api_usage_events_created_at ON api_usage_events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_api_usage_events_provider ON api_usage_events(provider, created_at DESC);


