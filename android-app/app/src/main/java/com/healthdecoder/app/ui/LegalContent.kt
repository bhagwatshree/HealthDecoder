package com.healthdecoder.app.ui

/**
 * Privacy Policy and Terms & Conditions shown in-app (LegalScreens.kt) and at signup
 * (RegisterScreen's consent checkbox). Each document is a list of (heading, body) sections so the
 * screen can render them as plain, scrollable text without a markdown renderer.
 *
 * DRAFT CONTENT, NOT REVIEWED BY A LAWYER: this was written to accurately describe what the app
 * actually does (verified against auth.js, ExportManager, BackupManager, GoogleSignInHelper,
 * GmailApiClient, DiscoveryScreen, and the AI proxy) rather than generic boilerplate, but it is
 * still a starting draft. Have it reviewed before relying on it for a real Play Store listing —
 * in particular the governing-law/jurisdiction line and the contact address are placeholders.
 *
 * A later audit found four statements that had drifted from — or never matched — the code. All
 * four are corrected above; they are listed here because each is the kind of claim that must be
 * re-checked whenever the corresponding feature changes, and because the Play Data Safety form
 * has to agree with them:
 *   1. It said reports are stored on our backend so they can be restored on a new device. They
 *      are not. NetworkModule declares uploadReport/updateReport but NOTHING CALLS THEM — records
 *      live only in the on-device SQLCipher database. The policy now says so, including the
 *      consequence: lose the device without a backup and the records are gone.
 *   2. It promised the AI providers do not train on submitted content. That was never verified,
 *      and README's own launch checklist flags the Gemini FREE tier as permitting Google to use
 *      submissions to improve its products. A privacy promise we cannot keep is worse than none,
 *      so the text now states the tier dependency plainly instead. If production moves to a paid
 *      tier where it does not apply, this paragraph should be revisited — not before.
 *   3. It described the Discovery location search as a live feature. It is switched off
 *      (FeatureFlags.DISCOVERY_ENABLED), and with it the only thing that ever collected location.
 *   4. It described Gmail inbox scanning as available. It is switched off
 *      (FeatureFlags.GMAIL_SYNC_ENABLED), and Google sign-in alone grants no inbox access.
 *
 * Points 3 and 4 describe the app AS SHIPPED TODAY. Re-enabling either flag makes this policy
 * wrong again, so the flag and this text have to move together.
 */
private const val LAST_UPDATED = "September 2026"
private const val CONTACT_EMAIL = "support@healthdecoder.app" // placeholder — replace with a real, monitored address

data class LegalSection(val heading: String, val body: String)

val PRIVACY_POLICY_SECTIONS: List<LegalSection> = listOf(
    LegalSection(
        "Last updated",
        LAST_UPDATED
    ),
    LegalSection(
        "What this policy covers",
        "This Privacy Policy explains what Health Decoder (\"the app\", \"we\") collects, why, " +
            "and how it's stored, when you scan medical reports, create an account, or use any " +
            "other feature of the app. Health Decoder is built to keep as much of your medical " +
            "data on your own device as possible, and to be explicit about the few things that " +
            "leave it."
    ),
    LegalSection(
        "Information you provide",
        "Account details — first/last name, date of birth, gender, and either an email+password " +
            "or a phone number verified by a one-time SMS code. If you choose \"Continue with " +
            "Google\", we receive your Google account's name and email via Firebase Authentication " +
            "to identify you — we never see your Google password.\n\n" +
            "Medical reports — the images/PDFs you scan or import, and the text, test results, " +
            "medications, and AI-generated interpretation extracted from them. This is the core " +
            "data the app exists to handle."
    ),
    LegalSection(
        "Information collected automatically",
        "A random per-install device identifier (not tied to your name unless you sign in), used " +
            "solely to apply the free daily usage limit fairly across installs. Basic app usage " +
            "needed for the app to function (e.g. which screen you're on) is not sent anywhere — " +
            "it stays on your device."
    ),
    LegalSection(
        "How your data is processed",
        "Scanned report images and extracted text are sent to Google's Gemini AI and, for Indic-" +
            "language text-to-speech, Sarvam AI, solely to interpret your report and answer " +
            "questions you ask about it. Before a page is sent, the app blanks out identifying " +
            "regions it can detect on the page itself.\n\n" +
            "How those providers may use what is sent depends on the API tier we are running on. " +
            "Google's free tier permits Google to use submitted content to improve its products; " +
            "its paid tiers do not. We therefore do not promise that your report contents are " +
            "never used for provider model improvement, and you should not assume it. If that " +
            "matters to you, do not send documents you would not want processed under those terms." +
            "\n\nWe do not sell your medical data to anyone, for any purpose."
    ),
    LegalSection(
        "Where your data is stored",
        "On your device: your reports, images, and account cache are stored in a local database " +
            "encrypted with SQLCipher (AES-256), keyed by a random passphrase generated on your " +
            "device and never sent to us.\n\n" +
            "Your reports never leave your device except through a backup or export you start " +
            "yourself (see \"Backup and export\" below). There is no server-side copy of your " +
            "medical records, and no way to restore them from our servers — if you lose the " +
            "device without a backup, the records are gone.\n\n" +
            "On our servers: if you create an account, we store your account details only — email " +
            "address, password (hashed), the profile fields you entered, your plan, and a daily " +
            "count of AI requests used for the free-tier limit. Backend infrastructure runs on " +
            "AWS; the database is Neon, in the same region as our servers."
    ),
    LegalSection(
        "Gmail access",
        "This version of the app does not read your email and does not request access to it. " +
            "Automatic detection of report attachments in Gmail is switched off.\n\n" +
            "Signing in with Google, where offered, is used only to identify your account. It " +
            "does not grant this app access to your Gmail messages. If inbox scanning is " +
            "reintroduced in a later version it will be opt-in and off by default, and this " +
            "policy will be updated before that happens."
    ),
    LegalSection(
        "Backup and export",
        "The in-app backup/export/\"Transfer Records\" features write a file directly to a " +
            "location you choose (local storage, or a Drive/OneDrive folder you pick via Android's " +
            "own file picker) — that file goes straight from your device to your chosen " +
            "destination; it does not pass through our servers."
    ),
    LegalSection(
        "Location",
        "This version of the app does not use or request your location at all. The \"find nearby " +
            "hospitals, labs and doctors\" feature is switched off, so nothing sends or stores " +
            "your location. If it is reintroduced in a later version, this policy will be updated " +
            "before that happens."
    ),
    LegalSection(
        "Your controls",
        "You can delete a single report from within the app at any time. Settings → \"Delete " +
            "Everything\" permanently erases every report, medicine, pending test, home reading " +
            "and image on this device. Because your medical records are only ever held on your " +
            "device, that deletion is complete — there is no server-side copy left behind. It " +
            "cannot be undone, and it does not reach backups or exports you have already saved " +
            "elsewhere; delete those yourself.\n\n" +
            "Deleting your account removes the account details we hold (see \"Where your data is " +
            "stored\"). You can sign out or delete your account at any time from Settings."
    ),
    LegalSection(
        "Children's privacy",
        "Health Decoder is not directed at children. If you are creating a family member's profile " +
            "for a child, you are doing so as their parent/guardian, and you are responsible for " +
            "that data."
    ),
    LegalSection(
        "Changes to this policy",
        "If this policy changes in a way that affects how your data is handled, we'll surface that " +
            "in the app rather than silently updating this page."
    ),
    LegalSection(
        "Contact",
        "Questions about this policy or your data: $CONTACT_EMAIL"
    )
)

val TERMS_AND_CONDITIONS_SECTIONS: List<LegalSection> = listOf(
    LegalSection(
        "Last updated",
        LAST_UPDATED
    ),
    LegalSection(
        "⚠ Not medical advice — read this first",
        "Health Decoder uses AI (large language models, currently Google Gemini and Sarvam AI) to " +
            "read and summarize medical reports. AI-generated interpretations, extracted values, " +
            "specialist suggestions, and answers in Chat can be INCOMPLETE, OUT OF DATE, OR WRONG. " +
            "The app does not practice medicine, does not diagnose any condition, and cannot and " +
            "does not tell you what you should or should not do about your health. Nothing in this " +
            "app is a substitute for the judgment of a qualified doctor or other healthcare " +
            "professional who has examined you.\n\n" +
            "Always verify every extracted number and every statement against your original " +
            "report. Always consult a qualified healthcare professional before making any medical " +
            "decision, starting or stopping any medication, or acting on anything shown in this " +
            "app. If you believe you are experiencing a medical emergency, call your local " +
            "emergency number or go to the nearest emergency room immediately — do not use this " +
            "app instead."
    ),
    LegalSection(
        "Accepting these terms",
        "By creating an account or using Health Decoder, you agree to these Terms & Conditions and " +
            "to the Privacy Policy. If you don't agree, please don't use the app."
    ),
    LegalSection(
        "Who can use this app",
        "You must be able to form a binding agreement to use Health Decoder with your own account. " +
            "A parent or guardian may create and manage a family member's profile on their behalf."
    ),
    LegalSection(
        "Your account",
        "You're responsible for keeping your login credentials confidential and for anything done " +
            "under your account. Tell us if you believe your account has been accessed without " +
            "your permission."
    ),
    LegalSection(
        "The free tier and your own API keys",
        "A limited number of AI-powered scans per day are provided free, pooled across a shared " +
            "key. You may optionally add your own Gemini/Sarvam API key in Settings, in which case " +
            "usage on that key is billed to you directly by that provider under their own terms — " +
            "we do not mark up or charge for API usage on a key you provide."
    ),
    LegalSection(
        "Acceptable use",
        "Don't use the app to process reports that aren't yours or that you don't have permission " +
            "to process on someone's behalf (e.g. a family member you care for). Don't attempt to " +
            "circumvent usage limits, reverse-engineer the app, or use it in any way that could " +
            "harm our infrastructure or other users."
    ),
    LegalSection(
        "No warranty",
        "The app is provided \"as is\", without warranty of any kind, express or implied, " +
            "including but not limited to accuracy, completeness, or fitness for a particular " +
            "medical purpose. AI extraction accuracy varies with scan quality, document layout, " +
            "and language, and is not guaranteed."
    ),
    LegalSection(
        "Limitation of liability",
        "To the maximum extent permitted by law, we are not liable for any injury, loss, or " +
            "damage — direct, indirect, or consequential — arising from your use of the app or " +
            "from any decision made or action taken (or not taken) based on information the app " +
            "displayed, including AI-generated interpretations."
    ),
    LegalSection(
        "Your content",
        "You own the reports and data you add. You grant us the limited right to process them " +
            "(including sending them to the AI providers named in the Privacy Policy) solely to " +
            "provide the app's features to you. We do not keep a copy of your reports — they stay " +
            "on your device — so deleting a report in the app deletes it outright, as described " +
            "in the Privacy Policy."
    ),
    LegalSection(
        "Changes and termination",
        "We may update these terms as the app changes; continued use after an update means you " +
            "accept the new terms. You may stop using the app and delete your account at any time " +
            "from Settings."
    ),
    LegalSection(
        "Governing law",
        "These terms are governed by the laws of India, without regard to conflict-of-law " +
            "principles. [Placeholder — confirm jurisdiction before publishing.]"
    ),
    LegalSection(
        "Contact",
        "Questions about these terms: $CONTACT_EMAIL"
    )
)
