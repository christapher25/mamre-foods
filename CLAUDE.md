# Mamre Foods billing system - rules for every agent

Read docs/01-product-spec.pdf, docs/02-architecture.pdf and
docs/03-agent-guide.pdf. Doc 1 wins on behaviour, Doc 2 on technology.

## Always
- Stay inside the folders you own (Doc 3 section 3.1).
- Name the spec section for every behaviour you add.
- Money is integer cents. Use Decimal for ingredient quantities.
- Tests first for pricing, allocation, balances and costing.
- Work offline-first and make writes idempotent.
- Put decisions in docs/DECISIONS.md, questions in docs/QUESTIONS.md.

## Never
- Show cost, profit or expense data to a worker (API or UI).
- Edit or delete invoices, payments, returns, expenses or batches.
- Let a worker change a price. Treat a missing price or cost as zero.
- Invent features, prices or quantities. Add dependencies silently.
- Weaken tests. Push to main. Edit another agent's folders.

## If unsure
Stop. Write the question in docs/QUESTIONS.md. Do not guess.