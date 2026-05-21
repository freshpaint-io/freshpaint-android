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

import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts and persists deep-link attribution data — ad-platform click IDs, UTM parameters, and the
 * Freshpaint click ID — from Intent URI query parameters captured by {@link
 * AnalyticsActivityLifecycleCallbacks#trackDeepLink}.
 *
 * <p>All SharedPreferences keys use the {@code "dl."} prefix to avoid collisions with the Install
 * Referrer namespace ({@code "ir."}).
 *
 * <p>Click IDs persist indefinitely. UTM parameters expire after 24 hours; expiry is evaluated at
 * read time using a caller-supplied {@code now} timestamp to keep the class testable without mocks.
 *
 * <p>All public methods are static and never throw. Instances are not created.
 *
 * <p><b>Naming convention:</b> all ad-platform click IDs are stored and returned with a {@code $}
 * prefix (e.g. {@code $gclid}, {@code $fbclid}). The Freshpaint-native click ID ({@code
 * fp_click_id}) is stored and returned <em>without</em> the {@code $} prefix, matching the Install
 * Referrer convention.
 */
final class DeepLinkAttributionManager {

  // -------------------------------------------------------------------------
  // SharedPreferences key constants — all under "dl." prefix
  // -------------------------------------------------------------------------

  private static final String DL_PREFIX = "dl.";
  private static final String KEY_FP_CLICK_ID = "dl.fp_click_id";

  /** Timestamp written alongside UTM params; used to evaluate the 24-hour expiry window. */
  private static final String KEY_UTM_STORED_AT = "dl.utm_stored_at";

  private static final long UTM_EXPIRY_MS = 24L * 60L * 60L * 1_000L; // 24 hours in ms

  private static final String[] UTM_PARAMS = {
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content"
  };

  private DeepLinkAttributionManager() {}

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /**
   * Parses {@code queryParams} and persists attribution data to {@code prefs}.
   *
   * <p>Uses {@link SharedPreferences.Editor#commit()} (synchronous) so the written values are
   * visible to background threads — in particular the SDK's {@code analyticsExecutor} — that may
   * read {@code prefs} immediately after this method returns on the main thread.
   *
   * <p>Deduplication: if a click ID is already stored with the same value, its {@code
   * _creation_time} is preserved. A changed value resets the creation time to {@code now}.
   *
   * @param queryParams key-value pairs extracted from the Intent URI (empty values already filtered
   *     out by the caller); {@code null} or empty map is a no-op
   * @param prefs the SDK's SharedPreferences instance
   * @param now current time in Unix milliseconds (caller-supplied for testability)
   */
  static void store(@Nullable Map<String, String> queryParams, SharedPreferences prefs, long now) {
    if (queryParams == null || queryParams.isEmpty()) {
      return;
    }

    SharedPreferences.Editor editor = prefs.edit();

    // Ad-platform click IDs
    for (String id : AttributionConstants.CLICK_IDS) {
      String val = queryParams.get(id);
      if (val != null && !val.isEmpty()) {
        putWithDedup(prefs, editor, DL_PREFIX + "$" + id, val, now);
      }
    }

    // Freshpaint click ID (stored without $ prefix, matching Install Referrer convention)
    String fpClickId = queryParams.get("fp_click_id");
    if (fpClickId != null && !fpClickId.isEmpty()) {
      putWithDedup(prefs, editor, KEY_FP_CLICK_ID, fpClickId, now);
    }

    // Google special: gacid → $gclid_campaign_id / $wbraid_campaign_id / $gbraid_campaign_id; see
    // AttributionConstants.routeGacidToGoogleCampaignIds.
    String gacid = queryParams.get("gacid");
    if (AttributionConstants.nonEmpty(gacid)) {
      boolean hasGclid = AttributionConstants.nonEmpty(queryParams.get("gclid"));
      boolean hasWbraid = AttributionConstants.nonEmpty(queryParams.get("wbraid"));
      boolean hasGbraid = AttributionConstants.nonEmpty(queryParams.get("gbraid"));
      AttributionConstants.routeGacidToGoogleCampaignIds(
          gacid,
          hasGclid,
          hasWbraid,
          hasGbraid,
          v -> putWithDedup(prefs, editor, DL_PREFIX + "$gclid_campaign_id", v, now),
          v -> putWithDedup(prefs, editor, DL_PREFIX + "$wbraid_campaign_id", v, now),
          v -> putWithDedup(prefs, editor, DL_PREFIX + "$gbraid_campaign_id", v, now));
    }

    // Facebook special fields
    String adId = queryParams.get("ad_id");
    if (adId != null && !adId.isEmpty()) {
      putWithDedup(prefs, editor, DL_PREFIX + "$fbclid_ad_id", adId, now);
    }
    String adsetId = queryParams.get("adset_id");
    if (adsetId != null && !adsetId.isEmpty()) {
      putWithDedup(prefs, editor, DL_PREFIX + "$fbclid_adset_id", adsetId, now);
    }
    String campaignId = queryParams.get("campaign_id");
    if (campaignId != null && !campaignId.isEmpty()) {
      putWithDedup(prefs, editor, DL_PREFIX + "$fbclid_campaign_id", campaignId, now);
    }

    // UTM params — atomically replaced per deep link. When any UTM param is present, ALL five
    // UTM keys are evaluated: present keys are written, absent keys are explicitly removed. This
    // prevents stale values from a previous deep link mixing with partial updates (e.g. a URL
    // with only utm_source must not re-surface a utm_campaign stored from a different campaign).
    boolean anyUtm = false;
    for (String utmKey : UTM_PARAMS) {
      String val = queryParams.get(utmKey);
      if (val != null && !val.isEmpty()) {
        anyUtm = true;
        break;
      }
    }
    if (anyUtm) {
      for (String utmKey : UTM_PARAMS) {
        String val = queryParams.get(utmKey);
        if (val != null && !val.isEmpty()) {
          editor.putString(DL_PREFIX + "utm." + utmKey, val);
        } else {
          editor.remove(DL_PREFIX + "utm." + utmKey);
        }
      }
      editor.putLong(KEY_UTM_STORED_AT, now);
    }

    editor.commit();
  }

  /**
   * Returns all stored deep-link attribution properties ready to be merged into an event payload.
   *
   * <p>Click IDs are always included if present. UTM parameters are omitted when they were stored
   * more than 24 hours before {@code now}.
   *
   * @param prefs the SDK's SharedPreferences instance
   * @param now current time in Unix milliseconds (caller-supplied for testability)
   * @return attribution key-value pairs; empty map if nothing has been stored
   */
  @NonNull
  static Map<String, Object> getStoredProperties(SharedPreferences prefs, long now) {
    Map<String, Object> result = new LinkedHashMap<>();

    // Ad-platform click IDs (no expiry)
    for (String id : AttributionConstants.CLICK_IDS) {
      addWithCreationTime(prefs, result, DL_PREFIX + "$" + id, "$" + id);
    }

    // Freshpaint click ID
    addWithCreationTime(prefs, result, KEY_FP_CLICK_ID, "fp_click_id");

    // Google special fields
    addWithCreationTime(prefs, result, DL_PREFIX + "$gclid_campaign_id", "$gclid_campaign_id");
    addWithCreationTime(prefs, result, DL_PREFIX + "$wbraid_campaign_id", "$wbraid_campaign_id");
    addWithCreationTime(prefs, result, DL_PREFIX + "$gbraid_campaign_id", "$gbraid_campaign_id");

    // Facebook special fields
    addWithCreationTime(prefs, result, DL_PREFIX + "$fbclid_ad_id", "$fbclid_ad_id");
    addWithCreationTime(prefs, result, DL_PREFIX + "$fbclid_adset_id", "$fbclid_adset_id");
    addWithCreationTime(prefs, result, DL_PREFIX + "$fbclid_campaign_id", "$fbclid_campaign_id");

    // UTM params — only include if stored within the last 24 hours
    long utmStoredAt = prefs.getLong(KEY_UTM_STORED_AT, 0L);
    if (utmStoredAt > 0L && (now - utmStoredAt) <= UTM_EXPIRY_MS) {
      for (String utmKey : UTM_PARAMS) {
        String val = prefs.getString(DL_PREFIX + "utm." + utmKey, null);
        if (val != null) {
          result.put(utmKey, val);
        }
      }
    }

    return result;
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  /**
   * Stores {@code value} under {@code prefKey} and updates {@code prefKey + "_creation_time"} to
   * {@code now} only when the value has changed (deduplication).
   */
  private static void putWithDedup(
      SharedPreferences prefs,
      SharedPreferences.Editor editor,
      String prefKey,
      String value,
      long now) {
    String existing = prefs.getString(prefKey, null);
    editor.putString(prefKey, value);
    if (!value.equals(existing)) {
      editor.putLong(prefKey + "_creation_time", now);
    }
  }

  /**
   * Reads {@code prefKey} from {@code prefs} and, if non-null, adds the value under {@code
   * outputKey} and its creation timestamp under {@code outputKey + "_creation_time"} to {@code
   * result}.
   */
  private static void addWithCreationTime(
      SharedPreferences prefs, Map<String, Object> result, String prefKey, String outputKey) {
    String val = prefs.getString(prefKey, null);
    if (val != null) {
      result.put(outputKey, val);
      long ct = prefs.getLong(prefKey + "_creation_time", 0L);
      if (ct != 0L) {
        result.put(outputKey + "_creation_time", ct);
      }
    }
  }
}
