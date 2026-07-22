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
    google_email VARCHAR(255),
    google_refresh_token TEXT,
    -- Bumped on password change so previously-issued JWTs (which embed the version at sign
    -- time, see backend/auth.js) stop verifying immediately instead of staying valid for the
    -- rest of their 30-day expiry.
    token_version INTEGER NOT NULL DEFAULT 0,
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

-- Static UI-chrome translations (button labels, titles, empty states) — the source of truth
-- for the app's non-AI-generated text. Editing a row here changes that string for every
-- install; the app fetches this table once on first launch and again whenever the user
-- picks a language, then caches it on-device. AI-generated content (chat answers, report
-- summaries) is translated separately, live, via the Sarvam API — not stored here.
CREATE TABLE IF NOT EXISTS ui_translations (
    id SERIAL PRIMARY KEY,
    language VARCHAR(30) NOT NULL,
    text_key TEXT NOT NULL,
    translated_text TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (language, text_key)
);

CREATE INDEX IF NOT EXISTS idx_ui_translations_language ON ui_translations(language);


-- Seed data (safe to re-run; existing rows are left untouched so manual DB edits stick)
INSERT INTO ui_translations (language, text_key, translated_text) VALUES
    ('Hindi', 'Scan Report', 'रिपोर्ट स्कैन करें'),
    ('Hindi', 'Records', 'रिकॉर्ड्स'),
    ('Hindi', 'Reminders', 'रिमाइंडर'),
    ('Hindi', 'Medication Tracker', 'दवा ट्रैकर'),
    ('Hindi', 'Pending Tests', 'लंबित जांच'),
    ('Hindi', 'Trends', 'रुझान'),
    ('Hindi', 'Account', 'खाता'),
    ('Hindi', 'Ask AI Assistant', 'एआई सहायक से पूछें'),
    ('Hindi', 'Ask about this page', 'इस पेज के बारे में पूछें'),
    ('Hindi', 'Compare Reports', 'रिपोर्ट की तुलना करें'),
    ('Hindi', 'Refresh', 'रीफ्रेश करें'),
    ('Hindi', 'Back', 'वापस'),
    ('Hindi', 'Search', 'खोजें'),
    ('Hindi', 'Today''s Meds', 'आज की दवाएं'),
    ('Hindi', 'Reports History', 'रिपोर्ट इतिहास'),
    ('Hindi', 'All Time', 'सभी समय'),
    ('Hindi', '1 Month', '1 महीना'),
    ('Hindi', '3 Months', '3 महीने'),
    ('Hindi', '6 Months', '6 महीने'),
    ('Marathi', 'Scan Report', 'अहवाल स्कॅन करा'),
    ('Marathi', 'Records', 'नोंदी'),
    ('Marathi', 'Reminders', 'स्मरणपत्रे'),
    ('Marathi', 'Medication Tracker', 'औषध ट्रॅकर'),
    ('Marathi', 'Pending Tests', 'प्रलंबित चाचण्या'),
    ('Marathi', 'Trends', 'कल'),
    ('Marathi', 'Account', 'खाते'),
    ('Marathi', 'Ask AI Assistant', 'एआय सहाय्यकाला विचारा'),
    ('Marathi', 'Ask about this page', 'या पानाबद्दल विचारा'),
    ('Marathi', 'Compare Reports', 'अहवालांची तुलना करा'),
    ('Marathi', 'Refresh', 'रिफ्रेश करा'),
    ('Marathi', 'Back', 'मागे'),
    ('Marathi', 'Search', 'शोधा'),
    ('Marathi', 'Today''s Meds', 'आजची औषधे'),
    ('Marathi', 'Reports History', 'अहवाल इतिहास'),
    ('Marathi', 'All Time', 'सर्व काळ'),
    ('Marathi', '1 Month', '1 महिना'),
    ('Marathi', '3 Months', '3 महिने'),
    ('Marathi', '6 Months', '6 महिने'),
    ('Gujarati', 'Scan Report', 'રિપોર્ટ સ્કેન કરો'),
    ('Gujarati', 'Records', 'રેકોર્ડ્સ'),
    ('Gujarati', 'Reminders', 'રિમાઇન્ડર્સ'),
    ('Gujarati', 'Medication Tracker', 'દવા ટ્રેકર'),
    ('Gujarati', 'Pending Tests', 'બાકી પરીક્ષણો'),
    ('Gujarati', 'Trends', 'વલણો'),
    ('Gujarati', 'Account', 'ખાતું'),
    ('Gujarati', 'Ask AI Assistant', 'AI સહાયકને પૂછો'),
    ('Gujarati', 'Ask about this page', 'આ પેજ વિશે પૂછો'),
    ('Gujarati', 'Compare Reports', 'રિપોર્ટ્સની સરખામણી કરો'),
    ('Gujarati', 'Refresh', 'તાજું કરો'),
    ('Gujarati', 'Back', 'પાછળ'),
    ('Gujarati', 'Search', 'શોધો'),
    ('Tamil', 'Scan Report', 'அறிக்கையை ஸ்கேன் செய்யவும்'),
    ('Tamil', 'Records', 'பதிவுகள்'),
    ('Tamil', 'Reminders', 'நினைவூட்டல்கள்'),
    ('Tamil', 'Medication Tracker', 'மருந்து டிராக்கர்'),
    ('Tamil', 'Pending Tests', 'நிலுவையிலுள்ள பரிசோதனைகள்'),
    ('Tamil', 'Trends', 'போக்குகள்'),
    ('Tamil', 'Account', 'கணக்கு'),
    ('Tamil', 'Ask AI Assistant', 'AI உதவியாளரிடம் கேளுங்கள்'),
    ('Tamil', 'Ask about this page', 'இந்தப் பக்கத்தைப் பற்றி கேளுங்கள்'),
    ('Tamil', 'Compare Reports', 'அறிக்கைகளை ஒப்பிடுக'),
    ('Tamil', 'Refresh', 'புதுப்பிக்கவும்'),
    ('Tamil', 'Back', 'பின்செல்'),
    ('Tamil', 'Search', 'தேடு'),
    ('Telugu', 'Scan Report', 'నివేదికను స్కాన్ చేయండి'),
    ('Telugu', 'Records', 'రికార్డులు'),
    ('Telugu', 'Reminders', 'రిమైండర్‌లు'),
    ('Telugu', 'Medication Tracker', 'మందుల ట్రాకర్'),
    ('Telugu', 'Pending Tests', 'పెండింగ్ పరీక్షలు'),
    ('Telugu', 'Trends', 'ధోరణులు'),
    ('Telugu', 'Account', 'ఖాతా'),
    ('Telugu', 'Ask AI Assistant', 'AI సహాయకుడిని అడగండి'),
    ('Telugu', 'Ask about this page', 'ఈ పేజీ గురించి అడగండి'),
    ('Telugu', 'Compare Reports', 'నివేదికలను పోల్చండి'),
    ('Telugu', 'Refresh', 'రిఫ్రెష్ చేయండి'),
    ('Telugu', 'Back', 'వెనుకకు'),
    ('Telugu', 'Search', 'వెతకండి'),
    ('Kannada', 'Scan Report', 'ವರದಿ ಸ್ಕ್ಯಾನ್ ಮಾಡಿ'),
    ('Kannada', 'Records', 'ದಾಖಲೆಗಳು'),
    ('Kannada', 'Reminders', 'ಜ್ಞಾಪನೆಗಳು'),
    ('Kannada', 'Medication Tracker', 'ಔಷಧಿ ಟ್ರ್ಯಾಕರ್'),
    ('Kannada', 'Pending Tests', 'ಬಾಕಿ ಪರೀಕ್ಷೆಗಳು'),
    ('Kannada', 'Trends', 'ಪ್ರವೃತ್ತಿಗಳು'),
    ('Kannada', 'Account', 'ಖಾತೆ'),
    ('Kannada', 'Ask AI Assistant', 'AI ಸಹಾಯಕರನ್ನು ಕೇಳಿ'),
    ('Kannada', 'Ask about this page', 'ಈ ಪುಟದ ಬಗ್ಗೆ ಕೇಳಿ'),
    ('Kannada', 'Compare Reports', 'ವರದಿಗಳನ್ನು ಹೋಲಿಸಿ'),
    ('Kannada', 'Refresh', 'ರಿಫ್ರೆಶ್ ಮಾಡಿ'),
    ('Kannada', 'Back', 'ಹಿಂದೆ'),
    ('Kannada', 'Search', 'ಹುಡುಕಿ'),
    ('Bengali', 'Scan Report', 'রিপোর্ট স্ক্যান করুন'),
    ('Bengali', 'Records', 'রেকর্ড'),
    ('Bengali', 'Reminders', 'রিমাইন্ডার'),
    ('Bengali', 'Medication Tracker', 'ওষুধ ট্র্যাকার'),
    ('Bengali', 'Pending Tests', 'মুলতুবি পরীক্ষা'),
    ('Bengali', 'Trends', 'প্রবণতা'),
    ('Bengali', 'Account', 'অ্যাকাউন্ট'),
    ('Bengali', 'Ask AI Assistant', 'AI সহকারীকে জিজ্ঞাসা করুন'),
    ('Bengali', 'Ask about this page', 'এই পৃষ্ঠা সম্পর্কে জিজ্ঞাসা করুন'),
    ('Bengali', 'Compare Reports', 'রিপোর্ট তুলনা করুন'),
    ('Bengali', 'Refresh', 'রিফ্রেশ করুন'),
    ('Bengali', 'Back', 'পিছনে'),
    ('Bengali', 'Search', 'খুঁজুন'),
    ('Punjabi', 'Scan Report', 'ਰਿਪੋਰਟ ਸਕੈਨ ਕਰੋ'),
    ('Punjabi', 'Records', 'ਰਿਕਾਰਡ'),
    ('Punjabi', 'Reminders', 'ਰਿਮਾਈਂਡਰ'),
    ('Punjabi', 'Medication Tracker', 'ਦਵਾਈ ਟਰੈਕਰ'),
    ('Punjabi', 'Pending Tests', 'ਬਕਾਇਆ ਟੈਸਟ'),
    ('Punjabi', 'Trends', 'ਰੁਝਾਨ'),
    ('Punjabi', 'Account', 'ਖਾਤਾ'),
    ('Punjabi', 'Ask AI Assistant', 'AI ਸਹਾਇਕ ਨੂੰ ਪੁੱਛੋ'),
    ('Punjabi', 'Ask about this page', 'ਇਸ ਪੰਨੇ ਬਾਰੇ ਪੁੱਛੋ'),
    ('Punjabi', 'Compare Reports', 'ਰਿਪੋਰਟਾਂ ਦੀ ਤੁਲਨਾ ਕਰੋ'),
    ('Punjabi', 'Refresh', 'ਤਾਜ਼ਾ ਕਰੋ'),
    ('Punjabi', 'Back', 'ਪਿੱਛੇ'),
    ('Punjabi', 'Search', 'ਖੋਜੋ'),
    ('Malayalam', 'Scan Report', 'റിപ്പോർട്ട് സ്കാൻ ചെയ്യുക'),
    ('Malayalam', 'Records', 'രേഖകൾ'),
    ('Malayalam', 'Reminders', 'ഓർമ്മപ്പെടുത്തലുകൾ'),
    ('Malayalam', 'Medication Tracker', 'മരുന്ന് ട്രാക്കർ'),
    ('Malayalam', 'Pending Tests', 'തീർച്ചയാകാത്ത പരിശോധനകൾ'),
    ('Malayalam', 'Trends', 'പ്രവണതകൾ'),
    ('Malayalam', 'Account', 'അക്കൗണ്ട്'),
    ('Malayalam', 'Ask AI Assistant', 'AI സഹായിയോട് ചോദിക്കുക'),
    ('Malayalam', 'Ask about this page', 'ഈ പേജിനെക്കുറിച്ച് ചോദിക്കുക'),
    ('Malayalam', 'Compare Reports', 'റിപ്പോർട്ടുകൾ താരതമ്യം ചെയ്യുക'),
    ('Malayalam', 'Refresh', 'പുതുക്കുക'),
    ('Malayalam', 'Back', 'തിരികെ'),
    ('Malayalam', 'Search', 'തിരയുക'),
    ('Odia', 'Scan Report', 'ରିପୋର୍ଟ ସ୍କାନ୍ କରନ୍ତୁ'),
    ('Odia', 'Records', 'ରେକର୍ଡ'),
    ('Odia', 'Reminders', 'ସ୍ମାରକ'),
    ('Odia', 'Medication Tracker', 'ଔଷଧ ଟ୍ରାକର୍'),
    ('Odia', 'Pending Tests', 'ବକେୟା ପରୀକ୍ଷା'),
    ('Odia', 'Trends', 'ଧାରା'),
    ('Odia', 'Account', 'ଖାତା'),
    ('Odia', 'Ask AI Assistant', 'AI ସହାୟକଙ୍କୁ ପଚାରନ୍ତୁ'),
    ('Odia', 'Ask about this page', 'ଏହି ପୃଷ୍ଠା ବିଷୟରେ ପଚାରନ୍ତୁ'),
    ('Odia', 'Compare Reports', 'ରିପୋର୍ଟଗୁଡ଼ିକୁ ତୁଳନା କରନ୍ତୁ'),
    ('Odia', 'Refresh', 'ସତେଜ କରନ୍ତୁ'),
    ('Odia', 'Back', 'ପଛକୁ'),
    ('Odia', 'Search', 'ଖୋଜନ୍ତୁ')
ON CONFLICT (language, text_key) DO NOTHING;

-- UHI/Beckn search sessions table for async results cache
CREATE TABLE IF NOT EXISTS uhi_search_sessions (
    search_id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    latitude NUMERIC(9, 6) NOT NULL,
    longitude NUMERIC(9, 6) NOT NULL,
    intent VARCHAR(100) NOT NULL,
    results JSONB DEFAULT '[]'::jsonb,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
