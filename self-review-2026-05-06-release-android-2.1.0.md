# Self-Review: release/android-2.1.0

**Date**: 2026-05-06
**Ticket**: FRP-53 — [Android] MMP Integration (parent: FRP-80) / FRP-86 — Release SDK versions with Android identifier logic update
**PR**: #45 — feat(mmp): MMP attribution support — release 2.1.0
**Base**: main
**Files changed**: 51
**Lines**: +6467 / -403

---

## Summary

This release branch merges the complete Android MMP attribution feature set into main and bumps the SDK to 2.1.0. The scope covers: `GetAdvertisingIdWorker` (AsyncTask replacement), `AttributionMiddleware` (universal device enrichment), `InstallReferrerManager` (Google Play referrer), `DeepLinkAttributionManager` (click IDs + UTM), enhanced `Application Installed` lifecycle event, GAID/android_id mutual-exclusion logic, and an extensive test suite (~2600 new test lines across six new test files).

The implementation is technically solid. Thread-safety for the device monitor, atomic SharedPreferences writes, and latch-based executor coordination are all handled with care and well-documented. The test suite is comprehensive and representative — integration tests cover the full attribution pipeline end-to-end, and unit tests cover edge cases like placeholder filtering, UTM expiry, deduplication, and race-condition scenarios. All 32 test tasks pass.

Two issues are worth flagging before merge: (1) an unused `androidx.security:security-crypto` dependency that was added when `StableDeviceId` existed but never removed after it was deleted — it adds unnecessary APK weight for all host apps; (2) the CHANGELOG entry references "Keystore-backed UUID" and "stable device ID" which no longer exist, potentially confusing host app developers reading release notes.

---

## Requirement Coverage

Primary tickets FRP-53 and FRP-86 reference a hierarchy of sub-tickets. The mapping below covers the Android-specific sub-tickets from FRP-86.

| Criterion | Status | Evidence |
|-----------|--------|----------|
| FRP-41: Configuration, AD_ID permission, trackFirstOpen flag | MET | `AndroidManifest.xml:30`; `Freshpaint.java:1164,1379`; `Freshpaint.Builder.trackFirstOpen()` |
| FRP-42: Modernize GAID retrieval — replace AsyncTask with ExecutorService + AttributionMiddleware | MET | `GetAdvertisingIdWorker.java`; `AnalyticsContext.java:159`; `AttributionMiddleware.java` |
| FRP-43: Enhanced Application Installed with full MMP attribution payload | MET | `Freshpaint.java:388-445`; `FreshpaintInstallEventTest.java` |
| FRP-44: Google Play Install Referrer integration | MET | `InstallReferrerManager.java`; `InstallReferrerManagerTest.java` |
| FRP-45: Deep link attribution — ad click IDs, UTM params | MET | `DeepLinkAttributionManager.java`; `AnalyticsActivityLifecycleCallbacks.java:154-186`; `DeepLinkAttributionManagerTest.java` |
| FRP-54: ANDROID_ID fallback in device context enrichment | MET | `GetAdvertisingIdWorker.java:128-133`; `AnalyticsContext.Device.putAndroidId()`; `AttributionMiddlewareTest.java` |
| FRP-57: try-catch around trackAttributionInformation in executor | MET | `AnalyticsActivityLifecycleCallbacks.java:123-130` |
| FRP-58: Defensive synchronization for Device.putAndroidId() | MET | `AnalyticsContext.java:459,483`; synchronized on device monitor |
| FRP-67/FRP-80: Flatten payload and revert event name to "Application Installed" | MET | `Freshpaint.java:388`; `AnalyticsTest.java:829-843` |
| FRP-71: Move click IDs into context in event JSON | MET | `AnalyticsActivityLifecycleCallbacks.java:174-185`; deep link properties moved to `Options.putContext()` |
| FRP-46: Integration tests and QA validation | MET | `MmpIntegrationTest.java` (679 lines); `AnalyticsLifecycleCallbacksAttributionTest.java` |
| Identifier logic: GAID-first, android_id fallback, no-id when opted out | MET | `GetAdvertisingIdWorker.java:115-149`; `AttributionMiddlewareTest.java` |
| Version bump 2.0.4 → 2.1.0 | MET | `gradle.properties:4-5` |

### Gaps

None for explicit requirements.

### Scope Creep

- None identified. All changes map to a listed sub-ticket or are direct enablers.

---

## Findings

### CRITICAL

None.

---

### WARNING

---

WARNING · `analytics/build.gradle:55`

**What**: `androidx.security:security-crypto:1.0.0` is declared as `implementation` but is unreferenced in any production source file. `StableDeviceId` (which used `EncryptedSharedPreferences`) was deleted in commit `6de4051`. The dependency was never removed.

**Why**: Every host app that consumes this SDK inherits the transitive `security-crypto` dependency — adding ~10 KB to the merged DEX and introducing `BouncyCastle`/Jetpack-Security classes that serve no purpose. It also signals to host-app dependency auditors that the SDK requires Keystore-backed encryption, which is no longer true.

**Fix**: Remove the line `implementation 'androidx.security:security-crypto:1.0.0'` from `analytics/build.gradle`. Verify the build still passes.

---

WARNING · `CHANGELOG.md:6`

**What**: The 2.1.0 changelog entry reads "stable device ID (Keystore-backed UUID)" and uses "gaid" and "android_id" in its description of `Application Installed` fields. `StableDeviceId` and the Keystore-backed UUID were removed in commit `6de4051`. The current implementation does not use `EncryptedSharedPreferences` or the Android Keystore at all.

**Why**: Host app developers reading release notes before upgrading will expect a Keystore-backed stable ID to be present. When they find it absent, they will raise a bug or lose trust in the changelog accuracy. SDK consumers often have security-review requirements that depend on accurate release notes.

**Fix**: Replace the first bullet in the 2.1.0 changelog with an accurate description:
```
- MMP attribution support: GAID collection via ExecutorService (replacing deprecated AsyncTask),
  Google Play Install Referrer integration, and deep link attribution for 24 ad platforms and UTM params.
```
Remove "Keystore-backed UUID" from the entry entirely.

---

WARNING · `analytics/src/main/java/io/freshpaint/android/AnalyticsActivityLifecycleCallbacks.java:158-163` and `DeepLinkAttributionManager.java:64`

**What**: The `trackDeepLink` method now routes only known click IDs (from `AttributionConstants.CLICK_IDS`) and five canonical UTM params (`utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content`) to `context`. All other query parameters — including `utm_id` (used in GA4 campaigns), custom UTM extensions, and any unrecognized attribution parameter — are silently dropped with no warning. The old behavior forwarded all non-empty query parameters to `properties`.

**Why**: This is a breaking behavior change for existing host apps that read `Deep Link Opened` properties. The `AnalyticsTest` test URL `app://track.com/open?utm_id=12345&gclid=abcd&nope=` demonstrates the drop: `utm_id=12345` is present in the URL but the new test asserts `properties().isEmpty()` and makes no assertion about `utm_id` in context — confirming the value is discarded. Host apps using GA4-style `utm_id` or custom UTM extensions for their own analytics pipeline will silently lose that data on upgrade. The CHANGELOG says "moved" but does not warn of the drop.

**Fix**: Either (a) forward unrecognized query parameters to `properties` as before (preserving backwards compatibility while still routing known attribution params to `context`), or (b) add an explicit `### Breaking Changes` section to the 2.1.0 CHANGELOG entry documenting that unknown query parameters are no longer forwarded to properties, and add a note to the PR description. Option (a) is safer for host apps.

---

### SUGGESTION

---

SUGGESTION · `analytics/src/test/java/io/freshpaint/android/internal/UtilsTest.java`

**What**: `Utils.isPlaceholderAndroidId()` is a new public utility method with five distinct branches (null, empty, `9774d56d682e549c`, `0000000000000000`, `unknown`) but has no direct test in `UtilsTest`. The old code filtered `000000000000000` (15 zeros, a bug), and the new code correctly filters `0000000000000000` (16 zeros, a proper 64-bit hex zero). This fix is covered implicitly by `AttributionMiddlewareTest.putAndroidId_placeholder0000_filtered()` but not by `UtilsTest`.

**Why**: `isPlaceholderAndroidId` is the declared single source of truth for placeholder detection used in both `getDeviceId()` and `Device.putAndroidId()`. A unit test in `UtilsTest` would make the 15→16 zero correction explicit and protect against accidental reversion.

**Fix**: Add a parameterized or explicit test in `UtilsTest`:
```java
@Test public void isPlaceholderAndroidId_recognizesAllPlaceholders() {
    assertThat(Utils.isPlaceholderAndroidId(null)).isTrue();
    assertThat(Utils.isPlaceholderAndroidId("")).isTrue();
    assertThat(Utils.isPlaceholderAndroidId("9774d56d682e549c")).isTrue();
    assertThat(Utils.isPlaceholderAndroidId("9774D56D682E549C")).isTrue(); // case-insensitive
    assertThat(Utils.isPlaceholderAndroidId("0000000000000000")).isTrue(); // 16 zeros
    assertThat(Utils.isPlaceholderAndroidId("000000000000000")).isFalse();  // 15 zeros — must NOT match
    assertThat(Utils.isPlaceholderAndroidId("unknown")).isTrue();
    assertThat(Utils.isPlaceholderAndroidId("abc123def456abcd")).isFalse();
}
```

---

## Positive Notes

- The `synchronized(sourceDevice)` invariant in `AttributionMiddleware` is well-founded: it relies on `ValueMap.coerceToValueMap` returning the same `Device` instance, and this is explicitly documented at the `AnalyticsContext.device()` declaration. This is a non-obvious correctness argument that was made explicit rather than left as a comment assumption.
- `InstallReferrerManager` isolates all `InstallReferrerClient` references inside `doCollect()` so the outer class loads cleanly when the library is absent. The documentation and the `NoClassDefFoundError` catch justification are accurate and actionable.
- `DeepLinkAttributionManager.store()` uses `commit()` (synchronous) instead of `apply()` with a precise rationale: the `analyticsExecutor` may read the preferences before `apply()`'s async flush completes. This is the correct choice and is documented at the call site.
- The `trackApplicationLifecycleEvents(long nowMillis)` package-private overload for clock injection is a clean testability pattern that avoids mocking `System.currentTimeMillis()` globally.
- `FreshpaintInstallEventTest` and `MmpIntegrationTest` together provide strong end-to-end coverage of the attribution pipeline, including edge cases like race conditions between the GAID worker and install event dispatch, UTM expiry, and deep link → install merge ordering.

---

## Verdict

**NEEDS FIXES**

Two warnings block merge in their current form: the `security-crypto` dead dependency inflates every host app's build and should be removed before publishing to Sonatype, and the CHANGELOG inaccuracy about "Keystore-backed UUID" will create support issues for SDK consumers who read release notes. The `Deep Link Opened` breaking change (WARNING 3) is technically contained in this release but its silent data-drop behavior needs to be either reverted or explicitly called out as a breaking change before the changelog is published. All tests pass and correctness is sound — these are documentation/packaging issues, not functional defects.
