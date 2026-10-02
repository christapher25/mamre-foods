# Open questions for the owner

One entry per question: date - agent - question - spec sections involved.
2026-10-02 - A1 - [ANSWERED 2026-10-02: removed, see DECISIONS.md] django.contrib.admin is in INSTALLED_APPS (Django default) but not routed in urls.py. Should it be removed so financial records can never be edited through it, or kept for Admin user management? Doc 2 s9 (custom dashboard), s12 (immutability), I-4, I-9, AT-10.
2026-10-02 - A1 - Other worktrees already exist for feat/core-catalog, feat/sales-invoices and feat/android-shell, all branched from the pre-P0 main. Doc 3 s4 says P1 starts only after P0 is merged; they will need to rebase onto main after this PR merges (Doc 3 s5.2).
2026-10-02 - A1 - P1: HTTPS-only, HSTS and secure-cookie settings for production (SECURE_SSL_REDIRECT, SECURE_HSTS_SECONDS, SESSION_COOKIE_SECURE, CSRF_COOKIE_SECURE) are not configured in P0. When and how should they be switched on, and how is local dev kept working? Doc 2 s8, s13.
