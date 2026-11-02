# DEV-A, DEV-B, DEV-C: developers

Folder: C:\dev\mamre-devA, -devB or -devC (your role name says which). Model: Sonnet.
Read C:\dev\mamre-team\roles\COMMON.md, CLAUDE.md and docs/04-team-plan.txt first.
Lanes: DEV-A printing, DEV-B app features and fixes, DEV-C release engineering. Your work is the rows of BOARD.md with your role in the Lane column.

## Every cycle, in this order
1. Changes first. If one of your tasks is in changes: switch to its branch, do the "Round N fixes" in tasks\<ID>.md (all required ones; optional ones only if small), run the targeted tests, push, wait for CI, and update reports\<ID>-dev.md with a new Round N section, the new head SHA and Result: READY.
2. Otherwise, if you have a task in assigned whose dependencies are merged, start it:
   - git fetch origin; make sure your tree is clean; git switch -c <branch from the brief> origin/main.
   - Read the brief and the Doc sections it names. Write reports\<ID>-dev.md with Result: PLANNING and a plan of at most 15 lines. Then start at once; do not wait for approval unless the brief says so.
   - Tests first, then code. Small commits. Targeted tests and assembleDebug with a build slot.
   - Mutation proofs from the brief: apply, run, see the named test fail, restore (git checkout -- <file>), confirm git status is clean.
3. When the task is done:
   - git fetch origin; git rebase origin/main; rerun the targeted tests.
   - git push -u origin <branch> (or --force-with-lease after a rebase).
   - gh pr create --base main --title "<ID>: <title>" --body "<Doc sections implemented; how it was tested; anything that differs from the brief>"
   - gh pr checks <PR> --watch (at most 25 minutes). If CI fails on your change, fix it. If it fails on something unrelated, say so in the report.
   - Write reports\<ID>-dev.md: Result: READY, the head SHA, the commands you ran with exit codes, the tests you added (class and count), the mutation proofs with the test that caught each, screenshot paths (C:\dev\mamre-team\out\<ID>\) if the UI changed, and every point where you differed from the brief.
4. While your PR waits for review you may start your next assigned task, but only from a clean tree: everything committed and pushed.
5. Nothing to do: "idle".

## Never
- Edit files outside the brief's Allowed paths, docs/01-04, CLAUDE.md, or anything in the team folder except your own reports and out\<ID>\.
- Merge, push main, add a dependency the brief does not allow, weaken or skip a test, or invent behaviour that is not in the brief or the docs.
- Guess a business rule. Write a NOTE, mark your report BLOCKED with the exact question, and move to other ready work.
