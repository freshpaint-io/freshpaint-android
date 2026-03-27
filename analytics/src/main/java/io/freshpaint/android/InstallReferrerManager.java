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

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;
import io.freshpaint.android.integrations.Logger;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Collects Google Play Install Referrer data and persists it to {@link SharedPreferences} for
 * merging into the {@code app_install} event payload.
 *
 * <p>The {@code com.android.installreferrer} library is an optional dependency ({@code compileOnly}
 * in the SDK's build.gradle). If absent at runtime, {@link #collectAndStore} returns silently via
 * the {@link NoClassDefFoundError} catch block. All other code paths that reference {@link
 * InstallReferrerClient} are isolated in {@link #doCollect} so the outer class loads cleanly even
 * when the library is missing.
 *
 * <p>All public methods are static and never throw. Instances are not created.
 */
class InstallReferrerManager {

  // -------------------------------------------------------------------------
  // SharedPreferences keys — all prefixed with "ir." to avoid collisions
  // -------------------------------------------------------------------------

  /** Guard flag: set to true once collection completes (success or failure). */
  @VisibleForTesting static final String KEY_IR_COLLECTED = "ir.collected";

  private static final String KEY_INSTALL_REFERRER = "ir.install_referrer";
  private static final String KEY_REFERRER_CLICK_TIMESTAMP = "ir.referrer_click_timestamp";
  private static final String KEY_INSTALL_BEGIN_TIMESTAMP = "ir.install_begin_timestamp";
  private static final String KEY_FP_CLICK_ID = "ir.fp_click_id";
  private static final String IR_PREFIX = "ir.";

  private static final String[] UTM_PARAMS = {
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content"
  };

  private InstallReferrerManager() {}

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /**
   * Connects to the Google Play Install Referrer service, waits up to {@code timeoutMs}
   * milliseconds for a response, and persists the parsed attribution data to {@code prefs}.
   *
   * <p>This method blocks the calling thread for up to {@code timeoutMs} milliseconds and must only
   * be called from a background thread (e.g. the SDK's {@code analyticsExecutor}).
   *
   * <p>If the {@code com.android.installreferrer} library is absent at runtime, or if any exception
   * occurs, the method returns silently — it never throws.
   *
   * <p>Idempotent: a second call is a no-op if {@link #KEY_IR_COLLECTED} is already {@code true}.
   */
  static void collectAndStore(Context context, SharedPreferences prefs, long timeoutMs) {
    collectAndStore(context, prefs, timeoutMs, null);
  }

  /**
   * Same as {@link #collectAndStore(Context, SharedPreferences, long)} but logs failures at {@link
   * Logger.Level#DEBUG} level when {@code logger} is non-null.
   */
  static void collectAndStore(
      Context context, SharedPreferences prefs, long timeoutMs, @Nullable Logger logger) {
    if (prefs.getBoolean(KEY_IR_COLLECTED, false)) {
      return;
    }
    try {
      // InstallReferrerClient.newBuilder() is called here (not inside doCollect) so the built
      // client can be injected for testing via doCollect(). If the installreferrer library is
      // absent at runtime, newBuilder() throws NoClassDefFoundError, which is caught below.
      // InstallReferrerManager itself loads cleanly because class resolution is lazy: the
      // InstallReferrerClient reference in the constant pool is only resolved when this line
      // executes, at which point the catch block is active.
      doCollect(InstallReferrerClient.newBuilder(context).build(), prefs, timeoutMs, logger);
    } catch (InterruptedException e) {
      // Restore interrupt status so the calling executor thread can honour shutdown signals.
      Thread.currentThread().interrupt();
    } catch (Exception | NoClassDefFoundError e) {
      // Optional dependency absent, service error, or unexpected failure — skip silently.
      if (logger != null) {
        logger.debug("Install Referrer collection failed: %s", e.toString());
      }
    } finally {
      prefs.edit().putBoolean(KEY_IR_COLLECTED, true).apply();
    }
  }

  /**
   * Returns a map of all stored Install Referrer properties from {@code prefs}, ready to be merged
   * into the {@code app_install} event payload. Returns an empty map if collection has not yet
   * completed or produced no data.
   */
  static Map<String, Object> getStoredProperties(SharedPreferences prefs) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (!prefs.getBoolean(KEY_IR_COLLECTED, false)) {
      return result;
    }

    // Raw referrer string
    String rawReferrer = prefs.getString(KEY_INSTALL_REFERRER, null);
    if (rawReferrer != null) {
      result.put("install_referrer", rawReferrer);
    }

    // Timestamps (omit if zero or negative — organic installs have no click timestamp;
    // negative values are error sentinels from the API and should not be stored)
    long referrerClickTs = prefs.getLong(KEY_REFERRER_CLICK_TIMESTAMP, 0L);
    if (referrerClickTs > 0L) {
      result.put("referrer_click_timestamp", referrerClickTs);
    }
    long installBeginTs = prefs.getLong(KEY_INSTALL_BEGIN_TIMESTAMP, 0L);
    if (installBeginTs > 0L) {
      result.put("install_begin_timestamp", installBeginTs);
    }

    // UTM params
    for (String utmKey : UTM_PARAMS) {
      String val = prefs.getString(IR_PREFIX + utmKey, null);
      if (val != null) {
        result.put(utmKey, val);
      }
    }

    // Freshpaint click ID
    String fpClickId = prefs.getString(KEY_FP_CLICK_ID, null);
    if (fpClickId != null) {
      result.put("fp_click_id", fpClickId);
    }

    // 24 ad-platform click IDs
    for (String id : AttributionConstants.CLICK_IDS) {
      String val = prefs.getString(IR_PREFIX + "$" + id, null);
      if (val != null) {
        result.put("$" + id, val);
        long creationTime = prefs.getLong(IR_PREFIX + "$" + id + "_creation_time", 0L);
        if (creationTime != 0L) {
          result.put("$" + id + "_creation_time", creationTime);
        }
      }
    }

    // Google special field
    String gclidCampaignId = prefs.getString(IR_PREFIX + "$gclid_campaign_id", null);
    if (gclidCampaignId != null) {
      result.put("$gclid_campaign_id", gclidCampaignId);
    }

    // Facebook special fields
    String fbclidAdId = prefs.getString(IR_PREFIX + "$fbclid_ad_id", null);
    if (fbclidAdId != null) {
      result.put("$fbclid_ad_id", fbclidAdId);
    }
    String fbclidAdsetId = prefs.getString(IR_PREFIX + "$fbclid_adset_id", null);
    if (fbclidAdsetId != null) {
      result.put("$fbclid_adset_id", fbclidAdsetId);
    }
    String fbclidCampaignId = prefs.getString(IR_PREFIX + "$fbclid_campaign_id", null);
    if (fbclidCampaignId != null) {
      result.put("$fbclid_campaign_id", fbclidCampaignId);
    }

    return result;
  }

  // -------------------------------------------------------------------------
  // Internal — all InstallReferrerClient references are confined here
  // -------------------------------------------------------------------------

  /**
   * Performs the actual Install Referrer connection and data collection. All references to {@link
   * InstallReferrerClient}, {@link InstallReferrerStateListener}, and {@link ReferrerDetails} are
   * inside this method so that the containing class loads cleanly when the library is absent.
   *
   * <p>Package-private and {@link VisibleForTesting} so unit tests can inject a pre-built client
   * without going through {@link InstallReferrerClient#newBuilder}.
   */
  @VisibleForTesting
  static void doCollect(
      final InstallReferrerClient client, final SharedPreferences prefs, long timeoutMs)
      throws InterruptedException {
    doCollect(client, prefs, timeoutMs, null);
  }

  @VisibleForTesting
  static void doCollect(
      final InstallReferrerClient client,
      final SharedPreferences prefs,
      long timeoutMs,
      @Nullable final Logger logger)
      throws InterruptedException {
    final CountDownLatch latch = new CountDownLatch(1);

    client.startConnection(
        new InstallReferrerStateListener() {
          @Override
          public void onInstallReferrerSetupFinished(int responseCode) {
            try {
              if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                try {
                  ReferrerDetails details = client.getInstallReferrer();
                  parseAndStore(details, prefs);
                } catch (Exception e) {
                  // Parse or query failure — skip, leave prefs empty for this run
                  if (logger != null) {
                    logger.debug("Install Referrer parse failed: %s", e.toString());
                  }
                }
              }
              // Non-OK response codes (FEATURE_NOT_SUPPORTED, SERVICE_UNAVAILABLE,
              // DEVELOPER_ERROR): no data to collect; fall through to disconnect.
            } finally {
              try {
                client.endConnection();
              } catch (Exception ignored) {
              }
              latch.countDown();
            }
          }

          @Override
          public void onInstallReferrerServiceDisconnected() {
            // Service disconnected unexpectedly before onInstallReferrerSetupFinished fired.
            // Count down so the await() below doesn't block for the full timeout.
            latch.countDown();
          }
        });

    boolean completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    if (!completed) {
      // Timeout: disconnect defensively. The callback may fire later; endConnection() is
      // safe to call multiple times.
      try {
        client.endConnection();
      } catch (Exception ignored) {
      }
    }
  }

  // -------------------------------------------------------------------------
  // Parsing helpers
  // -------------------------------------------------------------------------

  private static void parseAndStore(ReferrerDetails details, SharedPreferences prefs) {
    String rawReferrer = details.getInstallReferrer();
    long referrerClickTs = details.getReferrerClickTimestampSeconds() * 1000L;
    long installBeginTs = details.getInstallBeginTimestampSeconds() * 1000L;

    SharedPreferences.Editor editor = prefs.edit();

    if (rawReferrer != null && !rawReferrer.isEmpty()) {
      editor.putString(KEY_INSTALL_REFERRER, rawReferrer);
    }
    if (referrerClickTs > 0L) {
      editor.putLong(KEY_REFERRER_CLICK_TIMESTAMP, referrerClickTs);
    }
    if (installBeginTs > 0L) {
      editor.putLong(KEY_INSTALL_BEGIN_TIMESTAMP, installBeginTs);
    }

    Map<String, String> params = parseQueryParams(rawReferrer);
    long now = System.currentTimeMillis();

    // UTM params
    for (String utmKey : UTM_PARAMS) {
      String val = params.get(utmKey);
      if (val != null && !val.isEmpty()) {
        editor.putString(IR_PREFIX + utmKey, val);
      }
    }

    // Freshpaint click ID (stored without $ prefix)
    String fpClickId = params.get("fp_click_id");
    if (fpClickId != null && !fpClickId.isEmpty()) {
      editor.putString(KEY_FP_CLICK_ID, fpClickId);
    }

    // 24 ad-platform click IDs
    for (String id : AttributionConstants.CLICK_IDS) {
      String val = params.get(id);
      if (val != null && !val.isEmpty()) {
        String prefKey = IR_PREFIX + "$" + id;
        String creationTimeKey = IR_PREFIX + "$" + id + "_creation_time";
        // Dedup: if the same value is already stored, preserve the original creation_time.
        String existing = prefs.getString(prefKey, null);
        editor.putString(prefKey, val);
        if (!val.equals(existing)) {
          editor.putLong(creationTimeKey, now);
        }
      }
    }

    // Google special: gacid → $gclid_campaign_id
    String gacid = params.get("gacid");
    if (gacid != null && !gacid.isEmpty()) {
      editor.putString(IR_PREFIX + "$gclid_campaign_id", gacid);
    }

    // Facebook special fields
    String adId = params.get("ad_id");
    if (adId != null && !adId.isEmpty()) {
      editor.putString(IR_PREFIX + "$fbclid_ad_id", adId);
    }
    String adsetId = params.get("adset_id");
    if (adsetId != null && !adsetId.isEmpty()) {
      editor.putString(IR_PREFIX + "$fbclid_adset_id", adsetId);
    }
    String campaignId = params.get("campaign_id");
    if (campaignId != null && !campaignId.isEmpty()) {
      editor.putString(IR_PREFIX + "$fbclid_campaign_id", campaignId);
    }

    editor.apply();
  }

  /**
   * Parses a URL-encoded query string (e.g. {@code "utm_source=google&utm_campaign=sale"}) into a
   * key-value map. Uses pure-Java {@link URLDecoder} — no Android dependencies — so it is
   * exercisable in unit tests without Robolectric.
   *
   * <p>Note: {@link URLDecoder#decode} follows the {@code application/x-www-form-urlencoded}
   * convention and converts {@code +} to a space. The Google Play Install Referrer API uses {@code
   * %20} to encode spaces and does not emit literal {@code +} characters, so this has no effect on
   * values returned by the Play Store. Ad platforms that include literal {@code +} characters in
   * parameter values (not as a space encoding) would have them decoded as spaces; this is not a
   * known issue for the supported ad platforms.
   */
  @VisibleForTesting
  static Map<String, String> parseQueryParams(String referrer) {
    Map<String, String> params = new LinkedHashMap<>();
    if (referrer == null || referrer.isEmpty()) {
      return params;
    }
    try {
      for (String pair : referrer.split("&")) {
        int idx = pair.indexOf('=');
        if (idx <= 0) {
          continue;
        }
        String key = URLDecoder.decode(pair.substring(0, idx), "UTF-8");
        String value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8");
        if (!key.isEmpty()) {
          params.put(key, value);
        }
      }
    } catch (Exception ignored) {
      // Malformed referrer — return whatever was parsed so far
    }
    return params;
  }
}
