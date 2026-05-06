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
import android.os.Build;
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
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * End-to-end integration tests for the MMP attribution pipeline.
 *
 * <p>Each test covers a distinct install scenario, combining multiple attribution components (GAID,
 * InstallReferrerManager, DeepLinkAttributionManager) as they interact through {@link
 * Freshpaint#trackApplicationLifecycleEvents(long)}.
 *
 * <p>Uses {@link FakeSharedPreferences} and {@link TestUtils.SynchronousExecutor} to run all async
 * work on the calling thread. Runs under Robolectric so that Android framework classes referenced
 * transitively (e.g. {@code PackageInfo}, {@code Build.VERSION}) are safely stubbed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
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
   * An {@link AbstractExecutorService} that throws {@link AssertionError} on any submission,
   * ensuring no unexpected network operations occur during these tests.
   */
  private static class FailFastNetworkExecutor extends AbstractExecutorService {
    @Override
    public void execute(Runnable command) {
      throw new AssertionError("Unexpected task submitted to networkExecutor");
    }

    @Override
    public void shutdown() {}

    @Override
    public java.util.List<Runnable> shutdownNow() {
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return false;
    }

    @Override
    public boolean isTerminated() {
      return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

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
        new FailFastNetworkExecutor(),
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
  // Full first-open: GAID available + Install Referrer available
  // -------------------------------------------------------------------------

  /**
   * When GAID is resolved and Install Referrer data is pre-populated (as {@code
   * trackAttributionInformation()} would do on a real device), the {@code Application Installed}
   * event contains all attribution fields: {@code advertisingId}, {@code limit_ad_tracking}, {@code
   * install_referrer}, parsed UTM params, and click IDs.
   */
  @Test
  public void it1_fullFirstOpen_withGaidAndInstallReferrer() {
    // Device context with resolved GAID and ad tracking enabled (limit_ad_tracking = false)
    AnalyticsContext ctx = emptyContext();
    AnalyticsContext.Device device = new AnalyticsContext.Device();
    device.putAdvertisingInfo(
        "test-gaid-value", true); // adTrackingEnabled=true → advertisingId set, limit=false
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
    assertThat(event.event()).isEqualTo("Application Installed");

    Properties props = event.properties();
    assertThat(props.get("advertisingId")).isEqualTo("test-gaid-value");
    assertThat(props.get("limit_ad_tracking")).isEqualTo(false);
    // All required fields present
    assertThat(props).containsKey("install_timestamp");
    assertThat(props).containsKey("os_version");
    assertThat(props).containsKey("version");
    assertThat(props).containsKey("build");
    // All attribution fields in context
    assertThat(props).doesNotContainKey("$gclid");
    assertThat(props).doesNotContainKey("$gclid_creation_time");
    assertThat(event.context().get("$gclid")).isEqualTo("test-gclid");
    assertThat(event.context().get("$gclid_creation_time")).isEqualTo(1710000000000L);
    assertThat(event.context().get("install_referrer"))
        .isEqualTo("utm_source=google&utm_campaign=winter_sale");
    assertThat(event.context().get("utm_source")).isEqualTo("google");
    assertThat(event.context().get("utm_campaign")).isEqualTo("winter_sale");
  }

  // -------------------------------------------------------------------------
  // First-open without Play Services (no GAID, no referrer)
  // -------------------------------------------------------------------------

  /**
   * When the GAID worker has not run and no Install Referrer data is available (e.g. device has no
   * Google Play Services), {@code Application Installed} fires without {@code advertisingId} or
   * referrer fields. {@code limit_ad_tracking} defaults to {@code true} (conservative / tracking
   * limited).
   */
  @Test
  public void it2_firstOpen_withoutPlayServices_noGaidNoReferrer() {
    // No device entry in context (GAID worker never ran), no IR prefs
    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    TrackPayload event = tracksOf(captured).get(0);
    assertThat(event.event()).isEqualTo("Application Installed");

    Properties props = event.properties();
    assertThat(props).doesNotContainKey("advertisingId"); // absent, not explicit null
    assertThat(props.get("limit_ad_tracking")).isEqualTo(true); // conservative default
    assertThat(props).doesNotContainKey("install_referrer");
    // Required fields still present
    assertThat(props).containsKey("install_timestamp");
    assertThat(props).containsKey("os_version");
    assertThat(props).containsKey("version");
    assertThat(props).containsKey("build");
  }

  // -------------------------------------------------------------------------
  // Organic install: no referrer, no deep link
  // -------------------------------------------------------------------------

  /**
   * A clean organic install with no Install Referrer and no deep link. {@code Application
   * Installed} fires with exactly the required fields and no attribution fields.
   */
  @Test
  public void it3_organicInstall_noReferrerNoDeepLink() {
    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    TrackPayload event = tracksOf(captured).get(0);
    assertThat(event.event()).isEqualTo("Application Installed");

    Properties props = event.properties();
    // No attribution data in properties or context
    assertThat(props).doesNotContainKey("$gclid");
    assertThat(props).doesNotContainKey("$fbclid");
    assertThat(props).doesNotContainKey("utm_source");
    assertThat(props).doesNotContainKey("install_referrer");
    assertThat(event.context()).doesNotContainKey("$gclid");
    assertThat(event.context()).doesNotContainKey("utm_source");
    // Required fields
    assertThat(props).containsKey("install_timestamp");
    assertThat(props).containsKey("limit_ad_tracking");
    assertThat(props).containsKey("os_version");
    assertThat(props).containsKey("version");
    assertThat(props).containsKey("build");
  }

  // -------------------------------------------------------------------------
  // Attributed install: deep link with click IDs + UTMs (within window)
  // -------------------------------------------------------------------------

  /**
   * When deep-link attribution data is stored before {@code Application Installed} fires, the click
   * IDs and UTM params appear in the event payload — provided the UTM data is within the 24-hour
   * window.
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
    TrackPayload payload4 = tracksOf(captured).get(0);
    Properties props4 = payload4.properties();
    // All attribution fields in context
    assertThat(props4).doesNotContainKey("$gclid");
    assertThat(props4).doesNotContainKey("$fbclid");
    assertThat(props4).doesNotContainKey("utm_source");
    assertThat(props4).doesNotContainKey("utm_campaign");
    assertThat(payload4.context().get("$gclid")).isEqualTo("DL_GCLID");
    assertThat(payload4.context().get("$fbclid")).isEqualTo("DL_FBCLID");
    assertThat(payload4.context().get("utm_source")).isEqualTo("facebook");
    assertThat(payload4.context().get("utm_campaign")).isEqualTo("summer_sale");
  }

  /**
   * After the 24-hour UTM expiry window, click IDs still appear in {@code Application Installed}
   * but UTM params are omitted.
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
    TrackPayload payload4b = tracksOf(captured).get(0);
    // Click ID in context and persists indefinitely
    assertThat(payload4b.properties()).doesNotContainKey("$gclid");
    assertThat(payload4b.context().get("$gclid")).isEqualTo("DL_GCLID");
    // UTM expired — absent from both properties and context
    assertThat(payload4b.properties()).doesNotContainKey("utm_source");
    assertThat(payload4b.context()).doesNotContainKey("utm_source");
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
    TrackPayload payload4c = tracksOf(captured).get(0);
    // Click ID in context
    assertThat(payload4c.properties()).doesNotContainKey("$gclid");
    assertThat(payload4c.context().get("$gclid")).isEqualTo("DL_GCLID");
    // UTM still present at boundary — in context
    assertThat(payload4c.properties()).doesNotContainKey("utm_source");
    assertThat(payload4c.context().get("utm_source")).isEqualTo("facebook");
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
    TrackPayload payload4d = tracksOf(captured).get(0);
    // Click ID in context
    assertThat(payload4d.properties()).doesNotContainKey("$gclid");
    assertThat(payload4d.context().get("$gclid")).isEqualTo("DL_GCLID");
    // UTM expired — absent from both properties and context
    assertThat(payload4d.properties()).doesNotContainKey("utm_source");
    assertThat(payload4d.context()).doesNotContainKey("utm_source");
  }

  // -------------------------------------------------------------------------
  // Sideloaded app: no Install Referrer available
  // -------------------------------------------------------------------------

  /**
   * When the app is sideloaded (installed via APK, not Play Store), Install Referrer data is never
   * collected. {@code Application Installed} fires without referrer fields and does not crash.
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
    assertThat(event.event()).isEqualTo("Application Installed");

    Properties props = event.properties();
    assertThat(props).doesNotContainKey("install_referrer");
    assertThat(props).doesNotContainKey("$gclid");
    // Required fields still present — sideload path does not skip any required field
    assertThat(props).containsKey("install_timestamp");
    assertThat(props).containsKey("limit_ad_tracking");
    assertThat(props).containsKey("os_version");
    assertThat(props).containsKey("version");
    assertThat(props).containsKey("build");
  }

  // -------------------------------------------------------------------------
  // Schema validation: strict PRD alignment
  // -------------------------------------------------------------------------

  /**
   * Strict schema test: {@code Application Installed} must contain the required fields (install
   * timestamp, limit ad tracking, OS version, app version, and build), each with the correct type,
   * and the event name must be {@code "Application Installed"}.
   */
  @Test
  public void it6_schemaValidation_requiredFieldsWithCorrectTypes() {
    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    TrackPayload event = tracksOf(captured).get(0);

    assertThat(event.event()).isEqualTo("Application Installed");
    assertThat(event.event()).isNotEqualTo("Application Opened");

    Properties props = event.properties();

    // install_timestamp: ISO 8601 string
    Object installTimestamp = props.get("install_timestamp");
    assertThat(installTimestamp).isNotNull();
    assertThat(installTimestamp).isInstanceOf(String.class);
    assertThat(installTimestamp.toString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");

    // limit_ad_tracking: boolean (never absent — defaults to true when device is null)
    Object limitAdTracking = props.get("limit_ad_tracking");
    assertThat(limitAdTracking).isNotNull();
    assertThat(limitAdTracking).isInstanceOf(Boolean.class);

    // os_version: Freshpaint uses Build.VERSION.RELEASE; under Robolectric 4.x it is a non-null
    // String (see AnalyticsContextTest).
    assertThat(props.get("os_version"))
        .isNotNull()
        .isInstanceOf(String.class)
        .isEqualTo(Build.VERSION.RELEASE);

    // version: matches mocked PackageInfo.versionName; build is String.valueOf(versionCode)
    assertThat(props.get("version")).isEqualTo("1.0.0");
    assertThat(props.get("build")).isEqualTo("1");
  }

  // -------------------------------------------------------------------------
  // Regression: second launch does not re-fire Application Installed
  // -------------------------------------------------------------------------

  /**
   * On the second launch (first_open_tracked already set), {@code Application Installed} must not
   * fire again. No track event should be emitted when build and version are unchanged.
   */
  @Test
  public void it7a_regression_secondLaunch_appInstallNotFired() {
    // The actual guard is previousBuild != -1 (build=1 here). The first_open_tracked and
    // version entries are set to reflect a realistic second-launch SharedPreferences state,
    // not because they are required to suppress Application Installed on this code path.
    fakePrefs.store.put("first_open_tracked", true);
    fakePrefs.store.put("build", 1);
    fakePrefs.store.put("version", "1.0.0");

    Freshpaint fp = buildFreshpaint(emptyContext());
    fp.trackApplicationLifecycleEvents();

    boolean appInstallFired =
        tracksOf(captured).stream().anyMatch(p -> "Application Installed".equals(p.event()));
    assertThat(appInstallFired).isFalse();
  }

  // -------------------------------------------------------------------------
  // Regression: upgrade fires Application Updated, not Application Installed
  // -------------------------------------------------------------------------

  /**
   * An app version upgrade ({@code previousBuild != currentBuild}) must fire exactly {@code
   * "Application Updated"} and must never fire {@code Application Installed}.
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

    boolean appInstallFired =
        tracks.stream().anyMatch(p -> "Application Installed".equals(p.event()));
    assertThat(appInstallFired).isFalse();
  }

  // -------------------------------------------------------------------------
  // Regression: Deep Link Opened enrichment
  // -------------------------------------------------------------------------

  /**
   * When a deep link opens on the FIRST launch (before {@code Application Installed} fires, {@code
   * first_open_tracked=false}), {@code Deep Link Opened} must still carry stored attribution in
   * {@code context}. The guard {@code isFirstOpenTracked()} was intentionally removed — the data is
   * available the moment {@code storeDeepLinkAttribution()} returns, and enriching Deep Link Opened
   * unconditionally is more correct than skipping it on the first launch.
   */
  @Test
  public void it7c_deepLinkOpenedEnrichedOnFirstLaunch_beforeAppInstallFires() {
    // first_open_tracked is absent — Application Installed has not yet fired.
    assertThat(fakePrefs.store).doesNotContainKey("first_open_tracked");

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

    Uri mockUri = mock(Uri.class);
    when(mockUri.getQueryParameterNames())
        .thenReturn(new LinkedHashSet<>(Arrays.asList("gclid", "utm_source")));
    when(mockUri.getQueryParameter("gclid")).thenReturn("FIRST_LAUNCH_GCLID");
    when(mockUri.getQueryParameter("utm_source")).thenReturn("google_ads");
    when(mockUri.toString()).thenReturn("https://example.com?gclid=FIRST_LAUNCH_GCLID");

    Intent mockIntent = mock(Intent.class);
    when(mockIntent.getData()).thenReturn(mockUri);

    Activity mockActivity = mock(Activity.class);
    when(mockActivity.getIntent()).thenReturn(mockIntent);

    callbacks.onActivityCreated(mockActivity, null);

    List<TrackPayload> tracks = tracksOf(captured);
    assertThat(tracks).hasSize(1);
    TrackPayload event = tracks.get(0);
    assertThat(event.event()).isEqualTo("Deep Link Opened");
    // Attribution is present in context even on first launch (no isFirstOpenTracked guard).
    assertThat(event.context().get("$gclid")).isEqualTo("FIRST_LAUNCH_GCLID");
    assertThat(event.context().get("utm_source")).isEqualTo("google_ads");
    assertThat(event.context().get(AnalyticsActivityLifecycleCallbacks.DEEP_LINK_URL_CONTEXT_KEY))
        .isEqualTo("https://example.com?gclid=FIRST_LAUNCH_GCLID");
    Properties props = event.properties();
    assertThat(props)
        .doesNotContainKey(AnalyticsActivityLifecycleCallbacks.DEEP_LINK_URL_CONTEXT_KEY);
    assertThat(props).doesNotContainKey("utm_source");
    assertThat(props).doesNotContainKey("gclid");
    assertThat(props).doesNotContainKey("$gclid");
  }

  /**
   * When {@code first_open_tracked=true} ({@code Application Installed} already fired on a previous
   * launch) and a deep link is opened, {@code Deep Link Opened} must carry stored attribution
   * ($gclid, UTM, url) in {@code context} only — proving that {@link
   * AnalyticsActivityLifecycleCallbacks#trackDeepLink} enriches via {@link
   * Freshpaint#getDeepLinkAttributionProperties} and {@link Options#putContext}.
   *
   * <p>Uses Mockito mocks for {@code Activity}, {@code Intent}, and {@code Uri} so that {@code
   * uri.getQueryParameterNames()} and {@code uri.getQueryParameter()} can be stubbed without
   * Robolectric.
   */
  @Test
  public void it7c_regression_deepLinkOpenedEnrichedWhenFirstOpenAlreadyTracked() {
    // Signal that Application Installed was already tracked on a prior launch.
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
    assertThat(props).doesNotContainKey("url");
    assertThat(props).doesNotContainKey("utm_source");
    assertThat(props).doesNotContainKey("gclid");
    assertThat(event.context().get(AnalyticsActivityLifecycleCallbacks.DEEP_LINK_URL_CONTEXT_KEY))
        .isEqualTo("https://example.com?gclid=CLICK_ID_123&utm_source=google_ads");
    assertThat(event.context().get("$gclid")).isEqualTo("CLICK_ID_123");
    assertThat(event.context().get("utm_source")).isEqualTo("google_ads");
  }
}
