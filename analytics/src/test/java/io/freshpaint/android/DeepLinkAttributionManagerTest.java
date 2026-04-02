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

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link DeepLinkAttributionManager}. All tests use {@link FakeSharedPreferences}
 * and a controlled {@code now} timestamp — no Android framework or Robolectric required.
 */
public class DeepLinkAttributionManagerTest {

  private static final long T0 = 1_000_000L;
  private static final long T1 = T0 + 1_000L;
  private static final long T_25H = T0 + 25L * 60L * 60L * 1_000L;

  private FakeSharedPreferences prefs;

  @Before
  public void setUp() {
    prefs = new FakeSharedPreferences();
  }

  // -------------------------------------------------------------------------
  // All CLICK_IDS extracted with $ prefix
  // -------------------------------------------------------------------------

  @Test
  public void store_allClickIds_storedWithDollarPrefix() {
    Map<String, String> params = new LinkedHashMap<>();
    for (String id : AttributionConstants.CLICK_IDS) {
      params.put(id, "val_" + id);
    }

    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    for (String id : AttributionConstants.CLICK_IDS) {
      assertThat(stored).containsEntry("$" + id, "val_" + id);
      // AC3: each has a creation_time
      assertThat(stored).containsKey("$" + id + "_creation_time");
    }
  }

  // -------------------------------------------------------------------------
  // AC3: creation_time set to now on first store
  // -------------------------------------------------------------------------

  @Test
  public void store_clickId_setsCreationTimeToNow() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gclid", "GCL123");

    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    assertThat(stored).containsEntry("$gclid", "GCL123");
    assertThat(stored).containsEntry("$gclid_creation_time", T0);
  }

  // -------------------------------------------------------------------------
  // gacid → $gclid_campaign_id (legacy when no gclid/wbraid/gbraid), and matching
  // $wbraid_campaign_id / $gbraid_campaign_id when those click ids are present
  // -------------------------------------------------------------------------

  @Test
  public void store_gacid_storedAsGclidCampaignId() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gacid", "CAMPAIGN_ABC");

    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    assertThat(stored).containsEntry("$gclid_campaign_id", "CAMPAIGN_ABC");
    assertThat(stored).containsEntry("$gclid_campaign_id_creation_time", T0);
  }

  @Test
  public void store_gacid_sameValue_doesNotUpdateCreationTime() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gacid", "SAME_CAMPAIGN");
    DeepLinkAttributionManager.store(params, prefs, T0);
    DeepLinkAttributionManager.store(params, prefs, T1);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T1);
    assertThat(stored).containsEntry("$gclid_campaign_id", "SAME_CAMPAIGN");
    assertThat(stored).containsEntry("$gclid_campaign_id_creation_time", T0);
  }

  @Test
  public void store_gacid_differentValue_updatesCreationTime() {
    Map<String, String> params1 = new LinkedHashMap<>();
    params1.put("gacid", "OLD_CAMPAIGN");
    DeepLinkAttributionManager.store(params1, prefs, T0);

    Map<String, String> params2 = new LinkedHashMap<>();
    params2.put("gacid", "NEW_CAMPAIGN");
    DeepLinkAttributionManager.store(params2, prefs, T1);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T1);
    assertThat(stored).containsEntry("$gclid_campaign_id", "NEW_CAMPAIGN");
    assertThat(stored).containsEntry("$gclid_campaign_id_creation_time", T1);
  }

  @Test
  public void store_wbraidAndGacid_mapsGacidToWbraidCampaignIdOnly() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("wbraid", "WB123");
    params.put("gacid", "GCAMP_W");

    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    assertThat(stored).containsEntry("$wbraid", "WB123");
    assertThat(stored).containsEntry("$wbraid_campaign_id", "GCAMP_W");
    assertThat(stored).containsEntry("$wbraid_campaign_id_creation_time", T0);
    assertThat(stored).doesNotContainKey("$gclid_campaign_id");
  }

  @Test
  public void store_gbraidAndGacid_mapsGacidToGbraidCampaignIdOnly() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gbraid", "GB999");
    params.put("gacid", "GCAMP_G");

    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    assertThat(stored).containsEntry("$gbraid", "GB999");
    assertThat(stored).containsEntry("$gbraid_campaign_id", "GCAMP_G");
    assertThat(stored).containsEntry("$gbraid_campaign_id_creation_time", T0);
    assertThat(stored).doesNotContainKey("$gclid_campaign_id");
  }

  @Test
  public void store_gclidAndWbraidAndGacid_setsBothGoogleCampaignIds() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gclid", "G1");
    params.put("wbraid", "W1");
    params.put("gacid", "SHARED_GACID");

    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    assertThat(stored).containsEntry("$gclid_campaign_id", "SHARED_GACID");
    assertThat(stored).containsEntry("$wbraid_campaign_id", "SHARED_GACID");
  }

  // -------------------------------------------------------------------------
  // AC5: Facebook special fields
  // -------------------------------------------------------------------------

  @Test
  public void store_facebookSpecialFields_mappedCorrectly() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("fbclid", "FB_CLICK_ID");
    params.put("ad_id", "AD_123");
    params.put("adset_id", "ADSET_456");
    params.put("campaign_id", "CAMP_789");

    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    assertThat(stored).containsEntry("$fbclid", "FB_CLICK_ID");
    assertThat(stored).containsEntry("$fbclid_ad_id", "AD_123");
    assertThat(stored).containsEntry("$fbclid_adset_id", "ADSET_456");
    assertThat(stored).containsEntry("$fbclid_campaign_id", "CAMP_789");
    assertThat(stored).containsEntry("$fbclid_ad_id_creation_time", T0);
    assertThat(stored).containsEntry("$fbclid_adset_id_creation_time", T0);
    assertThat(stored).containsEntry("$fbclid_campaign_id_creation_time", T0);
  }

  @Test
  public void store_facebookSpecialFields_sameValues_doNotUpdateCreationTimes() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("ad_id", "AD_SAME");
    params.put("adset_id", "ADSET_SAME");
    params.put("campaign_id", "CAMP_SAME");
    DeepLinkAttributionManager.store(params, prefs, T0);
    DeepLinkAttributionManager.store(params, prefs, T1);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T1);
    assertThat(stored).containsEntry("$fbclid_ad_id_creation_time", T0);
    assertThat(stored).containsEntry("$fbclid_adset_id_creation_time", T0);
    assertThat(stored).containsEntry("$fbclid_campaign_id_creation_time", T0);
  }

  // -------------------------------------------------------------------------
  // AC6: deduplication — same value preserves creation_time
  // -------------------------------------------------------------------------

  @Test
  public void store_sameClickIdValue_doesNotUpdateCreationTime() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gclid", "SAME_VALUE");
    DeepLinkAttributionManager.store(params, prefs, T0);

    // Same value, later timestamp — creation_time must NOT change
    DeepLinkAttributionManager.store(params, prefs, T1);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T1);
    assertThat(stored).containsEntry("$gclid", "SAME_VALUE");
    assertThat(stored).containsEntry("$gclid_creation_time", T0);
  }

  // AC6: deduplication — different value resets creation_time
  @Test
  public void store_differentClickIdValue_updatesCreationTime() {
    Map<String, String> params1 = new LinkedHashMap<>();
    params1.put("gclid", "OLD_VALUE");
    DeepLinkAttributionManager.store(params1, prefs, T0);

    Map<String, String> params2 = new LinkedHashMap<>();
    params2.put("gclid", "NEW_VALUE");
    DeepLinkAttributionManager.store(params2, prefs, T1);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T1);
    assertThat(stored).containsEntry("$gclid", "NEW_VALUE");
    assertThat(stored).containsEntry("$gclid_creation_time", T1);
  }

  // -------------------------------------------------------------------------
  // AC7: click IDs persist after 24 hours (no expiry)
  // -------------------------------------------------------------------------

  @Test
  public void getStoredProperties_clickIdPersistsAfter24h() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gclid", "PERSIST_ME");
    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T_25H);
    assertThat(stored).containsEntry("$gclid", "PERSIST_ME");
    assertThat(stored).containsEntry("$gclid_creation_time", T0);
  }

  // -------------------------------------------------------------------------
  // AC8: UTM params stored
  // -------------------------------------------------------------------------

  @Test
  public void store_utmParams_allFiveStored() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("utm_source", "facebook");
    params.put("utm_medium", "cpc");
    params.put("utm_campaign", "summer_sale");
    params.put("utm_term", "shoes");
    params.put("utm_content", "banner_v2");

    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    assertThat(stored).containsEntry("utm_source", "facebook");
    assertThat(stored).containsEntry("utm_medium", "cpc");
    assertThat(stored).containsEntry("utm_campaign", "summer_sale");
    assertThat(stored).containsEntry("utm_term", "shoes");
    assertThat(stored).containsEntry("utm_content", "banner_v2");
  }

  // -------------------------------------------------------------------------
  // AC9: UTM expiry
  // -------------------------------------------------------------------------

  @Test
  public void getStoredProperties_utmReturnedExactlyAt24hBoundary() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("utm_source", "google");
    DeepLinkAttributionManager.store(params, prefs, T0);

    // Exactly at 24 h — should still be returned (boundary is inclusive: <= UTM_EXPIRY_MS)
    long exactly24h = T0 + 24L * 60L * 60L * 1_000L;
    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, exactly24h);
    assertThat(stored).containsEntry("utm_source", "google");
  }

  @Test
  public void getStoredProperties_utmExpiredAfter24h() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("utm_source", "google");
    params.put("gclid", "KEEP_ME"); // click ID must survive even when UTM expires
    DeepLinkAttributionManager.store(params, prefs, T0);

    long after24h = T0 + 24L * 60L * 60L * 1_000L + 1L;
    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, after24h);
    assertThat(stored).doesNotContainKey("utm_source");
    assertThat(stored).containsEntry("$gclid", "KEEP_ME");
  }

  // -------------------------------------------------------------------------
  // UTM atomic replacement — partial update must not mix with stale values
  // -------------------------------------------------------------------------

  /**
   * When a second deep link carries only a subset of UTM params, the UTM keys absent from the new
   * URL must be cleared. This prevents cross-campaign contamination (e.g. utm_source=facebook mixed
   * with utm_campaign=summer_sale from a previous Google campaign).
   */
  @Test
  public void store_partialUtmUpdate_clearsPreviousUtmKeys() {
    // First deep link: utm_source + utm_campaign
    Map<String, String> params1 = new LinkedHashMap<>();
    params1.put("utm_source", "google");
    params1.put("utm_campaign", "summer_sale");
    DeepLinkAttributionManager.store(params1, prefs, T0);

    // Second deep link: only utm_source — utm_campaign must NOT survive
    Map<String, String> params2 = new LinkedHashMap<>();
    params2.put("utm_source", "facebook");
    DeepLinkAttributionManager.store(params2, prefs, T1);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T1);
    assertThat(stored).containsEntry("utm_source", "facebook");
    assertThat(stored).doesNotContainKey("utm_campaign");
    assertThat(stored).doesNotContainKey("utm_medium");
    assertThat(stored).doesNotContainKey("utm_term");
    assertThat(stored).doesNotContainKey("utm_content");
  }

  /**
   * utm_stored_at must be updated to the new timestamp on partial updates so the expiry window is
   * anchored to the most recent deep link, not the original one.
   */
  @Test
  public void store_partialUtmUpdate_refreshesStoredAtTimestamp() {
    Map<String, String> params1 = new LinkedHashMap<>();
    params1.put("utm_source", "google");
    DeepLinkAttributionManager.store(params1, prefs, T0);

    Map<String, String> params2 = new LinkedHashMap<>();
    params2.put("utm_medium", "cpc");
    DeepLinkAttributionManager.store(params2, prefs, T1);

    // utm_stored_at is now T1; both reads at T1 must return the new UTM set only
    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T1);
    assertThat(stored).containsEntry("utm_medium", "cpc");
    assertThat(stored).doesNotContainKey("utm_source");
  }

  // -------------------------------------------------------------------------
  // fp_click_id stored without $ prefix
  // -------------------------------------------------------------------------

  @Test
  public void store_fpClickId_storedWithoutDollarPrefix() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("fp_click_id", "fp_abc123");

    DeepLinkAttributionManager.store(params, prefs, T0);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    assertThat(stored).containsEntry("fp_click_id", "fp_abc123");
    assertThat(stored).containsEntry("fp_click_id_creation_time", T0);
    assertThat(stored).doesNotContainKey("$fp_click_id");
  }

  @Test
  public void store_fpClickId_sameValue_doesNotUpdateCreationTime() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("fp_click_id", "fp_SAME");
    DeepLinkAttributionManager.store(params, prefs, T0);
    DeepLinkAttributionManager.store(params, prefs, T1);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T1);
    assertThat(stored).containsEntry("fp_click_id", "fp_SAME");
    assertThat(stored).containsEntry("fp_click_id_creation_time", T0);
  }

  @Test
  public void store_fpClickId_differentValue_updatesCreationTime() {
    Map<String, String> params1 = new LinkedHashMap<>();
    params1.put("fp_click_id", "fp_OLD");
    DeepLinkAttributionManager.store(params1, prefs, T0);

    Map<String, String> params2 = new LinkedHashMap<>();
    params2.put("fp_click_id", "fp_NEW");
    DeepLinkAttributionManager.store(params2, prefs, T1);

    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T1);
    assertThat(stored).containsEntry("fp_click_id", "fp_NEW");
    assertThat(stored).containsEntry("fp_click_id_creation_time", T1);
  }

  // -------------------------------------------------------------------------
  // Edge cases: null / empty input
  // -------------------------------------------------------------------------

  @Test
  public void store_emptyParams_noExceptionAndEmptyResult() {
    DeepLinkAttributionManager.store(new LinkedHashMap<>(), prefs, T0);

    assertThat(DeepLinkAttributionManager.getStoredProperties(prefs, T0)).isEmpty();
  }

  @Test
  public void store_nullParams_noException() {
    DeepLinkAttributionManager.store(null, prefs, T0);

    assertThat(DeepLinkAttributionManager.getStoredProperties(prefs, T0)).isEmpty();
  }

  @Test
  public void getStoredProperties_nothingStored_returnsEmptyMap() {
    assertThat(DeepLinkAttributionManager.getStoredProperties(prefs, T0)).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Round-trip correctness
  // -------------------------------------------------------------------------

  @Test
  public void store_thenGetStoredProperties_roundTrip() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("fbclid", "FB_ID");
    params.put("fp_click_id", "fp_xyz");
    params.put("utm_source", "tiktok");
    params.put("gacid", "CAM_123");

    DeepLinkAttributionManager.store(params, prefs, T0);
    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);

    assertThat(stored).containsEntry("$fbclid", "FB_ID");
    assertThat(stored).containsEntry("$fbclid_creation_time", T0);
    assertThat(stored).containsEntry("fp_click_id", "fp_xyz");
    assertThat(stored).containsEntry("fp_click_id_creation_time", T0);
    assertThat(stored).containsEntry("utm_source", "tiktok");
    assertThat(stored).containsEntry("$gclid_campaign_id", "CAM_123");
    assertThat(stored).containsEntry("$gclid_campaign_id_creation_time", T0);
  }

  // -------------------------------------------------------------------------
  // UTM not stored when no UTM params present
  // -------------------------------------------------------------------------

  @Test
  public void store_noUtmParams_utmStoredAtNotSet() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("gclid", "GCL123");
    // No UTM params

    DeepLinkAttributionManager.store(params, prefs, T0);

    // UTM params should not appear even within expiry window
    Map<String, Object> stored = DeepLinkAttributionManager.getStoredProperties(prefs, T0);
    assertThat(stored).doesNotContainKey("utm_source");
    assertThat(stored).doesNotContainKey("utm_campaign");
  }
}
