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

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import androidx.lifecycle.Lifecycle;
import io.freshpaint.android.integrations.BasePayload;
import io.freshpaint.android.integrations.Logger;
import io.freshpaint.android.integrations.TrackPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the {@code app_install} first-open event fired from {@link
 * Freshpaint#trackApplicationLifecycleEvents()}.
 *
 * <p>These tests use pure JVM + Mockito (no Robolectric) following the pattern established in
 * FRP-41. A capture {@link Middleware} intercepts payloads synchronously before they reach the
 * Android Handler, allowing direct assertions on dispatched events.
 */
public class FreshpaintInstallEventTest {

  // -------------------------------------------------------------------------
  // SynchronousExecutor — runs submitted tasks on the calling thread
  // -------------------------------------------------------------------------

  /**
   * Executes every submitted {@link Runnable} synchronously on the calling thread. This makes
   * {@link Freshpaint#track} and {@link Freshpaint#trackApplicationLifecycleEvents} fully
   * synchronous so assertions can run immediately after the call.
   */
  static class SynchronousExecutor extends AbstractExecutorService {
    private boolean terminated;

    @Override
    public void shutdown() {
      terminated = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
      return terminated;
    }

    @Override
    public boolean isTerminated() {
      return terminated;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return terminated;
    }

    @Override
    public void execute(Runnable r) {
      r.run();
    }
  }

  // -------------------------------------------------------------------------
  // Test state
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

  /**
   * Constructs a {@link Freshpaint} instance wired with a capture middleware and a synchronous
   * executor. All Android dependencies are mocked. The {@code trackFirstOpen} flag is configurable.
   */
  private Freshpaint buildFreshpaint(boolean trackFirstOpen) {
    Middleware captureMiddleware =
        chain -> {
          captured.add(chain.payload());
          chain.proceed(chain.payload());
        };

    Traits traits = Traits.create();
    Traits.Cache traitsCache = mock(Traits.Cache.class);
    when(traitsCache.get()).thenReturn(traits);
    AnalyticsContext analyticsContext = io.freshpaint.android.Utils.createContext(traits);

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
        new SynchronousExecutor(),
        false, // shouldTrackApplicationLifecycleEvents — we call the method directly
        new CountDownLatch(0),
        false,
        false,
        false,
        optOut,
        Crypto.none(),
        Collections.singletonList(captureMiddleware),
        Collections.emptyMap(),
        new ValueMap(),
        mock(Lifecycle.class),
        false,
        trackFirstOpen);
  }

  // -------------------------------------------------------------------------
  // AC1 — First launch fires app_install
  // -------------------------------------------------------------------------

  @Test
  public void firstLaunchFiresAppInstall() {
    // previousBuild defaults to -1 (key absent) → first install
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    assertThat(tracksOf(captured).get(0).event()).isEqualTo("app_install");
  }

  // AC1 — All 6 required fields present in app_install payload
  @Test
  public void firstLaunchAppInstallHasAllRequiredFields() {
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    Properties props = tracksOf(captured).get(0).properties();
    assertThat(props).containsKey("install_timestamp");
    assertThat(props).containsKey("device_id");
    assertThat(props).containsKey("limit_ad_tracking");
    assertThat(props).containsKey("os_version");
    assertThat(props).containsKey("app_version");
    // gaid is omitted when not yet resolved; see gaidAbsentWhenNotResolved
  }

  // AC5 — install_timestamp is a valid ISO 8601 string
  @Test
  public void installTimestampIsIso8601() {
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    Object ts = tracksOf(captured).get(0).properties().get("install_timestamp");
    assertThat(ts).isNotNull();
    assertThat(ts.toString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
  }

  // AC6 — device_id is non-empty
  @Test
  public void deviceIdIsNonEmpty() {
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    Object deviceId = tracksOf(captured).get(0).properties().get("device_id");
    assertThat(deviceId).isNotNull();
    assertThat(deviceId.toString()).isNotEmpty();
  }

  // AC6 — app_version matches the mocked PackageInfo
  @Test
  public void appVersionMatchesPackageInfo() {
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured).get(0).properties().get("app_version")).isEqualTo("1.0.0");
  }

  // -------------------------------------------------------------------------
  // AC2 — Second launch does NOT fire app_install
  // -------------------------------------------------------------------------

  @Test
  public void secondLaunchDoesNotFireAppInstall() {
    // Simulate first_open_tracked already set by a prior launch
    fakePrefs.store.put("first_open_tracked", true);

    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).isEmpty();
  }

  // -------------------------------------------------------------------------
  // AC3 — App upgrade fires Application Updated, not app_install
  // -------------------------------------------------------------------------

  @Test
  public void appUpgradeFiresApplicationUpdated() {
    // previousBuild (0) != currentBuild (1) and previousBuild != -1 → upgrade
    fakePrefs.store.put("build", 0);
    fakePrefs.store.put("version", "0.9.0");

    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    assertThat(tracksOf(captured).get(0).event()).isEqualTo("Application Updated");
  }

  @Test
  public void appUpgradeDoesNotFireAppInstall() {
    fakePrefs.store.put("build", 0);
    fakePrefs.store.put("version", "0.9.0");

    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    boolean appInstallFired =
        tracksOf(captured).stream().anyMatch(p -> "app_install".equals(p.event()));
    assertThat(appInstallFired).isFalse();
  }

  // -------------------------------------------------------------------------
  // AC4 — trackFirstOpen=false suppresses app_install
  // -------------------------------------------------------------------------

  @Test
  public void trackFirstOpenFalseSuppressesAppInstall() {
    Freshpaint fp = buildFreshpaint(false); // trackFirstOpen = false
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).isEmpty();
  }

  // -------------------------------------------------------------------------
  // AC9 — "Application Installed" is never tracked
  // -------------------------------------------------------------------------

  @Test
  public void applicationInstalledStringNeverTracked() {
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    boolean legacyFired =
        tracksOf(captured).stream().anyMatch(p -> "Application Installed".equals(p.event()));
    assertThat(legacyFired).isFalse();
  }

  // -------------------------------------------------------------------------
  // AC10 — first_open_tracked written atomically with VERSION_KEY/BUILD_KEY
  // -------------------------------------------------------------------------

  @Test
  public void firstOpenTrackedKeyWrittenAfterFirstLaunch() {
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    // first_open_tracked must be true after the first launch
    assertThat(fakePrefs.store.get("first_open_tracked")).isEqualTo(true);
  }

  @Test
  public void versionAndBuildKeysWrittenOnFirstLaunch() {
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    // VERSION_KEY and BUILD_KEY written in the same apply() as first_open_tracked
    assertThat(fakePrefs.store.get("version")).isEqualTo("1.0.0");
    assertThat(fakePrefs.store.get("build")).isEqualTo(1);
  }

  @Test
  public void firstOpenTrackedKeyWrittenEvenWhenTrackFirstOpenFalse() {
    Freshpaint fp = buildFreshpaint(false); // suppressed but key still written
    fp.trackApplicationLifecycleEvents();

    assertThat(fakePrefs.store).containsKey("first_open_tracked");
  }

  // -------------------------------------------------------------------------
  // gaid field — absent when not resolved, present when resolved
  // -------------------------------------------------------------------------

  /** gaid must be absent (not explicit null) when the GAID worker hasn't run yet. */
  @Test
  public void gaidAbsentWhenNotResolved() {
    // Device has no advertisingId key (GAID worker hasn't run) → gaid omitted from payload.
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured).get(0).properties()).doesNotContainKey("gaid");
  }

  // -------------------------------------------------------------------------
  // reset() — FIRST_OPEN_TRACKED_KEY must survive user reset
  // -------------------------------------------------------------------------

  /**
   * Calling {@link Freshpaint#reset()} must NOT remove {@code first_open_tracked}. If the key were
   * cleared on reset, a subsequent {@link Freshpaint#trackApplicationLifecycleEvents()} call would
   * re-fire {@code app_install}, double-counting the install in MMP backends.
   */
  @Test
  public void resetPreservesFirstOpenTrackedKey() {
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    assertThat(fakePrefs.store.get("first_open_tracked")).isEqualTo(true);

    fp.reset();

    assertThat(fakePrefs.store).containsKey("first_open_tracked");
    assertThat(fakePrefs.store.get("first_open_tracked")).isEqualTo(true);
  }

  // -------------------------------------------------------------------------
  // Privacy — limitAdTracking defaults to true when device context unavailable
  // -------------------------------------------------------------------------

  /**
   * When {@code analyticsContext.device()} returns null (no device key in the ValueMap), {@code
   * limit_ad_tracking} must default to {@code true} (conservative / tracking limited). Shipping
   * {@code false} would incorrectly signal "tracking permitted" to MMP backends.
   */
  @Test
  public void limitAdTrackingDefaultsTrueWhenDeviceIsNull() throws Exception {
    // Build a Freshpaint instance whose AnalyticsContext has NO device entry so that
    // analyticsContext.device() returns null.
    Middleware captureMiddleware =
        chain -> {
          captured.add(chain.payload());
          chain.proceed(chain.payload());
        };

    // An empty Traits + createContext produces an AnalyticsContext without a device entry.
    Traits traits = Traits.create();
    Traits.Cache traitsCache = mock(Traits.Cache.class);
    when(traitsCache.get()).thenReturn(traits);
    AnalyticsContext ctxNoDevice = io.freshpaint.android.Utils.createContext(traits);
    // Remove the device key so analyticsContext.device() returns null.
    ctxNoDevice.remove("device");

    BooleanPreference optOut = new BooleanPreference(fakePrefs, "opt-out", false);

    Freshpaint fp =
        new Freshpaint(
            application,
            mock(ExecutorService.class),
            mock(Stats.class),
            traitsCache,
            ctxNoDevice,
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
            new SynchronousExecutor(),
            false,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.singletonList(captureMiddleware),
            Collections.emptyMap(),
            new ValueMap(),
            mock(Lifecycle.class),
            false,
            true);

    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    Object limitAdTracking = tracksOf(captured).get(0).properties().get("limit_ad_tracking");
    assertThat(limitAdTracking).isEqualTo(true);
  }

  // -------------------------------------------------------------------------
  // AC13 — Install Referrer data merged into app_install payload (FRP-44)
  // -------------------------------------------------------------------------

  /**
   * When Install Referrer data is pre-populated in SharedPreferences (as
   * trackAttributionInformation does before trackApplicationLifecycleEvents runs), app_install must
   * include those fields.
   */
  @Test
  public void irDataMergedIntoAppInstallPayload() {
    // Simulate InstallReferrerManager.collectAndStore() having already run on the executor.
    fakePrefs.store.put(InstallReferrerManager.KEY_IR_COLLECTED, true);
    fakePrefs.store.put("ir.install_referrer", "utm_source=google&utm_campaign=winter_sale");
    fakePrefs.store.put("ir.utm_source", "google");
    fakePrefs.store.put("ir.utm_campaign", "winter_sale");
    fakePrefs.store.put("ir.$gclid", "test-gclid-value");
    fakePrefs.store.put("ir.$gclid_creation_time", 1710000000000L);

    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    Properties props = tracksOf(captured).get(0).properties();
    assertThat(props.get("install_referrer"))
        .isEqualTo("utm_source=google&utm_campaign=winter_sale");
    assertThat(props.get("utm_source")).isEqualTo("google");
    assertThat(props.get("utm_campaign")).isEqualTo("winter_sale");
    assertThat(props.get("$gclid")).isEqualTo("test-gclid-value");
    assertThat(props.get("$gclid_creation_time")).isEqualTo(1710000000000L);
  }

  // -------------------------------------------------------------------------
  // AC10 (FRP-45) — Deep-link attribution merged into app_install
  // -------------------------------------------------------------------------

  /**
   * When a deep link fires before app_install (first install scenario), the stored deep-link
   * attribution data must appear in the {@code app_install} payload. This test pre-populates
   * SharedPreferences with DL attribution (simulating a prior {@code trackDeepLink()} call) and
   * asserts the fields are present in the emitted event.
   */
  @Test
  public void appInstallIncludesStoredDeepLinkAttribution() {
    // Simulate trackDeepLink() having already stored DL attribution before app_install fires.
    Map<String, String> dlParams = new LinkedHashMap<>();
    dlParams.put("gclid", "DL_GCLID_123");
    dlParams.put("utm_source", "facebook");
    // Use a fixed timestamp well within the 24-hour UTM expiry window.
    DeepLinkAttributionManager.store(dlParams, fakePrefs, 1_000_000L);

    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    Properties props = tracksOf(captured).get(0).properties();
    assertThat(props.get("$gclid")).isEqualTo("DL_GCLID_123");
    assertThat(props).containsKey("$gclid_creation_time");
    // TODO: FRP-46 — assert UTM params in app_install once clock injection is available.
    // trackApplicationLifecycleEvents() calls System.currentTimeMillis() (~1.7e12 ms), which
    // makes the UTM stored at T=1_000_000ms always appear expired. Only asserting click ID here.
  }

  /**
   * DL click IDs must appear in {@code app_install} even when the IR prefs are also populated —
   * confirming DL data overwrites IR data for overlapping keys.
   */
  @Test
  public void appInstallDlOverwritesIrForOverlappingKeys() {
    // IR has a gclid value
    fakePrefs.store.put(InstallReferrerManager.KEY_IR_COLLECTED, true);
    fakePrefs.store.put("ir.$gclid", "IR_GCLID_VALUE");
    fakePrefs.store.put("ir.$gclid_creation_time", 500_000L);

    // DL has a different gclid value (more recent / more direct signal)
    Map<String, String> dlParams = new LinkedHashMap<>();
    dlParams.put("gclid", "DL_GCLID_VALUE");
    DeepLinkAttributionManager.store(dlParams, fakePrefs, 1_000_000L);

    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    Properties props = tracksOf(captured).get(0).properties();
    // DL value must win
    assertThat(props.get("$gclid")).isEqualTo("DL_GCLID_VALUE");
    assertThat(props.get("$gclid_creation_time")).isEqualTo(1_000_000L);
  }

  // -------------------------------------------------------------------------
  // AC11 (FRP-45) — isFirstOpenTracked() and getDeepLinkAttributionProperties()
  // -------------------------------------------------------------------------

  /** {@code isFirstOpenTracked()} must return false when the key is absent. */
  @Test
  public void isFirstOpenTracked_returnsFalseWhenKeyAbsent() {
    Freshpaint fp = buildFreshpaint(true);
    assertThat(fp.isFirstOpenTracked()).isFalse();
  }

  /**
   * {@code isFirstOpenTracked()} must return true after {@code trackApplicationLifecycleEvents()}
   * sets the flag.
   */
  @Test
  public void isFirstOpenTracked_returnsTrueAfterFirstLaunch() {
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();
    assertThat(fp.isFirstOpenTracked()).isTrue();
  }

  /**
   * {@code getDeepLinkAttributionProperties()} must return stored DL data from SharedPreferences.
   */
  @Test
  public void getDeepLinkAttributionProperties_returnsStoredDlData() {
    Map<String, String> dlParams = new LinkedHashMap<>();
    dlParams.put("gclid", "STORED_GCLID");
    DeepLinkAttributionManager.store(dlParams, fakePrefs, 1_000_000L);

    Freshpaint fp = buildFreshpaint(true);
    Map<String, Object> props = fp.getDeepLinkAttributionProperties(1_000_000L);
    assertThat(props).containsEntry("$gclid", "STORED_GCLID");
    assertThat(props).containsKey("$gclid_creation_time");
  }

  // -------------------------------------------------------------------------
  // AC3/AC4 — android_id in app_install payload
  // -------------------------------------------------------------------------

  /**
   * When the device context has a valid {@code android_id}, it must appear in the {@code
   * app_install} event properties.
   */
  @Test
  public void appInstall_containsAndroidId_whenPresent() {
    Middleware captureMiddleware =
        chain -> {
          captured.add(chain.payload());
          chain.proceed(chain.payload());
        };

    Traits traits = Traits.create();
    Traits.Cache traitsCache = mock(Traits.Cache.class);
    when(traitsCache.get()).thenReturn(traits);
    AnalyticsContext analyticsContext = io.freshpaint.android.Utils.createContext(traits);

    // Populate a device entry with android_id so the snapshot in
    // trackApplicationLifecycleEvents() picks it up.
    AnalyticsContext.Device device = new AnalyticsContext.Device();
    device.putAndroidId("real-android-id-xyz");
    analyticsContext.put("device", device);

    BooleanPreference optOut = new BooleanPreference(fakePrefs, "opt-out", false);
    Freshpaint fp =
        new Freshpaint(
            application,
            mock(ExecutorService.class),
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
            new SynchronousExecutor(),
            false,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.singletonList(captureMiddleware),
            Collections.emptyMap(),
            new ValueMap(),
            mock(Lifecycle.class),
            false,
            true);

    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    assertThat(tracksOf(captured).get(0).event()).isEqualTo("app_install");
    assertThat(tracksOf(captured).get(0).properties())
        .containsEntry("android_id", "real-android-id-xyz");
  }

  /**
   * When the device context has no valid android_id (null), the {@code android_id} key must be
   * absent from the {@code app_install} event properties.
   */
  @Test
  public void appInstall_doesNotContainAndroidId_whenNull() {
    // buildFreshpaint uses Utils.createContext which has no device entry → device() returns null
    // → androidId snapshot is null → "android_id" key absent from installProps.
    Freshpaint fp = buildFreshpaint(true);
    fp.trackApplicationLifecycleEvents();

    assertThat(tracksOf(captured)).hasSize(1);
    assertThat(tracksOf(captured).get(0).properties()).doesNotContainKey("android_id");
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private static List<TrackPayload> tracksOf(List<BasePayload> payloads) {
    List<TrackPayload> result = new ArrayList<>();
    for (BasePayload p : payloads) {
      if (p instanceof TrackPayload) {
        result.add((TrackPayload) p);
      }
    }
    return result;
  }
}
