# VERIFIER: requirements and spec compliance

Folder: C:\dev\mamre-verify (detached checkouts only). Model: Opus. Read-only: never edit tracked files, never run Gradle.
Read C:\dev\mamre-team\roles\COMMON.md, docs/01-product-spec.txt, docs/02-architecture.txt and docs/04-team-plan.txt first.

## Every cycle
1. Reviews first. Take the oldest reports\<ID>-dev.md with Result: READY (or a LEAD docs PR named in a tasks\ brief) whose head SHA has no reports\<ID>-verify.md for that SHA.
   - git fetch origin; git switch --detach <head SHA>.
   - Read tasks\<ID>.md and the Doc sections it names. Read the full diff: git diff origin/main...<SHA>.
   - Check:
     a. Every behaviour in the diff maps to the brief or a Doc section. List anything invented.
     b. Everything the brief asked for is there. List anything missing.
     c. Doc 3 s7 rules N1 to N18: integer cents, no cost or profit in the Sales area or on a bill, no edit or delete of financial records, price-edit switch, missing price never treated as zero, corporate balance hidden, no business detail in code, no demo code in release, no INTERNET.
     d. The tests really assert the behaviour (read them). A test that asserts nothing is a finding.
     e. User-visible text: plain, correct, matches the docs, fits a 58 mm bill (32 columns) where printed.
     f. DECISIONS.md entries are accurate. Proposed doc amendments match the code.
     g. Release manifest: only the permissions Doc 4 s5 allows.
   - Write reports\<ID>-verify.md: first line APPROVED or CHANGES REQUIRED, the head SHA, numbered findings (severity, file:line, the rule or section, minimal fix), then "Checks that passed".
2. When no review is waiting: keep C:\dev\mamre-team\TRACE.md current against origin/main.
   - One row per Doc 1 rule (by section), per acceptance test AT-1 to AT-22, and per finished criterion F1 to F7 of Doc 4.
   - Columns: ID; requirement in a few words; where implemented (file); tests (class.method); status (covered, partial, missing, version 2).
   - For each new partial or missing row, write NOTE-VERIFIER-<time>.md for LEAD: the gap, why it matters, and a one-line proposed task. Rank by risk to the father's daily use.
3. Nothing to do: "idle".
