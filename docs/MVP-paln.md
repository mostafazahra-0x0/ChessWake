# ChessWake — MVP Plan (10-Day Experiment)

## Purpose

This is NOT the product MVP. This is a personal experiment to answer one question:

**Does solving a chess puzzle to dismiss an alarm actually help you wake up, or does it just make you angry (like Alarmy's math puzzles did)?**

Everything below exists only to answer that question, on a real device, as fast as possible. Nothing else matters right now.

---

## Explicit Non-Goals (do not build these yet)

- No puzzle database (Room, SQLite) — one hardcoded puzzle is enough
- No difficulty levels
- No Sleep as Android integration
- No prayer times
- No statistics, streaks, rating
- No generic "challenge system" architecture
- No Material 3 Expressive / Dynamic Colors — default Material 3 is fine
- No Hilt — manual instantiation is fine for this scope
- No Stockfish — puzzle validation is a hardcoded move comparison
- No multiple puzzles — one mate-in-1 position, hardcoded

If you catch yourself building any of the above in the next 10 days, stop. That's scope creep, not progress.

---

## Success Criteria

At the end of 10 days you should be able to answer, from real experience:

1. Did the alarm reliably ring and launch the puzzle screen every time?
2. Did solving the puzzle feel like it woke you up, or just annoyed you?
3. Compared to a normal alarm, did you feel more awake afterward?

If the answer to #2 is "annoyed, like Alarmy" — that's a valid and useful result. It means the core idea needs rethinking before any further investment.

---

## Step 1 — Project Setup

- New Android Studio project: Empty Activity, Kotlin, Jetpack Compose
- Min SDK 26
- Confirm a basic "Hello World" screen builds and runs on your real device (not just an emulator — alarm behavior differs on real hardware)

**Done when:** app installs and opens on your phone.

---

## Step 2 — Bare Alarm (no chess yet)

- Use `AlarmManager.setExactAndAllowWhileIdle()` to schedule a one-time alarm a few minutes in the future
- On trigger: play a sound and/or vibrate, and open a full-screen Activity (use `setShowWhenLocked` + `setTurnScreenOn`, or a full-screen intent notification — whichever works reliably on your device)
- No chess puzzle yet. Just confirm the alarm fires reliably, including when:
  - Screen is off
  - Phone has been idle for a while (Doze mode)
  - App was killed/swiped away before the alarm time

**Done when:** the alarm reliably wakes the screen and makes noise, every time, under all three conditions above. This is the highest-risk technical part of the whole project — do not move on until this is solid.

---

## Step 3 — Static Puzzle Screen

- One hardcoded chess position (FEN string) with a mate-in-1 solution
- Simple Compose UI: 8x8 grid of squares (plain colored boxes are fine), Unicode chess piece characters (e.g. ♔♕♖♗♘♙) as text, no images needed
- Basic interaction: tap a piece, tap a destination square
- Compare the resulting move against the single hardcoded correct answer
- Show clear "Correct" / "Try again" feedback

**Done when:** you can visually see the board, make a move by tapping, and get correct/incorrect feedback — independent of the alarm, just running the app normally.

---

## Step 4 — Connect Alarm to Puzzle

- Alarm fires → opens the puzzle screen from Step 3 directly (instead of a blank screen)
- Sound/vibration continues looping until the puzzle is solved correctly
- Only a correct move stops the alarm and dismisses the screen

**Done when:** the full loop works end-to-end: alarm rings → puzzle appears → solve it → alarm stops.

---

## Step 5 — Real Usage (remaining days)

- Set this as your actual alarm for the rest of the 10 days
- Keep a plain note (even just in Obsidian) after each morning: how you felt, whether it worked, whether you got annoyed
- No feature work during this phase — only bug fixes if the alarm fails to fire or the app crashes

---

## After 10 Days

Review your notes honestly and decide, based on real data:

- **Worked well** → discuss what a real MVP (with the emergency exit, mood rating, etc.) should look like next
- **Felt like Alarmy** → discuss whether chess-as-CAPTCHA is the wrong mechanism entirely, before writing any more code
- **Mixed/unclear** → identify specifically what varied (puzzle difficulty, time of day, sleep quality) before drawing conclusions