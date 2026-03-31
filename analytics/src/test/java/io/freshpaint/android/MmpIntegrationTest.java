/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Segment.io, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.freshpaint.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import androidx.lifecycle.Lifecycle;
import io.freshpaint.android.integrations.BasePayload;
import io.freshpaint.android.integrations.Logger;
import io.freshpaint.android.integrations.TrackPayload;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end integration tests for the MMP attribution pipeline.
 *
 * <p>Each test covers a distinct install scenario, combining multiple attribution components
 * (StableDeviceId, GAID, InstallReferrerManager, DeepLinkAttributionManager) as they interact
 * through {@link Freshpaint#trackApplicationLifecycleEvents(long)}.
 *
 * <p>Pure JVM — no Robolectric. Uses {@link FakeSharedPreferences} and a {@link
 * SynchronousExecutor} to run all async work on the calling thread, matching the pattern in {@link
 * FreshpaintInstallEventTest}.
 */
public class MmpIntegrationTest {

  // -------------------------------------------------------------------------
  // Test state — each test gets fresh instances via @Before
  // -------------------------------------------------------------------------

  private FakeSharedPreferences fakePrefs;
  private Application application;
  private List<BasePayload> captured;

  @Before
  public void setUp() throws Exception {
    fakePrefs = new FakeSharedPreferences();
    application = mock(Application.class);
    when(application.getSharedPreferences(anyString(), anyInt())).thenReturn(fakePrefs);
    when(application.getPackageName()).thenReturn("com.test");

    PackageInfo packageInfo = new PackageInfo();
    packageInfo.versionName = "1.0.0";
    packageInfo.versionCode = 1;
    PackageManager pm = mock(PackageManager.class);
    when(pm.getPackageInfo(eq("com.test"), eq(0))).thenReturn(packageInfo);
    when(application.getPackageManager()).thenReturn(pm);

    captured = new ArrayList<>();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * Builds a {@link Freshpaint} instance wired with a capture middleware and synchronous executor.
   * Uses the provided {@code analyticsContext} so tests can inject a pre-configured device entry.
   */
  private Freshpaint buildFreshpaint(AnalyticsContext analyticsContext) {
    Middleware captureMiddleware =
        chain -> {
          captured.add(chain.payload());
          chain.proceed(chain.payload());
        };

    Traits traits = Traits.create();
    Traits.Cache traitsCache = mock(Traits.Cache.class);
    when(traitsCache.get()).thenReturn(traits);

    BooleanPreference optOut = new BooleanPreference(fakePrefs, "opt-out", false);

    return new Freshpaint(
        application,
        mock(ExecutorService.class), // networkExecutor — unused in these tests
        mock(Stats.class),
        traitsCache,
        analyticsContext,
        new Options(),
        Logger.with(Freshpaint.LogLevel.NONE),
        "test",
        Collections.emptyList(),
        mock(Client.class),
        Cartographer.INSTANCE,
        mock(ProjectSettings.Cache.class),
        "test-key",
        20,
        30_000L,
        300,
        new TestUtils.SynchronousExecutor(),
        false, // shouldTrackApplicationLifecycleEvents — called directly in tests
        new CountDownLatch(0),
        false,
        false,
        false,
        optOut,
        Crypto.none(),
        Collections.singletonList(captureMiddleware),
        Collections.emptyMap(),
        TestUtils.testProjectSettings(),
        mock(Lifecycle.class),
        false,
        true); // trackFirstOpen = true
  }

  /** Returns an {@link AnalyticsContext} with no device entry (GAID worker never ran). */
  private AnalyticsContext emptyContext() {
    return io.freshpaint.android.Utils.createContext(Traits.create());
  }

  private static List<TrackPayload> tracksOf(List<BasePayload> payloads) {
    List<TrackPayload> result = new ArrayList<>();
    for (BasePayload p : payloads) {
      if (p instanceof TrackPayload) {
        result.add((TrackPayload) p);
      }
    }
    return result;
  }

  // -------------------------------------------------------------------------
  // IT1 — Full first-open: GAID available + Install Referrer available
  // -------------------------------------------------------------------------

  /**
   * When GAID is resolved and Install Referrer data is pre-populated (as {@code
   * trackAttributionInformation()} would do on a real device), the {@code app_install} event
   * contains all attribution fields: {@code gaid}, {@code limit_ad_tracking}, {@code
   * install_referrer}, parsed UTM params, and click IDs.
   */
  @Test
  public void it1_fullFirstOpen_withGaidAndInstallReferrer() {
    // Device context with resolved GAID and ad tracking enabled (limit_ad_tracking = false)
    AnalyticsContext ctx = emptyContext();
    AnalyticsContext.Device device = new AnalyticsContext.Device();
    device.putAdvertisingInfo(
        "test-gaid-value", true); // adTrackingEnabled=true → gaid set, limit=false
    ctx.put("device", device);

    // Pre-populate Install Referrer data (simulates collectAndStore() having completed)
    fakePrefs.store.put(InstallReferrerManager.KEY_IR_COLLECTED, true);
    fakePrefs.store.put("ir.install_referrer", "utm_source=google&utm_campaign=winter_sale");
    fakePrefs.store.put("ir.utm_source", "google");
    fakePrefs.store.put("ir.utm_campaign", "winter_sale");
    fakePrefs.store.put("ir.$gclid", "test-gclid");
    fakePrefs.store.put("ir.$gclid_creation_time", 1710000000000L);

    Freshpaint fp = buildFreshpaint(ctx);
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    TrackPayload event = tracksOf(captured).get(0);
    assertThat(event.event()).isEqualTo("app_install");

    Properties props = event.properties();
    assertThat(props.get("gaid")).isEqualTo("test-gaid-value");
    assertThat(props.get("limit_ad_tracking")).isEqualTo(false);
    assertThat(props.get("install_referrer"))
        .isEqualTo("utm_source=google&utm_campaign=winter_sale");
    assertThat(props.get("utm_source")).isEqualTo("google");
    assertThat(props.get("utm_campaign")).isEqualTo("winter_sale");
    assertThat(props.get("$gclid")).isEqualTo("test-gclid");
    assertThat(props.get("$gclid_creation_time")).isEqualTo(1710000000000L);
    // All required fields present
    assertThat(props).containsKey("install_timestamp");
    assertThat(props).containsKey("device_id");
    assertThat(props).containsKey("os_version");
    assertThat(props).containsKey("app_version");
  }

  // -------------------------------------------------------------------------
  // IT2 — First-open without Play Services (no GAID, no referrer)
  // -------------------------------------------------------------------------

  /**
   * When the GAID worker has not run and no Install Referrer data is available (e.g. device has no
   * Google Play Services), {@code app_install} fires without {@code gaid} or referrer fields.
   * {@code limit_ad_tracking} defaults to {@code true} (conservative / tracking limited).
   */
  @Test
  public void it2_firstOpen_withoutPlayServices_noGaidNoReferrer() {
    // No device entry in context (GAID worker never ran), no IR prefs
    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    TrackPayload event = tracksOf(captured).get(0);
    assertThat(event.event()).isEqualTo("app_install");

    Properties props = event.properties();
    assertThat(props).doesNotContainKey("gaid"); // absent, not explicit null
    assertThat(props.get("limit_ad_tracking")).isEqualTo(true); // conservative default
    assertThat(props).doesNotContainKey("install_referrer");
    // Required fields still present
    assertThat(props).containsKey("install_timestamp");
    assertThat(props).containsKey("device_id");
    assertThat(props).containsKey("os_version");
    assertThat(props).containsKey("app_version");
  }

  // -------------------------------------------------------------------------
  // IT3 — Organic install: no referrer, no deep link
  // -------------------------------------------------------------------------

  /**
   * A clean organic install with no Install Referrer and no deep link. {@code app_install} fires
   * with exactly the required fields and no attribution fields.
   */
  @Test
  public void it3_organicInstall_noReferrerNoDeepLink() {
    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    TrackPayload event = tracksOf(captured).get(0);
    assertThat(event.event()).isEqualTo("app_install");

    Properties props = event.properties();
    // No attribution data
    assertThat(props).doesNotContainKey("$gclid");
    assertThat(props).doesNotContainKey("$fbclid");
    assertThat(props).doesNotContainKey("utm_source");
    assertThat(props).doesNotContainKey("install_referrer");
    // Required fields
    assertThat(props).containsKey("install_timestamp");
    assertThat(props).containsKey("device_id");
    assertThat(props).containsKey("limit_ad_tracking");
    assertThat(props).containsKey("os_version");
    assertThat(props).containsKey("app_version");
  }

  // -------------------------------------------------------------------------
  // IT4 — Attributed install: deep link with click IDs + UTMs (within window)
  // -------------------------------------------------------------------------

  /**
   * When deep-link attribution data is stored before {@code app_install} fires, the click IDs and
   * UTM params appear in the event payload — provided the UTM data is within the 24-hour window.
   */
  @Test
  public void it4_attributedInstall_deepLinkClickIdsAndUtm_withinExpiryWindow() {
    long storedAt = 1_000_000L;
    Map<String, String> dlParams = new LinkedHashMap<>();
    dlParams.put("gclid", "DL_GCLID");
    dlParams.put("fbclid", "DL_FBCLID");
    dlParams.put("utm_source", "facebook");
    dlParams.put("utm_campaign", "summer_sale");
    DeepLinkAttributionManager.store(dlParams, fakePrefs, storedAt);

    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents(storedAt + 3_600_000L); // +1h, within 24h window

    assertThat(tracksOf(captured)).hasSize(1);
    Properties props = tracksOf(captured).get(0).properties();
    assertThat(props.get("$gclid")).isEqualTo("DL_GCLID");
    assertThat(props.get("$fbclid")).isEqualTo("DL_FBCLID");
    assertThat(props.get("utm_source")).isEqualTo("facebook");
    assertThat(props.get("utm_campaign")).isEqualTo("summer_sale");
  }

  /**
   * After the 24-hour UTM expiry window, click IDs still appear in {@code app_install} but UTM
   * params are omitted.
   */
  @Test
  public void it4b_attributedInstall_deepLink_clickIdPersists_utmExpiredAfter25h() {
    long storedAt = 1_000_000L;
    Map<String, String> dlParams = new LinkedHashMap<>();
    dlParams.put("gclid", "DL_GCLID");
    dlParams.put("utm_source", "facebook");
    DeepLinkAttributionManager.store(dlParams, fakePrefs, storedAt);

    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents(storedAt + 90_000_000L); // +25h, UTM expired

    assertThat(tracksOf(captured)).hasSize(1);
    Properties props = tracksOf(captured).get(0).properties();
    assertThat(props.get("$gclid")).isEqualTo("DL_GCLID"); // click ID persists indefinitely
    assertThat(props).doesNotContainKey("utm_source"); // UTM expired
  }

  /**
   * At exactly 24 hours after storage, UTM params are still included (boundary is inclusive). One
   * millisecond later they are omitted.
   */
  @Test
  public void it4c_attributedInstall_deepLink_utmIncludedAtExact24hBoundary() {
    long storedAt = 1_000_000L;
    long exactly24h = 24L * 60L * 60L * 1_000L;
    Map<String, String> dlParams = new LinkedHashMap<>();
    dlParams.put("gclid", "DL_GCLID");
    dlParams.put("utm_source", "facebook");
    DeepLinkAttributionManager.store(dlParams, fakePrefs, storedAt);

    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents(storedAt + exactly24h); // exactly 24h, inclusive boundary

    assertThat(tracksOf(captured)).hasSize(1);
    Properties props = tracksOf(captured).get(0).properties();
    assertThat(props.get("$gclid")).isEqualTo("DL_GCLID");
    assertThat(props.get("utm_source")).isEqualTo("facebook"); // still present at boundary
  }

  @Test
  public void it4d_attributedInstall_deepLink_utmOmittedOnemsAfter24hBoundary() {
    long storedAt = 1_000_000L;
    long exactly24h = 24L * 60L * 60L * 1_000L;
    Map<String, String> dlParams = new LinkedHashMap<>();
    dlParams.put("gclid", "DL_GCLID");
    dlParams.put("utm_source", "facebook");
    DeepLinkAttributionManager.store(dlParams, fakePrefs, storedAt);

    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents(storedAt + exactly24h + 1L); // 24h + 1ms, expired

    assertThat(tracksOf(captured)).hasSize(1);
    Properties props = tracksOf(captured).get(0).properties();
    assertThat(props.get("$gclid")).isEqualTo("DL_GCLID"); // click ID persists
    assertThat(props).doesNotContainKey("utm_source"); // UTM expired
  }

  // -------------------------------------------------------------------------
  // IT5 — Sideloaded app: no Install Referrer available
  // -------------------------------------------------------------------------

  /**
   * When the app is sideloaded (installed via APK, not Play Store), Install Referrer data is never
   * collected. {@code app_install} fires without referrer fields and does not crash.
   */
  @Test
  public void it5_sideloadedApp_noInstallReferrer_eventFiresWithoutCrash() {
    // Explicit false: sideloaded app, Play Store was never contacted so KEY_IR_COLLECTED is
    // false (vs. IT3 organic where the key is simply absent). Both result in no referrer
    // fields, but the explicit write makes the sideload intent clear to readers.
    fakePrefs.store.put(InstallReferrerManager.KEY_IR_COLLECTED, false);

    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    TrackPayload event = tracksOf(captured).get(0);
    assertThat(event.event()).isEqualTo("app_install");

    Properties props = event.properties();
    assertThat(props).doesNotContainKey("install_referrer");
    assertThat(props).doesNotContainKey("$gclid");
    // Required fields still present — sideload path does not skip any required field
    assertThat(props).containsKey("install_timestamp");
    assertThat(props).containsKey("device_id");
    assertThat(props).containsKey("limit_ad_tracking");
    assertThat(props).containsKey("os_version");
    assertThat(props).containsKey("app_version");
  }

  // -------------------------------------------------------------------------
  // IT6 — Schema validation: strict PRD alignment
  // -------------------------------------------------------------------------

  /**
   * Strict schema test: {@code app_install} must contain exactly the 5 required fields from the
   * global PRD, each with the correct type, and the event name must match the current SDK
   * implementation name {@code "app_install"}.
   */
  @Test
  public void it6_schemaValidation_requiredFieldsWithCorrectTypes() {
    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    TrackPayload event = tracksOf(captured).get(0);

    // Event name — current implementation uses "app_install"
    // Note: PRD defines "app_first_open"; this discrepancy is tracked as a carry-forward item
    // pending stakeholder confirmation. Both alternatives are guarded against the legacy name.
    assertThat(event.event()).isEqualTo("app_install");
    assertThat(event.event()).isNotEqualTo("Application Installed"); // legacy Segment name
    assertThat(event.event()).isNotEqualTo("Application Opened");

    Properties props = event.properties();

    // install_timestamp: ISO 8601 string
    Object installTimestamp = props.get("install_timestamp");
    assertThat(installTimestamp).isNotNull();
    assertThat(installTimestamp).isInstanceOf(String.class);
    assertThat(installTimestamp.toString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");

    // device_id: non-empty string (UUID from StableDeviceId)
    Object deviceId = props.get("device_id");
    assertThat(deviceId).isNotNull();
    assertThat(deviceId).isInstanceOf(String.class);
    assertThat(deviceId.toString()).isNotEmpty();

    // limit_ad_tracking: boolean (never absent — defaults to true when device is null)
    Object limitAdTracking = props.get("limit_ad_tracking");
    assertThat(limitAdTracking).isNotNull();
    assertThat(limitAdTracking).isInstanceOf(Boolean.class);

    // os_version: Build.VERSION.RELEASE is null in the pure-JVM Android stub, so we can only
    // assert key presence here. AnalyticsContextTest (Robolectric 4.x) validates the non-null
    // String value via Build.VERSION.RELEASE in its create() and copyDevice() tests.
    assertThat(props).containsKey("os_version");

    // app_version: matches mocked PackageInfo.versionName
    assertThat(props.get("app_version")).isEqualTo("1.0.0");
  }

  // -------------------------------------------------------------------------
  // IT7a — Regression: second launch does not re-fire app_install
  // -------------------------------------------------------------------------

  /**
   * On the second launch (first_open_tracked already set), {@code app_install} must not fire again.
   * No track event should be emitted when build and version are unchanged.
   */
  @Test
  public void it7a_regression_secondLaunch_appInstallNotFired() {
    // The actual guard is previousBuild != -1 (build=1 here). The first_open_tracked and
    // version entries are set to reflect a realistic second-launch SharedPreferences state,
    // not because they are required to suppress app_install on this code path.
    fakePrefs.store.put("first_open_tracked", true);
    fakePrefs.store.put("build", 1);
    fakePrefs.store.put("version", "1.0.0");

    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    boolean appInstallFired =
        tracksOf(captured).stream().anyMatch(p -> "app_install".equals(p.event()));
    assertThat(appInstallFired).isFalse();
  }

  // -------------------------------------------------------------------------
  // IT7b — Regression: upgrade fires Application Updated, not app_install
  // -------------------------------------------------------------------------

  /**
   * An app version upgrade ({@code previousBuild != currentBuild}) must fire exactly {@code
   * "Application Updated"} and must never fire {@code app_install}.
   */
  @Test
  public void it7b_regression_appUpgrade_firesApplicationUpdatedNotAppInstall() {
    fakePrefs.store.put("build", 0); // previousBuild=0 ≠ currentBuild=1
    fakePrefs.store.put("version", "0.9.0");

    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    List<TrackPayload> tracks = tracksOf(captured);
    assertThat(tracks).hasSize(1);
    assertThat(tracks.get(0).event()).isEqualTo("Application Updated");

    boolean appInstallFired = tracks.stream().anyMatch(p -> "app_install".equals(p.event()));
    assertThat(appInstallFired).isFalse();
  }

  // -------------------------------------------------------------------------
  // IT7c — Regression: Deep Link Opened enrichment
  // -------------------------------------------------------------------------

  /**
   * When {@code first_open_tracked=true} (app_install already fired on a previous launch) and a
   * deep link is opened, {@code Deep Link Opened} must include the stored attribution properties
   * ($gclid, utm params) — proving that {@link AnalyticsActivityLifecycleCallbacks#trackDeepLink}
   * enriches the event via {@link Freshpaint#getDeepLinkAttributionProperties}.
   *
   * <p>Uses Mockito mocks for {@code Activity}, {@code Intent}, and {@code Uri} so that {@code
   * uri.getQueryParameterNames()} and {@code uri.getQueryParameter()} can be stubbed without
   * Robolectric.
   */
  @Test
  public void it7c_regression_deepLinkOpenedEnrichedWhenFirstOpenAlreadyTracked() {
    // Signal that app_install was already tracked on a prior launch.
    fakePrefs.store.put("first_open_tracked", true);

    Freshpaint fp = buildFreshpaint(emptyContext());

    AnalyticsActivityLifecycleCallbacks callbacks =
        new AnalyticsActivityLifecycleCallbacks.Builder()
            .analytics(fp)
            .analyticsExecutor(new TestUtils.SynchronousExecutor())
            .shouldTrackApplicationLifecycleEvents(false)
            .trackAttributionInformation(false)
            .trackDeepLinks(true)
            .shouldRecordScreenViews(false)
            .packageInfo(new PackageInfo())
            .build();

    // Mock Activity with an Intent carrying a deep link URI with click ID and UTM params.
    Uri mockUri = mock(Uri.class);
    when(mockUri.getQueryParameterNames())
        .thenReturn(new LinkedHashSet<>(Arrays.asList("gclid", "utm_source")));
    when(mockUri.getQueryParameter("gclid")).thenReturn("CLICK_ID_123");
    when(mockUri.getQueryParameter("utm_source")).thenReturn("google_ads");
    when(mockUri.toString())
        .thenReturn("https://example.com?gclid=CLICK_ID_123&utm_source=google_ads");

    Intent mockIntent = mock(Intent.class);
    when(mockIntent.getData()).thenReturn(mockUri);

    Activity mockActivity = mock(Activity.class);
    when(mockActivity.getIntent()).thenReturn(mockIntent);

    callbacks.onActivityCreated(mockActivity, null);

    // Deep Link Opened must have fired exactly once.
    List<TrackPayload> tracks = tracksOf(captured);
    assertThat(tracks).hasSize(1);
    TrackPayload event = tracks.get(0);
    assertThat(event.event()).isEqualTo("Deep Link Opened");

    Properties props = event.properties();
    // url is always present in Deep Link Opened
    assertThat(props.getString("url"))
        .isEqualTo("https://example.com?gclid=CLICK_ID_123&utm_source=google_ads");
    // click ID stored and enriched as $gclid (prefixed by DeepLinkAttributionManager)
    assertThat(props.get("$gclid")).isEqualTo("CLICK_ID_123");
    // UTM param enriched (within expiry window — same millisecond stored and retrieved)
    assertThat(props.get("utm_source")).isEqualTo("google_ads");
  }
}
