# UI Preview — Home Redesign

No APK is in this folder yet (see `README.md` for why). Until one lands, this is a
written walkthrough of the journey as implemented in code, so it's clear what to expect
before you install a build. A static visual mockup of the Home screen and the Records
screen was also produced during design — ask in the same conversation thread that built
this for the artifact link if you don't still have it.

## Top bar (every screen shows some subset of this)

- **Account** (top-left) — profile, plan/usage, theme, biometric login, change password,
  and a **Server Settings** row (moved here from its own top-level icon) covering server
  address, voice engine, backups, and the legacy per-request language dropdown used for
  AI-generated content.
- **Chat** (top-left, next to Account) — opens the AI assistant with no page context.
- **Logo + Refresh** (center) — refresh is its own small tap target next to the logo, not
  the logo itself.
- **Compare** (top-right) — unchanged, opens the report-comparison flow.
- **Language** (top-right, new) — a globe icon opening the 11 supported languages. Picking
  one re-labels button/title text app-wide (served from the backend `ui_translations`
  table, cached on-device) and also becomes the language used for AI chat answers and
  report explanations, same as before.

On first-ever launch, the language defaults to whatever the device's *active keyboard*
is set to (not the system display language), falling back to English if that keyboard
language isn't one of the 11 supported.

## Home screen

Six square actions, two per row, in this order:

1. **Scan Report** — opens the existing scan flow; on success lands on the new report's detail page.
2. **Records** — search, an "All Time / 1M / 3M / 6M" filter, clinical insights panel, the
   scanned-report list, and a Scan Report FAB. This is the old "Reports History" tab, now
   full-screen.
3. **Reminders** — today's medicine schedule by time slot (Morning/Afternoon/Evening/Night),
   quick-add for a medicine or appointment. This is the old "Today's Meds" tab.
4. **Medication Tracker** — full medication history with dosage/frequency, tap a card for
   details or to log a dose taken, long-press to multi-select for bulk delete or a bulk
   frequency change.
5. **Pending Tests** — recommended follow-up tests, a "resolved" state once a matching
   report is scanned, and a FAB to add one manually.
6. **Trends** — unchanged, parameter trend charts across scanned reports.

Each of the five destination screens above (everything except Trends, which already had
one) carries its own **Chat** icon in its top bar. Tapping it opens the assistant with
"Asking about: <screen name>" shown under the title, and the question sent to the AI is
prefixed with that context — so "what does this mean?" on the Medication Tracker screen
is scoped to medications, not the whole app.

## What's *not* covered yet

Static text on the older screens — Login, Register, Scan, Compare, Trends' own labels,
Report Detail, Detailed Analysis — isn't wrapped in the new translation layer yet. Only
the ~20 strings introduced by this redesign are covered. Extending translation coverage to
the rest of the app is a separate follow-up pass.
