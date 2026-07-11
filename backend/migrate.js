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

    console.log('Database migration completed successfully!');
    process.exit(0);
  } catch (error) {
    console.error('Database migration failed:', error);
    process.exit(1);
  }
}

runMigration();
