## Summary

<!-- What changed and why. -->

## Spec sections implemented

<!-- Name the Doc 1 / Doc 2 sections (Doc 3 section 5.3, step 1). Nothing more than these. -->

## How to test

<!-- Commands and manual steps. -->

## Checklist (Doc 3 sections 5.3, 8.2 and 10.1)

- [ ] Doc 1 / Doc 2 sections are named above; no behaviour without a section (nothing invented)
- [ ] All changed files are inside the folders my agent owns (Doc 3 section 3.1)
- [ ] Tests written first for money logic and all tests pass
- [ ] CI is green: pytest, ruff, `makemigrations --check`
- [ ] Money is integer cents, no floats (I-1); ingredient quantities use Decimal
- [ ] No cost, profit or expense data reachable by a worker (I-8)
- [ ] No financial record is edited or deleted (I-4, I-9)
- [ ] Works offline and is idempotent where sync can repeat it
- [ ] No tests deleted, skipped or weakened
- [ ] No new dependency or API shape change, or the reason is noted below
- [ ] Migrations are only in apps I own
- [ ] `docs/DECISIONS.md` updated if a decision was made
- [ ] `docs/QUESTIONS.md` updated if anything is open
- [ ] Rebased on `origin/main` before opening this PR
- [ ] Reviewer agent (Doc 3 section 9.3) reported PASS
- [ ] Owner approval, then squash merge. Delete the branch and remove the worktree afterwards.

## Shared files touched

<!-- Settings, URLs, requirements, CI: list them and say why (Doc 3 section 5.5). -->

## New dependencies

<!-- Doc 2 section 2 rule: none, or name each one and explain why. -->
