# Open questions for the owner

One entry per question: date - agent - question - spec sections involved.
2026-10-02 - A1 - [ANSWERED 2026-10-02: removed, see DECISIONS.md] django.contrib.admin is in INSTALLED_APPS (Django default) but not routed in urls.py. Should it be removed so financial records can never be edited through it, or kept for Admin user management? Doc 2 s9 (custom dashboard), s12 (immutability), I-4, I-9, AT-10.
2026-10-02 - A1 - Other worktrees already exist for feat/core-catalog, feat/sales-invoices and feat/android-shell, all branched from the pre-P0 main. Doc 3 s4 says P1 starts only after P0 is merged; they will need to rebase onto main after this PR merges (Doc 3 s5.2).
2026-10-02 - A1 - P1: HTTPS-only, HSTS and secure-cookie settings for production (SECURE_SSL_REDIRECT, SECURE_HSTS_SECONDS, SESSION_COOKIE_SECURE, CSRF_COOKIE_SECURE) are not configured in P0. When and how should they be switched on, and how is local dev kept working? Doc 2 s8, s13.
2026-10-02 - A1 - For A2 (P2): customer balance and opening_balance_cents are not in GET /sync/catalog. They must reach the device through GET /sync/customer-activity in P2. Doc 2 s5, s4.2, I-6.
2026-10-02 - A1 - Customer.notes is not sent to workers. Should any part of it ever reach the device? Doc 2 s5, I-8.
2026-10-02 - A1 - Seed product codes FRESH and CHAPATHI are placeholders. Confirm the real codes. Doc 1 s3, s4.1.
2026-10-02 - A1 - [ANSWERED 2026-10-02: Retail type defaults, see DECISIONS.md] resolve_price needs a customer. Which price list does a Walk-in (null customer) use: the Retail type defaults? Doc 1 s4.1, s4.2; Doc 2 s4.2. Needed by A2/A3 in P2.
2026-10-02 - A1 - For P7 hardening: login and refresh throttling uses Django's default per-process cache, so with several worker processes the limit is per process. It needs a shared cache (for example the database or Redis) when more than one process runs. Which cache backend, and does it count as a new dependency? Doc 2 s8, s13.
2026-10-02 - A3 - PIN or biometric lock after inactivity is NOT in the P1 shell (owner instruction). Which phase adds it? Doc 2 s6.8, s10 (Login row).
2026-10-02 - A3 - Repository tests in P1 run on the JVM against fake in-memory DAOs (no Robolectric, owner instruction), so real Room queries, converters and migrations are untested. Real Room tests need an instrumented-test phase; which one? Doc 2 s12.
2026-10-02 - A3 - /sync/catalog also returns settings (business_name, address, phone, footer_text) but P1 lists no Room table for them, so they are ignored for now. They are needed for the receipt header in P3; confirm they should be stored then. Doc 2 s5, s7.
2026-10-02 - A3 - Which phase handles a 426 (force update) answer and what should the worker see? P1 only sends X-App-Version. Doc 2 s5.2.
2026-10-02 - A3 - The real BASE_URL is a placeholder (api.example.invalid) until a server is hosted; set USE_FAKE_API=false and BASE_URL per build type then. Is a separate release build type with its own URL wanted? Doc 2 s13.
2026-10-02 - A3 - The new android workflow could not be run on GitHub (nothing is pushed). Please confirm it is green on the first pull request, since gradlew has no executable bit and the SDK 37 platform must install on the runner. Doc 2 s2 CI.
