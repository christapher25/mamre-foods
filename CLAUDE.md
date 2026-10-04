# Mamre Foods billing system - rules for every agent

Read docs/01-product-spec.txt, docs/02-architecture.txt and
docs/03-agent-guide.txt. Doc 1 wins on behaviour, Doc 2 on technology.
Version 1 is a local-first Android app: one Owner, one phone, no server.

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
- Use a destructive Room migration. Write a business detail in code.
- Ship demo code (fake API, demo logins, seeds) in a release build.
- Commit secrets or the signing key. Add dependencies silently.
- Weaken tests. Push to main. Edit another agent's folders.

## If unsure
Stop. Write the question in docs/QUESTIONS.md. Do not guess.

## Commands
- Android tests:  cd android && ./gradlew test
- Release build:  cd android && ./gradlew assembleRelease
