# Rules for every role on the Mamre team

Read this once when your session starts, and again after a /compact or when you are unsure.

## Where things are
- Team folder: C:\dev\mamre-team (BOARD.md, STATE.md, OWNER.md, TRACE.md, tasks\, reports\, locks\, out\, bin\, roles\).
- Repo: GitHub christapher25/mamre-foods. The main checkout C:\dev\mamre belongs to LEAD only.
- Sources of truth, in order: docs/01-product-spec.txt (Doc 1), docs/02-architecture.txt (Doc 2), docs/04-team-plan.txt (Doc 4), docs/03-agent-guide.txt (Doc 3), CLAUDE.md.

## Talking to each other
- Only LEAD edits BOARD.md, STATE.md, OWNER.md and tasks\*.md. Only VERIFIER edits TRACE.md.
- Everyone else writes only in reports\:
  - <TASK>-dev.md, <TASK>-test.md, <TASK>-verify.md
  - NOTE-<ROLE>-<yyyymmdd-hhmm>.md for a question, a blocker or a gap you found.
- Every report starts with this header, filled in:
  Task: / Role: / Branch: / PR: / Head SHA: / Result: READY | APPROVED | CHANGES REQUIRED | BLOCKED | PLANNING / Date:
- A review counts only for the head SHA it names. A new push needs new test and verify reports.
- Overwrite your own report for a new round, but keep a "Round N" section for each round.

## Builds (16 GB laptop: at most two Gradle runs at once)
- Before any gradlew command or starting the emulator:
  powershell -NoProfile -ExecutionPolicy Bypass -File C:\dev\mamre-team\bin\slot.ps1 acquire <ROLE>
  It prints SLOT n (you hold it) or BUSY (do reading or planning, and try again in a few minutes).
- Right after the run:
  powershell -NoProfile -ExecutionPolicy Bypass -File C:\dev\mamre-team\bin\slot.ps1 release <ROLE>
  Never end a cycle holding a slot. A slot older than 60 minutes is treated as abandoned.
- The emulator counts as one slot. Close it when done.
- JAVA_HOME and ANDROID_HOME are set for the user. If a shell lacks them: JAVA_HOME=C:\Program Files\Android\Android Studio\jbr, ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk.
- Devs: targeted tests (--tests ...) and assembleDebug. The full gate with --rerun-tasks is TESTER's and CI's job.
- Use --no-daemon for full gates so memory is freed afterwards.

## Git
- One session per folder. Do not start work if another session is in your folder.
- Devs branch per task from origin/main, push their own branch, open the PR with gh. Never push main. Force-push only your own branch after a rebase, with --force-with-lease.
- Only LEAD merges.
- Rebase onto origin/main before opening a PR, and when the top of BOARD.md says "main moved".
- Commit messages end with the two attribution lines from CLAUDE.md if it has them.

## Hard rules
CLAUDE.md and Doc 3 section 7 (N1 to N18) apply to everyone, always. No new dependency unless the task brief allows it. The release app never gets the INTERNET permission.

## Questions
Write a NOTE file and carry on with something else. Do not guess business rules (money, prices, what is printed, what the father sees). LEAD answers from the docs or forwards to OWNER.md.

## Cycles
You run under /loop. Each cycle:
1. Read BOARD.md and the reports that concern you.
2. Do the next piece of your work.
3. End with at most three lines of status.
If there is nothing to do, end the cycle with the single word "idle". Do not re-read the docs every cycle; read them when you start a new task.
If your context is getting long, write a handoff note into your report (Doc 3 s8.3 format) and a NOTE asking LEAD to have your tab restarted.
