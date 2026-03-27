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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extracts and persists deep-link attribution data — 24 ad-platform click IDs, UTM parameters, and
 * the Freshpaint click ID — from Intent URI query parameters captured by {@link
 * AnalyticsActivityLifecycleCallbacks#trackDeepLink}.
 *
 * <p>All SharedPreferences keys use the {@code "dl."} prefix to avoid collisions with the Install
 * Referrer namespace ({@code "ir."} from FRP-44).
 *
 * <p>Click IDs persist indefinitely. UTM parameters expire after 24 hours; expiry is evaluated at
 * read time using a caller-supplied {@code now} timestamp to keep the class testable without mocks.
 *
 * <p>All public methods are static and never throw. Instances are not created.
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
  static void store(Map<String, String> queryParams, SharedPreferences prefs, long now) {
    if (queryParams == null || queryParams.isEmpty()) {
      return;
    }

    SharedPreferences.Editor editor = prefs.edit();

    // 24 ad-platform click IDs
    for (String id : AttributionConstants.CLICK_IDS) {
      String val = queryParams.get(id);
      if (val != null && !val.isEmpty()) {
        String prefKey = DL_PREFIX + "$" + id;
        String creationTimeKey = DL_PREFIX + "$" + id + "_creation_time";
        String existing = prefs.getString(prefKey, null);
        editor.putString(prefKey, val);
        // Dedup: only update creation_time when the value changes.
        if (!val.equals(existing)) {
          editor.putLong(creationTimeKey, now);
        }
      }
    }

    // Freshpaint click ID (stored without $ prefix, matching Install Referrer convention)
    String fpClickId = queryParams.get("fp_click_id");
    if (fpClickId != null && !fpClickId.isEmpty()) {
      String existing = prefs.getString(KEY_FP_CLICK_ID, null);
      editor.putString(KEY_FP_CLICK_ID, fpClickId);
      if (!fpClickId.equals(existing)) {
        editor.putLong(KEY_FP_CLICK_ID + "_creation_time", now);
      }
    }

    // Google special: gacid → $gclid_campaign_id
    String gacid = queryParams.get("gacid");
    if (gacid != null && !gacid.isEmpty()) {
      String key = DL_PREFIX + "$gclid_campaign_id";
      String existing = prefs.getString(key, null);
      editor.putString(key, gacid);
      if (!gacid.equals(existing)) {
        editor.putLong(key + "_creation_time", now);
      }
    }

    // Facebook special fields
    String adId = queryParams.get("ad_id");
    if (adId != null && !adId.isEmpty()) {
      String key = DL_PREFIX + "$fbclid_ad_id";
      String existing = prefs.getString(key, null);
      editor.putString(key, adId);
      if (!adId.equals(existing)) {
        editor.putLong(key + "_creation_time", now);
      }
    }
    String adsetId = queryParams.get("adset_id");
    if (adsetId != null && !adsetId.isEmpty()) {
      String key = DL_PREFIX + "$fbclid_adset_id";
      String existing = prefs.getString(key, null);
      editor.putString(key, adsetId);
      if (!adsetId.equals(existing)) {
        editor.putLong(key + "_creation_time", now);
      }
    }
    String campaignId = queryParams.get("campaign_id");
    if (campaignId != null && !campaignId.isEmpty()) {
      String key = DL_PREFIX + "$fbclid_campaign_id";
      String existing = prefs.getString(key, null);
      editor.putString(key, campaignId);
      if (!campaignId.equals(existing)) {
        editor.putLong(key + "_creation_time", now);
      }
    }

    // UTM params — stored with a timestamp for expiry evaluation at read time
    boolean anyUtm = false;
    for (String utmKey : UTM_PARAMS) {
      String val = queryParams.get(utmKey);
      if (val != null && !val.isEmpty()) {
        editor.putString(DL_PREFIX + "utm." + utmKey, val);
        anyUtm = true;
      }
    }
    if (anyUtm) {
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
  static Map<String, Object> getStoredProperties(SharedPreferences prefs, long now) {
    Map<String, Object> result = new LinkedHashMap<>();

    // 24 ad-platform click IDs (no expiry)
    for (String id : AttributionConstants.CLICK_IDS) {
      String val = prefs.getString(DL_PREFIX + "$" + id, null);
      if (val != null) {
        result.put("$" + id, val);
        long creationTime = prefs.getLong(DL_PREFIX + "$" + id + "_creation_time", 0L);
        if (creationTime != 0L) {
          result.put("$" + id + "_creation_time", creationTime);
        }
      }
    }

    // Freshpaint click ID
    String fpClickId = prefs.getString(KEY_FP_CLICK_ID, null);
    if (fpClickId != null) {
      result.put("fp_click_id", fpClickId);
      long ct = prefs.getLong(KEY_FP_CLICK_ID + "_creation_time", 0L);
      if (ct != 0L) {
        result.put("fp_click_id_creation_time", ct);
      }
    }

    // Google special field
    String gclidCampaignId = prefs.getString(DL_PREFIX + "$gclid_campaign_id", null);
    if (gclidCampaignId != null) {
      result.put("$gclid_campaign_id", gclidCampaignId);
      long ct = prefs.getLong(DL_PREFIX + "$gclid_campaign_id_creation_time", 0L);
      if (ct != 0L) {
        result.put("$gclid_campaign_id_creation_time", ct);
      }
    }

    // Facebook special fields
    String fbclidAdId = prefs.getString(DL_PREFIX + "$fbclid_ad_id", null);
    if (fbclidAdId != null) {
      result.put("$fbclid_ad_id", fbclidAdId);
      long ct = prefs.getLong(DL_PREFIX + "$fbclid_ad_id_creation_time", 0L);
      if (ct != 0L) {
        result.put("$fbclid_ad_id_creation_time", ct);
      }
    }
    String fbclidAdsetId = prefs.getString(DL_PREFIX + "$fbclid_adset_id", null);
    if (fbclidAdsetId != null) {
      result.put("$fbclid_adset_id", fbclidAdsetId);
      long ct = prefs.getLong(DL_PREFIX + "$fbclid_adset_id_creation_time", 0L);
      if (ct != 0L) {
        result.put("$fbclid_adset_id_creation_time", ct);
      }
    }
    String fbclidCampaignId = prefs.getString(DL_PREFIX + "$fbclid_campaign_id", null);
    if (fbclidCampaignId != null) {
      result.put("$fbclid_campaign_id", fbclidCampaignId);
      long ct = prefs.getLong(DL_PREFIX + "$fbclid_campaign_id_creation_time", 0L);
      if (ct != 0L) {
        result.put("$fbclid_campaign_id_creation_time", ct);
      }
    }

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
}
