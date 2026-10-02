# Decisions

One line per decision: date - decision - reason.
2026-10-02 - A1 may create .github/, requirements and the empty apps for sales, sync, production, expenses, costing, reports and dashboard for P0 ONLY; these return to owner control (Doc 3 s3.1) after P0 - owner instruction.
2026-10-02 - requirements.txt, .env.example, pytest.ini and ruff.toml live in backend/, and CI installs and runs from backend/ - owner instruction.
2026-10-02 - Python 3.12 exactly (Doc 2 s2); verified 3.12.10 in the project .venv - owner instruction.
2026-10-02 - SECRET_KEY has an insecure default only when DEBUG is on and raises ImproperlyConfigured otherwise; CI sets DEBUG=True and a dummy key - owner instruction.
2026-10-02 - TIME_ZONE defaults to UTC with a PENDING P-7 comment; the business zone becomes an Admin setting later (Doc 1 s6.1, Doc 2 s4.1).
2026-10-02 - SENTRY_DSN is listed in .env.example only and not wired up in P0 (Sentry is not an authorized dependency).
2026-10-02 - Dependencies are pinned as compatible ranges (e.g. Django>=5.0,<6.0) rather than exact versions - keeps patch updates possible; revisit if the owner wants exact pins.
2026-10-02 - Django DEFAULT_AUTO_FIELD stays BigAutoField; each app owner sets UUID primary keys explicitly per model (Doc 2 s4.1).
2026-10-02 - ruff rule set is E, F, W, I, B, DJ with line length 100, migrations excluded - a conservative baseline; owner may tighten.
2026-10-02 - Removed django.contrib.admin from INSTALLED_APPS (no admin route existed); auth, sessions and messages stay - so financial records can never be edited through Django admin (Doc 2 s12, I-4, I-9, AT-10); owner answer to the QUESTIONS.md item.
2026-10-02 - dj-database-url (DATABASE_URL to Django DATABASES), python-dotenv (optional local .env loading) and psycopg[binary] (PostgreSQL 16 driver) are needed for env-based configuration and the locked stack (Doc 2 s2, s13); all three were on the owner's P0 dependency list.
