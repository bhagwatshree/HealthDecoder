# Legal Content Changes Required Before Production Launch

**Status: audit output, now partly APPLIED. Not legal advice, and not a policy.**

> **Update — content changes applied to `ui/LegalContent.kt`.** The wording gaps below have been
> written into the app: controller identity, legal basis, third-party (family) records, retention,
> security, breach, automated processing, full data rights, Grievance Officer, cross-border
> transfer, 18+ eligibility, and consent-versioning wording. Grievance contact is
> `medical.assisit@gmail.com`.
>
> **Three things did NOT change, and matter:**
> 1. **§2 (medical-device classification) is untouched.** It cannot be fixed by wording — it needs
>    a decision. Still the highest-consequence open item.
> 2. **Two of the new sentences are promises the code does not yet keep** — see "Promises now
>    outstanding" at the end of this document. They are true statements of intent and false
>    statements of fact until the engineering behind them exists.
> 3. **Placeholders remain** for things only the operator knows: `[LEGAL ENTITY NAME]`,
>    `[REGISTERED ADDRESS]`, `[GRIEVANCE OFFICER NAME]`, and the governing-law line.
>
> Everything written is draft wording for a lawyer to review, not vetted text. The
> "NOT REVIEWED BY A LAWYER" warning remains in the file, enforced by a test.

This lists what the app's Terms, Privacy Policy and consent text say today, what the code actually
does, and where those two disagree. Proposed wording is draft input for a lawyer, not text to ship
as-is.

Audited: `ui/LegalContent.kt` (201 lines, self-declared unreviewed draft), against
`AppSettings.kt`, `FeatureFlags.kt`, `NetworkModule.kt`, `LocalStore.kt`, `OcrEngine.kt`,
`MedicalEngine.kt`, `Models.kt`, `LocalRepository.kt`, `RegisterScreen.kt`, `TodaysMedicinesTab.kt`,
`MedicineScheduleStore.kt`, `backend/server.js`, `backend/db_init.sql`, `backend/migrate.js`.

## How to read this

- Every statement about **app behaviour** carries a `file:line` citation. Anything I could not
  prove from code is marked `[unverified]`.
- Every statement about **legal obligation** is marked `[confirm with lawyer]`. I am not qualified
  to assert what the law requires; those items describe the *factual behaviour* that raises the
  question, which is the part a lawyer cannot get from reading the policy alone.
- **Launch blocker** is my engineering judgement about risk, not a legal determination.

## Priority summary

| # | Item | Launch blocker | Why |
|---|---|---|---|
| 1 | Third-party (family) health data has no consent basis | **Yes** | The app's core feature collects other people's medical records; the text only addresses children |
| 2 | "Not a medical device" vs. what the app actually does | **Yes** | Cannot be fixed by wording; needs a classification decision before shipping |
| 3 | DPDP items entirely absent (Grievance Officer, child = 18, cross-border, rights) | **Yes** | Statutory content missing, and Play requires the policy to be accurate |
| 4 | Controller identity, legal basis, retention, breach, security | **Yes** (identity, basis) / No (rest) | A policy with a placeholder contact cannot be published |
| 5 | Consent is an un-versioned boolean | No, but blocks the *next* policy update | Cheaper to fix now than after users exist |
| 6 | Placeholder contact address and jurisdiction | **Yes** | Named in the file's own header as unresolved |

---

## 1. Third-party health data — the family-profile feature

**Launch blocker: yes.** The app actively encourages collecting a second person's medical records,
and the text has no consent basis for it.

### What the code does

- `FamilyProfile` (`model/Models.kt:9`) keys every record to a person; the shipped example profiles
  are `"Papa"` / `"Father"` and `"Mummy"` / `"Mother"` (`model/Models.kt:25-26`) — i.e. **competent
  adults**, not children.
- Any user can add one via `addFamilyMember` (`local/LocalRepository.kt:1339`); profiles are also
  auto-created from names read off scanned reports (`local/LocalRepository.kt:1311`).
- Those people's reports, medications and home readings are then stored, AI-processed and charted
  exactly like the account holder's own.

### What the text says today

Two sections touch this, and both address only the *child* case:

- `ui/LegalContent.kt:132-136` — *"Health Decoder is not directed at children. If you are creating a
  family member's profile for a child, you are doing so as their parent/guardian, and you are
  responsible for that data."*
- `ui/LegalContent.kt:175-178` — *"A parent or guardian may create and manage a family member's
  profile on their behalf."*
- `ui/LegalContent.kt:193-198` (Acceptable use) gets closest: *"Don't use the app to process reports
  that aren't yours or that you don't have permission to process on someone's behalf (e.g. a family
  member you care for)."*

### The gap

1. The **most common real case — an adult managing an elderly parent — is not covered by any of
   them.** A parent is not a child, and the user is not their guardian.
2. The wording *assigns responsibility to the user* rather than establishing a basis for the
   processing. `[confirm with lawyer]` whether responsibility can be transferred this way, or
   whether the operator remains accountable for data it stores and processes regardless.
3. The third party is not a user. They cannot see the policy, withdraw consent, or request
   deletion — there is no mechanism in the app for a non-user to do any of those.

### Proposed draft wording

> **Records you add for someone else**
>
> Health Decoder lets you keep records for family members. When you add someone else's reports, you
> are telling us you have that person's permission to do so, or that you are legally entitled to act
> for them (for example as a parent of a child, or under a power of attorney).
>
> We store and process those records the same way as your own. If the person asks us to remove
> their records, or you no longer have permission to hold them, you must delete that profile — and
> you can contact us at [ADDRESS] and we will help.
>
> We cannot verify the permission you assert. If you add someone's records without their agreement,
> that is your responsibility, not theirs.

**For the lawyer:** the questions this raises are (a) controller vs. processor for third-party
records, (b) whether an in-app assertion is an adequate basis, (c) how a non-user exercises rights
over their own data, and (d) whether an elderly-parent case needs different treatment from a child.

---

## 2. "Not a medical device" versus what the app does

**Launch blocker: yes** — not because the wording is wrong, but because the underlying
classification has to be settled before shipping, and wording cannot settle it.

### What the text claims

`ui/LegalContent.kt:154-168` — *"The app does not practice medicine, does not diagnose any condition,
and cannot and must not replace…"*, and the signup checkbox (`ui/RegisterScreen.kt:405`) has the user
affirm *"…this app is not a medical device and does not provide clinical advice."*

### What the code does

The app does considerably more than store documents:

| Behaviour | Evidence |
|---|---|
| Classifies lab values High/Low/Normal against reference ranges | `model/Models.kt:78` (`TestParameter.status`) |
| Produces AI clinical interpretation of a report | `ai/MedicalEngine.kt:102` (`healthInsights`) |
| Produces an AI deep-dive analysis | `ai/MedicalEngine.kt:298` (`detailedAnalysis`) |
| Compares reports and characterises change | `ai/MedicalEngine.kt:31` (`compareReports`) |
| Recommends a specialist, **with an urgency rating** | `model/Models.kt:117-121` — `urgency: "Routine \| Soon \| Urgent"` |
| **Creates medication reminders automatically from a scan** | `reminder/MedicineScheduleStore.kt:280`, called at `ui/TodaysMedicinesTab.kt:197` |

The last one is the sharpest: extracted medicines become scheduled alarms telling a patient to take
a dose, without a human confirming the extraction.

### The gap

`[confirm with lawyer]` Software that interprets clinical data and prompts action sits closer to
regulated medical-device software than a passive record store. In India that is CDSCO's remit; if the
app ever ships to the EU, MDR. **A disclaimer is not the test** — classification generally follows
intended purpose and function, not the label the developer applies.

I am flagging this as the item with the largest downside if wrong. It may well be fine; it should be
a decision on record rather than an assumption.

### Proposed draft wording (only if the lawyer confirms the classification holds)

> **What this app does and does not do**
>
> Health Decoder reads your medical documents and presents what they contain in plainer language.
> It marks values as high, low or normal **using the reference range printed on your own report**,
> and it can suggest the type of doctor a report's findings usually relate to. These are
> descriptions of your document, not medical opinions about you.
>
> The app is not a diagnostic tool and is not a substitute for a doctor. It cannot know your medical
> history, symptoms or context. Reminders it creates come from what it read on your prescription —
> **check them against the prescription itself before relying on them.**

**For the lawyer:** the facts that matter are the urgency ratings, the AI interpretation, and the
automatic creation of medication reminders from an AI extraction.

---

## 3. India DPDP Act items — absent entirely

**Launch blocker: yes.**

Verified absent from `ui/LegalContent.kt` (case-insensitive search across all 201 lines):
`grievance`, `retention`, `legal basis`, `breach`, `cross-border`, `controller`, `fiduciary`,
`nominate`, `rectif`, `portab`, `withdraw`, `DPDP`, and any reference to age `18`. The single
occurrence of "transfer" is `ui/LegalContent.kt:108`, referring to the **"Transfer Records" backup
feature** — not cross-border transfer.

### 3a. Grievance Officer

`[confirm with lawyer]` The DPDP Act requires a Data Fiduciary to publish contact details for
grievance redressal. Nothing in the policy names one. The only contact is a placeholder email at
`ui/LegalContent.kt:143-144`.

> **Grievance Officer**
>
> If you have a complaint about how we handle your data, contact our Grievance Officer:
> [NAME], [ADDRESS], [EMAIL]. We will acknowledge within [N] days and respond within [N] days.

### 3b. Child = under 18, and no age gate exists

The signup flow **collects date of birth** (`ui/RegisterScreen.kt:133`, `:173`; stored
`backend/db_init.sql:79`) but **never checks it against a minimum age** — no age validation exists
anywhere in the app or backend (verified by search across `android-app/app/src/main` and
`backend/`). The current text at `ui/LegalContent.kt:132-136` relies on the user's own assertion.

`[confirm with lawyer]` DPDP defines a child as under 18 — stricter than GDPR — and requires
verifiable parental consent for processing a child's data. The app already holds the DOB needed to
enforce a gate.

> **Children**
>
> You must be 18 or older to create an account. If you are adding records for a child, you must be
> that child's parent or legal guardian.

### 3c. Cross-border transfer

Report pages and extracted text leave the device to Google Gemini and Sarvam AI
(`ai/OcrEngine.kt:244`), after on-device redaction of identifying regions (`ai/OcrEngine.kt:535`).
Account data sits on AWS with a Neon database (`ui/LegalContent.kt:92-95`).

`[confirm with lawyer]` where those processors are located and what disclosure or mechanism applies.

### 3d. Data Principal rights

`ui/LegalContent.kt:121-130` covers **deletion only**. Absent: access, correction, portability,
withdrawal of consent, grievance redressal, and the DPDP-specific **right to nominate**.

> **Your rights over your data**
>
> You can ask us to: give you a copy of the account data we hold; correct it; delete it; withdraw
> consent you previously gave; or nominate someone to exercise these rights if you die or become
> incapacitated. Your medical records are held on your device, so you can already view, correct and
> delete those yourself at any time.

---

## 4. Standard clauses missing

### 4a. Controller identity — **launch blocker: yes**

The policy never names the legal entity responsible. `ui/LegalContent.kt:47` says only *"Health
Decoder (\"the app\", \"we\")"*, and the contact is a placeholder
(`ui/LegalContent.kt:15` marks `CONTACT_EMAIL` as *"placeholder — replace with a real, monitored
address"*).

### 4b. Legal basis — **launch blocker: yes**

`[confirm with lawyer]` Health data is generally a special category needing an explicit basis. The
policy states no basis for any processing.

### 4c. Retention — launch blocker: no, but factually determinable now

Nothing states how long anything is kept. What the code shows:

- **Medical records:** on-device only, kept until the user deletes them (`local/LocalStore.kt:65`,
  encrypted with a device-generated passphrase).
- **Account data:** persists until account deletion (`network/NetworkModule.kt:193`).
- **AI responses containing report content:** cached server-side in `ai_response_cache` with
  `response_text` holding the full model output (`backend/migrate.js:404-407`). A sweep deletes
  completed rows older than 30 minutes (`backend/server.js:928`) — but it is **probabilistic**,
  firing on roughly 2% of completed requests, so it is best-effort rather than guaranteed. The table
  has **no user or device column**, so account deletion cannot target those rows.

> **How long we keep things**
>
> Your medical records stay on your device until you delete them; we never hold a copy. Account
> details are kept until you delete your account. Content sent for AI processing is cached briefly
> (under an hour) to avoid repeat processing, then deleted.

**Note for engineering, not the lawyer:** the wording above is only true if the sweep is made
deterministic. As written it is a best-effort cleanup.

### 4d. Security — launch blocker: no

Encryption is mentioned in passing at `ui/LegalContent.kt:85-87` but there is no security section.

### 4e. Breach notification — launch blocker: no

Absent. `[confirm with lawyer]` DPDP notification duties to the Board and to affected persons.

### 4f. Automated decision-making — launch blocker: no

The app produces AI interpretations of health data (`ai/MedicalEngine.kt:102`, `:298`) and the policy
does not address automated processing.

---

## 5. Consent is an un-versioned boolean

**Launch blocker: no — but it blocks the first policy update after launch.**

Consent is stored as a single boolean with no version and no timestamp:

- `local/AppSettings.kt:19` — `KEY_DISCLAIMER_ACCEPTED = "medical_disclaimer_accepted"`
- `local/AppSettings.kt:89-93` — plain `getBoolean` / `putBoolean`
- Set once at `Navigation.kt:276`
- The signup checkbox at `ui/RegisterScreen.kt:405` is likewise not recorded against a version

`ui/LegalContent.kt:138-141` promises: *"If this policy changes in a way that affects how your data
is handled, we'll surface that in the app rather than silently updating this page."* **The code
cannot currently keep that promise** — there is no version to compare against, so no way to detect
that a user has seen an older version.

Since the policy will change the moment a lawyer reviews it, this becomes load-bearing immediately
after launch.

### Proposed draft wording

> **Changes to this policy**
>
> We will show you this policy again, and ask you to accept it again, if we change it in a way that
> materially affects how your data is handled. We record which version you accepted and when. Minor
> corrections that do not change our handling of your data will be published here with an updated
> date, without interrupting you.

**Engineering prerequisite:** replace the boolean with `acceptedVersion` + `acceptedAt`, migrating
existing `true` values to the version shipped before the change so nobody is re-prompted spuriously.

---

## 6. Placeholders that must be resolved

Named in the file's own header (`ui/LegalContent.kt:8-12`):

- `CONTACT_EMAIL` (`ui/LegalContent.kt:15`) — flagged in-code as a placeholder
- Governing law / jurisdiction (`ui/LegalContent.kt:228`) — flagged in-code as
  *"[Placeholder — confirm jurisdiction before publishing.]"*
- No dispute-resolution clause exists
- **Launch blocker: yes.** A published policy cannot carry a placeholder contact address.

**Correction to this audit:** an earlier draft of this section recommended adding an emergency
clause. That was wrong — one already exists, inside the medical-disclaimer section
(`ui/LegalContent.kt`, "⚠ Not medical advice"): *"If you believe you are experiencing a medical
emergency, call your local emergency number or go to the nearest emergency room immediately — do
not use this app instead."* No change made; duplicating it would have weakened both copies.

---

## Verification of the four previously-corrected statements

Per the audit brief, these were re-checked rather than re-litigated. All four still hold:

| Correction | Still accurate? | Evidence |
|---|---|---|
| Records are **not** stored on the backend | Yes | `network/NetworkModule.kt:59,68` declare `uploadReport`/`updateReport`; no caller anywhere in the app. (`ui/ReportDetailScreen.kt:250` calls `LocalRepository.updateReport`, which writes to the local store.) Text at `ui/LegalContent.kt:88-91` matches |
| No promise that AI providers don't train on content | Yes | `ui/LegalContent.kt:76-82` states the tier dependency instead |
| Location not used | Yes | `FeatureFlags.DISCOVERY_ENABLED` is false; text at `ui/LegalContent.kt:114-119` matches. Note the backend table `uhi_search_sessions` still **exists** with `latitude`/`longitude` columns (`backend/db_init.sql:336-340`) — dormant, but any rows written during earlier testing are still there |
| Gmail not read | Yes | `FeatureFlags.GMAIL_SYNC_ENABLED` is false; text at `ui/LegalContent.kt:98-105` matches |

---

## What only a lawyer can decide

1. Whether the family-profile model is lawful as built, and what the user must represent (§1)
2. Whether the app's function keeps it outside medical-device regulation (§2)
3. The exact DPDP obligations and their wording (§3)
4. The legal basis for processing health data (§4b)
5. Jurisdiction, dispute resolution, and the limitation-of-liability language (§6)

## Promises now outstanding

Applying the content created two statements that are **currently untrue as statements of fact**.
Both are ordinary intent for a health app; neither is backed by code yet. Until they are, the policy
claims something the app does not do — which is the exact defect class this audit was written to
find, so they are listed rather than buried.

| New wording | What the code does | Fix |
|---|---|---|
| *"we record which version you accepted and when"* ("Changes to this policy") | Consent is a bare boolean, `KEY_DISCLAIMER_ACCEPTED` (`local/AppSettings.kt:19`, `:89-93`), with no version or timestamp | Replace with `acceptedVersion` + `acceptedAt`; migrate existing `true` to the pre-change version so nobody is re-prompted |
| *"You must be 18 or older"* ("Children", and Terms "Who can use this app") | DOB is collected (`ui/RegisterScreen.kt:133`, `:173`) but never checked — no age validation anywhere in app or backend | Add an age check at signup using the DOB already collected |

A third, milder one: the retention section says cached AI results are deleted "once they are no
longer needed". The sweep exists but is probabilistic (`backend/server.js:928`, ~2% of requests), so
this is best-effort. The wording was deliberately written not to promise a fixed window — but making
the sweep deterministic would let it be stated plainly, and is the honest end state.

## What engineering must do regardless of the lawyer's answers

1. Version the consent record (§5) — prerequisite for honouring the existing "we'll tell you"
   promise at `ui/LegalContent.kt:138-141`
2. Make the AI-cache sweep deterministic and add an ownership column, or stop storing response
   bodies (§4c)
3. Decide whether to add an age gate, given DOB is already collected but never checked (§3b)
4. Replace the placeholder contact and jurisdiction (§6)
