import pg from 'pg';
import dotenv from 'dotenv';

dotenv.config();

const { Pool } = pg;

// Neon (and most cloud Postgres hosts) require SSL/TLS. Auto-detect Lambda or
// a Neon connection string and enable SSL; skip for local dev.
const isCloud = !!process.env.AWS_LAMBDA_FUNCTION_NAME
  || (process.env.PGHOST || '').includes('neon')
  || process.env.PGSSLMODE === 'require';

// Setup database connection pool using environment variables
const pool = new Pool({
  // If DATABASE_URL is set (Neon gives you one), use it directly.
  ...(process.env.DATABASE_URL
    ? { connectionString: process.env.DATABASE_URL }
    : {
        user: process.env.PGUSER || 'postgres',
        password: process.env.PGPASSWORD || 'postgres',
        host: process.env.PGHOST || 'localhost',
        port: parseInt(process.env.PGPORT || '5432', 10),
        database: process.env.PGDATABASE || 'medical_db',
      }),
  ssl: isCloud ? { rejectUnauthorized: false } : false,
});

// Test connection on startup
pool.query('SELECT NOW()', (err, res) => {
  if (err) {
    console.error('Error connecting to PostgreSQL database:', err.stack);
  } else {
    console.log('PostgreSQL connection established successfully at:', res.rows[0].now);
  }
});

export default {
  query: (text, params) => pool.query(text, params),
  pool,
};
