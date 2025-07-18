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

import static android.content.Context.MODE_PRIVATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import io.freshpaint.android.integrations.AliasPayload;
import io.freshpaint.android.integrations.GroupPayload;
import io.freshpaint.android.integrations.IdentifyPayload;
import io.freshpaint.android.integrations.Integration;
import io.freshpaint.android.integrations.Logger;
import io.freshpaint.android.integrations.ScreenPayload;
import io.freshpaint.android.integrations.TrackPayload;
import io.freshpaint.android.internal.Utils;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.assertj.core.data.MapEntry;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class AnalyticsTest {

  private static final String SETTINGS =
      "{\n"
          + "  \"integrations\": {\n"
          + "    \"test\": {\n"
          + "      \"foo\": \"bar\"\n"
          + "    }\n"
          + "  },\n"
          + "  \"plan\": {\n"
          + "    \n"
          + "  }\n"
          + "}";

  @Mock Traits.Cache traitsCache;
  @Spy Utils.AnalyticsNetworkExecutorService networkExecutor;
  @Spy ExecutorService analyticsExecutor = new TestUtils.SynchronousExecutor();
  @Mock Client client;
  @Mock Stats stats;
  @Mock ProjectSettings.Cache projectSettingsCache;
  @Mock Integration integration;
  @Mock Lifecycle lifecycle;
  private Options defaultOptions;
  private Integration.Factory factory;
  private BooleanPreference optOut;
  private Application application;
  private Traits traits;
  private AnalyticsContext analyticsContext;
  private Freshpaint freshpaint;

  @Before
  public void setUp() throws IOException, NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    initMocks(this);
    defaultOptions = new Options();
    application = TestUtils.mockApplication();
    traits = Traits.create();
    when(traitsCache.get()).thenReturn(traits);

    PackageInfo packageInfo = new PackageInfo();
    packageInfo.versionCode = 100;
    packageInfo.versionName = "1.0.0";

    PackageManager packageManager = mock(PackageManager.class);
    when(packageManager.getPackageInfo("com.foo", 0)).thenReturn(packageInfo);
    when(application.getPackageName()).thenReturn("com.foo");
    when(application.getPackageManager()).thenReturn(packageManager);

    analyticsContext = io.freshpaint.android.Utils.createContext(traits);
    factory =
        new Integration.Factory() {
          @Override
          public Integration<?> create(ValueMap settings, Freshpaint freshpaint) {
            return integration;
          }

          @Override
          public String key() {
            return "test";
          }
        };
    when(projectSettingsCache.get()) //
        .thenReturn(ProjectSettings.create(Cartographer.INSTANCE.fromJson(SETTINGS)));

    SharedPreferences sharedPreferences =
        RuntimeEnvironment.application.getSharedPreferences("analytics-test-qaz", MODE_PRIVATE);
    optOut = new BooleanPreference(sharedPreferences, "opt-out-test", false);

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.VERBOSE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            false,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    // Used by singleton tests.
    TestUtils.grantPermission(RuntimeEnvironment.application, Manifest.permission.INTERNET);
  }

  @After
  public void tearDown() {
    RuntimeEnvironment.application
        .getSharedPreferences("analytics-android-qaz", MODE_PRIVATE)
        .edit()
        .clear()
        .commit();
  }

  @Test
  public void invalidIdentify() {
    try {
      freshpaint.identify(null, null, null);
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Either userId or some traits must be provided.");
    }
  }

  @Test
  public void identify() {
    freshpaint.identify("prateek", new Traits().putUsername("f2prateek"), null);

    verify(integration)
        .identify(
            argThat(
                new TestUtils.NoDescriptionMatcher<IdentifyPayload>() {
                  @Override
                  protected boolean matchesSafely(IdentifyPayload item) {
                    return item.userId().equals("prateek")
                        && item.traits().username().equals("f2prateek");
                  }
                }));
  }

  @Test
  public void identifyUpdatesCache() {
    freshpaint.identify("foo", new Traits().putValue("bar", "qaz"), null);

    assertThat(traits)
        .contains(MapEntry.entry("userId", "foo"))
        .contains(MapEntry.entry("bar", "qaz"));
    assertThat(analyticsContext.traits())
        .contains(MapEntry.entry("userId", "foo"))
        .contains(MapEntry.entry("bar", "qaz"));
    verify(traitsCache).set(traits);
    verify(integration)
        .identify(
            argThat(
                new TestUtils.NoDescriptionMatcher<IdentifyPayload>() {
                  @Override
                  protected boolean matchesSafely(IdentifyPayload item) {
                    // Exercises a bug where payloads didn't pick up userId in identify correctly.
                    // https://github.com/segmentio/analytics-android/issues/169
                    return item.userId().equals("foo");
                  }
                }));
  }

  @Test
  public void invalidGroup() {
    try {
      freshpaint.group(null);
      fail("null groupId should throw exception");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("groupId must not be null or empty.");
    }

    try {
      freshpaint.group("");
      fail("empty groupId and name should throw exception");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("groupId must not be null or empty.");
    }
  }

  @Test
  public void group() {
    freshpaint.group("segment", new Traits().putEmployees(42), null);

    verify(integration)
        .group(
            argThat(
                new TestUtils.NoDescriptionMatcher<GroupPayload>() {
                  @Override
                  protected boolean matchesSafely(GroupPayload item) {
                    return item.groupId().equals("segment") && item.traits().employees() == 42;
                  }
                }));
  }

  @Test
  public void invalidTrack() {
    try {
      freshpaint.track(null);
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("event must not be null or empty.");
    }
    try {
      freshpaint.track("   ");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("event must not be null or empty.");
    }
  }

  @Test
  public void track() {
    freshpaint.track("wrote tests", new Properties().putUrl("github.com"));
    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("wrote tests")
                        && //
                        payload.properties().url().equals("github.com");
                  }
                }));
  }

  @Test
  public void invalidScreen() throws Exception {
    try {
      freshpaint.screen(null, (String) null);
      fail("null category and name should throw exception");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("either category or name must be provided.");
    }

    try {
      freshpaint.screen("", "");
      fail("empty category and name should throw exception");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("either category or name must be provided.");
    }
  }

  @Test
  public void screen() {
    freshpaint.screen("android", "saw tests", new Properties().putUrl("github.com"));
    verify(integration)
        .screen(
            argThat(
                new TestUtils.NoDescriptionMatcher<ScreenPayload>() {
                  @Override
                  protected boolean matchesSafely(ScreenPayload payload) {
                    return payload.name().equals("saw tests")
                        && //
                        payload.category().equals("android")
                        && //
                        payload.properties().url().equals("github.com");
                  }
                }));
  }

  @Test
  public void optionsDisableIntegrations() {
    freshpaint.screen("foo", "bar", null, new Options().setIntegration("test", false));
    freshpaint.track("foo", null, new Options().setIntegration("test", false));
    freshpaint.group("foo", null, new Options().setIntegration("test", false));
    freshpaint.identify("foo", null, new Options().setIntegration("test", false));
    freshpaint.alias("foo", new Options().setIntegration("test", false));

    freshpaint.screen(
        "foo", "bar", null, new Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false));
    freshpaint.track(
        "foo", null, new Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false));
    freshpaint.group(
        "foo", null, new Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false));
    freshpaint.identify(
        "foo", null, new Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false));
    freshpaint.alias("foo", new Options().setIntegration(Options.ALL_INTEGRATIONS_KEY, false));

    verifyNoMoreInteractions(integration);
  }

  @Test
  public void optionsCustomContext() {
    freshpaint.track("foo", null, new Options().putContext("from_tests", true));

    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.context().get("from_tests") == Boolean.TRUE;
                  }
                }));
  }

  @Test
  public void optOutDisablesEvents() throws IOException {
    freshpaint.optOut(true);
    freshpaint.track("foo");
    verifyNoMoreInteractions(integration);
  }

  @Test
  public void emptyTrackingPlan() throws IOException {
    freshpaint.projectSettings =
        ProjectSettings.create(
            Cartographer.INSTANCE.fromJson(
                "{\n"
                    + "  \"integrations\": {\n"
                    + "    \"test\": {\n"
                    + "      \"foo\": \"bar\"\n"
                    + "    }\n"
                    + "  },\n"
                    + "  \"plan\": {\n"
                    + "  }\n"
                    + "}"));

    freshpaint.track("foo");
    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("foo");
                  }
                }));
    verifyNoMoreInteractions(integration);
  }

  @Test
  public void emptyEventPlan() throws IOException {
    freshpaint.projectSettings =
        ProjectSettings.create(
            Cartographer.INSTANCE.fromJson(
                "{\n"
                    + "  \"integrations\": {\n"
                    + "    \"test\": {\n"
                    + "      \"foo\": \"bar\"\n"
                    + "    }\n"
                    + "  },\n"
                    + "  \"plan\": {\n"
                    + "    \"track\": {\n"
                    + "    }\n"
                    + "  }\n"
                    + "}"));

    freshpaint.track("foo");
    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("foo");
                  }
                }));
    verifyNoMoreInteractions(integration);
  }

  @Test
  public void trackingPlanDisablesEvent() throws IOException {
    freshpaint.projectSettings =
        ProjectSettings.create(
            Cartographer.INSTANCE.fromJson(
                "{\n"
                    + "  \"integrations\": {\n"
                    + "    \"test\": {\n"
                    + "      \"foo\": \"bar\"\n"
                    + "    }\n"
                    + "  },\n"
                    + "  \"plan\": {\n"
                    + "    \"track\": {\n"
                    + "      \"foo\": {\n"
                    + "        \"enabled\": false\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}"));

    freshpaint.track("foo");
    verifyNoMoreInteractions(integration);
  }

  @Test
  public void trackingPlanDisablesEventForSingleIntegration() throws IOException {
    freshpaint.projectSettings =
        ProjectSettings.create(
            Cartographer.INSTANCE.fromJson(
                "{\n"
                    + "  \"integrations\": {\n"
                    + "    \"test\": {\n"
                    + "      \"foo\": \"bar\"\n"
                    + "    }\n"
                    + "  },\n"
                    + "  \"plan\": {\n"
                    + "    \"track\": {\n"
                    + "      \"foo\": {\n"
                    + "        \"enabled\": true,\n"
                    + "        \"integrations\": {\n"
                    + "          \"test\": false\n"
                    + "        }\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}"));

    freshpaint.track("foo");
    verifyNoMoreInteractions(integration);
  }

  @Test
  public void trackingPlanDisabledEventCannotBeOverriddenByOptions() throws IOException {
    freshpaint.projectSettings =
        ProjectSettings.create(
            Cartographer.INSTANCE.fromJson(
                "{\n"
                    + "  \"integrations\": {\n"
                    + "    \"test\": {\n"
                    + "      \"foo\": \"bar\"\n"
                    + "    }\n"
                    + "  },\n"
                    + "  \"plan\": {\n"
                    + "    \"track\": {\n"
                    + "      \"foo\": {\n"
                    + "        \"enabled\": false\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}"));

    freshpaint.track("foo", null, new Options().setIntegration("test", true));
    verifyNoMoreInteractions(integration);
  }

  @Test
  public void trackingPlanDisabledEventForIntegrationOverriddenByOptions() throws IOException {
    freshpaint.projectSettings =
        ProjectSettings.create(
            Cartographer.INSTANCE.fromJson(
                "{\n"
                    + "  \"integrations\": {\n"
                    + "    \"test\": {\n"
                    + "      \"foo\": \"bar\"\n"
                    + "    }\n"
                    + "  },\n"
                    + "  \"plan\": {\n"
                    + "    \"track\": {\n"
                    + "      \"foo\": {\n"
                    + "        \"enabled\": true,\n"
                    + "        \"integrations\": {\n"
                    + "          \"test\": false\n"
                    + "        }\n"
                    + "      }\n"
                    + "    }\n"
                    + "  }\n"
                    + "}"));

    freshpaint.track("foo", null, new Options().setIntegration("test", true));
    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("foo");
                  }
                }));
    verifyNoMoreInteractions(integration);
  }

  @Test
  public void invalidAlias() {
    try {
      freshpaint.alias(null);
      fail("null new id should throw error");
    } catch (IllegalArgumentException expected) {
      assertThat(expected).hasMessage("newId must not be null or empty.");
    }
  }

  @Test
  public void alias() {
    String anonymousId = traits.anonymousId();
    freshpaint.alias("foo");
    ArgumentCaptor<AliasPayload> payloadArgumentCaptor =
        ArgumentCaptor.forClass(AliasPayload.class);
    verify(integration).alias(payloadArgumentCaptor.capture());
    Assertions.assertThat(payloadArgumentCaptor.getValue())
        .containsEntry("previousId", anonymousId)
        .containsEntry("userId", "foo");
  }

  @Test
  public void aliasWithCachedUserID() {
    freshpaint.identify(
        "prayansh", new Traits().putValue("bar", "qaz"), null); // refer identifyUpdatesCache
    freshpaint.alias("foo");
    ArgumentCaptor<AliasPayload> payloadArgumentCaptor =
        ArgumentCaptor.forClass(AliasPayload.class);
    verify(integration).alias(payloadArgumentCaptor.capture());
    Assertions.assertThat(payloadArgumentCaptor.getValue())
        .containsEntry("previousId", "prayansh")
        .containsEntry("userId", "foo");
  }

  @Test
  public void flush() {
    freshpaint.flush();

    verify(integration).flush();
  }

  @Test
  public void reset() {
    freshpaint.reset();

    verify(integration).reset();
  }

  @Test
  public void getSnapshot() throws Exception {
    freshpaint.getSnapshot();

    verify(stats).createSnapshot();
  }

  @Test
  public void logoutClearsTraitsAndUpdatesContext() {
    analyticsContext.setTraits(new Traits().putAge(20).putAvatar("bar"));

    freshpaint.logout();

    verify(traitsCache).delete();
    verify(traitsCache)
        .set(
            argThat(
                new TypeSafeMatcher<Traits>() {
                  @Override
                  protected boolean matchesSafely(Traits traits) {
                    return !Utils.isNullOrEmpty(traits.anonymousId());
                  }

                  @Override
                  public void describeTo(Description description) {}
                }));
    assertThat(analyticsContext.traits()).hasSize(1).containsKey("anonymousId");
  }

  @Test
  public void onIntegrationReadyShouldFailForNullKey() {
    try {
      freshpaint.onIntegrationReady((String) null, mock(Freshpaint.Callback.class));
      fail("registering for null integration should fail");
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("key cannot be null or empty.");
    }
  }

  @Test
  public void onIntegrationReady() {
    Freshpaint.Callback<Void> callback = mock(Freshpaint.Callback.class);
    freshpaint.onIntegrationReady("test", callback);
    verify(callback).onReady(null);
  }

  @Test
  public void shutdown() {
    assertThat(freshpaint.shutdown).isFalse();
    freshpaint.shutdown();
    verify(application).unregisterActivityLifecycleCallbacks(freshpaint.activityLifecycleCallback);
    verify(stats).shutdown();
    verify(networkExecutor).shutdown();
    assertThat(freshpaint.shutdown).isTrue();
    try {
      freshpaint.track("foo");
      fail("Enqueuing a message after shutdown should throw.");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Cannot enqueue messages after client is shutdown.");
    }

    try {
      freshpaint.flush();
      fail("Enqueuing a message after shutdown should throw.");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Cannot enqueue messages after client is shutdown.");
    }
  }

  @Test
  public void shutdownTwice() {
    assertThat(freshpaint.shutdown).isFalse();
    freshpaint.shutdown();
    freshpaint.shutdown();
    verify(stats).shutdown();
    assertThat(freshpaint.shutdown).isTrue();
  }

  @Test
  public void shutdownDisallowedOnCustomSingletonInstance() throws Exception {
    Freshpaint.singleton = null;
    try {
      Freshpaint freshpaint = new Freshpaint.Builder(RuntimeEnvironment.application, "foo").build();
      Freshpaint.setSingletonInstance(freshpaint);
      freshpaint.shutdown();
      fail("Calling shutdown() on static singleton instance should throw");
    } catch (UnsupportedOperationException ignored) {
    }
  }

  @Test
  public void setSingletonInstanceMayOnlyBeCalledOnce() {
    Freshpaint.singleton = null;

    Freshpaint freshpaint = new Freshpaint.Builder(RuntimeEnvironment.application, "foo").build();
    Freshpaint.setSingletonInstance(freshpaint);

    try {
      Freshpaint.setSingletonInstance(freshpaint);
      fail("Can't set singleton instance twice.");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Singleton instance already exists.");
    }
  }

  @Test
  public void setSingletonInstanceAfterWithFails() {
    Freshpaint.singleton = null;

    Freshpaint.setSingletonInstance(
        new Freshpaint.Builder(RuntimeEnvironment.application, "foo") //
            .build());

    Freshpaint freshpaint = new Freshpaint.Builder(RuntimeEnvironment.application, "bar").build();
    try {
      Freshpaint.setSingletonInstance(freshpaint);
      fail("Can't set singleton instance after with().");
    } catch (IllegalStateException e) {
      assertThat(e).hasMessage("Singleton instance already exists.");
    }
  }

  @Test
  public void setSingleInstanceReturnedFromWith() {
    Freshpaint.singleton = null;
    Freshpaint freshpaint = new Freshpaint.Builder(RuntimeEnvironment.application, "foo").build();
    Freshpaint.setSingletonInstance(freshpaint);
    assertThat(Freshpaint.with(RuntimeEnvironment.application)).isSameAs(freshpaint);
  }

  @Test
  public void multipleInstancesWithSameTagThrows() throws Exception {
    new Freshpaint.Builder(RuntimeEnvironment.application, "foo").build();
    try {
      new Freshpaint.Builder(RuntimeEnvironment.application, "bar").tag("foo").build();
      fail("Creating client with duplicate should throw.");
    } catch (IllegalStateException expected) {
      assertThat(expected) //
          .hasMessageContaining("Duplicate analytics client created with tag: foo.");
    }
  }

  @Test
  public void multipleInstancesWithSameTagIsAllowedAfterShutdown() throws Exception {
    new Freshpaint.Builder(RuntimeEnvironment.application, "foo").build().shutdown();
    new Freshpaint.Builder(RuntimeEnvironment.application, "bar").tag("foo").build();
  }

  @Test
  public void getSnapshotInvokesStats() throws Exception {
    freshpaint.getSnapshot();
    verify(stats).createSnapshot();
  }

  @Test
  public void invalidURlsThrowAndNotCrash() throws Exception {
    ConnectionFactory connection = new ConnectionFactory();

    try {
      connection.openConnection("SOME_BUSTED_URL");
      fail("openConnection did not throw when supplied an invalid URL as expected.");
    } catch (IOException expected) {
      assertThat(expected).hasMessageContaining("Attempted to use malformed url");
      assertThat(expected).isInstanceOf(IOException.class);
    }
  }

  @Test
  public void trackApplicationLifecycleEventsInstalled() throws NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<DefaultLifecycleObserver> callback = new AtomicReference<>();
    doNothing()
        .when(lifecycle)
        .addObserver(
            argThat(
                new TestUtils.NoDescriptionMatcher<LifecycleObserver>() {
                  @Override
                  protected boolean matchesSafely(LifecycleObserver item) {
                    callback.set((DefaultLifecycleObserver) item);
                    return true;
                  }
                }));
    LifecycleOwner mockLifecycleOwner = mock(LifecycleOwner.class);

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    callback.get().onCreate(mockLifecycleOwner);

    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Application Installed")
                        && //
                        payload.properties().getString("version").equals("1.0.0")
                        && //
                        payload.properties().getString("build").equals(String.valueOf(100));
                  }
                }));

    callback.get().onCreate(mockLifecycleOwner);
    verifyNoMoreInteractions(integration); // Application Installed is not duplicated
  }

  @Test
  public void trackApplicationLifecycleEventsUpdated() throws NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    PackageInfo packageInfo = new PackageInfo();
    packageInfo.versionCode = 101;
    packageInfo.versionName = "1.0.1";

    SharedPreferences sharedPreferences =
        RuntimeEnvironment.application.getSharedPreferences("analytics-android-qaz", MODE_PRIVATE);
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putInt("build", 100);
    editor.putString("version", "1.0.0");
    editor.apply();
    when(application.getSharedPreferences("analytics-android-qaz", MODE_PRIVATE))
        .thenReturn(sharedPreferences);

    PackageManager packageManager = mock(PackageManager.class);
    when(packageManager.getPackageInfo("com.foo", 0)).thenReturn(packageInfo);
    when(application.getPackageName()).thenReturn("com.foo");
    when(application.getPackageManager()).thenReturn(packageManager);

    final AtomicReference<DefaultLifecycleObserver> callback = new AtomicReference<>();
    doNothing()
        .when(lifecycle)
        .addObserver(
            argThat(
                new TestUtils.NoDescriptionMatcher<LifecycleObserver>() {
                  @Override
                  protected boolean matchesSafely(LifecycleObserver item) {
                    callback.set((DefaultLifecycleObserver) item);
                    return true;
                  }
                }));
    LifecycleOwner mockLifecycleOwner = mock(LifecycleOwner.class);

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    callback.get().onCreate(mockLifecycleOwner);

    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Application Updated")
                        && //
                        payload.properties().getString("previous_version").equals("1.0.0")
                        && //
                        payload.properties().getString("previous_build").equals(String.valueOf(100))
                        && //
                        payload.properties().getString("version").equals("1.0.1")
                        && //
                        payload.properties().getString("build").equals(String.valueOf(101));
                  }
                }));
  }

  @Test
  public void recordScreenViews() throws NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new TestUtils.NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            false,
            new CountDownLatch(0),
            true,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    Activity activity = mock(Activity.class);
    PackageManager packageManager = mock(PackageManager.class);
    ActivityInfo info = mock(ActivityInfo.class);

    when(activity.getPackageManager()).thenReturn(packageManager);
    //noinspection WrongConstant
    when(packageManager.getActivityInfo(any(ComponentName.class), eq(PackageManager.GET_META_DATA)))
        .thenReturn(info);
    when(info.loadLabel(packageManager)).thenReturn("Foo");

    callback.get().onActivityStarted(activity);

    verify(integration)
        .screen(
            argThat(
                new TestUtils.NoDescriptionMatcher<ScreenPayload>() {
                  @Override
                  protected boolean matchesSafely(ScreenPayload payload) {
                    return payload.name().equals("Foo");
                  }
                }));
  }

  @Test
  public void trackDeepLinks() {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new TestUtils.NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            true,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    final String expectedUrl = "app://track.com/open?utm_id=12345&gclid=abcd&nope=";

    Activity activity = mock(Activity.class);
    Intent intent = mock(Intent.class);
    Uri uri = Uri.parse(expectedUrl);

    when(intent.getData()).thenReturn(uri);
    when(activity.getIntent()).thenReturn(intent);

    callback.get().onActivityCreated(activity, new Bundle());

    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Deep Link Opened")
                        && payload.properties().getString("url").equals(expectedUrl)
                        && payload.properties().getString("gclid").equals("abcd")
                        && payload.properties().getString("utm_id").equals("12345");
                  }
                }));
  }

  @Test
  public void trackDeepLinks_disabled() {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new TestUtils.NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    final String expectedUrl = "app://track.com/open?utm_id=12345&gclid=abcd&nope=";

    Activity activity = mock(Activity.class);
    Intent intent = mock(Intent.class);
    Uri uri = Uri.parse(expectedUrl);

    when(intent.getData()).thenReturn(uri);
    when(activity.getIntent()).thenReturn(intent);

    callback.get().onActivityCreated(activity, new Bundle());

    verify(integration, never())
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Deep Link Opened")
                        && payload.properties().getString("url").equals(expectedUrl)
                        && payload.properties().getString("gclid").equals("abcd")
                        && payload.properties().getString("utm_id").equals("12345");
                  }
                }));
  }

  @Test
  public void trackDeepLinks_null() {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new TestUtils.NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    Activity activity = mock(Activity.class);

    when(activity.getIntent()).thenReturn(null);

    callback.get().onActivityCreated(activity, new Bundle());

    verify(integration, never())
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Deep Link Opened");
                  }
                }));
  }

  @Test
  public void trackDeepLinks_nullData() {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new TestUtils.NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    Activity activity = mock(Activity.class);

    Intent intent = mock(Intent.class);

    when(activity.getIntent()).thenReturn(intent);
    when(intent.getData()).thenReturn(null);

    callback.get().onActivityCreated(activity, new Bundle());

    verify(integration, never())
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Deep Link Opened");
                  }
                }));
  }

  @Test
  public void registerActivityLifecycleCallbacks() throws NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> callback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new TestUtils.NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    callback.set(item);
                    return true;
                  }
                }));

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            false,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    Activity activity = mock(Activity.class);
    Bundle bundle = new Bundle();

    callback.get().onActivityCreated(activity, bundle);
    verify(integration).onActivityCreated(activity, bundle);

    callback.get().onActivityStarted(activity);
    verify(integration).onActivityStarted(activity);

    callback.get().onActivityResumed(activity);
    verify(integration).onActivityResumed(activity);

    callback.get().onActivityPaused(activity);
    verify(integration).onActivityPaused(activity);

    callback.get().onActivityStopped(activity);
    verify(integration).onActivityStopped(activity);

    callback.get().onActivitySaveInstanceState(activity, bundle);
    verify(integration).onActivitySaveInstanceState(activity, bundle);

    callback.get().onActivityDestroyed(activity);
    verify(integration).onActivityDestroyed(activity);

    verifyNoMoreInteractions(integration);
  }

  @Test
  public void trackApplicationLifecycleEventsApplicationOpened() throws NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<DefaultLifecycleObserver> callback = new AtomicReference<>();
    doNothing()
        .when(lifecycle)
        .addObserver(
            argThat(
                new TestUtils.NoDescriptionMatcher<LifecycleObserver>() {
                  @Override
                  protected boolean matchesSafely(LifecycleObserver item) {
                    callback.set((DefaultLifecycleObserver) item);
                    return true;
                  }
                }));
    LifecycleOwner mockLifecycleOwner = mock(LifecycleOwner.class);

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    callback.get().onCreate(mockLifecycleOwner);
    callback.get().onStart(mockLifecycleOwner);

    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Application Opened")
                        && payload.properties().getString("version").equals("1.0.0")
                        && payload.properties().getString("build").equals(String.valueOf(100))
                        && !payload.properties().getBoolean("from_background", true);
                  }
                }));
  }

  @Test
  public void trackApplicationLifecycleEventsApplicationBackgrounded()
      throws NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<DefaultLifecycleObserver> callback = new AtomicReference<>();
    doNothing()
        .when(lifecycle)
        .addObserver(
            argThat(
                new TestUtils.NoDescriptionMatcher<LifecycleObserver>() {
                  @Override
                  protected boolean matchesSafely(LifecycleObserver item) {
                    callback.set((DefaultLifecycleObserver) item);
                    return true;
                  }
                }));

    LifecycleOwner mockLifecycleOwner = mock(LifecycleOwner.class);

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    Activity backgroundedActivity = mock(Activity.class);
    when(backgroundedActivity.isChangingConfigurations()).thenReturn(false);

    callback.get().onCreate(mockLifecycleOwner);
    callback.get().onResume(mockLifecycleOwner);
    callback.get().onStop(mockLifecycleOwner);

    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Application Backgrounded");
                  }
                }));
  }

  @Test
  public void trackApplicationLifecycleEventsApplicationForegrounded()
      throws NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<DefaultLifecycleObserver> callback = new AtomicReference<>();
    doNothing()
        .when(lifecycle)
        .addObserver(
            argThat(
                new TestUtils.NoDescriptionMatcher<LifecycleObserver>() {
                  @Override
                  protected boolean matchesSafely(LifecycleObserver item) {
                    callback.set((DefaultLifecycleObserver) item);
                    return true;
                  }
                }));
    LifecycleOwner mockLifecycleOwner = mock(LifecycleOwner.class);

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    callback.get().onCreate(mockLifecycleOwner);
    callback.get().onStart(mockLifecycleOwner);
    callback.get().onStop(mockLifecycleOwner);
    callback.get().onStart(mockLifecycleOwner);

    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Application Backgrounded");
                  }
                }));

    verify(integration)
        .track(
            argThat(
                new TestUtils.NoDescriptionMatcher<TrackPayload>() {
                  @Override
                  protected boolean matchesSafely(TrackPayload payload) {
                    return payload.event().equals("Application Opened")
                        && payload.properties().getBoolean("from_background", false);
                  }
                }));
  }

  @Test
  public void unregisterActivityLifecycleCallbacks() throws NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<Application.ActivityLifecycleCallbacks> registeredCallback =
        new AtomicReference<>();
    final AtomicReference<Application.ActivityLifecycleCallbacks> unregisteredCallback =
        new AtomicReference<>();
    doNothing()
        .when(application)
        .registerActivityLifecycleCallbacks(
            argThat(
                new TestUtils.NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    registeredCallback.set(item);
                    return true;
                  }
                }));
    doNothing()
        .when(application)
        .unregisterActivityLifecycleCallbacks(
            argThat(
                new TestUtils.NoDescriptionMatcher<Application.ActivityLifecycleCallbacks>() {
                  @Override
                  protected boolean matchesSafely(Application.ActivityLifecycleCallbacks item) {
                    unregisteredCallback.set(item);
                    return true;
                  }
                }));

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            false,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    assertThat(freshpaint.shutdown).isFalse();
    freshpaint.shutdown();

    // Same callback was registered and unregistered
    assertThat(freshpaint.activityLifecycleCallback).isSameAs(registeredCallback.get());
    assertThat(freshpaint.activityLifecycleCallback).isSameAs(unregisteredCallback.get());

    Activity activity = mock(Activity.class);
    Bundle bundle = new Bundle();

    // Verify callbacks do not call through after shutdown
    registeredCallback.get().onActivityCreated(activity, bundle);
    verify(integration, never()).onActivityCreated(activity, bundle);

    registeredCallback.get().onActivityStarted(activity);
    verify(integration, never()).onActivityStarted(activity);

    registeredCallback.get().onActivityResumed(activity);
    verify(integration, never()).onActivityResumed(activity);

    registeredCallback.get().onActivityPaused(activity);
    verify(integration, never()).onActivityPaused(activity);

    registeredCallback.get().onActivityStopped(activity);
    verify(integration, never()).onActivityStopped(activity);

    registeredCallback.get().onActivitySaveInstanceState(activity, bundle);
    verify(integration, never()).onActivitySaveInstanceState(activity, bundle);

    registeredCallback.get().onActivityDestroyed(activity);
    verify(integration, never()).onActivityDestroyed(activity);

    verifyNoMoreInteractions(integration);
  }

  @Test
  public void removeLifecycleObserver() throws NameNotFoundException {
    Freshpaint.INSTANCES.clear();

    final AtomicReference<DefaultLifecycleObserver> registeredCallback = new AtomicReference<>();
    final AtomicReference<DefaultLifecycleObserver> unregisteredCallback = new AtomicReference<>();
    doNothing()
        .when(lifecycle)
        .addObserver(
            argThat(
                new TestUtils.NoDescriptionMatcher<LifecycleObserver>() {
                  @Override
                  protected boolean matchesSafely(LifecycleObserver item) {
                    registeredCallback.set((DefaultLifecycleObserver) item);
                    return true;
                  }
                }));
    doNothing()
        .when(lifecycle)
        .removeObserver(
            argThat(
                new TestUtils.NoDescriptionMatcher<LifecycleObserver>() {
                  @Override
                  protected boolean matchesSafely(LifecycleObserver item) {
                    unregisteredCallback.set((DefaultLifecycleObserver) item);
                    return true;
                  }
                }));
    LifecycleOwner mockLifecycleOwner = mock(LifecycleOwner.class);

    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            false,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    assertThat(freshpaint.shutdown).isFalse();
    freshpaint.shutdown();
    AnalyticsActivityLifecycleCallbacks lifecycleObserverSpy =
        spy(freshpaint.activityLifecycleCallback);

    // Same callback was registered and unregistered
    assertThat(freshpaint.activityLifecycleCallback).isSameAs(registeredCallback.get());
    assertThat(freshpaint.activityLifecycleCallback).isSameAs(unregisteredCallback.get());

    // Verify callbacks do not call through after shutdown
    registeredCallback.get().onCreate(mockLifecycleOwner);
    verify(lifecycleObserverSpy, never()).onCreate(mockLifecycleOwner);

    registeredCallback.get().onStop(mockLifecycleOwner);
    verify(lifecycleObserverSpy, never()).onStop(mockLifecycleOwner);

    registeredCallback.get().onStart(mockLifecycleOwner);
    verify(lifecycleObserverSpy, never()).onStart(mockLifecycleOwner);

    verifyNoMoreInteractions(lifecycleObserverSpy);
  }

  @Test
  public void overridingOptionsDoesNotModifyGlobalAnalytics() {
    freshpaint.track("event", null, new Options().putContext("testProp", true));
    ArgumentCaptor<TrackPayload> payload = ArgumentCaptor.forClass(TrackPayload.class);
    verify(integration).track(payload.capture());
    Assertions.assertThat(payload.getValue().context()).containsKey("testProp");
    Assertions.assertThat(payload.getValue().context().get("testProp")).isEqualTo(true);
    assertThat(freshpaint.analyticsContext).doesNotContainKey("testProp");
  }

  @Test
  public void overridingOptionsWithDefaultOptionsPlusAdditional() {
    freshpaint.track("event", null, freshpaint.getDefaultOptions().putContext("testProp", true));
    ArgumentCaptor<TrackPayload> payload = ArgumentCaptor.forClass(TrackPayload.class);
    verify(integration).track(payload.capture());
    Assertions.assertThat(payload.getValue().context()).containsKey("testProp");
    Assertions.assertThat(payload.getValue().context().get("testProp")).isEqualTo(true);
    assertThat(freshpaint.analyticsContext).doesNotContainKey("testProp");
  }

  @Test
  public void enableExperimentalNanosecondResolutionTimestamps() {
    Freshpaint.INSTANCES.clear();
    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            true);

    freshpaint.track("event");
    ArgumentCaptor<TrackPayload> payload = ArgumentCaptor.forClass(TrackPayload.class);
    verify(integration).track(payload.capture());
    String timestamp = (String) payload.getValue().get("timestamp");
    assertThat(timestamp).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.\\d{9}Z");
  }

  @Test
  public void disableExperimentalNanosecondResolutionTimestamps() {
    Freshpaint.INSTANCES.clear();
    freshpaint =
        new Freshpaint(
            application,
            networkExecutor,
            stats,
            traitsCache,
            analyticsContext,
            defaultOptions,
            Logger.with(Freshpaint.LogLevel.NONE),
            "qaz",
            Collections.singletonList(factory),
            client,
            Cartographer.INSTANCE,
            projectSettingsCache,
            "foo",
            Utils.DEFAULT_FLUSH_QUEUE_SIZE,
            Utils.DEFAULT_FLUSH_INTERVAL,
            Utils.DEFAULT_SESSION_TIMEOUT_SECONDS,
            analyticsExecutor,
            true,
            new CountDownLatch(0),
            false,
            false,
            false,
            optOut,
            Crypto.none(),
            Collections.<Middleware>emptyList(),
            Collections.<String, List<Middleware>>emptyMap(),
            new ValueMap(),
            lifecycle,
            false);

    freshpaint.track("event");
    ArgumentCaptor<TrackPayload> payload = ArgumentCaptor.forClass(TrackPayload.class);
    verify(integration).track(payload.capture());
    String timestamp = (String) payload.getValue().get("timestamp");
    assertThat(timestamp).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.\\d{3}Z");
  }
}
