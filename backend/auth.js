import crypto from 'crypto';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
// Modular imports (not `import admin from 'firebase-admin'`): the CJS default import
// loses its properties (admin.credential === undefined) when esbuild bundles for ESM.
import { initializeApp, cert } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import db from './db.js';

const JWT_SECRET = process.env.JWT_SECRET || 'dev-insecure-secret-change-me';
// Derive a 32-byte key regardless of the length of the configured secret.
const ENCRYPTION_KEY = crypto.createHash('sha256').update(process.env.ENCRYPTION_KEY || JWT_SECRET).digest();

// Phone (MSISDN) OTP login/signup verifies a Firebase Phone Auth ID token — the phone number
// itself was already OTP-verified client-side by the Firebase SDK before this ever runs.
// Optional: stays uninitialized (and phone auth returns a clear 501) until the service
// account JSON is configured, so email/password auth keeps working either way.
let firebaseApp = null;
if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
  try {
    // Accept either raw JSON or base64-encoded JSON — base64 survives being passed
    // through `sam deploy --parameter-overrides`, which mangles quotes and spaces.
    let rawServiceAccount = process.env.FIREBASE_SERVICE_ACCOUNT_JSON.trim();
    if (!rawServiceAccount.startsWith('{')) {
      rawServiceAccount = Buffer.from(rawServiceAccount, 'base64').toString('utf8');
    }
    const serviceAccount = JSON.parse(rawServiceAccount);
    firebaseApp = initializeApp({ credential: cert(serviceAccount) });
  } catch (error) {
    console.error('Failed to initialize Firebase Admin (check FIREBASE_SERVICE_ACCOUNT_JSON):', error.message);
  }
}

export function isPhoneAuthConfigured() {
  return !!firebaseApp;
}

/** Verifies a Firebase Phone Auth ID token and returns the verified E.164 phone number. */
export async function verifyPhoneIdToken(idToken) {
  if (!firebaseApp) throw new Error('Phone auth is not configured on this server.');
  const decoded = await getAuth(firebaseApp).verifyIdToken(idToken);
  if (!decoded.phone_number) throw new Error('This sign-in token is not a verified phone number.');
  return decoded.phone_number;
}

// Native "Sign in with Google" (Android Credential Manager -> Firebase Auth) shares the same
// Firebase Admin setup as phone-OTP — both are Firebase ID tokens, just with different claims.
export function isGoogleAuthConfigured() {
  return !!firebaseApp;
}

/** Verifies a Firebase ID token produced by signing in with a Google credential and returns
 *  the verified email + display name pieces. */
export async function verifyGoogleSignInIdToken(idToken) {
  if (!firebaseApp) throw new Error('Google sign-in is not configured on this server.');
  const decoded = await getAuth(firebaseApp).verifyIdToken(idToken);
  if (!decoded.email) throw new Error('This sign-in token does not include a verified email.');
  return {
    email: String(decoded.email).toLowerCase(),
    firstName: decoded.given_name || decoded.name?.split(' ')?.[0] || 'Google',
    lastName: decoded.family_name || 'User',
  };
}

export async function hashPassword(password) {
  return bcrypt.hash(password, 10);
}

export async function verifyPassword(password, hash) {
  return bcrypt.compare(password, hash);
}

export function signToken(user) {
  return jwt.sign({ sub: user.id, email: user.email }, JWT_SECRET, { expiresIn: '30d' });
}

/** Encrypts a plaintext string (e.g. a user's own API key) for storage. */
export function encrypt(text) {
  if (!text) return null;
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', ENCRYPTION_KEY, iv);
  const encrypted = Buffer.concat([cipher.update(text, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, tag, encrypted]).toString('base64');
}

/** Reverses encrypt(). Returns null (and logs) if the value can't be decrypted. */
export function decrypt(payload) {
  if (!payload) return null;
  try {
    const buf = Buffer.from(payload, 'base64');
    const iv = buf.subarray(0, 12);
    const tag = buf.subarray(12, 28);
    const encrypted = buf.subarray(28);
    const decipher = crypto.createDecipheriv('aes-256-gcm', ENCRYPTION_KEY, iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(encrypted), decipher.final()]).toString('utf8');
  } catch (error) {
    console.error('Failed to decrypt stored key:', error.message);
    return null;
  }
}

/** Express middleware: verifies the Bearer JWT and loads the user row onto req.user. */
export async function requireAuth(req, res, next) {
  try {
    const header = req.headers.authorization || '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : null;
    if (!token) return res.status(401).json({ error: 'Authentication required.' });

    const payload = jwt.verify(token, JWT_SECRET);
    const result = await db.query('SELECT * FROM users WHERE id = $1', [payload.sub]);
    if (result.rows.length === 0) return res.status(401).json({ error: 'Invalid session.' });

    req.user = result.rows[0];
    next();
  } catch (error) {
    return res.status(401).json({ error: 'Invalid or expired session.' });
  }
}

/** Shape returned to the client for the logged-in user (never includes secrets). */
export function publicUser(u) {
  return {
    id: u.id,
    firstName: u.first_name,
    lastName: u.last_name,
    dateOfBirth: u.date_of_birth,
    gender: u.gender,
    email: u.email,
    msisdn: u.msisdn,
    plan: u.plan,
    hasOwnGeminiKey: !!u.own_gemini_key,
    hasOwnSarvamKey: !!u.own_sarvam_key,
    createdAt: u.created_at,
  };
}
