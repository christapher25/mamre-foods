# Open questions for the owner

One entry per question: date - agent - question - spec sections involved.
2026-10-02 - A1 - [ANSWERED 2026-10-02: removed, see DECISIONS.md] django.contrib.admin is in INSTALLED_APPS (Django default) but not routed in urls.py. Should it be removed so financial records can never be edited through it, or kept for Admin user management? Doc 2 s9 (custom dashboard), s12 (immutability), I-4, I-9, AT-10.
2026-10-02 - A1 - Other worktrees already exist for feat/core-catalog, feat/sales-invoices and feat/android-shell, all branched from the pre-P0 main. Doc 3 s4 says P1 starts only after P0 is merged; they will need to rebase onto main after this PR merges (Doc 3 s5.2).
2026-10-02 - A1 - P1: HTTPS-only, HSTS and secure-cookie settings for production (SECURE_SSL_REDIRECT, SECURE_HSTS_SECONDS, SESSION_COOKIE_SECURE, CSRF_COOKIE_SECURE) are not configured in P0. When and how should they be switched on, and how is local dev kept working? Doc 2 s8, s13.
2026-10-02 - A1 - For A2 (P2): customer balance and opening_balance_cents are not in GET /sync/catalog. They must reach the device through GET /sync/customer-activity in P2. Doc 2 s5, s4.2, I-6.
2026-10-02 - A1 - Customer.notes is not sent to workers. Should any part of it ever reach the device? Doc 2 s5, I-8.
2026-10-02 - A1 - Seed product codes FRESH and CHAPATHI are placeholders. Confirm the real codes. Doc 1 s3, s4.1.
2026-10-02 - A1 - [ANSWERED 2026-10-02: Retail type defaults, see DECISIONS.md] resolve_price needs a customer. Which price list does a Walk-in (null customer) use: the Retail type defaults? Doc 1 s4.1, s4.2; Doc 2 s4.2. Needed by A2/A3 in P2.
