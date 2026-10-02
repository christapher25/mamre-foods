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
2026-10-02 - P1 contract first: openapi.yaml v0.2.0 adds /auth/login, /auth/refresh, /me and /sync/catalog; auth errors use the Doc 2 s5.2 body {code,message,details} through a small exception handler in accounts - owner instruction.
2026-10-02 - A1 may add GET /api/v1/sync/catalog in the catalog app, routed from config/urls.py, for P1 ONLY although sync/ belongs to A2; A2 takes it over after P1 - owner authorisation.
2026-10-02 - CustomerType and PriceOverride get is_active (Doc 2 s4.2 lists none; needed for sync removal, Doc 1 s4.2 "active override"). PriceDefault is history and is never removed - owner answer.
2026-10-02 - effective_from is a DateField and resolve_price takes a date "at" (Doc 1 s4.3); unique keys on (customer_type, product, effective_from) and (customer, product, effective_from); unit_price_cents must be greater than 0 (Doc 1 s4.2, never default to zero) - owner answer.
2026-10-02 - Seed product codes FRESH (Mamre Fresh Chapathi) and CHAPATHI (Mamre Chapathi) are PLACEHOLDERS; Doc 1 s3 gives names but no codes - owner answer.
2026-10-02 - Workers get JWT tokens only; Admin signs in with sessions when the dashboard exists. /auth/login refuses admin accounts with the same generic error (Doc 2 s2, s8). /me returns the code of the most recent active device or null - owner answer.
2026-10-02 - SyncCounter (single-row table in catalog) supplies monotonic sync_version values (Doc 2 s4.1). Every change to Product, CustomerType, Customer, PriceDefault, PriceOverride and AppSetting bumps it, only through catalog services.py; AppSetting therefore also carries sync_version; a test fails if a change does not bump it - owner answer.
2026-10-02 - Worker catalog serializers use explicit field whitelists. Customer excludes notes and opening_balance_cents; Product excludes packing_cost_cents and yield_per_kg (Doc 2 I-8). Balance is not sent in P1 - owner answer.
2026-10-02 - Login and refresh use DRF ScopedRateThrottle with rates configurable by settings (Doc 2 s8); no new dependency - owner answer.
2026-10-02 - The data migration seeds the 3 customer types, 2 products and AppSetting business_name="Mamre Foods" only; no prices (P-4), no address, phone or footer (P-6) - owner answer.
2026-10-02 - Price resolution ignores inactive overrides entirely; within overrides and within defaults the latest effective_from not after "at" wins; pick_price is the pure core, resolve_price reads rows via selectors (Doc 1 s4.2, s4.3).
2026-10-02 - Price changes are audited in catalog services (create and update, before/after JSON, same transaction). A PriceDefault update may change unit_price_cents only; a new price is a new row with a new effective_from; PriceDefault has no deactivate or delete (owner answer 1).
2026-10-02 - Catalog models refuse a save that did not bump sync_version (RuntimeError), so a change outside services.py fails loudly (owner answer 7).
2026-10-02 - GET /sync/catalog sends no pagination: rows changed after the cursor, ordered by sync_version; the new cursor is the highest version returned, or the request cursor if nothing changed; an empty cursor= counts as omitted; settings are limited to business_name, address, phone, footer_text (Doc 2 s5, s6.4).
2026-10-02 - Cursor safety relies on next_sync_version locking the SyncCounter row until the writing transaction commits, so on PostgreSQL versions become visible in order; SQLite (tests only) serialises writers anyway (Doc 2 s2, s6.4).
2026-10-02 - The sync_version guard lives in apps/accounts/sync_guard.py (abstract SyncGuarded plus a queryset), the lowest layer, so accounts.AppSetting and the catalog models share it with no import cycle; SyncCounter and next_sync_version stay in catalog and accounts.set_setting imports them lazily. The guard refuses save() without a new bump, QuerySet.update, bulk_create and bulk_update (reviewer items 2, 3).
