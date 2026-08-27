# ChessWake

**An open-source Android alarm app that makes you solve a chess puzzle before you can dismiss it.**

ChessWake started from a simple idea: instead of tapping "dismiss" on autopilot, you solve a small chess puzzle first — just enough mental engagement to break the inertia of half-asleep dismissal. It's privacy-first, offline-first, and free.

> **Current status:** scoped to a 10-day personal experiment — see [docs/MVP-paln.md](docs/MVP-paln.md). The active feature set is intentionally minimal (one hardcoded puzzle, no accounts).

---

## Features

- **Chess-based alarm dismissal** — solve a puzzle to turn off the alarm
- **Local puzzle database** — no internet required
- **Material 3 Expressive UI** with Android dynamic colors (Material You)
- **Light / Dark / System themes**
- **Fully offline** — no account, no cloud, no tracking
- **Sleep as Android integration** (optional)

> ChessWake is under active early development. The long-term vision includes prayer-time-based alarms, additional cognitive challenge types (memory, math, patterns), training mode, and personal statistics — introduced gradually as the core experience proves reliable.

---

## How It Works

```
Alarm rings → Chess puzzle appears → You solve it → Alarm dismisses
```

That's it. No accounts, no setup friction, no cloud dependency.

---

## Tech Stack

| Layer                | Technology                            |
|-----------------------|----------------------------------------|
| Language              | Kotlin                                |
| UI Toolkit            | Jetpack Compose                       |
| Design System         | Material 3 Expressive + Dynamic Color |
| Local Persistence     | Room / SQLite                         |
| Async                 | Kotlin Coroutines & Flow              |
| Dependency Injection  | Hilt                                  |
| Scheduling            | AlarmManager                          |
| Chess Engine          | Stockfish (where applicable)          |
| Build                 | Gradle (Kotlin DSL)                   |

---

## Privacy

ChessWake is privacy-first by design:

- No account required
- No cloud dependency for core functionality
- No trackers, no ads, no analytics by default
- All data (alarms, puzzle history, settings) stored locally on-device

Any future cloud features (e.g. cross-device sync) will be strictly optional and will never be required to use the core app.

---

## Installation

> ChessWake is not yet available on any app store.

Open the project root in **Android Studio**, or build from a terminal:

```bash
git clone https://github.com/mostafazahra-0x0/ChessWake.git
cd ChessWake
./gradlew assembleDebug
```

Requires: JDK 17, Android SDK with API 35. Build output: `app/build/outputs/apk/debug/app-debug.apk`.

---

## Contributing

Contributions are welcome. Please open an issue to discuss significant changes before submitting a pull request.

---

## License

This project is licensed under the **MIT License** — see [LICENSE](LICENSE) for details.

---

## Why ChessWake?

Most alarms optimize for speed of dismissal. ChessWake optimizes for actually waking up — using a small, deliberate mental challenge to interrupt the "dismiss and fall back asleep" cycle.
