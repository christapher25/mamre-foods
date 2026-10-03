# Mamre Foods billing system - rules for every agent

Read docs/01-product-spec.txt, docs/02-architecture.txt and
docs/03-agent-guide.txt before writing any code.
Doc 1 wins on behaviour, Doc 2 on technology, Doc 3 on process.

## Always
- Stay inside the folders you own (Doc 3 section 3.1).
- Name the spec section for every behaviour you add.
- Money is integer cents. Material quantities are Long milli-units.
- Tests first for pricing, allocation, balances, usage and costing.
- Work offline-first and make writes idempotent.
- Put decisions in docs/DECISIONS.md, questions in docs/QUESTIONS.md.
- Before starting, check that no other session is working in your folder.

## Never
- Show cost, profit or expense data to a worker (API or UI).
- Edit or delete invoices, payments, returns, purchases, expenses or damage
  entries. Correct with void, credit or a reversing entry.
- Let a worker change a price unless the customer type's "worker can edit
  price" switch is on. Treat a missing price or cost as zero.
- Invent features, prices or quantities. Add dependencies silently.
- Weaken tests. Push to main. Edit another agent's folders.
- Ship demo code (fake API, demo logins, seeds) in a release build.

## If unsure
Stop. Write the question in docs/QUESTIONS.md. Do not guess.

## Commands (once the projects exist)
- Backend tests:  cd backend && pytest
- Lint:           cd backend && ruff check .
- Migrations:     cd backend && python manage.py makemigrations --check
- Android tests:  cd android && ./gradlew test
