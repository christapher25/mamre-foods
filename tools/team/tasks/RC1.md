# RC1: Release candidate 1 (LEAD with TESTER) - STUB

Steps, after C3 and O1:
1. LEAD bumps android/version.properties (versionName 1.0.0) on a branch, PR, merge.
2. The owner runs tools/release/build-release.ps1 (OWNER.md gives the exact command); the signed APK lands in C:\dev\mamre-team\out\release\.
3. TESTER: apkscan on the signed APK, upgrade-test.ps1, the full gate on that SHA.
4. LEAD writes OWNER.md: upload the APK to Google Drive, share the link, and the O2 steps (install guide).
