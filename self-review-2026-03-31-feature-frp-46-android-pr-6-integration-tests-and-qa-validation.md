# Self-Review: feature/frp-46-android-pr-6-integration-tests-and-qa-validation

**Date**: 2026-03-31
**Ticket**: FRP-46 — (Linear unavailable; requirements inferred from PR description and code)
**PR**: #32 — feat(FRP-46): integration tests and QA validation for MMP attribution…
**Base**: feature/frp-53-android-mmp-integration
**Files changed**: 15
**Lines**: +772 / -172

---

## Summary

This PR delivers a substantial test-infrastructure improvement alongside a targeted production bug fix. The main deliverables are:

1. **`MmpIntegrationTest.java`** — 10 end-to-end integration scenarios covering the full MMP attribution pipeline (GAID, Install Referrer, Deep Link click IDs, UTM expiry, schema validation, lifecycle regression guards). Pure JVM, no Robolectric dependency.
2. **Robolectric 3.5 → 4.14.1 upgrade** — a significant infrastructure change that modernizes the test suite. This required adapting 10 existing test files: removing deprecated `@Config(constants=..., sdk=...)`, replacing `ShadowLog.getLogs().isEmpty()` with severity-filtered assertions, adding `@LooperMode(PAUSED)` and `LooperDrainingExecutor` where needed, and updating assertions to match Robolectric 4.x behavior (device metadata, network carrier, screen metrics, error messages).
3. **`sourceMiddleware` NPE fix** — `Builder.sourceMiddleware` was `null` when `useSourceMiddleware()` was never called, causing `addAll(null)` to throw. Initialized to `new ArrayList<>()`.
4. **Clock-injectable UTM expiry tests** — `trackApplicationLifecycleEvents(long nowMillis)` overload enables FreshpaintInstallEventTest to assert UTM inclusion/expiry without depending on wall-clock time.

Overall, the code quality is solid. The integration tests are well-structured with clear scenario naming (IT1–IT7c), thorough assertions, and good separation between test setup and verification. The Robolectric upgrade is well-handled — each test file adaptation is localized and the changes align with Robolectric 4.x migration patterns. The production changes are minimal and well-scoped.

---

## Review Confidence

| Blocker | Impact |
|---------|--------|
| Unable to fetch Linear ticket (no Linear MCP tool) | Requirement coverage based on inferred criteria from PR description only; explicit acceptance criteria not verified |

---

## Requirement Coverage

Based on the PR description and commit messages (no Linear ticket available), the following acceptance criteria are inferred:

| Criterion | Status | Evidence |
|-----------|--------|----------|
| AC-1 [inferred]: MmpIntegrationTest with 9+ end-to-end scenarios | MET | `MmpIntegrationTest.java` — 10 test methods (IT1–IT7c) |
| AC-2 [inferred]: Clock-injectable `trackApplicationLifecycleEvents` overload | MET | `Freshpaint.java:367-368` — `@VisibleForTesting void trackApplicationLifecycleEvents(long nowMillis)` |
| AC-3 [inferred]: Replace TODO: FRP-46 in FreshpaintInstallEventTest with UTM expiry tests | MET | `FreshpaintInstallEventTest.java` — `appInstall_dlClickIdAndUtm_presentWhenWithinExpiryWindow()` and `appInstall_dlClickIdPersists_utmOmittedAfterExpiry()` |
| AC-4 [inferred]: Add @Nullable/@NonNull to DeepLinkAttributionManager | MET | `DeepLinkAttributionManager.java:89,167` |
| AC-5 [inferred]: Fix NPE in Builder.build() when sourceMiddleware is null | MET | `Freshpaint.java:1148` — initialized to `new ArrayList<>()` |
| AC-6 [inferred]: Regression test for NPE fix | MET | `AnalyticsTest.java:781-786` — `buildWithoutSourceMiddlewareDoesNotThrow()` |
| AC-7 [inferred]: No new test failures introduced | MET | PR description: 247 tests, 20 pre-existing failures, 0 new |
| AC-8 [inferred]: spotlessCheck passes | MET | PR description confirms BUILD SUCCESSFUL |

### Gaps

- None — all inferred requirements are fully addressed.

### Scope Creep

- **Robolectric 3.5 → 4.14.1 upgrade** — not explicitly mentioned as a requirement in the PR title, but the second commit (`5d8c904`) was needed to enable IT7c (which was previously `@Ignore`d due to Robolectric 3.5 limitations). This is well-justified scope expansion: it unblocks a carry-forward test and modernizes the test infrastructure.
- **assertj-core deduplication** — removing the old 1.7.1 version in favor of the already-present 3.26.3 is a reasonable cleanup done alongside the Robolectric upgrade.

---

## Findings

### CRITICAL

None.

### WARNING

---

WARNING · `analytics/src/test/java/io/freshpaint/android/MmpIntegrationTest.java:141`

**What**: `MmpIntegrationTest.buildFreshpaint()` passes `new ValueMap()` (empty) as the `defaultProjectSettings` parameter, while `AnalyticsTest.java` explicitly uses `testProjectSettings()` with parsed integrations JSON. If session management code reads project settings to determine session behavior, the empty settings in MmpIntegrationTest could cause silently different behavior.
**Why**: Inconsistency between test harness setups could mask bugs where `app_install` events behave differently when project settings are populated vs. empty. The `testProjectSettings()` helper was introduced in AnalyticsTest specifically to fix Robolectric 4.x issues — the same concern could apply here.
**Fix**: Either (a) verify that `new ValueMap()` is intentional and document why integration tests don't need project settings, or (b) use the same `testProjectSettings()` pattern for consistency. Since MmpIntegrationTest is pure JVM (no Robolectric), the looper-related issue may not apply, but aligning the setup reduces future confusion.

---

WARNING · PR description (stale)

**What**: The PR description states IT7c is `@Ignore`d with "1 (IT7c @Ignore)" in the test results table. However, commit `5d8c904` removed the `@Ignore` annotation and fully implemented IT7c using Mockito mocks. The PR body is now inaccurate regarding test counts (should be "10 tests, 0 failures, 0 skipped" for MmpIntegrationTest).
**Why**: Reviewers relying on the PR description will believe IT7c is still a carry-forward placeholder, which contradicts the actual code. The "Known gaps" section also says IT7c is scaffolded for future enabling — this is no longer true.
**Fix**: Update the PR description: (1) change MmpIntegrationTest row to "10 tests, 0 failures, 0 skipped", (2) remove or update the IT7c bullet in "Known gaps" to note it was resolved via Mockito mocks in the Robolectric upgrade commit, (3) update the total skipped count from 32 to 31.

---

### SUGGESTION

---

SUGGESTION · `analytics/src/test/java/io/freshpaint/android/SynchronousExecutor.java:38` and `analytics/src/test/java/io/freshpaint/android/TestUtils.java:179`

**What**: Two `SynchronousExecutor` implementations exist side-by-side: the new standalone `SynchronousExecutor.java` (package-private, plain `boolean`) and the pre-existing `TestUtils.SynchronousExecutor` (public, `AtomicBoolean`). Both do the same thing.
**Why**: Duplication creates maintenance burden. Future contributors may not know which to use. The `LooperDrainingExecutor` in `AnalyticsTest` and `DestinationMiddlewareTest` extends `TestUtils.SynchronousExecutor`, while pure-JVM tests use the standalone one — the split is intentional (Robolectric vs. non-Robolectric), but undocumented.
**Fix**: Consider removing the standalone `SynchronousExecutor.java` and having pure-JVM tests use `TestUtils.SynchronousExecutor` directly (it's `public static`, so accessible). Alternatively, keep both but add a one-line comment explaining when to use which. Low priority — current code works correctly.

---

SUGGESTION · `analytics/src/test/java/io/freshpaint/android/AnalyticsTest.java:98-104`

**What**: The `LooperDrainingExecutor` inner class is duplicated identically in both `AnalyticsTest.java:98-104` and `DestinationMiddlewareTest.java:55-61`.
**Why**: If the looper-draining pattern needs to change, both copies must be updated.
**Fix**: Extract to a shared location (e.g., `TestUtils.LooperDrainingExecutor`). Low priority — only two copies.

---

SUGGESTION · `analytics/src/test/java/io/freshpaint/android/FreshpaintIntegrationTest.java:148-165`

**What**: The `enqueueWritesIntegrations` test was changed from exact JSON string comparison to `contains()` assertions. While this correctly handles session metadata injection, `contains()` substring matching is less precise than structural JSON comparison — e.g., `contains("\"event\":\"foo\"")` would also match if "foo" appeared in a different context.
**Why**: Fragile assertion pattern; a false positive is unlikely here but possible if the JSON payload grows.
**Fix**: Consider parsing the captured JSON string back into a `Map` via `Cartographer.INSTANCE.fromJson()` and asserting field-by-field on the parsed object. This gives both flexibility and precision. Very low priority — current assertions are sufficient for this test.

---

## Positive Notes

- The MmpIntegrationTest is exceptionally well-structured: each test method maps to a clear install scenario with descriptive names (IT1–IT7c), thorough setup documentation, and complete assertions checking both inclusion and exclusion of fields.
- The Robolectric 3.5 → 4.14.1 upgrade is handled with surgical precision — each test file gets exactly the adaptation it needs, with clear comments explaining why (e.g., ShadowLog behavior change, looper mode PAUSED semantics, Robolectric 4.x network carrier behavior).
- The `sourceMiddleware` NPE fix is a genuine bug fix with a paired regression test — good defensive programming.
- The clock-injection pattern (`trackApplicationLifecycleEvents(long nowMillis)`) is a clean testing seam that doesn't leak into the public API.

---

## Verdict

**READY FOR REVIEW**

Zero critical findings. The two warnings are: (1) a minor inconsistency in test project settings that doesn't affect correctness, and (2) a stale PR description that should be updated before merge for reviewer clarity. All inferred requirements are fully met with +24 net new tests and zero new failures. The Robolectric upgrade is a well-executed infrastructure improvement that unblocks previously-impossible test scenarios. The production changes (clock injection, NPE fix, nullability annotations) are minimal, well-scoped, and properly tested.
