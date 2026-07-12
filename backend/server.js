import express from 'express';
import cors from 'cors';
import multer from 'multer';
import path from 'path';
import fs from 'fs';
import { fileURLToPath } from 'url';
import dotenv from 'dotenv';
import db from './db.js';
import { scanMedicalReport, generateReportComparison, generateHealthInsights, generateChatResponse, generateDetailedAnalysis, generateMedicineInfo, generateSpeech } from './ocr.js';
import { hashPassword, verifyPassword, signToken, requireAuth, encrypt, decrypt, publicUser, verifyPhoneIdToken, isPhoneAuthConfigured, verifyGoogleSignInIdToken, isGoogleAuthConfigured } from './auth.js';
import crypto from 'crypto';

import { resolveKeysForUser, peekAssignmentForUser } from './keyPool.js';
import { runWithUsageContext, trackFirebaseVerify } from './usageTracker.js';
import jwt from 'jsonwebtoken';

dotenv.config();

const JWT_SECRET = process.env.JWT_SECRET || 'dev-insecure-secret-change-me';


// Helpers for paths in ES Modules. Named __srcFilename/__srcDirname (not __filename/__dirname)
// because the Lambda bundle (see template.yaml's esbuild Banner) injects its own top-level
// __filename/__dirname for the whole bundled file — esbuild can't see inside that raw banner
// text to rename around a collision, so declaring the standard names here a second time
// causes "SyntaxError: Identifier '__filename' has already been declared" at Lambda cold start.
const __srcFilename = fileURLToPath(import.meta.url);
const __srcDirname = path.dirname(__srcFilename);

const app = express();
const PORT = process.env.PORT || 3000;

// Enable CORS for mobile app connectivity
app.use(cors());
app.use(express.json());

// Use /tmp on Lambda (the only writable directory), local uploads/ folder otherwise.
const isLambda = !!process.env.AWS_LAMBDA_FUNCTION_NAME;
const uploadsDir = isLambda
  ? path.join('/tmp', 'uploads')
  : path.join(__srcDirname, 'uploads');
if (!fs.existsSync(uploadsDir)) {
  fs.mkdirSync(uploadsDir, { recursive: true });
}

// Serve uploaded images statically (local dev only — on Lambda, images stay on the device)
if (!isLambda) {
  app.use('/uploads', express.static(uploadsDir));
}

// Setup Multer for file uploads
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, uploadsDir);
  },
  filename: (req, file, cb) => {
    // Generate a unique filename: timestamp + original extension
    const uniqueSuffix = Date.now() + '-' + Math.round(Math.random() * 1e9);
    const ext = path.extname(file.originalname);
    cb(null, `report-${uniqueSuffix}${ext}`);
  },
});

const upload = multer({
  storage,
  limits: { fileSize: 10 * 1024 * 1024 }, // 10MB limit
  fileFilter: (req, file, cb) => {
    const allowedTypes = /jpeg|jpg|png|webp|pdf/;
    const ext = path.extname(file.originalname).toLowerCase();
    const mimeType = file.mimetype;
    
    if (allowedTypes.test(ext) || allowedTypes.test(mimeType)) {
      cb(null, true);
    } else {
      cb(new Error('Only images (JPEG, PNG, WEBP) and PDFs are allowed'));
    }
  },
});

// Test Endpoint
app.get('/api/health', (req, res) => {
  res.json({ status: 'healthy', timestamp: new Date() });
});

// ─── Auth & per-user free tier ────────────────────────────────────────────────
// The Android app calls Gemini/Sarvam directly from the phone (not proxied through this
// server) — these routes exist so a logged-in user's phone can ask "which key should I use
// right now", with the server tracking free-tier usage and handing back either a personal
// key (if the user added one) or a key from the pooled house rotation. See keyPool.js.

const VALID_GENDERS = ['male', 'female', 'other', 'prefer_not_to_say'];

app.post('/api/auth/signup', async (req, res) => {
  try {
    const { firstName, lastName, dateOfBirth, gender, email, password, phoneIdToken } = req.body || {};

    if (!firstName || !String(firstName).trim() || !lastName || !String(lastName).trim()) {
      return res.status(400).json({ error: 'First name and last name are required.' });
    }
    if (!dateOfBirth || Number.isNaN(Date.parse(dateOfBirth))) {
      return res.status(400).json({ error: 'A valid date of birth is required.' });
    }
    if (!VALID_GENDERS.includes(gender)) {
      return res.status(400).json({ error: 'A valid gender is required.' });
    }
    if (!email || !String(email).trim() || !password || String(password).length < 6) {
      return res.status(400).json({ error: 'A valid email and a password of at least 6 characters are required.' });
    }
    if (!phoneIdToken) {
      return res.status(400).json({ error: 'Phone verification is required.' });
    }
    if (!isPhoneAuthConfigured()) {
      return res.status(501).json({ error: 'Phone verification is not configured on this server yet.' });
    }

    let msisdn;
    try {
      msisdn = await verifyPhoneIdToken(String(phoneIdToken));
      trackFirebaseVerify(null, true);
    } catch (error) {
      trackFirebaseVerify(null, false);
      return res.status(401).json({ error: 'Phone verification failed. Please request a new OTP and try again.' });
    }

    const normalizedEmail = String(email).trim().toLowerCase();

    // Email and MSISDN each get their own uniqueness check so we can return which one collided
    // — both are UNIQUE at the DB level too (see db_init.sql) as the source of truth.
    const existingEmail = await db.query('SELECT id FROM users WHERE email = $1', [normalizedEmail]);
    if (existingEmail.rows.length > 0) {
      return res.status(409).json({ error: 'An account with this email already exists.' });
    }
    const existingPhone = await db.query('SELECT id FROM users WHERE msisdn = $1', [msisdn]);
    if (existingPhone.rows.length > 0) {
      return res.status(409).json({ error: 'An account with this phone number already exists.' });
    }

    const passwordHash = await hashPassword(String(password));
    const result = await db.query(
      `INSERT INTO users (first_name, last_name, date_of_birth, gender, email, msisdn, password_hash)
       VALUES ($1, $2, $3, $4, $5, $6, $7) RETURNING *`,
      [String(firstName).trim(), String(lastName).trim(), dateOfBirth, gender, normalizedEmail, msisdn, passwordHash]
    );
    const user = result.rows[0];
    res.status(201).json({ token: signToken(user), user: publicUser(user) });
  } catch (error) {
    // Race condition: two signups for the same email/phone landing concurrently past the
    // pre-checks above — the DB's UNIQUE constraints are the real guard here.
    if (error.code === '23505') {
      return res.status(409).json({ error: 'An account with this email or phone number already exists.' });
    }
    console.error('Signup failed:', error);
    res.status(500).json({ error: 'Failed to create account' });
  }
});

app.post('/api/auth/login', async (req, res) => {
  try {
    const { email, password } = req.body || {};
    if (!email || !password) {
      return res.status(400).json({ error: 'Email and password are required.' });
    }
    const result = await db.query('SELECT * FROM users WHERE email = $1', [String(email).trim().toLowerCase()]);
    const user = result.rows[0];
    if (!user || !(await verifyPassword(String(password), user.password_hash))) {
      return res.status(401).json({ error: 'Invalid email or password.' });
    }
    res.json({ token: signToken(user), user: publicUser(user) });
  } catch (error) {
    console.error('Login failed:', error);
    res.status(500).json({ error: 'Failed to log in' });
  }
});

// Phone+OTP login: the phone number was already OTP-verified client-side by the Firebase SDK;
// this just confirms that verification (via the ID token) and looks up the linked account.
app.post('/api/auth/login-phone', async (req, res) => {
  try {
    const { phoneIdToken } = req.body || {};
    if (!phoneIdToken) {
      return res.status(400).json({ error: 'Phone verification token is required.' });
    }
    if (!isPhoneAuthConfigured()) {
      return res.status(501).json({ error: 'Phone verification is not configured on this server yet.' });
    }

    let msisdn;
    try {
      msisdn = await verifyPhoneIdToken(String(phoneIdToken));
      trackFirebaseVerify(null, true);
    } catch (error) {
      trackFirebaseVerify(null, false);
      return res.status(401).json({ error: 'Phone verification failed. Please request a new OTP and try again.' });
    }

    const result = await db.query('SELECT * FROM users WHERE msisdn = $1', [msisdn]);
    const user = result.rows[0];
    if (!user) {
      return res.status(404).json({ error: 'No account found for this phone number. Sign up first.' });
    }
    res.json({ token: signToken(user), user: publicUser(user) });
  } catch (error) {
    console.error('Phone login failed:', error);
    res.status(500).json({ error: 'Failed to log in' });
  }
});

// Native "Sign in with Google": the device already picked an account and produced a Firebase
// ID token via Credential Manager + FirebaseAuth.signInWithCredential — no browser, no OAuth
// client secret. This only proves identity (email); it never requests Gmail scope/refresh
// tokens — that's a separate, deliberately browser-based flow (see /api/auth/google below),
// needed only when a user opts into email scanning.
app.post('/api/auth/google-signin', async (req, res) => {
  try {
    const { idToken } = req.body || {};
    if (!idToken) {
      return res.status(400).json({ error: 'Google ID token is required.' });
    }
    if (!isGoogleAuthConfigured()) {
      return res.status(501).json({ error: 'Google sign-in is not configured on this server yet.' });
    }

    let email, firstName, lastName;
    try {
      ({ email, firstName, lastName } = await verifyGoogleSignInIdToken(String(idToken)));
      trackFirebaseVerify(null, true);
    } catch (error) {
      trackFirebaseVerify(null, false);
      return res.status(401).json({ error: 'Google sign-in verification failed. Please try again.' });
    }

    const existing = await db.query('SELECT * FROM users WHERE email = $1 OR google_email = $1', [email]);
    let user = existing.rows[0];

    if (!user) {
      const msisdnHash = crypto.createHash('md5').update(email).digest('hex').substring(0, 10);
      const dummyMsisdn = `google_${msisdnHash}`;
      const dummyPasswordHash = await hashPassword(crypto.randomBytes(16).toString('hex'));

      const inserted = await db.query(
        `INSERT INTO users (first_name, last_name, date_of_birth, gender, email, msisdn, password_hash, google_email)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8) RETURNING *`,
        [firstName, lastName, '2000-01-01', 'prefer_not_to_say', email, dummyMsisdn, dummyPasswordHash, email]
      );
      user = inserted.rows[0];
    } else if (!user.google_email) {
      await db.query('UPDATE users SET google_email = $1 WHERE id = $2', [email, user.id]);
      user.google_email = email;
    }

    res.json({ token: signToken(user), user: publicUser(user) });
  } catch (error) {
    console.error('Google sign-in failed:', error);
    res.status(500).json({ error: 'Failed to sign in with Google.' });
  }
});

app.get('/api/auth/me', requireAuth, (req, res) => {
  res.json(publicUser(req.user));
});

app.post('/api/auth/reset-password-otp', async (req, res) => {
  try {
    const { phoneIdToken, newPassword } = req.body || {};
    if (!phoneIdToken || !newPassword || String(newPassword).length < 6) {
      return res.status(400).json({ error: 'Phone verification token and a password of at least 6 characters are required.' });
    }
    if (!isPhoneAuthConfigured()) {
      return res.status(501).json({ error: 'Phone verification is not configured on this server.' });
    }

    let msisdn;
    try {
      msisdn = await verifyPhoneIdToken(String(phoneIdToken));
      trackFirebaseVerify(null, true);
    } catch (error) {
      trackFirebaseVerify(null, false);
      return res.status(401).json({ error: 'Phone verification failed. Please try again.' });
    }

    const passwordHash = await hashPassword(String(newPassword));
    const result = await db.query(
      'UPDATE users SET password_hash = $1 WHERE msisdn = $2 RETURNING id',
      [passwordHash, msisdn]
    );

    if (result.rowCount === 0) {
      return res.status(404).json({ error: 'No account found linked to this phone number.' });
    }

    res.json({ success: true, message: 'Password reset successfully.' });
  } catch (error) {
    console.error('Reset password failed:', error);
    res.status(500).json({ error: 'Failed to reset password.' });
  }
});

app.post('/api/auth/change-password', requireAuth, async (req, res) => {
  try {
    const { currentPassword, newPassword } = req.body || {};
    if (!currentPassword || !newPassword || String(newPassword).length < 6) {
      return res.status(400).json({ error: 'Current password and a new password of at least 6 characters are required.' });
    }

    const result = await db.query('SELECT password_hash FROM users WHERE id = $1', [req.user.id]);
    const user = result.rows[0];
    if (!user || !(await verifyPassword(String(currentPassword), user.password_hash))) {
      return res.status(401).json({ error: 'Incorrect current password.' });
    }

    const passwordHash = await hashPassword(String(newPassword));
    await db.query('UPDATE users SET password_hash = $1 WHERE id = $2', [passwordHash, req.user.id]);

    res.json({ success: true, message: 'Password updated successfully.' });
  } catch (error) {
    console.error('Change password failed:', error);
    res.status(500).json({ error: 'Failed to change password.' });
  }
});

// Helper to get Google redirect URI dynamically
const getGoogleRedirectUri = (req) => {
  const host = req.get('host');
  const protocol = req.protocol === 'https' || host.includes('execute-api') || host.includes('ngrok') ? 'https' : 'http';
  return `${protocol}://${host}/api/auth/google/callback`;
};

// 1. Redirect user to Google for authentication
app.get('/api/auth/google', (req, res) => {
  const clientId = process.env.GOOGLE_CLIENT_ID;
  const clientSecret = process.env.GOOGLE_CLIENT_SECRET;
  if (!clientId || !clientSecret) {
    return res.status(400).send(`
      <html>
        <body style="font-family: sans-serif; padding: 40px; text-align: center; background-color: #f8f9fa;">
          <div style="max-width: 500px; margin: auto; padding: 30px; border-radius: 12px; background: white; box-shadow: 0 4px 6px rgba(0,0,0,0.1);">
            <h2 style="color: #d32f2f; margin-top: 0;">Google OAuth Not Configured</h2>
            <p style="color: #495057; font-size: 15px; line-height: 1.6;">
              Please configure the Google OAuth client credentials on the server. Add the following variables to your <code>.env</code> file:
            </p>
            <pre style="background: #e9ecef; padding: 12px; border-radius: 6px; text-align: left; font-size: 14px;">GOOGLE_CLIENT_ID=your_client_id<br>GOOGLE_CLIENT_SECRET=your_client_secret</pre>
            <p style="color: #6c757d; font-size: 13px;">Once added, restart your server and try again.</p>
            <button onclick="window.close()" style="margin-top: 15px; padding: 10px 24px; font-size: 15px; font-weight: bold; color: white; background: #007bff; border: none; border-radius: 6px; cursor: pointer;">Close Window</button>
          </div>
        </body>
      </html>
    `);
  }

  const state = req.query.state || 'login';
  const redirectUri = getGoogleRedirectUri(req);
  // gmail.readonly (not the full https://mail.google.com/ IMAP scope) — narrower scope means
  // Google's standard verification is enough; IMAP access needs the "restricted" mail.google.com
  // scope, which additionally requires a paid third-party security assessment. The tradeoff is
  // that email scanning must go through the Gmail REST API instead of raw IMAP — see
  // GmailApiClient.kt / EmailScanWorker.kt on the Android side.
  const scope = 'openid email profile https://www.googleapis.com/auth/gmail.readonly';
  
  const googleAuthUrl = `https://accounts.google.com/o/oauth2/v2/auth?` + 
    `client_id=${clientId}&` +
    `redirect_uri=${encodeURIComponent(redirectUri)}&` +
    `response_type=code&` +
    `scope=${encodeURIComponent(scope)}&` +
    `access_type=offline&` +
    `prompt=consent&` +
    `state=${encodeURIComponent(state)}`;

  res.redirect(googleAuthUrl);
});

// 2. Google Redirect Callback Handler
app.get('/api/auth/google/callback', async (req, res) => {
  try {
    const { code, state } = req.query;
    if (!code) {
      return res.status(400).send('Authorization code not provided from Google.');
    }

    const clientId = process.env.GOOGLE_CLIENT_ID;
    const clientSecret = process.env.GOOGLE_CLIENT_SECRET;
    const redirectUri = getGoogleRedirectUri(req);

    // Exchange authorization code for tokens
    const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        code,
        client_id: clientId,
        client_secret: clientSecret,
        redirect_uri: redirectUri,
        grant_type: 'authorization_code'
      })
    });

    if (!tokenResponse.ok) {
      const errorText = await tokenResponse.text();
      console.error('Failed to exchange Google OAuth code:', errorText);
      return res.status(tokenResponse.status).send(`Failed to authenticate with Google: ${errorText}`);
    }

    const tokens = await tokenResponse.json();
    const idToken = tokens.id_token;
    if (!idToken) {
      return res.status(400).send('No ID token received from Google.');
    }

    // Decode ID token payload (no network request needed, safely decode base64)
    const payloadParts = idToken.split('.');
    if (payloadParts.length < 2) {
      return res.status(400).send('Invalid ID token format.');
    }
    const idTokenPayload = JSON.parse(Buffer.from(payloadParts[1], 'base64').toString('utf8'));
    
    const email = idTokenPayload.email?.toLowerCase();
    if (!email) {
      return res.status(400).send('Google did not return an email address.');
    }

    const givenName = idTokenPayload.given_name || 'Google';
    const familyName = idTokenPayload.family_name || 'User';
    const encryptedRefreshToken = tokens.refresh_token ? encrypt(tokens.refresh_token) : null;

    let targetUser = null;

    // Check if we are linking an account or logging in
    if (state && state.startsWith('link|')) {
      const jwtToken = state.split('|')[1];
      let loggedInUser = null;
      try {
        const payload = jwt.verify(jwtToken, JWT_SECRET);
        const result = await db.query('SELECT * FROM users WHERE id = $1', [payload.sub]);
        loggedInUser = result.rows[0];
      } catch (e) {
        return res.status(401).send('Session expired. Please log in again from the app settings page before linking Google.');
      }

      if (!loggedInUser) {
        return res.status(401).send('User not found.');
      }

      // Link the Google account details
      const updateQuery = encryptedRefreshToken 
        ? 'UPDATE users SET google_email = $1, google_refresh_token = $2 WHERE id = $3 RETURNING *'
        : 'UPDATE users SET google_email = $1 WHERE id = $2 RETURNING *';
      
      const updateParams = encryptedRefreshToken
        ? [email, encryptedRefreshToken, loggedInUser.id]
        : [email, loggedInUser.id];
      
      const updateResult = await db.query(updateQuery, updateParams);
      targetUser = updateResult.rows[0];

      // Redirect back to app linking callback
      const appLinkUrl = `medicalscanner://oauth2-link?google_email=${encodeURIComponent(email)}&google_access_token=${encodeURIComponent(tokens.access_token)}`;
      return res.redirect(appLinkUrl);
    }

    // Default: Login flow
    // Find if user already exists (by email, or by google_email)
    const existingUserRes = await db.query(
      'SELECT * FROM users WHERE email = $1 OR google_email = $1',
      [email]
    );

    if (existingUserRes.rows.length > 0) {
      targetUser = existingUserRes.rows[0];
      // Update Google refresh token if we got a new one during this authentication
      if (encryptedRefreshToken) {
        await db.query(
          'UPDATE users SET google_email = $1, google_refresh_token = $2 WHERE id = $3',
          [email, encryptedRefreshToken, targetUser.id]
        );
      }
    } else {
      // Create new user (automatically generate details)
      const dummyDob = '2000-01-01';
      const dummyGender = 'prefer_not_to_say';
      // derive msisdn: unique string, max 20 chars
      const msisdnHash = crypto.createHash('md5').update(email).digest('hex').substring(0, 10);
      const dummyMsisdn = `google_${msisdnHash}`;
      const dummyPassword = crypto.randomBytes(16).toString('hex');
      const dummyPasswordHash = await hashPassword(dummyPassword);

      const insertRes = await db.query(
        `INSERT INTO users (first_name, last_name, date_of_birth, gender, email, msisdn, password_hash, google_email, google_refresh_token)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING *`,
        [givenName, familyName, dummyDob, dummyGender, email, dummyMsisdn, dummyPasswordHash, email, encryptedRefreshToken]
      );
      targetUser = insertRes.rows[0];
    }

    const appSessionToken = signToken(targetUser);
    
    // Redirect back to the android app
    const appUrl = `medicalscanner://oauth2?` + 
      `token=${encodeURIComponent(appSessionToken)}&` +
      `email=${encodeURIComponent(targetUser.email)}&` +
      `google_email=${encodeURIComponent(email)}&` +
      `google_access_token=${encodeURIComponent(tokens.access_token)}`;

    res.redirect(appUrl);
  } catch (error) {
    console.error('Google OAuth callback failed:', error);
    res.status(500).send(`Authentication failed: ${error.message}`);
  }
});

// 3. Authenticated endpoint to fetch a refreshed access token for the linked Gmail account
app.get('/api/auth/google/token', requireAuth, async (req, res) => {
  try {
    const user = req.user;
    if (!user.google_refresh_token) {
      return res.status(400).json({ error: 'No Google account linked to this user.' });
    }

    const decryptedRefreshToken = decrypt(user.google_refresh_token);
    if (!decryptedRefreshToken) {
      return res.status(500).json({ error: 'Failed to decrypt refresh token.' });
    }

    const clientId = process.env.GOOGLE_CLIENT_ID;
    const clientSecret = process.env.GOOGLE_CLIENT_SECRET;
    if (!clientId || !clientSecret) {
      return res.status(500).json({ error: 'Google OAuth client credentials are not configured on the server.' });
    }

    // Request fresh access token from Google
    const response = await fetch('https://oauth2.googleapis.com/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: clientId,
        client_secret: clientSecret,
        refresh_token: decryptedRefreshToken,
        grant_type: 'refresh_token'
      })
    });

    if (!response.ok) {
      const errorText = await response.text();
      console.error('Failed to refresh Google access token:', errorText);
      return res.status(response.status).json({ error: `Failed to refresh Google access token: ${errorText}` });
    }

    const data = await response.json();
    res.json({ access_token: data.access_token });
  } catch (error) {
    console.error('Failed to refresh Google access token:', error);
    res.status(500).json({ error: 'Internal server error while refreshing token.' });
  }
});



// Issues the Gemini/Sarvam key the phone should use right now, and accounts for free-tier
// usage. Call once per app session (or once/day) rather than before every AI call.
app.get('/api/auth/keys', requireAuth, async (req, res) => {
  try {
    const resolved = await resolveKeysForUser(req.user);
    res.json(resolved);
  } catch (error) {
    console.error('Error resolving keys for user:', error);
    res.status(500).json({ error: 'Failed to resolve API keys' });
  }
});

// Read-only usage/quota display (e.g. the Account screen) — unlike /api/auth/keys, this
// never consumes a free-tier issuance, so it's safe to call every time the screen opens.
app.get('/api/auth/usage', requireAuth, async (req, res) => {
  try {
    const usage = await peekAssignmentForUser(req.user);
    res.json(usage);
  } catch (error) {
    console.error('Error reading usage for user:', error);
    res.status(500).json({ error: 'Failed to read usage' });
  }
});

app.post('/api/user/gemini-key', requireAuth, async (req, res) => {
  try {
    const { api_key } = req.body || {};
    const value = api_key && String(api_key).trim() ? encrypt(String(api_key).trim()) : null;
    await db.query('UPDATE users SET own_gemini_key = $1 WHERE id = $2', [value, req.user.id]);
    res.json({ message: value ? 'Gemini API key saved.' : 'Gemini API key removed.', hasOwnGeminiKey: !!value });
  } catch (error) {
    console.error('Error saving Gemini key:', error);
    res.status(500).json({ error: 'Failed to save Gemini API key' });
  }
});

app.post('/api/user/sarvam-key', requireAuth, async (req, res) => {
  try {
    const { api_key } = req.body || {};
    const value = api_key && String(api_key).trim() ? encrypt(String(api_key).trim()) : null;
    await db.query('UPDATE users SET own_sarvam_key = $1 WHERE id = $2', [value, req.user.id]);
    res.json({ message: value ? 'Sarvam API key saved.' : 'Sarvam API key removed.', hasOwnSarvamKey: !!value });
  } catch (error) {
    console.error('Error saving Sarvam key:', error);
    res.status(500).json({ error: 'Failed to save Sarvam API key' });
  }
});

// Endpoint: Medicine info — what a tablet is for, in the patient's local language
app.post('/api/medicine-info', async (req, res) => {
  try {
    const { medicine_name, language } = req.body || {};
    if (!medicine_name || !String(medicine_name).trim()) {
      return res.status(400).json({ error: 'medicine_name is required.' });
    }
    const info = await runWithUsageContext({ userId: req.user?.id, operation: 'medicine-info' }, () =>
      generateMedicineInfo(String(medicine_name).trim(), language || 'English'));
    res.json(info);
  } catch (error) {
    console.error('Error generating medicine info:', error);
    res.status(500).json({ error: 'Failed to generate medicine info', details: error.message });
  }
});

// Endpoint: Text-to-speech (Sarvam) — speaks the answer in the local language
app.post('/api/tts', async (req, res) => {
  try {
    const { text, language, engine } = req.body || {};
    if (!text || !String(text).trim()) {
      return res.status(400).json({ error: 'text is required.' });
    }
    const result = await runWithUsageContext({ userId: req.user?.id, operation: 'tts' }, () =>
      generateSpeech(String(text), language || 'English', engine || 'sarvam'));
    res.json(result);
  } catch (error) {
    console.error('Error generating speech:', error);
    res.status(500).json({ error: 'Failed to generate speech', details: error.message });
  }
});

// Endpoint: AI Chat — patient asks plain-language questions about their records
app.post('/api/chat', async (req, res) => {
  try {
    const { question, patient_name, report_id, history, language } = req.body || {};
    if (!question || !String(question).trim()) {
      return res.status(400).json({ error: 'A question is required.' });
    }

    // Gather grounding context: a specific report, a patient's history, or recent reports.
    let reports = [];
    if (report_id) {
      const r = await db.query('SELECT * FROM medical_reports WHERE id = $1', [report_id]);
      reports = r.rows;
    } else if (patient_name && String(patient_name).trim()) {
      const r = await db.query(
        `SELECT * FROM medical_reports WHERE patient_name ILIKE $1
         ORDER BY report_date DESC, created_at DESC LIMIT 15`,
        [patient_name]
      );
      reports = r.rows;
    } else {
      const r = await db.query(
        'SELECT * FROM medical_reports ORDER BY report_date DESC, created_at DESC LIMIT 15'
      );
      reports = r.rows;
    }

    const result = await runWithUsageContext({ userId: req.user?.id, operation: 'chat' }, () =>
      generateChatResponse(String(question), reports, Array.isArray(history) ? history : [], language || 'English'));
    res.json({ answer: result.answer, source: result.source });
  } catch (error) {
    console.error('Error handling chat request:', error);
    res.status(500).json({ error: 'Failed to generate chat response', details: error.message });
  }
});

// Endpoint: Scan & Save Medical Report
app.post('/api/reports', upload.single('image'), async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ error: 'Please upload an image file of the medical report.' });
    }

    const filePath = req.file.path;
    const mimeType = req.file.mimetype;
    const webPath = `/uploads/${req.file.filename}`;

    const useSarvam = req.body.use_sarvam === 'true';
    const localOcrText = req.body.local_ocr_text || '';
    const scanType = req.body.scan_type || '';
    const reportCategory = req.body.report_category || '';

    console.log(`Starting scan for uploaded file: ${req.file.filename} (scanType: ${scanType}, category: ${reportCategory})`);
    
    // Call the OCR/AI extraction utility
    const ocrData = await runWithUsageContext({ userId: req.user?.id, operation: 'ocr' }, () =>
      scanMedicalReport(filePath, mimeType, useSarvam, localOcrText, scanType, reportCategory));
    
    // Extract values with sensible defaults
    const patientName = ocrData.patientName || 'Unknown Patient';
    
    // Parse the date accurately, fallback to null or current date if invalid
    let reportDate = null;
    if (ocrData.reportDate) {
      const parsedDate = new Date(ocrData.reportDate);
      if (!isNaN(parsedDate.getTime())) {
        reportDate = ocrData.reportDate;
      }
    }
    if (!reportDate) {
      reportDate = new Date().toISOString().split('T')[0]; // fallback to today
    }

    const reportType = ocrData.reportType || (scanType === 'prescription' ? 'Prescription' : 'Other');
    const extractedText = ocrData.rawText || '';
    const comments = ocrData.comments || '';
    const medications = ocrData.medications || [];

    // Normalize category
    let category = reportCategory;
    if (!category) {
      if (reportType === 'Prescription') {
        category = 'prescription';
      } else {
        category = 'other';
      }
    }

    // Fetch previous report of the same category for the same patient to run comparison
    let previousReport = null;
    try {
      const prevResult = await db.query(
        `SELECT * FROM medical_reports 
         WHERE patient_name = $1 
           AND (report_category = $2 OR (report_category IS NULL AND report_type = 'Prescription' AND $2 = 'prescription'))
           AND report_date < $3::date
         ORDER BY report_date DESC, created_at DESC 
         LIMIT 1`,
        [patientName, category, reportDate]
      );
      previousReport = prevResult.rows[0];
    } catch (prevError) {
      console.error('Error fetching previous report for comparison:', prevError);
    }

    // Generate comparison result + health insights (interpretation, specialist recommendations,
    // alignment, side-effects) — billed under the same 'ocr' operation as the scan itself.
    const { comparisonResult, healthInsights } = await runWithUsageContext({ userId: req.user?.id, operation: 'ocr' }, async () => ({
      comparisonResult: await generateReportComparison({
        patient_name: patientName,
        report_date: reportDate,
        report_type: reportType,
        report_category: category,
        medications: medications,
        test_results: ocrData.testResults || { parameters: [], findings: [] },
        comments: comments
      }, previousReport),
      healthInsights: await generateHealthInsights({
        patient_name: patientName,
        report_date: reportDate,
        report_type: reportType,
        report_category: category,
        medications: medications,
        test_results: ocrData.testResults || { parameters: [], findings: [] },
        comments: comments
      }),
    }));

    // Insert into PostgreSQL database
    const queryText = `
      INSERT INTO medical_reports 
        (patient_name, report_date, report_type, extracted_text, comments, medications, image_path, test_results, comparison_result, report_category, health_insights)
      VALUES 
        ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
      RETURNING *;
    `;

    const values = [
      patientName,
      reportDate,
      reportType,
      extractedText,
      comments,
      JSON.stringify(medications),
      webPath,
      JSON.stringify(ocrData.testResults || { parameters: [], findings: [] }),
      JSON.stringify(comparisonResult),
      category,
      JSON.stringify(healthInsights)
    ];

    const dbResult = await db.query(queryText, values);
    const savedReport = dbResult.rows[0];

    // 1. Auto-insert recommended tests from OCR
    if (ocrData.recommendedTests && Array.isArray(ocrData.recommendedTests)) {
      for (const test of ocrData.recommendedTests) {
        if (test.testName) {
          const testDueDate = test.dueDate || null;
          await db.query(
            `INSERT INTO pending_tests (patient_name, test_name, due_date, status) 
             VALUES ($1, $2, $3, 'Pending')`,
            [patientName, test.testName, testDueDate]
          );
          console.log(`Auto-added pending test: ${test.testName} for ${patientName}`);
        }
      }
    }

    // 2. Auto-resolve matching pending tests for this patient
    try {
      const pendingRes = await db.query(
        `SELECT id, test_name FROM pending_tests 
         WHERE patient_name = $1 AND status = 'Pending'`,
        [patientName]
      );
      for (const pending of pendingRes.rows) {
        const testNameClean = pending.test_name.toLowerCase().replace(/test|profile|check/g, '').trim();
        const rawTextClean = extractedText.toLowerCase();
        const commentsClean = comments.toLowerCase();
        const typeClean = reportType.toLowerCase();

        if (
          testNameClean.length > 2 && 
          (rawTextClean.includes(testNameClean) || commentsClean.includes(testNameClean) || typeClean.includes(testNameClean))
        ) {
          await db.query(
            `UPDATE pending_tests 
             SET status = 'Completed', resolved_report_id = $1 
             WHERE id = $2`,
            [savedReport.id, pending.id]
          );
          console.log(`Auto-resolved pending test: ${pending.test_name} for ${patientName}`);
        }
      }
    } catch (resolveError) {
      console.error('Error auto-resolving pending tests:', resolveError);
    }

    console.log(`Successfully processed and saved report: ${savedReport.id}`);
    res.status(201).json(savedReport);

  } catch (error) {
    console.error('Error processing medical report upload:', error);
    res.status(500).json({ error: 'Failed to process and scan medical report', details: error.message });
  }
});

// Endpoint: Delete ALL data (reports, pending tests, medication logs, and uploaded images)
app.delete('/api/data/all', async (req, res) => {
  try {
    await db.query('TRUNCATE TABLE medication_logs, pending_tests, medical_reports RESTART IDENTITY CASCADE;');

    // Remove uploaded image files.
    let removedFiles = 0;
    try {
      for (const f of fs.readdirSync(uploadsDir)) {
        try { fs.unlinkSync(path.join(uploadsDir, f)); removedFiles++; } catch (_) {}
      }
    } catch (_) {}

    console.log(`Deleted all data. Removed ${removedFiles} image file(s).`);
    res.json({ message: 'All data deleted', removedFiles });
  } catch (error) {
    console.error('Error deleting all data:', error);
    res.status(500).json({ error: 'Failed to delete all data', details: error.message });
  }
});

// Endpoint: Get All Reports History
// Optional query params (default behavior unchanged for existing clients):
//   ?summary=true   – return only the light list columns (no OCR text / analysis JSON)
//   ?limit=&offset= – paginate
app.get('/api/reports', async (req, res) => {
  try {
    const summary = req.query.summary === 'true';
    const limit = Math.min(parseInt(req.query.limit, 10) || 0, 500);
    const offset = Math.max(parseInt(req.query.offset, 10) || 0, 0);

    const cols = summary
      ? 'id, patient_name, report_date, report_type, report_category, image_path, created_at'
      : '*';
    let sql = `SELECT ${cols} FROM medical_reports ORDER BY report_date DESC, created_at DESC`;
    const params = [];
    if (limit > 0) { params.push(limit); sql += ` LIMIT $${params.length}`; }
    if (offset > 0) { params.push(offset); sql += ` OFFSET $${params.length}`; }

    const result = await db.query(sql, params);
    res.json(result.rows);
  } catch (error) {
    console.error('Error fetching reports history:', error);
    res.status(500).json({ error: 'Failed to fetch reports history' });
  }
});

// Full-text search expression; must stay identical to the GIN index expression in
// db_init.sql / migrate.js so Postgres can serve the query from the index.
const REPORT_SEARCH_VECTOR = `to_tsvector('english',
  coalesce(patient_name, '') || ' ' || coalesce(report_type, '') || ' ' ||
  coalesce(comments, '') || ' ' || coalesce(extracted_text, ''))`;

// Endpoint: Full-text search over reports (patient, type, comments, OCR text).
// NOTE: registered before /api/reports/:id so "search" isn't captured as an id.
app.get('/api/reports/search', async (req, res) => {
  try {
    const q = String(req.query.q || '').trim();
    if (!q) return res.json([]);
    const limit = Math.min(parseInt(req.query.limit, 10) || 50, 200);

    const result = await db.query(
      `SELECT id, patient_name, report_date, report_type, report_category, image_path, created_at,
              ts_rank(${REPORT_SEARCH_VECTOR}, websearch_to_tsquery('english', $1)) AS rank
       FROM medical_reports
       WHERE ${REPORT_SEARCH_VECTOR} @@ websearch_to_tsquery('english', $1)
       ORDER BY rank DESC, report_date DESC
       LIMIT $2`,
      [q, limit]
    );
    res.json(result.rows);
  } catch (error) {
    console.error('Error searching reports:', error);
    res.status(500).json({ error: 'Failed to search reports' });
  }
});

// Endpoint: Get Specific Report Detail
app.get('/api/reports/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const result = await db.query('SELECT * FROM medical_reports WHERE id = $1', [id]);
    
    if (result.rows.length === 0) {
      return res.status(404).json({ error: 'Report not found' });
    }
    
    res.json(result.rows[0]);
  } catch (error) {
    console.error('Error fetching report details:', error);
    res.status(500).json({ error: 'Failed to fetch report details' });
  }
});

// Ensures the on-demand detailed_analysis cache column exists (runs lazily, once).
let detailedAnalysisColumnReady = false;
async function ensureDetailedAnalysisColumn() {
  if (detailedAnalysisColumnReady) return;
  await db.query(`ALTER TABLE medical_reports ADD COLUMN IF NOT EXISTS detailed_analysis JSONB;`);
  detailedAnalysisColumnReady = true;
}

// Endpoint: On-demand Detailed Analysis for a single report (generated + cached separately
// from the lightweight inline health_insights). Pass ?refresh=true to regenerate.
app.get('/api/reports/:id/detailed-analysis', async (req, res) => {
  try {
    const { id } = req.params;
    const refresh = req.query.refresh === 'true';

    await ensureDetailedAnalysisColumn();

    const result = await db.query('SELECT * FROM medical_reports WHERE id = $1', [id]);
    if (result.rows.length === 0) {
      return res.status(404).json({ error: 'Report not found' });
    }
    const report = result.rows[0];

    // Serve cached analysis unless a refresh was explicitly requested.
    let cached = report.detailed_analysis;
    if (typeof cached === 'string') {
      try { cached = JSON.parse(cached); } catch (_) { cached = null; }
    }
    if (cached && cached.sections && cached.sections.length > 0 && !refresh) {
      return res.json({ ...cached, cached: true });
    }

    const analysis = await runWithUsageContext({ userId: req.user?.id, operation: 'detailed-analysis' }, () =>
      generateDetailedAnalysis(report));
    analysis.generatedAt = new Date().toISOString();

    // Persist so repeat views don't re-call the AI.
    try {
      await db.query('UPDATE medical_reports SET detailed_analysis = $1 WHERE id = $2', [JSON.stringify(analysis), id]);
    } catch (persistErr) {
      console.error('Failed to cache detailed analysis:', persistErr.message);
    }

    res.json({ ...analysis, cached: false });
  } catch (error) {
    console.error('Error generating detailed analysis:', error);
    res.status(500).json({ error: 'Failed to generate detailed analysis', details: error.message });
  }
});

// Endpoint: Update Report (for manual corrections)
app.put('/api/reports/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const { patient_name, report_date, report_type, comments, medications, extracted_text, test_results, report_category } = req.body;

    // The UPDATE below clears the cached detailed analysis, so make sure that column exists.
    await ensureDetailedAnalysisColumn();

    // Normalize category
    let category = report_category;
    if (!category) {
      if (report_type === 'Prescription') {
        category = 'prescription';
      } else {
        const currentReportRes = await db.query('SELECT report_category FROM medical_reports WHERE id = $1', [id]);
        category = (currentReportRes.rows[0] && currentReportRes.rows[0].report_category) || 'other';
      }
    }

    const currentTestResults = test_results || { parameters: [], findings: [] };

    // Recalculate comparison with previous report
    let previousReport = null;
    try {
      const prevResult = await db.query(
        `SELECT * FROM medical_reports 
         WHERE patient_name = $1 
           AND (report_category = $2 OR (report_category IS NULL AND report_type = 'Prescription' AND $2 = 'prescription'))
           AND report_date < $3::date
           AND id != $4
         ORDER BY report_date DESC, created_at DESC 
         LIMIT 1`,
        [patient_name, category, report_date, id]
      );
      previousReport = prevResult.rows[0];
    } catch (prevError) {
      console.error('Error fetching previous report for comparison during update:', prevError);
    }

    const { comparisonResult, healthInsights } = await runWithUsageContext({ userId: req.user?.id, operation: 'ocr' }, async () => ({
      comparisonResult: await generateReportComparison({
        patient_name,
        report_date,
        report_type,
        report_category: category,
        medications: medications || [],
        test_results: currentTestResults,
        comments
      }, previousReport),
      // Regenerate health insights
      healthInsights: await generateHealthInsights({
        patient_name,
        report_date,
        report_type,
        report_category: category,
        medications: medications || [],
        test_results: currentTestResults,
        comments
      }),
    }));

    const queryText = `
      UPDATE medical_reports 
      SET 
        patient_name = $1, 
        report_date = $2, 
        report_type = $3, 
        comments = $4, 
        medications = $5,
        extracted_text = $6,
        test_results = $7,
        comparison_result = $8,
        report_category = $9,
        health_insights = $10,
        detailed_analysis = NULL
      WHERE id = $11
      RETURNING *;
    `;
    
    const values = [
      patient_name,
      report_date,
      report_type,
      comments,
      JSON.stringify(medications || []),
      extracted_text,
      JSON.stringify(currentTestResults),
      JSON.stringify(comparisonResult),
      category,
      JSON.stringify(healthInsights),
      id
    ];
    
    const result = await db.query(queryText, values);
    
    if (result.rows.length === 0) {
      return res.status(404).json({ error: 'Report not found' });
    }
    
    console.log(`Updated report details for id: ${id}`);
    res.json(result.rows[0]);
  } catch (error) {
    console.error('Error updating report:', error);
    res.status(500).json({ error: 'Failed to update report details' });
  }
});

// Endpoint: Delete Report
app.delete('/api/reports/:id', async (req, res) => {
  try {
    const { id } = req.params;
    
    // First, find the report to get the image path
    const findResult = await db.query('SELECT image_path FROM medical_reports WHERE id = $1', [id]);
    
    if (findResult.rows.length === 0) {
      return res.status(404).json({ error: 'Report not found' });
    }
    
    const relativeImagePath = findResult.rows[0].image_path;
    const absoluteImagePath = path.join(__srcDirname, relativeImagePath);
    
    // Delete database record
    await db.query('DELETE FROM medical_reports WHERE id = $1', [id]);
    
    // Delete local file if it exists
    if (fs.existsSync(absoluteImagePath)) {
      fs.unlinkSync(absoluteImagePath);
      console.log(`Deleted local file: ${absoluteImagePath}`);
    }
    
    console.log(`Deleted database record for id: ${id}`);
    res.json({ message: 'Report deleted successfully' });
  } catch (error) {
    console.error('Error deleting report:', error);
    res.status(500).json({ error: 'Failed to delete report' });
  }
});

// Endpoint: Get Dashboard Data (Reports, Medications Tracker, Pending Tests)
app.get('/api/dashboard', async (req, res) => {
  try {
    // Optional period filter: 1m, 3m, 6m, 1y, all
    const { period } = req.query;
    let dateFilter = '';
    if (period && period !== 'all') {
      const monthsMap = { '1m': 1, '3m': 3, '6m': 6, '1y': 12 };
      const months = monthsMap[period];
      if (months) {
        dateFilter = `AND report_date >= NOW() - INTERVAL '${months} months'`;
      }
    }

    // 1. Get reports (period-filtered)
    const reportsRes = await db.query(
      `SELECT * FROM medical_reports WHERE 1=1 ${dateFilter} ORDER BY report_date DESC, created_at DESC`
    );
    // Normalize any reports that have test_results/comparison_result stored as [] from old schema
    const reports = reportsRes.rows.map(r => ({
      ...r,
      test_results: Array.isArray(r.test_results) ? { parameters: [], findings: [] } : (r.test_results || { parameters: [], findings: [] }),
      comparison_result: Array.isArray(r.comparison_result) ? { hasComparison: false } : (r.comparison_result || { hasComparison: false }),
      health_insights: Array.isArray(r.health_insights) ? {} : (r.health_insights || {})
    }));

    // 2. Get pending tests
    const pendingRes = await db.query('SELECT * FROM pending_tests ORDER BY due_date ASC, created_at DESC');
    const pendingTests = pendingRes.rows;

    // 3. Process Medication Dosage History
    const historyRes = await db.query(
      'SELECT id, patient_name, medications, report_date FROM medical_reports ORDER BY report_date ASC, created_at ASC'
    );
    
    const medicationHistory = [];
    const patientMedMap = {}; // patientName -> { medName -> [dosages...] }
    const latestReportDate = {}; // patientName -> latestReportDate

    // Build chronological dosage lists
    for (const row of historyRes.rows) {
      const patient = row.patient_name || 'Unknown Patient';
      const date = row.report_date;

      let meds = [];
      try {
        meds = typeof row.medications === 'string' ? JSON.parse(row.medications) : row.medications;
      } catch (e) {
        meds = [];
      }

      if (!Array.isArray(meds)) meds = [];

      // Only medicine-carrying reports (prescriptions) advance the latest snapshot;
      // otherwise a newer lab report marks every medicine "Discontinued".
      if (meds.length === 0) continue;

      if (!latestReportDate[patient] || new Date(date) >= new Date(latestReportDate[patient])) {
        latestReportDate[patient] = date;
      }

      if (!patientMedMap[patient]) {
        patientMedMap[patient] = {};
      }

      for (const med of meds) {
        if (!med.name) continue;
        const medName = med.name.trim();
        
        if (!patientMedMap[patient][medName]) {
          patientMedMap[patient][medName] = [];
        }
        
        patientMedMap[patient][medName].push({
          reportId: row.id,
          dosage: med.dosage || '1 tablet',
          frequency: med.frequency || '',
          duration: med.duration || '',
          isOptional: med.isOptional || false,
          weeklySchedule: med.weeklySchedule || [],
          notes: med.notes || '',
          date: date
        });
      }
    }

    // Classify changes and build output
    for (const patient of Object.keys(patientMedMap)) {
      const latestDate = latestReportDate[patient];
      
      for (const medName of Object.keys(patientMedMap[patient])) {
        const historyList = patientMedMap[patient][medName];
        if (historyList.length === 0) continue;

        const current = historyList[historyList.length - 1];
        let previous = null;
        
        // Search backwards for a different dosage/frequency to establish previous dosage
        if (historyList.length > 1) {
          for (let k = historyList.length - 2; k >= 0; k--) {
            if (historyList[k].dosage !== current.dosage || historyList[k].frequency !== current.frequency) {
              previous = historyList[k];
              break;
            }
          }
          // Default to the immediate previous one if all were identical
          if (!previous) {
            previous = historyList[historyList.length - 2];
          }
        }

        const isOmitted = new Date(current.date).getTime() < new Date(latestDate).getTime();
        
        let status = 'Active';
        if (isOmitted) {
          status = 'Discontinued';
        } else if (previous && (previous.dosage !== current.dosage || previous.frequency !== current.frequency)) {
          status = 'Changed';
        }

        medicationHistory.push({
          reportId: current.reportId,
          patientName: patient,
          medicineName: medName,
          currentDosage: current.dosage,
          currentFrequency: current.frequency,
          currentDuration: current.duration,
          isOptional: current.isOptional || false,
          weeklySchedule: current.weeklySchedule || [],
          notes: current.notes || '',
          previousDosage: previous ? previous.dosage : '',
          previousFrequency: previous ? previous.frequency : '',
          status: status,
          lastUpdated: current.date
        });
      }
    }

    // 4. Extract recent test inferences for dashboard summary
    const testInferences = reports
      .filter(r => r.comparison_result && r.comparison_result.hasComparison)
      .map(r => ({
        reportId: r.id,
        patientName: r.patient_name,
        reportDate: r.report_date,
        reportCategory: r.report_category,
        summary: r.comparison_result.comparisonSummary,
        status: r.comparison_result.status
      }));

    res.json({
      reports,
      pendingTests,
      medicationHistory,
      testInferences: testInferences.slice(0, 5) // Send the top 5 recent clinical insights
    });

  } catch (error) {
    console.error('Error fetching dashboard data:', error);
    res.status(500).json({ error: 'Failed to retrieve dashboard data' });
  }
});

// Endpoint: Health Summary — Trends across a time window
app.get('/api/health-summary', async (req, res) => {
  try {
    const { patient_name, period } = req.query;
    if (!patient_name) {
      return res.status(400).json({ error: 'patient_name query parameter is required.' });
    }

    const monthsMap = { '1m': 1, '3m': 3, '6m': 6, '1y': 12 };
    const months = monthsMap[period] || null;
    const dateFilter = months ? `AND report_date >= NOW() - INTERVAL '${months} months'` : '';

    const reportsRes = await db.query(
      `SELECT id, patient_name, report_date, report_type, report_category, test_results, medications, comparison_result, health_insights, comments
       FROM medical_reports
       WHERE patient_name ILIKE $1 ${dateFilter}
       ORDER BY report_date ASC, created_at ASC`,
      [patient_name]
    );
    const reports = reportsRes.rows;

    if (reports.length === 0) {
      return res.json({ overallNarrative: 'No reports found for this patient in the selected period.', parameterTrends: [], medicationTimeline: [], activeFlags: [] });
    }

    // ── Build parameter trends ────────────────────────────────────────────────
    const paramMap = {}; // paramName -> [{date, value}]
    for (const r of reports) {
      let testResults = r.test_results;
      if (typeof testResults === 'string') testResults = JSON.parse(testResults);
      const params = testResults?.parameters || [];
      const dateStr = r.report_date instanceof Date ? r.report_date.toISOString().split('T')[0] : String(r.report_date).split('T')[0];
      for (const p of params) {
        if (!paramMap[p.name]) paramMap[p.name] = [];
        paramMap[p.name].push({ date: dateStr, value: p.value, unit: p.unit || '', status: p.status || '' });
      }
    }

    const parameterTrends = Object.entries(paramMap).map(([name, dataPoints]) => {
      // Determine trend direction from first to last numeric value
      let trend = 'stable';
      const numericPoints = dataPoints.filter(d => !isNaN(parseFloat(d.value)));
      if (numericPoints.length >= 2) {
        const first = parseFloat(numericPoints[0].value);
        const last = parseFloat(numericPoints[numericPoints.length - 1].value);
        const latestStatus = numericPoints[numericPoints.length - 1].status.toLowerCase();
        const prevStatus = numericPoints[0].status.toLowerCase();
        if (latestStatus === 'normal' && prevStatus !== 'normal') trend = 'improving';
        else if (latestStatus !== 'normal' && prevStatus === 'normal') trend = 'worsening';
        else if (Math.abs(last - first) / (Math.abs(first) || 1) < 0.05) trend = 'stable';
        else if (last < first) trend = 'decreasing';
        else trend = 'increasing';
      }
      return { name, dataPoints, trend };
    });

    // ── Build medication timeline ──────────────────────────────────────────────
    const medicationTimeline = [];
    let prevMedSet = {};
    for (const r of reports) {
      let meds = r.medications;
      if (typeof meds === 'string') meds = JSON.parse(meds);
      if (!Array.isArray(meds)) meds = [];
      const dateStr = r.report_date instanceof Date ? r.report_date.toISOString().split('T')[0] : String(r.report_date).split('T')[0];

      const currentMedSet = {};
      for (const m of meds) { currentMedSet[m.name] = m; }

      const added = meds.filter(m => !prevMedSet[m.name]).map(m => m.name);
      const removed = Object.keys(prevMedSet).filter(n => !currentMedSet[n]);
      const changed = meds
        .filter(m => prevMedSet[m.name] && (prevMedSet[m.name].dosage !== m.dosage || prevMedSet[m.name].frequency !== m.frequency))
        .map(m => `${m.name} (${prevMedSet[m.name].dosage} → ${m.dosage})`);

      if (added.length || removed.length || changed.length || medicationTimeline.length === 0) {
        medicationTimeline.push({
          date: dateStr,
          reportId: r.id,
          reportCategory: r.report_category,
          added,
          removed,
          changed,
          activeMedicines: meds.map(m => ({ name: m.name, dosage: m.dosage, frequency: m.frequency }))
        });
      }
      prevMedSet = currentMedSet;
    }

    // ── Collect active flags from health insights ─────────────────────────────
    const activeFlags = [];
    for (const r of reports.slice().reverse()) { // Most recent first
      let hi = r.health_insights;
      if (typeof hi === 'string') hi = JSON.parse(hi);
      if (hi && hi.prescriptionAlignment && hi.prescriptionAlignment.flags) {
        for (const flag of hi.prescriptionAlignment.flags) {
          if (!activeFlags.includes(flag)) activeFlags.push(flag);
        }
      }
      if (activeFlags.length >= 5) break;
    }

    // ── Build overall narrative ───────────────────────────────────────────────
    const worsening = parameterTrends.filter(t => t.trend === 'worsening');
    const improving = parameterTrends.filter(t => t.trend === 'improving');
    const allMeds = (medicationTimeline[medicationTimeline.length - 1]?.activeMedicines || []).map(m => m.name).join(', ');
    const periodLabel = monthsMap[period] ? `the last ${period.replace('m', ' month').replace('1y', '1 year')}` : 'all time';

    let overallNarrative = `Over ${periodLabel}, ${reports.length} report(s) were recorded for ${patient_name}. `;
    if (improving.length > 0) overallNarrative += `${improving.map(t => t.name).join(', ')} show${improving.length === 1 ? 's' : ''} improvement. `;
    if (worsening.length > 0) overallNarrative += `${worsening.map(t => t.name).join(', ')} show${worsening.length === 1 ? 's' : ''} a worsening trend — consult your doctor. `;
    if (improving.length === 0 && worsening.length === 0 && parameterTrends.length > 0) overallNarrative += 'Test parameters remain stable. ';
    if (allMeds) overallNarrative += `Currently active medicines: ${allMeds}.`;

    res.json({ overallNarrative, parameterTrends, medicationTimeline, activeFlags });

  } catch (error) {
    console.error('Error building health summary:', error);
    res.status(500).json({ error: 'Failed to build health summary', details: error.message });
  }
});

// Endpoint: Create Pending Test manually
app.post('/api/pending-tests', async (req, res) => {
  try {
    const { patient_name, test_name, due_date } = req.body;
    if (!patient_name || !test_name) {
      return res.status(400).json({ error: 'Patient name and Test name are required.' });
    }
    
    const query = `
      INSERT INTO pending_tests (patient_name, test_name, due_date, status)
      VALUES ($1, $2, $3, 'Pending')
      RETURNING *;
    `;
    const result = await db.query(query, [patient_name, test_name, due_date || null]);
    res.status(201).json(result.rows[0]);
  } catch (error) {
    console.error('Error creating pending test:', error);
    res.status(500).json({ error: 'Failed to create pending test' });
  }
});

// Endpoint: Manually resolve pending test
app.post('/api/pending-tests/:id/resolve', async (req, res) => {
  try {
    const { id } = req.params;
    const { resolved_report_id } = req.body;
    
    const query = `
      UPDATE pending_tests 
      SET status = 'Completed', resolved_report_id = $1 
      WHERE id = $2
      RETURNING *;
    `;
    const result = await db.query(query, [resolved_report_id || null, id]);
    if (result.rows.length === 0) {
      return res.status(404).json({ error: 'Pending test not found' });
    }
    res.json(result.rows[0]);
  } catch (error) {
    console.error('Error resolving pending test:', error);
    res.status(500).json({ error: 'Failed to resolve pending test' });
  }
});

// Endpoint: Delete pending test
app.delete('/api/pending-tests/:id', async (req, res) => {
  try {
    const { id } = req.params;
    const result = await db.query('DELETE FROM pending_tests WHERE id = $1 RETURNING *', [id]);
    if (result.rows.length === 0) {
      return res.status(404).json({ error: 'Pending test not found' });
    }
    res.json({ message: 'Pending test deleted successfully' });
  } catch (error) {
    console.error('Error deleting pending test:', error);
    res.status(500).json({ error: 'Failed to delete pending test' });
  }
});

// Endpoint: Log Medication Intake/Activity
app.post('/api/medications/log', async (req, res) => {
  try {
    const { patientName, medicineName, actionType, frequency, notes } = req.body;
    if (!patientName || !medicineName || !actionType) {
      return res.status(400).json({ error: 'patientName, medicineName, and actionType are required.' });
    }

    const query = `
      INSERT INTO medication_logs (patient_name, medicine_name, action_type, frequency, notes)
      VALUES ($1, $2, $3, $4, $5)
      RETURNING *;
    `;
    const result = await db.query(query, [patientName, medicineName, actionType, frequency || null, notes || null]);
    res.status(201).json(result.rows[0]);
  } catch (error) {
    console.error('Error logging medication activity:', error);
    res.status(500).json({ error: 'Failed to log medication activity' });
  }
});

// Endpoint: Fetch Medication Logs (history)
app.get('/api/medications/log', async (req, res) => {
  try {
    const { patientName, medicineName } = req.query;
    if (!patientName || !medicineName) {
      return res.status(400).json({ error: 'patientName and medicineName are query parameters.' });
    }

    const query = `
      SELECT id, patient_name, medicine_name, action_type, frequency, notes, taken_at
      FROM medication_logs
      WHERE patient_name = $1 AND medicine_name = $2
      ORDER BY taken_at DESC;
    `;
    const result = await db.query(query, [patientName, medicineName]);
    res.json(result.rows);
  } catch (error) {
    console.error('Error fetching medication logs:', error);
    res.status(500).json({ error: 'Failed to fetch medication logs' });
  }
});

// Endpoint: Update Medication Details in report JSONB
app.post('/api/medications/update', async (req, res) => {
  try {
    const { patientName, medicineName, reportId, dosage, frequency, duration, isOptional, weeklySchedule, notes } = req.body;
    if (!patientName || !medicineName || !reportId) {
      return res.status(400).json({ error: 'patientName, medicineName, and reportId are required.' });
    }

    // Fetch existing report
    const result = await db.query('SELECT medications FROM medical_reports WHERE id = $1', [reportId]);
    if (result.rows.length === 0) {
      return res.status(404).json({ error: 'Report not found' });
    }

    let medications = result.rows[0].medications;
    if (typeof medications === 'string') {
      medications = JSON.parse(medications);
    }
    if (!Array.isArray(medications)) {
      medications = [];
    }

    let updated = false;
    medications = medications.map(med => {
      if (med.name && med.name.trim().toLowerCase() === medicineName.trim().toLowerCase()) {
        updated = true;
        return {
          ...med,
          dosage: dosage !== undefined ? dosage : med.dosage,
          frequency: frequency !== undefined ? frequency : med.frequency,
          duration: duration !== undefined ? duration : med.duration,
          isOptional: isOptional !== undefined ? isOptional : med.isOptional,
          weeklySchedule: weeklySchedule !== undefined ? weeklySchedule : med.weeklySchedule,
          notes: notes !== undefined ? notes : med.notes
        };
      }
      return med;
    });

    if (!updated) {
      // Add a new entry to the medications array
      medications.push({
        name: medicineName,
        dosage: dosage || '',
        frequency: frequency || '',
        duration: duration || '',
        isOptional: !!isOptional,
        weeklySchedule: weeklySchedule || ['Everyday'],
        notes: notes || ''
      });
    }

    // Save back to db
    await db.query('UPDATE medical_reports SET medications = $1 WHERE id = $2', [JSON.stringify(medications), reportId]);

    // Insert update activity to medication logs
    await db.query(
      `INSERT INTO medication_logs (patient_name, medicine_name, action_type, frequency, notes)
       VALUES ($1, $2, 'UPDATE_DETAILS', $3, $4)`,
      [patientName, medicineName, frequency || '', notes || `Dosage: ${dosage || ''}, Duration: ${duration || ''}`]
    );

    res.json({ message: 'Medication updated successfully', medications });
  } catch (error) {
    console.error('Error updating medication details:', error);
    res.status(500).json({ error: 'Failed to update medication details' });
  }
});

// Helper: load, transform, and save a report's medications JSONB array.
async function mutateReportMedications(reportId, transform) {
  const r = await db.query('SELECT medications FROM medical_reports WHERE id = $1', [reportId]);
  if (r.rows.length === 0) return 0;
  let meds = r.rows[0].medications;
  if (typeof meds === 'string') { try { meds = JSON.parse(meds); } catch (_) { meds = []; } }
  if (!Array.isArray(meds)) meds = [];
  const { medications, changed } = transform(meds);
  if (changed > 0) {
    await db.query('UPDATE medical_reports SET medications = $1 WHERE id = $2', [JSON.stringify(medications), reportId]);
  }
  return changed;
}

// Groups bulk items ({ reportId, medicineName, ... }) by reportId.
function groupItemsByReport(items) {
  const byReport = {};
  for (const it of items) {
    if (!it || !it.reportId || !it.medicineName) continue;
    if (!byReport[it.reportId]) byReport[it.reportId] = [];
    byReport[it.reportId].push(it);
  }
  return byReport;
}

// Endpoint: Bulk delete medications from their reports (multi-select)
app.post('/api/medications/bulk-delete', async (req, res) => {
  try {
    const { items } = req.body || {};
    if (!Array.isArray(items) || items.length === 0) {
      return res.status(400).json({ error: 'items array (with reportId & medicineName) is required.' });
    }

    const byReport = groupItemsByReport(items);
    let removed = 0;
    for (const [reportId, list] of Object.entries(byReport)) {
      const names = list.map(it => it.medicineName.trim().toLowerCase());
      removed += await mutateReportMedications(reportId, (meds) => {
        const before = meds.length;
        const medications = meds.filter(m => !(m.name && names.includes(m.name.trim().toLowerCase())));
        return { medications, changed: before - medications.length };
      });
    }

    res.json({ message: 'Selected medications deleted', removed });
  } catch (error) {
    console.error('Error bulk-deleting medications:', error);
    res.status(500).json({ error: 'Failed to delete medications', details: error.message });
  }
});

// Endpoint: Bulk update medications, e.g. change frequency/schedule for several at once
app.post('/api/medications/bulk-update', async (req, res) => {
  try {
    const { items } = req.body || {};
    if (!Array.isArray(items) || items.length === 0) {
      return res.status(400).json({ error: 'items array (with reportId & medicineName) is required.' });
    }

    const byReport = groupItemsByReport(items);
    let updated = 0;
    for (const [reportId, list] of Object.entries(byReport)) {
      updated += await mutateReportMedications(reportId, (meds) => {
        let changed = 0;
        const medications = meds.map(m => {
          const match = list.find(it => it.medicineName && m.name &&
            it.medicineName.trim().toLowerCase() === m.name.trim().toLowerCase());
          if (!match) return m;
          changed++;
          return {
            ...m,
            dosage: match.dosage !== undefined ? match.dosage : m.dosage,
            frequency: match.frequency !== undefined ? match.frequency : m.frequency,
            duration: match.duration !== undefined ? match.duration : m.duration,
            isOptional: match.isOptional !== undefined ? match.isOptional : m.isOptional,
            weeklySchedule: match.weeklySchedule !== undefined ? match.weeklySchedule : m.weeklySchedule,
            notes: match.notes !== undefined ? match.notes : m.notes,
          };
        });
        return { medications, changed };
      });

      // Log each change for history/audit.
      for (const it of list) {
        try {
          await db.query(
            `INSERT INTO medication_logs (patient_name, medicine_name, action_type, frequency, notes)
             VALUES ($1, $2, 'BULK_UPDATE', $3, $4)`,
            [it.patientName || 'Unknown Patient', it.medicineName, it.frequency || '', 'Bulk edit']
          );
        } catch (_) { /* logging is best-effort */ }
      }
    }

    res.json({ message: 'Selected medications updated', updated });
  } catch (error) {
    console.error('Error bulk-updating medications:', error);
    res.status(500).json({ error: 'Failed to update medications', details: error.message });
  }
});

// Endpoint: Compare two uploaded reports/images directly (no DB save)
app.post('/api/compare', upload.fields([
  { name: 'report1', maxCount: 1 },
  { name: 'report2', maxCount: 1 }
]), async (req, res) => {
  try {
    const files = req.files;
    if (!files || !files.report1 || !files.report2) {
      return res.status(400).json({ error: 'Both report1 and report2 files are required.' });
    }

    const file1 = files.report1[0];
    const file2 = files.report2[0];

    const scanType1 = req.body.scan_type_1 || '';
    const scanType2 = req.body.scan_type_2 || '';
    const category1 = req.body.report_category_1 || 'other';
    const category2 = req.body.report_category_2 || 'other';

    console.log(`Compare request: ${file1.filename} vs ${file2.filename}`);

    // OCR both in parallel
    const [ocr1, ocr2] = await runWithUsageContext({ userId: req.user?.id, operation: 'compare' }, () => Promise.all([
      scanMedicalReport(file1.path, file1.mimetype, false, '', scanType1, category1),
      scanMedicalReport(file2.path, file2.mimetype, false, '', scanType2, category2)
    ]));

    const report1Data = {
      patient_name: ocr1.patientName || 'Report 1',
      report_date: ocr1.reportDate || new Date().toISOString().split('T')[0],
      report_type: ocr1.reportType || 'Report',
      report_category: category1,
      medications: ocr1.medications || [],
      test_results: ocr1.testResults || { parameters: [], findings: [] },
      comments: ocr1.comments || '',
      raw_text: ocr1.rawText || ''
    };

    const report2Data = {
      patient_name: ocr2.patientName || 'Report 2',
      report_date: ocr2.reportDate || new Date().toISOString().split('T')[0],
      report_type: ocr2.reportType || 'Report',
      report_category: category2,
      medications: ocr2.medications || [],
      test_results: ocr2.testResults || { parameters: [], findings: [] },
      comments: ocr2.comments || '',
      raw_text: ocr2.rawText || ''
    };

    // Treat report1 as "previous" and report2 as "new" for comparison
    const comparison = await runWithUsageContext({ userId: req.user?.id, operation: 'compare' }, () =>
      generateReportComparison(report2Data, { id: 'compare-temp', ...report1Data }));

    // Delete temp uploaded files since we're not saving to DB
    try { fs.unlinkSync(file1.path); } catch (_) {}
    try { fs.unlinkSync(file2.path); } catch (_) {}

    res.json({ report1: report1Data, report2: report2Data, comparison });

  } catch (error) {
    console.error('Error comparing reports:', error);
    res.status(500).json({ error: 'Failed to compare reports', details: error.message });
  }
});

// Global Error Handler for Multer errors
app.use((err, req, res, next) => {
  if (err instanceof multer.MulterError) {
    return res.status(400).json({ error: `File upload error: ${err.message}` });
  }
  if (err) {
    return res.status(400).json({ error: err.message });
  }
  next();
});

// Start listening only when run directly (not when imported by lambda.js on AWS)
if (!isLambda) {
  app.listen(PORT, '0.0.0.0', () => {
    console.log(`Backend server running on http://0.0.0.0:${PORT}`);
    console.log(`Test connection locally at http://localhost:${PORT}/api/health`);
  });
}

export default app;
