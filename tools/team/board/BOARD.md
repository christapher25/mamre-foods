# BOARD (LEAD edits this file; everyone reads it)

main: (LEAD writes "main moved to <sha> (<ID>)" here after each merge)

| ID | Lane | Title | Depends on | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|---|---|
| T0 | LEAD | State audit; fill this board and STATE.md | - | assigned | - | - | - |
| V1 | VERIFIER | TRACE.md from main and ranked gap list | - | assigned | - | - | - |
| B1 | DEV-B | Flaky InvoiceFlowViewModelTest root cause + two test gaps | T0 | todo | fix/android-flaky-and-test-gaps | - | - |
| A1 | DEV-A | Printer auto-detect chain, ESC/POS, Printer check screen | T0 | todo | feat/android-print-autodetect | - | - |
| C1 | DEV-C | Release signing from local file, version scheme, keystore script, minSdk report | T0 | todo | feat/android-release-signing | - | - |
| L1 | LEAD | Docs catch-up | T0 | todo | docs/v1.3-catch-up | - | - |
| B2 | DEV-B | First-run Set up your business + Finish setup card | B1, L1 | todo | feat/android-first-run-setup | - | - |
| A2 | OWNER | Device check on the handheld (Printer check photo) | A1 merged | todo | - | - | - |
| A3 | DEV-A | Finish printing for the real device; golden bills on paper | A2 | todo | feat/android-print-device | - | - |
| C2 | DEV-C | Upgrade test (install over keeps data) + install and update guides | C1 | todo | feat/android-upgrade-test | - | - |
| O1 | OWNER | Create the signing key once; store it in two places | C1 merged | todo | - | - | - |
| C3 | DEV-C | Freeze schema 1 (hash test) + remove legacy lock-state branch | A3, B2, C2 | todo | feat/android-schema-freeze | - | - |
| RC1 | LEAD+TESTER | Release candidate 1: signed APK, full proof, upgrade test, download link | C3, O1 | todo | - | - | - |
| O2 | OWNER | Install RC1 on the handheld; set PIN, business, prices; first real bill | RC1 | todo | - | - | - |
