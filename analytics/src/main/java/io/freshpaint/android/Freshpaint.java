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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import io.freshpaint.android.integrations.AliasPayload;
import io.freshpaint.android.integrations.BasePayload;
import io.freshpaint.android.integrations.GroupPayload;
import io.freshpaint.android.integrations.IdentifyPayload;
import io.freshpaint.android.integrations.Integration;
import io.freshpaint.android.integrations.Logger;
import io.freshpaint.android.integrations.ScreenPayload;
import io.freshpaint.android.integrations.TrackPayload;
import io.freshpaint.android.internal.Private;
import io.freshpaint.android.internal.Utils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The entry point into the Freshpaint for Android SDK.
 *
 * <p>Analytics for Android will automatically batch events, queue them to disk, and upload it
 * periodically to Freshpaint for you.
 *
 * <p>This class is the main entry point into the client API. Use {@link
 * #with(android.content.Context)} for the global singleton instance or construct your own instance
 * with {@link Builder}.
 */
public class Freshpaint {

  static final Handler HANDLER =
      new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
          throw new AssertionError("Unknown handler message received: " + msg.what);
        }
      };
  @Private static final String OPT_OUT_PREFERENCE_KEY = "opt-out";
  static final String WRITE_KEY_RESOURCE_IDENTIFIER = "analytics_write_key";
  static final List<String> INSTANCES = new ArrayList<>(1);

  /* This is intentional since we're only using the application context. */
  @SuppressLint("StaticFieldLeak")
  static volatile Freshpaint singleton = null;

  @Private static final Properties EMPTY_PROPERTIES = new Properties();
  private static final String VERSION_KEY = "version";
  private static final String BUILD_KEY = "build";
  private static final String TRACKED_ATTRIBUTION_KEY = "tracked_attribution";
  private static final String TRAITS_KEY = "traits";

  private final Application application;
  final ExecutorService networkExecutor;
  final Stats stats;
  private final @NonNull List<Middleware> sourceMiddleware;
  private final @NonNull Map<String, List<Middleware>> destinationMiddleware;
  @Private final Options defaultOptions;
  @Private final Traits.Cache traitsCache;
  @Private final AnalyticsContext analyticsContext;
  private final Logger logger;
  final String tag;
  final Client client;
  final Cartographer cartographer;
  private final ProjectSettings.Cache projectSettingsCache;
  final Crypto crypto;
  @Private final AnalyticsActivityLifecycleCallbacks activityLifecycleCallback;
  @Private final Lifecycle lifecycle;
  ProjectSettings projectSettings; // todo: make final (non-final for testing).
  @Private final String writeKey;
  final int flushQueueSize;
  final long flushIntervalInMillis;
  // Retrieving the advertising ID is asynchronous. This latch helps us wait to ensure the
  // advertising ID is ready.
  private final CountDownLatch advertisingIdLatch;
  private final ExecutorService analyticsExecutor;
  private final BooleanPreference optOut;

  final Map<String, Boolean> bundledIntegrations = new ConcurrentHashMap<>();
  private List<Integration.Factory> factories;
  // todo: use lightweight map implementation.
  private Map<String, Integration<?>> integrations;
  volatile boolean shutdown;
  protected final int sessionTimeoutSeconds;

  @Private final boolean nanosecondTimestamps;

  /**
   * Return a reference to the global default {@link Freshpaint} instance.
   *
   * <p>This instance is automatically initialized with defaults that are suitable to most
   * implementations.
   *
   * <p>If these settings do not meet the requirements of your application, you can override
   * defaults in {@code analytics.xml}, or you can construct your own instance with full control
   * over the configuration by using {@link Builder}.
   *
   * <p>By default, events are uploaded every 30 seconds, or every 20 events (whichever occurs
   * first), and debugging is disabled.
   */
  public static Freshpaint with(Context context) {
    if (singleton == null) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      synchronized (Freshpaint.class) {
        if (singleton == null) {
          String writeKey = Utils.getResourceString(context, WRITE_KEY_RESOURCE_IDENTIFIER);
          Builder builder = new Builder(context, writeKey);

          try {
            String packageName = context.getPackageName();
            int flags = context.getPackageManager().getApplicationInfo(packageName, 0).flags;
            boolean debugging = (flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (debugging) {
              builder.logLevel(LogLevel.INFO);
            }
          } catch (PackageManager.NameNotFoundException ignored) {
          }

          singleton = builder.build();
        }
      }
    }
    return singleton;
  }

  /**
   * Set the global instance returned from {@link #with}.
   *
   * <p>This method must be called before any calls to {@link #with} and may only be called once.
   */
  public static void setSingletonInstance(Freshpaint freshpaint) {
    synchronized (Freshpaint.class) {
      if (singleton != null) {
        throw new IllegalStateException("Singleton instance already exists.");
      }
      singleton = freshpaint;
    }
  }

  Freshpaint(
      Application application,
      ExecutorService networkExecutor,
      Stats stats,
      Traits.Cache traitsCache,
      AnalyticsContext analyticsContext,
      Options defaultOptions,
      @NonNull Logger logger,
      String tag,
      @NonNull List<Integration.Factory> factories,
      Client client,
      Cartographer cartographer,
      ProjectSettings.Cache projectSettingsCache,
      String writeKey,
      int flushQueueSize,
      long flushIntervalInMillis,
      int sessionTimeoutSeconds,
      final ExecutorService analyticsExecutor,
      final boolean shouldTrackApplicationLifecycleEvents,
      CountDownLatch advertisingIdLatch,
      final boolean shouldRecordScreenViews,
      final boolean trackAttributionInformation,
      final boolean trackDeepLinks,
      BooleanPreference optOut,
      Crypto crypto,
      @NonNull List<Middleware> sourceMiddleware,
      @NonNull Map<String, List<Middleware>> destinationMiddleware,
      @NonNull final ValueMap defaultProjectSettings,
      @NonNull Lifecycle lifecycle,
      boolean nanosecondTimestamps) {
    this.application = application;
    this.networkExecutor = networkExecutor;
    this.stats = stats;
    this.traitsCache = traitsCache;
    this.analyticsContext = analyticsContext;
    this.defaultOptions = defaultOptions;
    this.logger = logger;
    this.tag = tag;
    this.client = client;
    this.cartographer = cartographer;
    this.projectSettingsCache = projectSettingsCache;
    this.writeKey = writeKey;
    this.flushQueueSize = flushQueueSize;
    this.flushIntervalInMillis = flushIntervalInMillis;
    this.sessionTimeoutSeconds = sessionTimeoutSeconds;
    this.advertisingIdLatch = advertisingIdLatch;
    this.optOut = optOut;
    this.factories = factories;
    this.analyticsExecutor = analyticsExecutor;
    this.crypto = crypto;
    this.sourceMiddleware = sourceMiddleware;
    this.destinationMiddleware = destinationMiddleware;
    this.lifecycle = lifecycle;
    this.nanosecondTimestamps = nanosecondTimestamps;

    namespaceSharedPreferences();

    analyticsExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            if (!defaultProjectSettings.containsKey("integrations")) {
              defaultProjectSettings.put("integrations", new ValueMap());
            }
            if (!defaultProjectSettings.getValueMap("integrations").containsKey("Freshpaint")) {
              defaultProjectSettings.getValueMap("integrations").put("Freshpaint", new ValueMap());
            }
            if (!defaultProjectSettings
                .getValueMap("integrations")
                .getValueMap("Freshpaint")
                .containsKey("apiKey")) {
              defaultProjectSettings
                  .getValueMap("integrations")
                  .getValueMap("Freshpaint")
                  .putValue("apiKey", Freshpaint.this.writeKey);
            }
            projectSettings = ProjectSettings.create(defaultProjectSettings);
            HANDLER.post(
                new Runnable() {
                  @Override
                  public void run() {
                    performInitializeIntegrations(projectSettings);
                  }
                });
          }
        });

    activityLifecycleCallback =
        new AnalyticsActivityLifecycleCallbacks.Builder()
            .analytics(this)
            .analyticsExecutor(analyticsExecutor)
            .shouldTrackApplicationLifecycleEvents(shouldTrackApplicationLifecycleEvents)
            .trackAttributionInformation(trackAttributionInformation)
            .trackDeepLinks(trackDeepLinks)
            .shouldRecordScreenViews(shouldRecordScreenViews)
            .packageInfo(getPackageInfo(application))
            .build();

    application.registerActivityLifecycleCallbacks(activityLifecycleCallback);
    lifecycle.addObserver(activityLifecycleCallback);
  }

  @Private
  void trackAttributionInformation() {
    // Freshpaint doesn't support tracking attirbution information.
    return;
  }

  @Private
  void trackApplicationLifecycleEvents() {
    // Get the current version.
    PackageInfo packageInfo = getPackageInfo(application);
    String currentVersion = packageInfo.versionName;
    int currentBuild = packageInfo.versionCode;

    // Get the previous recorded version.
    SharedPreferences sharedPreferences = Utils.getFreshpaintSharedPreferences(application, tag);
    String previousVersion = sharedPreferences.getString(VERSION_KEY, null);
    int previousBuild = sharedPreferences.getInt(BUILD_KEY, -1);

    // Check and track Application Installed or Application Updated.
    if (previousBuild == -1) {
      track(
          "Application Installed",
          new Properties() //
              .putValue(VERSION_KEY, currentVersion)
              .putValue(BUILD_KEY, String.valueOf(currentBuild)));
    } else if (currentBuild != previousBuild) {
      track(
          "Application Updated",
          new Properties() //
              .putValue(VERSION_KEY, currentVersion)
              .putValue(BUILD_KEY, String.valueOf(currentBuild))
              .putValue("previous_" + VERSION_KEY, previousVersion)
              .putValue("previous_" + BUILD_KEY, String.valueOf(previousBuild)));
    }

    // Update the recorded version.
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putString(VERSION_KEY, currentVersion);
    editor.putInt(BUILD_KEY, currentBuild);
    editor.apply();
  }

  static PackageInfo getPackageInfo(Context context) {
    PackageManager packageManager = context.getPackageManager();
    try {
      return packageManager.getPackageInfo(context.getPackageName(), 0);
    } catch (PackageManager.NameNotFoundException e) {
      throw new AssertionError("Package not found: " + context.getPackageName());
    }
  }

  @Private
  void recordScreenViews(Activity activity) {
    PackageManager packageManager = activity.getPackageManager();
    try {
      ActivityInfo info =
          packageManager.getActivityInfo(activity.getComponentName(), PackageManager.GET_META_DATA);
      CharSequence activityLabel = info.loadLabel(packageManager);
      screen(activityLabel.toString());
    } catch (PackageManager.NameNotFoundException e) {
      throw new AssertionError("Activity Not Found: " + e.toString());
    } catch (Exception e) {
      logger.error(e, "Unable to track screen view for %s", activity.toString());
    }
  }

  @Private
  void runOnMainThread(final IntegrationOperation operation) {
    if (shutdown) {
      return;
    }
    analyticsExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            HANDLER.post(
                new Runnable() {
                  @Override
                  public void run() {
                    performRun(operation);
                  }
                });
          }
        });
  }

  // Analytics API

  /**
   * @see #identify(String, Traits, Options)
   */
  public void identify(@NonNull String userId) {
    identify(userId, null, null);
  }

  /**
   * @see #identify(String, Traits, Options)
   */
  public void identify(@NonNull Traits traits) {
    identify(null, traits, null);
  }

  /**
   * Identify lets you tie one of your users and their actions to a recognizable {@code userId}. It
   * also lets you record {@code traits} about the user, like their email, name, account type, etc.
   *
   * <p>Traits and userId will be automatically cached and available on future sessions for the same
   * user. To update a trait on the server, call identify with the same user id (or null). You can
   * also use {@link #identify(Traits)} for this purpose.
   *
   * @param userId Unique identifier which you recognize a user by in your own database. If this is
   *     null or empty, any previous id we have (could be the anonymous id) will be used.
   * @param newTraits Traits about the user.
   * @param options To configure the call, these override the defaultOptions, to extend use
   *     #getDefaultOptions()
   * @throws IllegalArgumentException if both {@code userId} and {@code newTraits} are not provided
   * @see <a
   *     href="https://docs.freshpaint.io/getting-started/building-out-your-account/setting-up-identify">Identify
   *     Documentation</a>
   */
  public void identify(
      final @Nullable String userId,
      final @Nullable Traits newTraits,
      final @Nullable Options options) {
    assertNotShutdown();
    if (Utils.isNullOrEmpty(userId) && Utils.isNullOrEmpty(newTraits)) {
      throw new IllegalArgumentException("Either userId or some traits must be provided.");
    }

    analyticsExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            Traits traits = traitsCache.get();
            if (!Utils.isNullOrEmpty(userId)) {
              traits.putUserId(userId);
            }
            if (!Utils.isNullOrEmpty(newTraits)) {
              traits.putAll(newTraits);
            }

            traitsCache.set(traits); // Save the new traits
            analyticsContext.setTraits(traits); // Update the references

            IdentifyPayload.Builder builder =
                new IdentifyPayload.Builder().traits(traitsCache.get());
            fillAndEnqueue(builder, options);
          }
        });
  }

  /**
   * @see #group(String, Traits, Options)
   */
  public void group(@NonNull String groupId) {
    group(groupId, null, null);
  }

  /**
   * @see #group(String, Traits, Options)
   */
  public void group(@NonNull String groupId, @Nullable Traits traits) {
    group(groupId, traits, null);
  }

  /**
   * The group method lets you associate a user with a group. It also lets you record custom traits
   * about the group, like industry or number of employees.
   *
   * <p>If you've called {@link #identify(String, Traits, Options)} before, this will automatically
   * remember the userId. If not, it will fall back to use the anonymousId instead.
   *
   * @param groupId Unique identifier which you recognize a group by in your own database. Must not
   *     be null or empty.
   * @param options To configure the call, these override the defaultOptions, to extend use
   *     #getDefaultOptions()
   * @throws IllegalArgumentException if groupId is null or an empty string.
   * @see <a href="https://docs.freshpaint.io/developer-docs/freshpaint-sdk-reference#group">Group
   *     Documentation</a>
   */
  public void group(
      @NonNull final String groupId,
      @Nullable final Traits groupTraits,
      @Nullable final Options options) {
    assertNotShutdown();
    if (Utils.isNullOrEmpty(groupId)) {
      throw new IllegalArgumentException("groupId must not be null or empty.");
    }

    analyticsExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            final Traits finalGroupTraits;
            if (groupTraits == null) {
              finalGroupTraits = new Traits();
            } else {
              finalGroupTraits = groupTraits;
            }

            GroupPayload.Builder builder =
                new GroupPayload.Builder().groupId(groupId).traits(finalGroupTraits);
            fillAndEnqueue(builder, options);
          }
        });
  }

  /**
   * @see #track(String, Properties, Options)
   */
  public void track(@NonNull String event) {
    track(event, null, null);
  }

  /**
   * @see #track(String, Properties, Options)
   */
  public void track(@NonNull String event, @Nullable Properties properties) {
    track(event, properties, null);
  }

  /**
   * The track method is how you record any actions your users perform. Each action is known by a
   * name, like 'Purchased a T-Shirt'. You can also record properties specific to those actions. For
   * example a 'Purchased a Shirt' event might have properties like revenue or size.
   *
   * @param event Name of the event. Must not be null or empty.
   * @param properties {@link Properties} to add extra information to this call.
   * @param options To configure the call, these override the defaultOptions, to extend use
   *     #getDefaultOptions()
   * @throws IllegalArgumentException if event name is null or an empty string.
   * @see <a href="https://docs.freshpaint.io/developer-docs/freshpaint-sdk-reference#track">Track
   *     Documentation</a>
   */
  public void track(
      final @NonNull String event,
      final @Nullable Properties properties,
      @Nullable final Options options) {
    assertNotShutdown();
    if (Utils.isNullOrEmpty(event)) {
      throw new IllegalArgumentException("event must not be null or empty.");
    }

    analyticsExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            final Properties finalProperties;
            if (properties == null) {
              finalProperties = EMPTY_PROPERTIES;
            } else {
              finalProperties = properties;
            }

            TrackPayload.Builder builder =
                new TrackPayload.Builder().event(event).properties(finalProperties);
            fillAndEnqueue(builder, options);
          }
        });
  }

  /**
   * @see #screen(String, String, Properties, Options)
   * @deprecated Use {@link #screen(String)} instead.
   */
  @Deprecated
  public void screen(@Nullable String category, @Nullable String name) {
    screen(category, name, null, null);
  }

  /**
   * @see #screen(String, String, Properties, Options)
   * @deprecated Use {@link #screen(String, Properties)} instead.
   */
  @Deprecated
  public void screen(
      @Nullable String category, @Nullable String name, @Nullable Properties properties) {
    screen(category, name, properties, null);
  }

  /**
   * @see #screen(String, String, Properties, Options)
   */
  public void screen(@Nullable String name) {
    screen(null, name, null, null);
  }

  /**
   * @see #screen(String, String, Properties, Options)
   */
  public void screen(@Nullable String name, @Nullable Properties properties) {
    screen(null, name, properties, null);
  }

  /**
   * The screen methods let your record whenever a user sees a screen of your mobile app, and attach
   * a name, category or properties to the screen. Either category or name must be provided.
   *
   * @param category A category to describe the screen. Deprecated.
   * @param name A name for the screen.
   * @param properties {@link Properties} to add extra information to this call.
   * @param options To configure the call, these override the defaultOptions, to extend use
   *     #getDefaultOptions()
   */
  public void screen(
      @Nullable final String category,
      @Nullable final String name,
      @Nullable final Properties properties,
      @Nullable final Options options) {
    assertNotShutdown();
    if (Utils.isNullOrEmpty(category) && Utils.isNullOrEmpty(name)) {
      throw new IllegalArgumentException("either category or name must be provided.");
    }

    analyticsExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            final Properties finalProperties;
            if (properties == null) {
              finalProperties = EMPTY_PROPERTIES;
            } else {
              finalProperties = properties;
            }

            //noinspection deprecation
            ScreenPayload.Builder builder =
                new ScreenPayload.Builder()
                    .name(name)
                    .category(category)
                    .properties(finalProperties);
            fillAndEnqueue(builder, options);
          }
        });
  }

  /**
   * @see #alias(String, Options)
   */
  public void alias(@NonNull String newId) {
    alias(newId, null);
  }

  /**
   * The alias method is used to merge two user identities, effectively connecting two sets of user
   * data as one. This is an advanced method, but it is required to manage user identities
   * successfully in some of our integrations.
   *
   * <p>Usage:
   *
   * <pre> <code>
   *   analytics.track("user did something");
   *   analytics.alias(newId);
   *   analytics.identify(newId);
   * </code> </pre>
   *
   * @param newId The new ID you want to alias the existing ID to. The existing ID will be either
   *     the previousId if you have called identify, or the anonymous ID.
   * @param options To configure the call, these override the defaultOptions, to extend use
   *     #getDefaultOptions()
   * @throws IllegalArgumentException if newId is null or empty
   * @see <a href="https://docs.freshpaint.io/developer-docs/freshpaint-sdk-reference#alias">Alias
   *     Documentation</a>
   */
  public void alias(final @NonNull String newId, final @Nullable Options options) {
    assertNotShutdown();
    if (Utils.isNullOrEmpty(newId)) {
      throw new IllegalArgumentException("newId must not be null or empty.");
    }

    analyticsExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            AliasPayload.Builder builder =
                new AliasPayload.Builder()
                    .userId(newId)
                    .previousId(analyticsContext.traits().currentId());
            fillAndEnqueue(builder, options);
          }
        });
  }

  private void waitForAdvertisingId() {
    try {
      advertisingIdLatch.await(15, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      logger.error(e, "Thread interrupted while waiting for advertising ID.");
    }
    if (advertisingIdLatch.getCount() == 1) {
      logger.debug(
          "Advertising ID may not be collected because the API did not respond within 15 seconds.");
    }
  }

  @Private
  void fillAndEnqueue(BasePayload.Builder<?, ?> builder, Options options) {
    waitForAdvertisingId();

    // TODO (major version change) -> do not override, merge it with defaultOptions
    final Options finalOptions;
    if (options == null) {
      finalOptions = defaultOptions;
    } else {
      finalOptions = options;
    }

    // Create a new working copy
    AnalyticsContext contextCopy =
        new AnalyticsContext(new LinkedHashMap<>(analyticsContext.size()));
    contextCopy.putAll(analyticsContext);
    contextCopy.putAll(finalOptions.context());
    contextCopy = contextCopy.unmodifiableCopy();

    builder.context(contextCopy);
    builder.anonymousId(contextCopy.traits().anonymousId());
    builder.integrations(finalOptions.integrations());
    builder.nanosecondTimestamps(nanosecondTimestamps);
    String cachedUserId = contextCopy.traits().userId();
    if (!builder.isUserIdSet() && !Utils.isNullOrEmpty(cachedUserId)) {
      // userId is not set, retrieve from cached traits and set for payload
      builder.userId(cachedUserId);
    }
    enqueue(builder.build());
  }

  void enqueue(BasePayload payload) {
    if (optOut.get()) {
      return;
    }
    logger.verbose("Created payload %s.", payload);
    Middleware.Chain chain =
        new MiddlewareChainRunner(
            0,
            payload,
            sourceMiddleware,
            new Middleware.Callback() {
              @Override
              public void invoke(BasePayload payload) {
                run(payload);
              }
            });
    chain.proceed(payload);
  }

  void run(BasePayload payload) {
    logger.verbose("Running payload %s.", payload);
    final IntegrationOperation operation =
        IntegrationOperation.freshpaintEvent(payload, destinationMiddleware);
    HANDLER.post(
        new Runnable() {
          @Override
          public void run() {
            performRun(operation);
          }
        });
  }

  /**
   * Asynchronously flushes all messages in the queue to the server, and tells bundled integrations
   * to do the same.
   */
  public void flush() {
    if (shutdown) {
      throw new IllegalStateException("Cannot enqueue messages after client is shutdown.");
    }
    runOnMainThread(IntegrationOperation.FLUSH);
  }

  /** Get the {@link AnalyticsContext} used by this instance. */
  @SuppressWarnings("UnusedDeclaration")
  public AnalyticsContext getAnalyticsContext() {
    // TODO (major version change) hide internals (don't give out working copy), expose a better API
    //  for modifying the global context
    return analyticsContext;
  }

  /** Get a copy of the default {@link Options} used by this instance */
  public Options getDefaultOptions() {
    return new Options(defaultOptions.integrations(), defaultOptions.context());
  }

  /** Creates a {@link StatsSnapshot} of the current stats for this instance. */
  public StatsSnapshot getSnapshot() {
    return stats.createSnapshot();
  }

  /** Return the {@link Application} used to create this instance. */
  public Application getApplication() {
    return application;
  }

  /**
   * Return the {@link LogLevel} for this instance.
   *
   * @deprecated This will be removed in a future release.
   */
  @Deprecated
  public LogLevel getLogLevel() {
    return logger.logLevel;
  }

  /**
   * Return the {@link Logger} instance used by this client.
   *
   * @deprecated This will be removed in a future release.
   */
  @Deprecated
  public Logger getLogger() {
    return logger;
  }

  /** Return a new {@link Logger} with the given sub-tag. */
  public Logger logger(String tag) {
    return logger.subLog(tag);
  }

  /**
   * Logs out the current user by clearing any information, including traits and user id.
   *
   * @deprecated Use {@link #reset()} instead
   */
  @Deprecated
  public void logout() {
    reset();
  }

  /**
   * Resets the analytics client by clearing any stored information about the user. Events queued on
   * disk are not cleared, and will be uploaded at a later time. Preserves BUILD and VERSION values.
   */
  public void reset() {
    SharedPreferences sharedPreferences = Utils.getFreshpaintSharedPreferences(application, tag);
    // LIB-1578: only remove traits, preserve BUILD and VERSION keys in order to to fix over-sending
    // of 'Application Installed' events and under-sending of 'Application Updated' events
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.remove(TRAITS_KEY + "-" + tag);
    editor.apply();

    traitsCache.delete();
    traitsCache.set(Traits.create());
    analyticsContext.setTraits(traitsCache.get());
    runOnMainThread(IntegrationOperation.RESET);
  }

  /**
   * Set the opt-out status for the current device and analytics client combination. This flag is
   * persisted across device reboots, so you can simply call this once during your application (such
   * as in a screen where a user can opt out of analytics tracking).
   */
  public void optOut(boolean optOut) {
    this.optOut.set(optOut);
  }

  /**
   * Register to be notified when a bundled integration is ready.
   *
   * <p>In most cases, integrations would have already been initialized, and the callback will be
   * invoked fairly quickly. However there may be a latency the first time the app is launched, and
   * we don't have settings for bundled integrations yet. This is compounded if the user is offline
   * on the first run.
   *
   * <p>You can only register for one callback per integration at a time, and passing in a {@code
   * callback} will remove the previous callback for that integration.
   *
   * <p>Usage:
   *
   * <pre> <code>
   *   analytics.onIntegrationReady("Amplitude", new Callback() {
   *     {@literal @}Override public void onIntegrationReady(Object instance) {
   *       Amplitude.enableLocationListening();
   *     }
   *   });
   *   analytics.onIntegrationReady("Mixpanel", new Callback<>() {
   *     {@literal @}Override public void onIntegrationReady(MixpanelAPI mixpanel) {
   *       mixpanel.clearSuperProperties();
   *     }
   *   })*
   * </code> </pre>
   */
  public <T> void onIntegrationReady(final String key, final Callback<T> callback) {
    if (Utils.isNullOrEmpty(key)) {
      throw new IllegalArgumentException("key cannot be null or empty.");
    }

    analyticsExecutor.submit(
        new Runnable() {
          @Override
          public void run() {
            HANDLER.post(
                new Runnable() {
                  @Override
                  public void run() {
                    performCallback(key, callback);
                  }
                });
          }
        });
  }

  /**
   * @deprecated Use {@link #onIntegrationReady(String, Callback)} instead.
   */
  @Deprecated
  public void onIntegrationReady(
      @SuppressWarnings("deprecation") BundledIntegration integration, Callback callback) {
    if (integration == null) {
      throw new IllegalArgumentException("integration cannot be null");
    }
    onIntegrationReady(integration.key, callback);
  }

  /**
   * @deprecated
   */
  @Deprecated
  public enum BundledIntegration {
    AMPLITUDE("Amplitude"),
    APPS_FLYER("AppsFlyer"),
    APPTIMIZE("Apptimize"),
    BUGSNAG("Bugsnag"),
    COUNTLY("Countly"),
    CRITTERCISM("Crittercism"),
    FLURRY("Flurry"),
    GOOGLE_ANALYTICS("Google Analytics"),
    KAHUNA("Kahuna"),
    LEANPLUM("Leanplum"),
    LOCALYTICS("Localytics"),
    MIXPANEL("Mixpanel"),
    QUANTCAST("Quantcast"),
    TAPLYTICS("Taplytics"),
    TAPSTREAM("Tapstream"),
    UXCAM("UXCam");

    /** The key that identifies this integration in our API. */
    final String key;

    BundledIntegration(String key) {
      this.key = key;
    }
  }

  /**
   * Stops this instance from accepting further requests. In-flight events may not be uploaded right
   * away.
   */
  public void shutdown() {
    if (this == singleton) {
      throw new UnsupportedOperationException("Default singleton instance cannot be shutdown.");
    }
    if (shutdown) {
      return;
    }
    application.unregisterActivityLifecycleCallbacks(activityLifecycleCallback);
    lifecycle.removeObserver(activityLifecycleCallback);
    // Only supplied by us for testing, so it's ok to shut it down. If we were to make this public,
    // we'll have to add a check similar to that of AnalyticsNetworkExecutorService below.
    analyticsExecutor.shutdown();
    if (networkExecutor instanceof Utils.AnalyticsNetworkExecutorService) {
      networkExecutor.shutdown();
    }
    stats.shutdown();
    shutdown = true;
    synchronized (INSTANCES) {
      INSTANCES.remove(tag);
    }
  }

  private void assertNotShutdown() {
    if (shutdown) {
      throw new IllegalStateException("Cannot enqueue messages after client is shutdown.");
    }
  }

  /** Controls the level of logging. */
  public enum LogLevel {
    /** No logging. */
    NONE,
    /** Log exceptions only. */
    INFO,
    /** Log exceptions and print debug output. */
    DEBUG,
    /**
     * Log exceptions and print debug output.
     *
     * @deprecated Use {@link LogLevel#DEBUG} instead.
     */
    @Deprecated
    BASIC,
    /** Same as {@link LogLevel#DEBUG}, and log transformations in bundled integrations. */
    VERBOSE;

    public boolean log() {
      return this != NONE;
    }
  }

  /**
   * A callback interface that is invoked when the Analytics client initializes bundled
   * integrations.
   */
  public interface Callback<T> {

    /**
     * This method will be invoked once for each callback.
     *
     * @param instance The underlying instance that has been initialized with the settings from
     *     freshpaint.
     */
    void onReady(T instance);
  }

  /** Fluent API for creating {@link Freshpaint} instances. */
  public static class Builder {

    private final Application application;
    private String writeKey;
    private boolean collectDeviceID = Utils.DEFAULT_COLLECT_DEVICE_ID;
    private int flushQueueSize = Utils.DEFAULT_FLUSH_QUEUE_SIZE;
    private long flushIntervalInMillis = Utils.DEFAULT_FLUSH_INTERVAL;
    private Options defaultOptions;
    private int sessionTimeoutSeconds = Utils.DEFAULT_SESSION_TIMEOUT_SECONDS;
    private String tag;
    private LogLevel logLevel;
    private ExecutorService networkExecutor;
    private ExecutorService executor;
    private ConnectionFactory connectionFactory;
    private final List<Integration.Factory> factories = new ArrayList<>();
    private List<Middleware> sourceMiddleware;
    private Map<String, List<Middleware>> destinationMiddleware;
    private boolean trackApplicationLifecycleEvents = false;
    private boolean recordScreenViews = false;
    private boolean trackAttributionInformation = false;
    private boolean trackDeepLinks = false;
    private boolean nanosecondTimestamps = false;
    private Crypto crypto;
    private ValueMap defaultProjectSettings = new ValueMap();

    /** Start building a new {@link Freshpaint} instance. */
    public Builder(Context context, String writeKey) {
      if (context == null) {
        throw new IllegalArgumentException("Context must not be null.");
      }
      if (!Utils.hasPermission(context, Manifest.permission.INTERNET)) {
        throw new IllegalArgumentException("INTERNET permission is required.");
      }
      application = (Application) context.getApplicationContext();
      if (application == null) {
        throw new IllegalArgumentException("Application context must not be null.");
      }

      if (Utils.isNullOrEmpty(writeKey)) {
        throw new IllegalArgumentException("writeKey must not be null or empty.");
      }
      this.writeKey = writeKey;
    }

    /**
     * Set the queue size at which the client should flush events. The client will automatically
     * flush events to Freshpaint when the queue reaches {@code flushQueueSize}.
     *
     * @throws IllegalArgumentException if the flushQueueSize is less than or equal to zero.
     */
    public Builder flushQueueSize(int flushQueueSize) {
      if (flushQueueSize <= 0) {
        throw new IllegalArgumentException("flushQueueSize must be greater than or equal to zero.");
      }
      // 250 is a reasonably high number to trigger queue size flushes.
      // The queue may go over this size (as much as 1000), but you should flush much before then.
      if (flushQueueSize > 250) {
        throw new IllegalArgumentException("flushQueueSize must be less than or equal to 250.");
      }
      this.flushQueueSize = flushQueueSize;
      return this;
    }

    /**
     * Set the interval at which the client should flush events. The client will automatically flush
     * events to Freshpaint every {@code flushInterval} duration, regardless of {@code
     * flushQueueSize}.
     *
     * @throws IllegalArgumentException if the flushInterval is less than or equal to zero.
     */
    public Builder flushInterval(long flushInterval, TimeUnit timeUnit) {
      if (timeUnit == null) {
        throw new IllegalArgumentException("timeUnit must not be null.");
      }
      if (flushInterval <= 0) {
        throw new IllegalArgumentException("flushInterval must be greater than zero.");
      }
      this.flushIntervalInMillis = timeUnit.toMillis(flushInterval);
      return this;
    }

    /**
     * Enable or disable collection of {@link android.provider.Settings.Secure#ANDROID_ID}, {@link
     * android.os.Build#SERIAL} or the Telephony Identifier retrieved via TelephonyManager as
     * available. Collection of the device identifier is enabled by default.
     */
    public Builder collectDeviceId(boolean collect) {
      this.collectDeviceID = collect;
      return this;
    }

    /**
     * Set some default options for all calls. This will only be used to figure out which
     * integrations should be enabled or not for actions by default.
     *
     * @see Options
     */
    public Builder defaultOptions(Options defaultOptions) {
      if (defaultOptions == null) {
        throw new IllegalArgumentException("defaultOptions must not be null.");
      }
      // Make a defensive copy
      this.defaultOptions = new Options();
      for (Map.Entry<String, Object> entry : defaultOptions.integrations().entrySet()) {
        if (entry.getValue() instanceof Boolean) {
          this.defaultOptions.setIntegration(entry.getKey(), (Boolean) entry.getValue());
        } else {
          // A value is provided for an integration, and it is not a boolean. Assume it is enabled.
          this.defaultOptions.setIntegration(entry.getKey(), true);
        }
      }
      return this;
    }

    public Builder sessionTimeoutSeconds(int timeoutSeconds) {
      if (timeoutSeconds < 0) {
        throw new IllegalArgumentException(
            "Timeout seconds must be greater than or equal to zero.");
      }

      this.sessionTimeoutSeconds = timeoutSeconds;

      return this;
    }

    /**
     * Set a tag for this instance. The tag is used to generate keys for caching. By default the
     * writeKey is used. You may want to specify an alternative one, if you want the instances with
     * the same writeKey to share different caches (you probably do).
     *
     * @throws IllegalArgumentException if the tag is null or empty.
     */
    public Builder tag(String tag) {
      if (Utils.isNullOrEmpty(tag)) {
        throw new IllegalArgumentException("tag must not be null or empty.");
      }
      this.tag = tag;
      return this;
    }

    /** Set a {@link LogLevel} for this instance. */
    public Builder logLevel(LogLevel logLevel) {
      if (logLevel == null) {
        throw new IllegalArgumentException("LogLevel must not be null.");
      }
      this.logLevel = logLevel;
      return this;
    }

    /**
     * @deprecated As of {@code 3.0.1}, this method does nothing.
     */
    @Deprecated
    public Builder disableBundledIntegrations() {
      return this;
    }

    /**
     * Specify the executor service for making network calls in the background.
     *
     * <p>Note: Calling {@link Freshpaint#shutdown()} will not shutdown supplied executors.
     *
     * <p>Use it with care! http://bit.ly/1JVlA2e
     */
    public Builder networkExecutor(ExecutorService networkExecutor) {
      if (networkExecutor == null) {
        throw new IllegalArgumentException("Executor service must not be null.");
      }
      this.networkExecutor = networkExecutor;
      return this;
    }

    /**
     * Specify the connection factory for customizing how connections are created.
     *
     * <p>This is a beta API, and might be changed in the future. Use it with care!
     * http://bit.ly/1JVlA2e
     */
    public Builder connectionFactory(ConnectionFactory connectionFactory) {
      if (connectionFactory == null) {
        throw new IllegalArgumentException("ConnectionFactory must not be null.");
      }
      this.connectionFactory = connectionFactory;
      return this;
    }

    /** Specify the crypto interface for customizing how data is stored at rest. */
    public Builder crypto(Crypto crypto) {
      if (crypto == null) {
        throw new IllegalArgumentException("Crypto must not be null.");
      }
      this.crypto = crypto;
      return this;
    }

    /** TODO: docs */
    public Builder use(Integration.Factory factory) {
      if (factory == null) {
        throw new IllegalArgumentException("Factory must not be null.");
      }
      factories.add(factory);
      return this;
    }

    /**
     * Automatically track application lifecycle events, including "Application Installed",
     * "Application Updated" and "Application Opened".
     */
    public Builder trackApplicationLifecycleEvents() {
      this.trackApplicationLifecycleEvents = true;
      return this;
    }

    /** Automatically record screen calls when activities are created. */
    public Builder recordScreenViews() {
      this.recordScreenViews = true;
      return this;
    }

    /** Automatically track attribution information from enabled providers. */
    public Builder trackAttributionInformation() {
      this.trackAttributionInformation = true;
      return this;
    }

    /** Automatically track deep links as part of the screen call. */
    public Builder trackDeepLinks() {
      this.trackDeepLinks = true;
      return this;
    }

    /**
     * @see #useSourceMiddleware(Middleware)
     * @deprecated Use {@link #useSourceMiddleware(Middleware)} instead.
     */
    @Deprecated
    public Builder middleware(Middleware middleware) {
      return useSourceMiddleware(middleware);
    }

    /**
     * Add a {@link Middleware} custom source middleware. This will be run before sending to all
     * integrations
     */
    public Builder useSourceMiddleware(Middleware middleware) {
      Utils.assertNotNull(middleware, "middleware");
      if (sourceMiddleware == null) {
        sourceMiddleware = new ArrayList<>();
      }
      if (sourceMiddleware.contains(middleware)) {
        throw new IllegalStateException("Source Middleware is already registered.");
      }
      sourceMiddleware.add(middleware);
      return this;
    }

    /**
     * Add a {@link Middleware} custom destination middleware, for a particular destination. This
     * will be run before sending to the associated destination
     */
    public Builder useDestinationMiddleware(String key, Middleware middleware) {
      if (Utils.isNullOrEmpty(key)) {
        throw new IllegalArgumentException("key must not be null or empty.");
      }
      Utils.assertNotNull(middleware, "middleware");
      if (destinationMiddleware == null) {
        destinationMiddleware = new HashMap<>();
      }
      List<Middleware> middlewareList = destinationMiddleware.get(key);
      if (middlewareList == null) {
        middlewareList = new ArrayList<>();
        destinationMiddleware.put(key, middlewareList);
      }
      if (middlewareList.contains(middleware)) {
        throw new IllegalStateException("Destination Middleware is already registered.");
      }
      middlewareList.add(middleware);
      return this;
    }

    /**
     * Enable the use of nanoseconds timestamps for all payloads. Timestamps will be formatted as
     * yyyy-MM-ddThh:mm:ss.nnnnnnnnnZ Note: This is an experimental feature (and strictly opt-in)
     */
    public Builder experimentalNanosecondTimestamps() {
      this.nanosecondTimestamps = true;
      return this;
    }

    /** Set the default project settings to use, if Freshpaint cannot be reached. */
    public Builder defaultProjectSettings(ValueMap defaultProjectSettings) {
      Utils.assertNotNull(defaultProjectSettings, "defaultProjectSettings");
      this.defaultProjectSettings = defaultProjectSettings;
      return this;
    }

    /**
     * The executor on which payloads are dispatched asynchronously. This is not exposed publicly.
     */
    Builder executor(ExecutorService executor) {
      this.executor = Utils.assertNotNull(executor, "executor");
      return this;
    }

    /** Create a {@link Freshpaint} client. */
    public Freshpaint build() {
      if (Utils.isNullOrEmpty(tag)) {
        tag = writeKey;
      }
      synchronized (INSTANCES) {
        if (INSTANCES.contains(tag)) {
          throw new IllegalStateException(
              "Duplicate analytics client created with tag: "
                  + tag
                  + ". If you want to use multiple Analytics clients, use a different writeKey "
                  + "or set a tag via the builder during construction.");
        }
        INSTANCES.add(tag);
      }

      if (defaultOptions == null) {
        defaultOptions = new Options();
      }
      if (logLevel == null) {
        logLevel = LogLevel.NONE;
      }
      if (networkExecutor == null) {
        networkExecutor = new Utils.AnalyticsNetworkExecutorService();
      }
      if (connectionFactory == null) {
        connectionFactory = new ConnectionFactory();
      }
      if (crypto == null) {
        crypto = Crypto.none();
      }

      final Stats stats = new Stats();
      final Cartographer cartographer = Cartographer.INSTANCE;
      final Client client = new Client(writeKey, connectionFactory);

      ProjectSettings.Cache projectSettingsCache =
          new ProjectSettings.Cache(application, cartographer, tag);

      BooleanPreference optOut =
          new BooleanPreference(
              Utils.getFreshpaintSharedPreferences(application, tag),
              OPT_OUT_PREFERENCE_KEY,
              false);

      Traits.Cache traitsCache = new Traits.Cache(application, cartographer, tag);
      if (!traitsCache.isSet() || traitsCache.get() == null) {
        Traits traits = Traits.create();
        traitsCache.set(traits);
      }

      Logger logger = Logger.with(logLevel);
      AnalyticsContext analyticsContext =
          AnalyticsContext.create(application, traitsCache.get(), collectDeviceID);
      CountDownLatch advertisingIdLatch = new CountDownLatch(1);
      analyticsContext.attachAdvertisingId(application, advertisingIdLatch, logger);

      List<Integration.Factory> factories = new ArrayList<>(1 + this.factories.size());
      factories.add(FreshpaintIntegration.FACTORY);
      factories.addAll(this.factories);

      List<Middleware> srcMiddleware = Utils.immutableCopyOf(this.sourceMiddleware);
      Map<String, List<Middleware>> destMiddleware =
          Utils.isNullOrEmpty(this.destinationMiddleware)
              ? Collections.<String, List<Middleware>>emptyMap()
              : Utils.immutableCopyOf(this.destinationMiddleware);

      ExecutorService executor = this.executor;
      if (executor == null) {
        executor = Executors.newSingleThreadExecutor();
      }
      Lifecycle lifecycle = ProcessLifecycleOwner.get().getLifecycle();
      return new Freshpaint(
          application,
          networkExecutor,
          stats,
          traitsCache,
          analyticsContext,
          defaultOptions,
          logger,
          tag,
          Collections.unmodifiableList(factories),
          client,
          cartographer,
          projectSettingsCache,
          writeKey,
          flushQueueSize,
          flushIntervalInMillis,
          sessionTimeoutSeconds,
          executor,
          trackApplicationLifecycleEvents,
          advertisingIdLatch,
          recordScreenViews,
          trackAttributionInformation,
          trackDeepLinks,
          optOut,
          crypto,
          srcMiddleware,
          destMiddleware,
          defaultProjectSettings,
          lifecycle,
          nanosecondTimestamps);
    }
  }

  void performInitializeIntegrations(ProjectSettings projectSettings) throws AssertionError {
    if (Utils.isNullOrEmpty(projectSettings)) {
      throw new AssertionError("ProjectSettings is empty!");
    }
    ValueMap integrationSettings = projectSettings.integrations();

    integrations = new LinkedHashMap<>(factories.size());
    for (int i = 0; i < factories.size(); i++) {
      if (Utils.isNullOrEmpty(integrationSettings)) {
        logger.debug("Integration settings are empty");
        continue;
      }
      Integration.Factory factory = factories.get(i);
      String key = factory.key();
      if (Utils.isNullOrEmpty(key)) {
        throw new AssertionError("The factory key is empty!");
      }
      ValueMap settings = integrationSettings.getValueMap(key);
      if (Utils.isNullOrEmpty(settings)) {
        logger.debug("Integration %s is not enabled.", key);
        continue;
      }
      Integration integration = factory.create(settings, this);
      if (integration == null) {
        logger.info("Factory %s couldn't create integration.", factory);
      } else {
        integrations.put(key, integration);
        bundledIntegrations.put(key, false);
      }
    }
    factories = null;
  }

  /** Runs the given operation on all integrations. */
  void performRun(IntegrationOperation operation) {
    for (Map.Entry<String, Integration<?>> entry : integrations.entrySet()) {
      String key = entry.getKey();
      long startTime = System.nanoTime();
      operation.run(key, entry.getValue(), projectSettings);
      long endTime = System.nanoTime();
      long durationInMillis = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      stats.dispatchIntegrationOperation(key, durationInMillis);
      logger.debug("Ran %s on integration %s in %d ns.", operation, key, endTime - startTime);
    }
  }

  @Private
  <T> void performCallback(String key, Callback<T> callback) {
    for (Map.Entry<String, Integration<?>> entry : integrations.entrySet()) {
      if (key.equals(entry.getKey())) {
        callback.onReady((T) entry.getValue().getUnderlyingInstance());
        return;
      }
    }
  }

  /**
   * Previously (until version 4.1.7) shared preferences were not namespaced by a tag. This meant
   * that all analytics instances shared the same shared preferences. This migration checks if the
   * namespaced shared preferences instance contains {@code namespaceSharedPreferences: true}. If it
   * does, the migration is already run and does not need to be run again. If it doesn't, it copies
   * the legacy shared preferences mapping into the namespaced shared preferences, and sets
   * namespaceSharedPreferences to false.
   */
  private void namespaceSharedPreferences() {
    SharedPreferences newSharedPreferences = Utils.getFreshpaintSharedPreferences(application, tag);
    BooleanPreference namespaceSharedPreferences =
        new BooleanPreference(newSharedPreferences, "namespaceSharedPreferences", true);

    if (namespaceSharedPreferences.get()) {
      SharedPreferences legacySharedPreferences =
          application.getSharedPreferences("analytics-android", Context.MODE_PRIVATE);
      Utils.copySharedPreferences(legacySharedPreferences, newSharedPreferences);
      namespaceSharedPreferences.set(false);
    }
  }
}
