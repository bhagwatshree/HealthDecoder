import pg from 'pg';
import dotenv from 'dotenv';
import { count, observe } from './metrics.js';

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

// An idle pooled connection dropped by Neon (autosuspend, a restart, a network blip) surfaces
// here rather than at any one query's call site. Without a listener, node-postgres treats it as
// an unhandled 'error' event and takes the whole process down - so this both keeps the Lambda
// alive and gives the DbConnectionErrors alarm the signal it watches for. Nothing to retry: the
// pool discards the dead client and the next query gets a fresh one.
pool.on('error', (err) => {
  count('DbConnectionErrors');
  console.error('Idle PostgreSQL client error:', err?.message || err);
});

// Test connection on startup
pool.query('SELECT NOW()', (err, res) => {
  if (err) {
    count('DbConnectionErrors');
    console.error('Error connecting to PostgreSQL database:', err.stack);
  } else {
    console.log('PostgreSQL connection established successfully at:', res.rows[0].now);
  }
});

/**
 * Every query in the app goes through here, so this is the one place that can see how the
 * database is actually behaving. Neon is not an AWS resource - CloudWatch publishes nothing
 * about it - so unless these three numbers are emitted from the client side, a degrading or
 * throttling database is invisible until users start reporting failures.
 *
 * Metrics only; the error is rethrown untouched so every existing caller's error handling
 * behaves exactly as it did before.
 */
async function query(text, params) {
  const start = Date.now();
  try {
    const result = await pool.query(text, params);
    count('DbQueries');
    observe('DbQueryLatencyMs', Date.now() - start);
    return result;
  } catch (error) {
    count('DbQueries');
    count('DbQueryErrors');
    observe('DbQueryLatencyMs', Date.now() - start);
    throw error;
  }
}

export default {
  query,
  pool,
};
