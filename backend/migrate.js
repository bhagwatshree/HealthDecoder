import db from './db.js';

async function runMigration() {
  console.log('Running database migrations...');
  try {
    const tableQuery = `
      CREATE TABLE IF NOT EXISTS medication_logs (
          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          patient_name VARCHAR(255) NOT NULL,
          medicine_name VARCHAR(255) NOT NULL,
          action_type VARCHAR(50) NOT NULL, -- 'TAKEN' or 'UPDATE_DETAILS'
          frequency VARCHAR(255),
          notes TEXT,
          taken_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
      );
    `;
    await db.query(tableQuery);
    console.log('Table medication_logs created or already exists.');

    // Add new columns to medical_reports table if they don't exist
    const reportColumnsQuery = `
      ALTER TABLE medical_reports ADD COLUMN IF NOT EXISTS test_results JSONB DEFAULT '[]'::jsonb;
      ALTER TABLE medical_reports ADD COLUMN IF NOT EXISTS comparison_result JSONB DEFAULT '{}'::jsonb;
      ALTER TABLE medical_reports ADD COLUMN IF NOT EXISTS report_category VARCHAR(100);
      ALTER TABLE medical_reports ADD COLUMN IF NOT EXISTS health_insights JSONB DEFAULT '{}'::jsonb;
      ALTER TABLE medical_reports ADD COLUMN IF NOT EXISTS detailed_analysis JSONB;
    `;
    await db.query(reportColumnsQuery);
    console.log('Columns test_results, comparison_result, report_category, health_insights, detailed_analysis checked/added to medical_reports table.');

    const indexQuery = `
      CREATE INDEX IF NOT EXISTS idx_medication_logs_patient_med ON medication_logs(patient_name, medicine_name);
    `;
    await db.query(indexQuery);
    console.log('Index idx_medication_logs_patient_med created or already exists.');

    // Composite index for "this patient's reports, newest first" and the
    // previous-report comparison lookup (patient + category + date).
    await db.query(`
      CREATE INDEX IF NOT EXISTS idx_medical_reports_patient_cat_date
          ON medical_reports(patient_name, report_category, report_date DESC);
    `);
    console.log('Index idx_medical_reports_patient_cat_date created or already exists.');

    // Full-text search index over patient, type, comments, and extracted OCR text.
    // The expression must stay identical to REPORT_SEARCH_VECTOR in server.js.
    await db.query(`
      CREATE INDEX IF NOT EXISTS idx_medical_reports_fts ON medical_reports USING GIN (
          to_tsvector('english',
              coalesce(patient_name, '') || ' ' || coalesce(report_type, '') || ' ' ||
              coalesce(comments, '') || ' ' || coalesce(extracted_text, ''))
      );
    `);
    console.log('Index idx_medical_reports_fts created or already exists.');

    // User accounts + per-user free-tier AI usage tracking.
    await db.query(`
      CREATE TABLE IF NOT EXISTS users (
          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          email VARCHAR(255) UNIQUE NOT NULL,
          password_hash TEXT NOT NULL,
          plan VARCHAR(20) NOT NULL DEFAULT 'free',
          own_gemini_key TEXT,
          own_sarvam_key TEXT,
          usage_count INTEGER NOT NULL DEFAULT 0,
          usage_period_start DATE NOT NULL DEFAULT CURRENT_DATE,
          created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
      );
    `);
    await db.query(`CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);`);
    console.log('Table users created or already exists.');

    // Registration profile fields + phone (MSISDN) login. Added as nullable so this is safe
    // to run against a users table that may already have rows; the API layer enforces these
    // as required for new signups. msisdn gets a UNIQUE index (partial, so it only applies to
    // non-null values) — combined with the existing UNIQUE on email, this pins each user row
    // to exactly one email and exactly one phone number, a true 1:1 pairing.
    const userProfileColumnsQuery = `
      ALTER TABLE users ADD COLUMN IF NOT EXISTS first_name VARCHAR(100);
      ALTER TABLE users ADD COLUMN IF NOT EXISTS last_name VARCHAR(100);
      ALTER TABLE users ADD COLUMN IF NOT EXISTS date_of_birth DATE;
      ALTER TABLE users ADD COLUMN IF NOT EXISTS gender VARCHAR(20);
      ALTER TABLE users ADD COLUMN IF NOT EXISTS msisdn VARCHAR(20);
    `;
    await db.query(userProfileColumnsQuery);
    await db.query(`
      DO $$ BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_gender_check') THEN
          ALTER TABLE users ADD CONSTRAINT users_gender_check
            CHECK (gender IN ('male', 'female', 'other', 'prefer_not_to_say'));
        END IF;
      END $$;
    `);
    await db.query(`CREATE UNIQUE INDEX IF NOT EXISTS idx_users_msisdn_unique ON users(msisdn) WHERE msisdn IS NOT NULL;`);
    console.log('Columns first_name, last_name, date_of_birth, gender, msisdn checked/added to users table.');

    // Google OAuth columns
    await db.query(`
      ALTER TABLE users ADD COLUMN IF NOT EXISTS google_email VARCHAR(255);
      ALTER TABLE users ADD COLUMN IF NOT EXISTS google_refresh_token TEXT;
    `);
    console.log('Columns google_email, google_refresh_token checked/added to users table.');


    // One row per external AI/SMS call — feeds the local cost dashboard (D:\Medical_Admin_Dashboard).
    await db.query(`
      CREATE TABLE IF NOT EXISTS api_usage_events (
          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
          user_id UUID REFERENCES users(id) ON DELETE SET NULL,
          provider VARCHAR(20) NOT NULL,
          operation VARCHAR(50) NOT NULL,
          model VARCHAR(100),
          input_tokens INTEGER,
          output_tokens INTEGER,
          units INTEGER,
          latency_ms INTEGER,
          cost_usd NUMERIC(12, 6) NOT NULL DEFAULT 0,
          success BOOLEAN NOT NULL DEFAULT true
      );
    `);
    await db.query(`CREATE INDEX IF NOT EXISTS idx_api_usage_events_created_at ON api_usage_events(created_at DESC);`);
    await db.query(`CREATE INDEX IF NOT EXISTS idx_api_usage_events_provider ON api_usage_events(provider, created_at DESC);`);
    console.log('Table api_usage_events created or already exists.');

    console.log('Database migration completed successfully!');
    process.exit(0);
  } catch (error) {
    console.error('Database migration failed:', error);
    process.exit(1);
  }
}

runMigration();
