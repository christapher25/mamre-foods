# Mamre Foods billing system - rules for every agent

Read docs/01-product-spec.txt, docs/02-architecture.txt,
docs/04-team-plan.txt and docs/03-agent-guide.txt. Doc 1 wins on behaviour, Doc 2 on technology.
Version 1 is a local-first Android app: one phone, one admin account and
salesman accounts (Doc 1 s2.2), no server.

## Always
- Stay inside the folders you own (Doc 3 section 3.1).
- Name the spec section for every behaviour you add.
- Money is integer cents. Material quantities are Long milli-units.
- Tests first for pricing, ledger, usage and every database migration.
- Write through use cases in one transaction.
- Put decisions in docs/DECISIONS.md, questions in docs/QUESTIONS.md.
- Before starting, check that no other session is working in your folder.

## Never
- Show cost, profit or expense data in the Sales area or on a bill.
- Edit or delete bills, payments, returns, purchases, expenses or damage
  entries. Correct with void, credit or a reversing entry.
- Let a line's price differ from the list price unless the customer
  type's switch is on. Treat a missing price or cost as zero.
- Show a corporate account's balance on a bill or in the Sales area.
- Let a salesman account reach the Admin area. Store a password in
  plain, or log, show or export a password or its hash.
- Use a destructive Room migration. Write a business detail in code.
- Ship demo code (fake API, demo logins, seeds) in a release build.
- Commit secrets or the signing key. Add dependencies silently.
- Weaken tests. Push to main. Edit files outside your task's allowed paths.

## Team mode (docs/04-team-plan.txt)
- Several sessions work at once, each in its own folder. Your role is
  named in your first prompt; follow C:\dev\mamre-team\roles\<role>.md.
- Talk only through files in C:\dev\mamre-team (board, tasks, reports).
- Devs push their own branch and open a PR with gh. Only LEAD merges.
  Nobody pushes main.
- Before any Gradle run or the emulator, take a build slot:
  C:\dev\mamre-team\bin\slot.ps1 acquire <ROLE>, and release it after.

## If unsure
Stop. Write the question in docs/QUESTIONS.md. Do not guess.

## Commands
- Android tests:  cd android && ./gradlew test
- Release build:  cd android && ./gradlew assembleRelease
