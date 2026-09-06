# Implementation Plan — Manual Health Data Entry ("Add a Reading")

Status: **PLAN ONLY — nothing implemented yet.**
Base: `master` @ `1e6f142` (versionCode 32 / 1.3.23)
Drafted from a read of `Models.kt`, `MedicalDatabase.kt`, `Daos.kt`, `DashboardEngine.kt`,
`LocalRepository.kt`, `TrendsScreen.kt`, `HomeScreen.kt`, `ExportManager.kt`, `UnitConverter.kt`
and `TestReference.kt` on this branch.

---

## 1. Why this feature

Today every data point in the app arrives through a **document** — a scan, a PDF, a Gmail
attachment — and lands as a `MedicalReport` row. That means the app only knows about a patient on
the ~4–10 days a year they visit a lab.

The people this app is built for (diabetics, hypertensives, elderly parents managed by an adult
child) measure themselves **at home, daily**. A BP cuff and a glucometer produce more clinically
useful longitudinal data than the annual lab panel does, and right now none of it is captured.
Adding it turns Trends from "a chart with 4 dots" into a real chart, and turns the Doctor Brief
from "here are my old reports" into "here is my last 30 days of home BP" — which is exactly what a
physician asks for at a consult.

---

## 2. What a patient can log manually — the catalogue

Grouped by tier, in build order. **Only Tier 1a ships in Phase 1** — sugar, BP, heart rate,
oxygen. Everything below it is deliberately deferred, and because of the catalogue-driven design in
§4.2 each later metric is one `VitalMetric` entry rather than a new screen, so the order below is a
sequencing decision and not a commitment to build all of it.

### Tier 1a — the first release: sugar, BP, heart rate, oxygen

Four metrics, deliberately. They are a coherent set rather than an arbitrary cut: a home BP monitor
produces **BP + pulse** in one measurement, a pulse oximeter produces **SpO₂ + pulse**, and a
glucometer produces sugar. The MVP is therefore "everything the two commonest home devices and a
glucometer give you", which is also the set that covers diabetes and hypertension — the two
conditions this app's users overwhelmingly have.

| Metric | `key` | Inputs | Units | Context options |
|---|---|---|---|---|
| Blood sugar (glucometer) | `glucose` | value | mg/dL, mmol/L | Fasting; Before meal; 2 h after meal; Random; Bedtime; During low-sugar symptoms |
| Blood pressure | `bp` | systolic + diastolic (+ optional pulse) | mmHg | Sitting / Standing / Lying; Left / Right arm; Before medicine / After medicine; Morning / Evening |
| Pulse / heart rate | `pulse` | value | bpm | At rest; After activity |
| SpO₂ (pulse oximeter) | `spo2` | value (+ optional pulse) | % | At rest; After walking; On oxygen |

**Design consequence — pulse arrives from three places.** It can be logged standalone (`pulse`), or
alongside a BP reading (`bp.value3`), or alongside an SpO₂ reading (`spo2.value3`). The Pulse trend
must therefore be built by **unioning all three sources**, not by reading the `pulse` metric alone —
otherwise a user who always takes their pulse from the BP monitor (i.e. most users) opens the Pulse
chart and finds it empty. `buildVitalsSummary` needs an explicit derived-metric rule for this; it is
the one non-obvious piece of Phase 1.

### Tier 1b — the next two, once 1a is working

Same shape, no new architecture, so these are a catalogue entry each:

| Metric | `key` | Inputs | Units | Context options |
|---|---|---|---|---|
| Weight | `weight` | value (BMI auto-derived from profile height) | kg, lb | Morning / Evening; Before / After meal |
| Body temperature | `temp` | value | °C, °F | Oral / Underarm / Forehead / Ear |

### Tier 2 — condition-specific home devices

- **INR** (home coagulometer — CoaguChek and similar fingerstick devices; the app already trends
  INR from labs). Two constraints, both easy to get wrong:
  - **Offer it only for vitamin K antagonists** — warfarin, acenocoumarol. Patients on DOACs
    (apixaban, rivaroxaban, dabigatran, edoxaban) are not INR-monitored at all, so the
    personalisation rule in §6.1 must key on the specific drug and must **not** be broadened to
    "on a blood thinner".
  - Home fingerstick INR and lab venous INR are known to diverge at high INR values and in
    patients with lupus anticoagulant / antiphospholipid syndrome. Since INR drives warfarin
    dosing, this is the sharpest case for the display separation in §7.1 — the two readings must
    never share a line.
- **Peak flow / PEFR** (asthma, COPD) — L/min
- **Blood ketones** (mmol/L) and **urine ketones** (dipstick: Negative / Trace / + / ++ / +++)
- **Urine dipstick protein / glucose** (same semi-quantitative scale)
- **Insulin dose** — units + type (basal / bolus / mixed)
- **Respiratory rate** — breaths/min
- **Waist circumference**, **body fat %**
- **Daily fluid intake & urine output** — the I/O chart heart-failure and dialysis patients are
  told to keep; nothing else in the app captures it
- **Blood glucose from a CGM**, typed in manually

### Tier 3 — subjective and lifestyle (no device, still clinically useful)

- **Pain score** 0–10 + body site + type (dull / sharp / burning)
- **Sleep** — hours + quality (poor / fair / good)
- **Mood / stress / anxiety** — 1–5 scale
- **Energy / fatigue** — 1–5 scale
- **Symptom log** — tagged multi-select (headache, dizziness, nausea, breathlessness,
  palpitations, chest pain, swelling/oedema, cough, fever feeling, rash) + severity + duration +
  free note
- **Steps / exercise** — minutes + activity type
- **Water intake** — glasses / mL
- **Diet note** — free text; **carb count** for diabetics
- **Smoking** (per day) and **alcohol** (units)
- **Bowel movement** — Bristol scale (IBD / GI patients)
- **Menstrual cycle** — start/end, flow, symptoms
- **Side effect experienced** — pick a medicine from the existing medication tracker + describe.
  High value: it gives real feedback against `HealthInsights.sideEffects`, which today is
  AI-predicted with nothing to check it against.

### Tier 4 — manual entry that isn't a "reading"

Same entry point conceptually, different code paths:

- **Type in a lab value from a paper report** the user doesn't want to scan (or an old report
  predating the app). This is a *report*, not a vital → creates a `MedicalReport` with
  `analyzed = true` and no `imagePath`. See §7.4.
- **Doctor visit note** — date, doctor, complaint, advice; no document.
- **One-time profile facts** that feed the Doctor Brief: blood group, height, known allergies,
  chronic conditions, past surgeries, family history, emergency contact. These are *per-patient
  attributes*, not a time series — they belong on `ProfileScreen` / `FamilyProfile`, and this
  screen should deep-link to them, not duplicate them.
- **Vaccination / immunisation record**.

### Explicitly out of scope for now

Pregnancy tracking (fundal height, kick counts) and paediatric growth percentiles. Both are real
needs, but each is its own feature with its own reference curves.

---

## 3. Architecture decision: a new `vitals` table, **not** synthetic `MedicalReport` rows

The tempting shortcut is to save each reading as a `MedicalReport` with
`reportCategory = "Manual Entry"` and one `TestParameter`, so it flows into Trends, Records, Doctor
Brief, Compare, export and FTS **for free**. That should be rejected. Reasons, in order of weight:

1. **Duplicate detection would actively discard the data.** `LocalRepository.saveScan` →
   `LocalStore.findContentDuplicate(patient, date, category, text)` treats the same
   patient + date + category + similar text as a duplicate and *skips* it. Two BP readings on the
   same day are the normal case, not a duplicate.
2. **Trends would collapse them too.** `buildHealthSummary` dedupes with `seenPerReport` (one point
   per test per report) and `seenValueForDate` (same test + date + value across reports) —
   deliberate for reports, wrong for a twice-daily log.
3. **Volume mismatch.** BP twice a day is ~700 rows/year/patient against ~10 reports. Those rows
   would flow into the Records list, the `reports_fts` index, and `LocalStore.findStoredDuplicates`,
   which does pairwise Jaccard over report text.
4. **The shape doesn't fit.** BP is inherently *two* linked numbers plus a pulse; `TestParameter`
   is single-valued, so BP becomes three unrelated parameters. `MedicalReport` also demands
   `imagePath`, `extractedText`, `pageHashes` and `sourcePageIndices` — all meaningless here — and
   `deleteReport`'s file-reference counting has nothing to count.
5. **Clinically, home readings are a different measurement and must stay labelled as such.** Home
   BP targets are not clinic BP targets, and a capillary glucometer reading is not a venous plasma
   glucose. Merging them into one indistinguishable series is a genuine medical-accuracy
   regression, not just a modelling inconvenience.

**Decision:** a dedicated `vitals` table + a declarative metric catalogue, bridged into the
existing Doctor Brief / Chat / export pipelines at read time.

### 3.1 Internally part of the medical record; separately displayed, always

Two different questions, two different answers, and they must not be conflated:

- **Is manual data part of the patient's medical record?** Yes. It belongs to the same patient,
  travels in the same backup and export, is scoped by the same `userEmail` visibility rule, follows
  the same patient on a `mergePatient`, feeds the Doctor Brief, and is available as Chat context.
  Internally it is first-class medical data, not a side note.
- **Is it displayed alongside lab data?** No — never merged. Because the same quantity (blood
  sugar, INR, BP) can arrive from *both* a lab report and a home device, the two must be viewed
  separately or the chart silently lies about what it is showing. Separate trend namespace,
  separate mode on the Trends screen, separate section in the Doctor Brief. See §7.1 and §7.2.

The separate table is what makes that cheap: the display separation falls out of the storage
model instead of being enforced by filtering rules scattered across every read path.

---

## 4. Data model

### 4.1 Entity (`model/VitalReading.kt`)

One table for every metric. Extra numeric slots cover the multi-value metrics (BP, SpO₂ + pulse)
without a table per metric.

```kotlin
@Entity(
    tableName = "vitals",
    indices = [Index("patientName", "metric", "recordedAt"), Index("recordedAt")]
)
data class VitalReading(
    @PrimaryKey val id: String,
    val patientName: String,
    val metric: String,            // stable key from VitalCatalog: "bp" | "glucose" | "weight" | ...
    val value: String,             // primary value, stored exactly as typed
    val value2: String = "",       // diastolic (BP); empty otherwise
    val value3: String = "",       // pulse taken alongside BP / SpO2
    val unit: String = "",
    val context: String = "",      // "Fasting" | "Sitting, Left arm" | "Oral"
    val note: String = "",
    val recordedAt: String,        // ISO local date-TIME, "2026-09-06T08:15" — vitals are
                                   // time-of-day sensitive, unlike reports which are date-only
    val createdAt: String,
    val source: String = "manual", // room for "device" / "healthconnect" later
    val userEmail: String? = null  // same NULL-is-visible-to-everyone rule as `reports`
)
```

### 4.2 The catalogue (`model/VitalCatalog.kt`) — the load-bearing piece

Adding a metric must be **one data entry, not a new screen**. The entry form, validation, units,
context chips and trend name are all generated from this:

```kotlin
data class VitalMetric(
    val key: String,
    val displayName: String,        // localised through the existing tr() / UiTranslations path
    val emoji: String,
    val group: String,              // "Vitals" | "Diabetes" | "Lifestyle" | "Symptoms"
    val fields: List<VitalField>,   // 1..3 numeric fields, or a choice field (dipstick scale)
    val units: List<String>,        // first = default; empty = unitless
    val contextOptions: List<String>,
    val plausibleRange: ClosedFloatingPointRange<Float>, // typo guard, NOT a clinical range
    val decimals: Int,
    val trendName: String?          // canonical Trends line, or null = not charted
)
```

`plausibleRange` is a **data-entry sanity check only** (systolic 60–300, weight 1–400 kg), to catch
a fat-fingered `1200`. It is deliberately not a normal range — see §8.

### 4.3 DAO + migration

- `VitalDao` in `local/db/Daos.kt`: `getFor(patient, metric, fromIso, toIso)`,
  `getRecent(patient, limit)`, `latestPerMetric(patient)`, `upsert`, `deleteById`, and
  `renamePatient` — the merge-patient path in `LocalRepository.mergePatient` must re-key vitals
  too, exactly as `PendingTestDao` and `MedLogDao` already do.
- `MedicalDatabase` version **6 → 7**, `MIGRATION_6_7` = `CREATE TABLE IF NOT EXISTS vitals (...)`,
  registered alongside the existing migrations.
- Backup is free: the `.db` lives inside `records/`, which `BackupManager` zips wholesale.

---

## 5. Repository API (`LocalRepository`)

```kotlin
suspend fun addVital(context, reading: VitalReading): VitalReading
suspend fun updateVital(context, reading: VitalReading)
suspend fun deleteVital(context, id: String)
suspend fun getVitals(context, patient: String, metric: String? = null, days: Int? = null): List<VitalReading>
suspend fun latestVitals(context, patient: String): Map<String, VitalReading>   // "today" cards
suspend fun vitalStats(context, patient: String, metric: String, days: Int): VitalStats
```

`VitalStats` = count, min, max, mean, latest, and `inTargetPercent` — the numbers the Doctor Brief
section (§7.2) is built from.

---

## 6. UI

### 6.1 New screen — `ManualEntry` NavKey

```kotlin
@Serializable
data class ManualEntry(val metric: String? = null) : NavKey
```

Layout, top to bottom:

1. **Patient selector** — same family-profile chip pattern as Home, defaulting to
   `AppSettings.getActivePatient`.
2. **Quick-log grid** — in Phase 1 this is simply the four Tier-1a tiles: Sugar, BP, Heart rate,
   Oxygen. No personalisation logic needed yet, because four tiles fit on screen at once and there
   is nothing to prioritise between them.
   Once the catalogue grows past ~6 (Phase 2), it becomes *personalised* ordering: the app can
   already infer conditions from data it holds — an existing Blood Sugar / HbA1c trend or metformin
   in the medication tracker → Sugar first; a BP medicine → BP first; warfarin (and specifically
   not a DOAC, see §2 Tier 2) → INR — falling back to the Tier-1a four when nothing is known. That
   is the difference between a screen people use daily and one they open once, but it is only worth
   building when there is more than a screenful to order.
3. **Entry bottom sheet** — opened by a tile, generated from the `VitalMetric`. Big numeric inputs
   (`KeyboardType.Number`, auto-advance systolic → diastolic), date/time defaulted to *now* and
   editable, context chips, optional note, Save. Two taps and a number should complete a BP entry.
4. **Recent log** — grouped by day, newest first, each row tap-to-edit and swipe-to-delete.
5. **"All metrics"** — the full Tier 1–3 catalogue grouped by `VitalMetric.group`, with search.

### 6.2 Entry points

- A **7th Home tile** — "Add Reading" 🩺, alongside the existing six.
- A **FAB on the Trends screen** — the most natural place; the user is already looking at the chart
  they want to add a point to.
- A **"Log now"** action on the reminder notification (§7.5).

---

## 7. Integration points

### 7.1 Trends — lab and home stay separate, always

Manual readings are **never plotted on a lab trend line**, not even marked as distinct. A
glucometer reading and a lab plasma glucose are different measurements taken under different
conditions against different thresholds; overlaying them produces a chart that *looks* like one
series and isn't. The same applies to a home BP cuff vs a clinic reading. So the separation is
structural, not cosmetic:

- **Separate namespace.** A manual metric maps to its own canonical trend name — `Blood Sugar
  (Home)`, `Blood Pressure (Home)`, `INR (Home)` — so it can never collide with a lab canonical
  name coming out of `DashboardEngine.canonicalParamName`, no matter how the catalogue grows.
- **Separate top-level mode on the Trends screen.** A `Lab reports` / `Home readings` segmented
  control sits above the existing category dropdown and switches the whole screen's data source.
  The dropdown's contents change per mode: lab mode keeps today's `TREND_CATEGORIES` untouched;
  home mode lists the `VitalMetric.group`s (Vitals / Diabetes / Lifestyle / Symptoms).
- **Separate builder.** Rather than threading vitals through `buildHealthSummary` and inheriting
  its report-shaped dedup rules (§3, reason 2), add
  `DashboardEngine.buildVitalsSummary(patient, vitals): HealthSummary`, emitting the same
  `ParameterTrend` / `TrendDataPoint` shape from `VitalReading` rows. The existing chart
  composables then render it unchanged — only the data source differs.
  **`buildHealthSummary` is not modified at all.**
- `TrendDataPoint.source` (`"report"` | `"manual"`, defaulted) is still added — not to mark points
  on a shared line, but so click-through knows to open an entry sheet rather than a report, and so
  nothing downstream (Brief, Chat, export) can present a self-reported number as lab-verified.
- Home mode can then do what lab mode structurally cannot: plot **multiple readings per day** (BP
  twice daily is two points, not one averaged dot), draw **systolic and diastolic as a dual line**
  on one chart, and **filter by context** — show Fasting only, or 2 h after meal only. That last
  one is what makes a home sugar log readable at all, and it has no meaning for lab data.

Deliberately deferred, and opt-in when it lands: a **"Lab vs Home" overlay** for a single metric,
triggered as an explicit user action from either mode, for the consult case where a doctor wants to
check whether the home cuff agrees with the clinic. Off by default, never the primary view.

### 7.2 Doctor Brief — the highest-value output

A **separate section**, below the existing lab section, never interleaved with it:
**"Home readings (last 30 days)"** — per logged metric: count, average, range, and % of readings
within target. `"BP: 42 readings, avg 138/86, range 118/72–162/98, 64% in target"` is precisely
what a physician wants and what a patient currently cannot produce. Include it in
`DoctorBriefData.toPlainText()` so it flows into the existing share and TTS paths.

Where a metric exists in both places — sugar, INR — the brief prints **two labelled lines, never
one blended figure**: `Blood sugar (lab, 2 results)` and `Blood sugar (home, 58 readings)`. Averaging
a lab value into a glucometer log would produce a number that describes nothing real, and a doctor
reading the brief has to be able to tell at a glance which is which.

### 7.3 Chat

Fold a recent-vitals digest into the chat context so "is my BP okay?" and "my sugar has been high
this week, why?" can actually be answered. A compact digest only — never the raw 700 rows.

### 7.4 Manual lab-report entry (Tier 4)

Separate path, same entry point: a "Type in a report" tab collecting patient / date / report type /
category plus a repeating parameter row (name, value, unit, reference range), then writing a
`MedicalReport` with `analyzed = true`, empty `imagePath`, and `extractedText` synthesised from the
typed values so FTS still finds it. Reuse `DashboardEngine.canonicalParamName` for name
suggestions as the user types.

### 7.5 Reminders to log

Mirror `MedicineReminderManager` — a `VitalReminderStore` + `AlarmManager` receiver: "Log your BP
at 8:00 AM". Reuse `BootReceiver` re-scheduling. The notification carries a direct action into the
entry sheet for that metric (`ManualEntry(metric = "bp")`).

### 7.6 Export / backup / sync

- Zip backup: **already covered** (§4.3).
- `ExportManager.Payload`: add `vitals: List<VitalReading> = emptyList()`, plus merge-by-id on
  import and a per-patient filter matching the existing report filter. The absent field must
  default to empty so older export zips still import.
- `AccountSync` / `BackupSync`: include the new table wherever `reports` / `pending_tests` are
  enumerated.

---

## 8. Safety and medical-accuracy rules

The app's existing posture (see the `TestReference` docstring: *"a fixed reference (NOT
AI-generated) to avoid any inaccuracy in medical data"*) carries over:

1. **Normal ranges are a curated constant table**, never AI-generated, with the guideline source
   named in a comment — and they must be **home-measurement** thresholds where those differ from
   clinic ones (home/ambulatory BP target 135/85, not clinic 140/90).
2. **Never diagnose.** A reading is shown as Normal / High / Low in the app's existing status
   colours, with the same educational, non-diagnostic framing `TestReference` already uses.
2a. **Classify a number by its own threshold, not its neighbour's.** Systolic and diastolic are
   independently meaningful: 150/70 is a high systolic beside a perfectly normal diastolic. A
   chart showing one of the two alone must use that component's own status, or it paints a normal
   number red. `VitalReference` therefore exposes `systolicStatus`/`diastolicStatus` for the
   per-component trend lines, and a combined `bpStatus` — derived from the other two so they can't
   drift — only for a badge describing one whole reading. The same rule applies to any future
   multi-component metric.
3. **Critical values get a conservative prompt, not a silent green tick.** A curated critical band
   (e.g. systolic ≥ 180 or diastolic ≥ 120; glucose < 54 mg/dL; SpO₂ < 90%) shows a card: *"This
   reading is outside the usual range. Please contact your doctor — seek urgent care if you feel
   unwell."* No auto-dialling, no auto-messaging anyone, no severity claims beyond that.
4. **`plausibleRange` ≠ clinical range.** Rejecting a typo must never look like a medical
   judgement, and a genuinely extreme-but-real reading must still be savable.
5. **Unit handling reuses `UnitConverter`.** mg/dL ↔ mmol/L for glucose already exists and is
   verified; do not add a second conversion path. kg ↔ lb and °C ↔ °F are new but trivial and exact.
6. Manual entries carry `source = "manual"` end-to-end so nothing downstream can present a
   self-reported number as a lab-verified one.

---

## 9. Phasing

**Phase 1 — MVP, four metrics only: sugar, BP, heart rate, oxygen.** `vitals` table + migration
6→7 + DAO + repository API; `VitalCatalog` with the four Tier-1a metrics; the entry screen, sheet
and recent log; Home tile + Trends FAB; the `Lab reports` / `Home readings` segmented control and
`buildVitalsSummary`, including the BP dual-line chart, the pulse union rule (§2 Tier 1a) and
sugar context filtering; `ExportManager` field.

The whole point of the cut is that Phase 1 still builds **all** the load-bearing structure — table,
migration, catalogue, entry-sheet generator, separate trends mode — against only four metrics. Every
later tier is then genuinely additive, and if the design is wrong we find out on four metrics
instead of twenty.

**Phase 1b.** Weight (+ derived BMI) and body temperature. Two catalogue entries; the only new
code is the kg ↔ lb and °C ↔ °F conversions. This is the checkpoint that proves the catalogue
design actually holds — if adding these two needs more than a catalogue entry each, fix that before
Phase 2.

**Phase 2.** Tier-2 device metrics and Tier-3 symptom/lifestyle logging; Doctor Brief section;
critical-value cards; log reminders; personalised quick-log ordering (§6.1).

**Phase 3.** Manual lab-report entry (§7.4); Chat context; the opt-in "Lab vs Home" overlay (§7.1);
Health Connect / Google Fit import (`source = "healthconnect"` — the reason that column exists);
CSV export of a metric; doctor-set personal target ranges.

---

## 10. Files touched (Phase 1)

New:
- `model/VitalReading.kt`, `model/VitalCatalog.kt`
- `ui/ManualEntryScreen.kt`, `ui/components/VitalEntrySheet.kt`
- `util/VitalReference.kt` (curated ranges, mirroring `TestReference`'s discipline)

Modified:
- `local/db/MedicalDatabase.kt` (v7 + `MIGRATION_6_7` + `vitalDao()`), `local/db/Daos.kt`
- `local/LocalStore.kt`, `local/LocalRepository.kt` (CRUD + `mergePatient` re-key)
- `model/Models.kt` (`TrendDataPoint.source`)
- `ai/DashboardEngine.kt` (**new** `buildVitalsSummary` + home-metric groups; `buildHealthSummary`
  and `TREND_CATEGORIES` left untouched)
- `ui/TrendsScreen.kt` (Lab/Home segmented control, per-mode dropdown, FAB, BP dual line, context
  filter chips)
- `ui/HomeScreen.kt` (tile), `NavigationKeys.kt`, `Navigation.kt`
- `backup/ExportManager.kt`
- `ui/UiTranslations.kt` (new strings)
- `app/build.gradle` versionCode / versionName bump, per the repo's per-release convention

---

## 11. Decisions needed before Phase 1 starts

1. **Home tile count.** The grid is 6 tiles today (+3 discovery). Add a 7th, or replace/merge one?
2. **Tier 3 breadth for Phase 2.** The lifestyle/symptom list is long; which of it is actually
   wanted — the clinical ones (pain, symptoms, sleep) only, or the wellness ones (steps, water,
   diet) too?
3. **Critical-value card wording** needs a final sign-off pass, since it is the one place the app
   comes closest to telling someone to seek care.

**Settled:** lab and home data are displayed separately everywhere — separate trend namespace,
separate mode on the Trends screen, separate Doctor Brief section, no merged line and no blended
average (§3.1, §7.1, §7.2).
