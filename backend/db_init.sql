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


