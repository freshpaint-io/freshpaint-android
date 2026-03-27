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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;

/**
 * Unit tests for {@link InstallReferrerManager}.
 *
 * <p>Tests use pure JVM + Mockito (no Robolectric). {@link InstallReferrerClient} is mocked via
 * Mockito 5.x inline mock maker (supports final classes). The listener callback is fired
 * synchronously inside the {@code startConnection()} answer so that {@link
 * InstallReferrerManager#doCollect} completes deterministically without real timing — except for
 * the timeout test, where the listener is intentionally never fired.
 */
public class InstallReferrerManagerTest {

  // -------------------------------------------------------------------------
  // Test state
  // -------------------------------------------------------------------------

  private FakeSharedPreferences prefs;
  private InstallReferrerClient mockClient;

  @Before
  public void setUp() {
    prefs = new FakeSharedPreferences();
    mockClient = mock(InstallReferrerClient.class);
    // Default: startConnection() does nothing. Listener is never fired → timeout path.
  }

  // -------------------------------------------------------------------------
  // Helper: configure client to fire a specific response code synchronously
  // -------------------------------------------------------------------------

  /**
   * Configures {@code client.startConnection()} to immediately fire {@link
   * InstallReferrerStateListener#onInstallReferrerSetupFinished} with {@code responseCode}. This
   * makes {@link InstallReferrerManager#doCollect} synchronous and deterministic.
   */
  private void setUpSyncResponse(InstallReferrerClient client, int responseCode) {
    doAnswer(
            inv -> {
              InstallReferrerStateListener listener = inv.getArgument(0);
              listener.onInstallReferrerSetupFinished(responseCode);
              return null;
            })
        .when(client)
        .startConnection(any(InstallReferrerStateListener.class));
  }

  private ReferrerDetails mockDetails(String rawReferrer, long clickSec, long beginSec)
      throws Exception {
    ReferrerDetails details = mock(ReferrerDetails.class);
    when(details.getInstallReferrer()).thenReturn(rawReferrer);
    when(details.getReferrerClickTimestampSeconds()).thenReturn(clickSec);
    when(details.getInstallBeginTimestampSeconds()).thenReturn(beginSec);
    return details;
  }

  private InstallReferrerClient okClient(ReferrerDetails details) throws Exception {
    InstallReferrerClient client = mock(InstallReferrerClient.class);
    when(client.getInstallReferrer()).thenReturn(details);
    setUpSyncResponse(client, InstallReferrerClient.InstallReferrerResponse.OK);
    return client;
  }

  // -------------------------------------------------------------------------
  // AC1 — UTM params parsed correctly
  // -------------------------------------------------------------------------

  @Test
  public void utmParamsParsedFromReferrerString() throws Exception {
    String referrer =
        "utm_source=google&utm_medium=cpc&utm_campaign=winter_sale"
            + "&utm_term=shoes&utm_content=banner_v2";
    ReferrerDetails details = mockDetails(referrer, 0L, 0L);
    InstallReferrerClient client = okClient(details);

    InstallReferrerManager.doCollect(client, prefs, 5_000L);

    assertThat(prefs.store.get("ir.utm_source")).isEqualTo("google");
    assertThat(prefs.store.get("ir.utm_medium")).isEqualTo("cpc");
    assertThat(prefs.store.get("ir.utm_campaign")).isEqualTo("winter_sale");
    assertThat(prefs.store.get("ir.utm_term")).isEqualTo("shoes");
    assertThat(prefs.store.get("ir.utm_content")).isEqualTo("banner_v2");
  }

  // -------------------------------------------------------------------------
  // AC2 — All 24 click IDs extracted with $ prefix and _creation_time
  // -------------------------------------------------------------------------

  @Test
  public void allTwentyFourClickIdsExtractedWithPrefixAndCreationTime() throws Exception {
    StringBuilder referrer = new StringBuilder();
    for (String id : AttributionConstants.CLICK_IDS) {
      if (referrer.length() > 0) referrer.append("&");
      referrer.append(id).append("=value_").append(id);
    }
    ReferrerDetails details = mockDetails(referrer.toString(), 0L, 0L);
    InstallReferrerClient client = okClient(details);

    InstallReferrerManager.doCollect(client, prefs, 5_000L);

    for (String id : AttributionConstants.CLICK_IDS) {
      assertThat(prefs.store.get("ir.$" + id)).as("$ prefix for %s", id).isEqualTo("value_" + id);
      assertThat(prefs.store.containsKey("ir.$" + id + "_creation_time"))
          .as("_creation_time for %s", id)
          .isTrue();
    }
  }

  // -------------------------------------------------------------------------
  // AC3 — fp_click_id stored without $ prefix
  // -------------------------------------------------------------------------

  @Test
  public void fpClickIdStoredWithoutDollarPrefix() throws Exception {
    ReferrerDetails details = mockDetails("fp_click_id=fp_abc123xyz", 0L, 0L);
    InstallReferrerClient client = okClient(details);

    InstallReferrerManager.doCollect(client, prefs, 5_000L);

    assertThat(prefs.store.get("ir.fp_click_id")).isEqualTo("fp_abc123xyz");
    assertThat(prefs.store.containsKey("ir.$fp_click_id")).isFalse();
  }

  // -------------------------------------------------------------------------
  // AC4 — Google special: gacid → $gclid_campaign_id
  // -------------------------------------------------------------------------

  @Test
  public void googleGacidStoredAsGclidCampaignId() throws Exception {
    ReferrerDetails details = mockDetails("gacid=12345678", 0L, 0L);
    InstallReferrerClient client = okClient(details);

    InstallReferrerManager.doCollect(client, prefs, 5_000L);

    assertThat(prefs.store.get("ir.$gclid_campaign_id")).isEqualTo("12345678");
    assertThat(prefs.store.containsKey("ir.$gacid")).isFalse();
  }

  // -------------------------------------------------------------------------
  // AC5 — Facebook special fields
  // -------------------------------------------------------------------------

  @Test
  public void facebookSpecialFieldsMappedCorrectly() throws Exception {
    String referrer = "ad_id=ad999&adset_id=adset123&campaign_id=camp456";
    ReferrerDetails details = mockDetails(referrer, 0L, 0L);
    InstallReferrerClient client = okClient(details);

    InstallReferrerManager.doCollect(client, prefs, 5_000L);

    assertThat(prefs.store.get("ir.$fbclid_ad_id")).isEqualTo("ad999");
    assertThat(prefs.store.get("ir.$fbclid_adset_id")).isEqualTo("adset123");
    assertThat(prefs.store.get("ir.$fbclid_campaign_id")).isEqualTo("camp456");
  }

  // -------------------------------------------------------------------------
  // AC6 — FEATURE_NOT_SUPPORTED → graceful skip
  // -------------------------------------------------------------------------

  @Test
  public void featureNotSupportedReturnsGracefully() throws Exception {
    setUpSyncResponse(
        mockClient, InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED);

    InstallReferrerManager.doCollect(mockClient, prefs, 5_000L);

    assertThat(prefs.store.containsKey("ir.install_referrer")).isFalse();
    verify(mockClient, times(1)).endConnection();
  }

  // -------------------------------------------------------------------------
  // AC7 — SERVICE_UNAVAILABLE → graceful skip
  // -------------------------------------------------------------------------

  @Test
  public void serviceUnavailableReturnsGracefully() throws Exception {
    setUpSyncResponse(
        mockClient, InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE);

    InstallReferrerManager.doCollect(mockClient, prefs, 5_000L);

    assertThat(prefs.store.containsKey("ir.install_referrer")).isFalse();
    verify(mockClient, times(1)).endConnection();
  }

  // -------------------------------------------------------------------------
  // AC8 — DEVELOPER_ERROR → graceful skip
  // -------------------------------------------------------------------------

  @Test
  public void developerErrorReturnsGracefully() throws Exception {
    setUpSyncResponse(mockClient, InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR);

    InstallReferrerManager.doCollect(mockClient, prefs, 5_000L);

    assertThat(prefs.store.containsKey("ir.install_referrer")).isFalse();
    verify(mockClient, times(1)).endConnection();
  }

  // -------------------------------------------------------------------------
  // AC9 — Timeout → no hang, endConnection() called
  // -------------------------------------------------------------------------

  @Test(timeout = 6_000) // fails if test hangs beyond 6 s
  public void timeoutDoesNotHang() throws Exception {
    // Default mock: startConnection() does nothing → listener never fires → timeout

    long start = System.currentTimeMillis();
    InstallReferrerManager.doCollect(mockClient, prefs, 500L); // short timeout for test speed
    long elapsed = System.currentTimeMillis() - start;

    assertThat(elapsed).isGreaterThanOrEqualTo(500L).isLessThan(3_000L);
    verify(mockClient, times(1)).endConnection();
    assertThat(prefs.store.containsKey("ir.install_referrer")).isFalse();
  }

  // -------------------------------------------------------------------------
  // AC10 — collectAndStore() never throws (outer exception contract)
  // (The NoClassDefFoundError path is verified by code inspection since the library
  // cannot be removed from the test classpath at runtime. This test verifies the outer
  // catch is in place by triggering an internal NPE via a mock Context.)
  // -------------------------------------------------------------------------

  @Test
  public void collectAndStoreNeverThrows() {
    // A mock Context causes InstallReferrerClient.newBuilder(ctx) to NPE or fail internally.
    // The outer catch(Exception | NoClassDefFoundError) in collectAndStore() must absorb it.
    Context mockContext = mock(Context.class);
    try {
      InstallReferrerManager.collectAndStore(mockContext, prefs, 100L);
    } catch (Throwable t) {
      throw new AssertionError("collectAndStore() must never throw, but got: " + t, t);
    }
    // Guard flag set by the finally block
    assertThat(prefs.getBoolean(InstallReferrerManager.KEY_IR_COLLECTED, false)).isTrue();
  }

  // -------------------------------------------------------------------------
  // AC10 (direct) — NoClassDefFoundError path in collectAndStore()
  // Uses mockStatic to simulate the installreferrer library being absent at
  // runtime by making InstallReferrerClient.newBuilder() throw NoClassDefFoundError.
  // -------------------------------------------------------------------------

  @Test
  public void collectAndStoreCatchesNoClassDefFoundError() {
    try (MockedStatic<InstallReferrerClient> mocked = mockStatic(InstallReferrerClient.class)) {
      mocked
          .when(() -> InstallReferrerClient.newBuilder(any()))
          .thenThrow(
              new NoClassDefFoundError("com/android/installreferrer/api/InstallReferrerClient"));

      Context ctx = mock(Context.class);
      try {
        InstallReferrerManager.collectAndStore(ctx, prefs, 100L);
      } catch (Throwable t) {
        throw new AssertionError(
            "collectAndStore() must not propagate NoClassDefFoundError, but got: " + t, t);
      }
      // Guard flag must be set by the finally block even when NCDF is thrown
      assertThat(prefs.getBoolean(InstallReferrerManager.KEY_IR_COLLECTED, false)).isTrue();
      // No referrer data collected
      assertThat(prefs.store.size()).isEqualTo(1); // only KEY_IR_COLLECTED
    }
  }

  // -------------------------------------------------------------------------
  // AC11 — Dedup: same click ID value does NOT update _creation_time
  // -------------------------------------------------------------------------

  @Test
  public void dedupSameValueDoesNotUpdateCreationTime() throws Exception {
    // First collection
    ReferrerDetails details1 = mockDetails("gclid=abc123", 0L, 0L);
    InstallReferrerClient client1 = okClient(details1);
    InstallReferrerManager.doCollect(client1, prefs, 5_000L);

    long originalCreationTime = (Long) prefs.store.get("ir.$gclid_creation_time");
    assertThat(originalCreationTime).isGreaterThan(0L);

    // Ensure at least 1ms passes
    Thread.sleep(2);

    // Second collection with SAME value — remove guard to allow re-run
    prefs.store.remove(InstallReferrerManager.KEY_IR_COLLECTED);
    ReferrerDetails details2 = mockDetails("gclid=abc123", 0L, 0L);
    InstallReferrerClient client2 = okClient(details2);
    InstallReferrerManager.doCollect(client2, prefs, 5_000L);

    // creation_time must NOT have changed
    assertThat(prefs.store.get("ir.$gclid_creation_time")).isEqualTo(originalCreationTime);
  }

  // -------------------------------------------------------------------------
  // AC12 — Dedup: different click ID value DOES update _creation_time
  // -------------------------------------------------------------------------

  @Test
  public void dedupDifferentValueUpdatesCreationTime() throws Exception {
    // First collection
    ReferrerDetails details1 = mockDetails("gclid=abc123", 0L, 0L);
    InstallReferrerClient client1 = okClient(details1);
    InstallReferrerManager.doCollect(client1, prefs, 5_000L);

    long originalCreationTime = (Long) prefs.store.get("ir.$gclid_creation_time");

    Thread.sleep(2); // ensure a new timestamp is different

    // Second collection with DIFFERENT value
    prefs.store.remove(InstallReferrerManager.KEY_IR_COLLECTED);
    ReferrerDetails details2 = mockDetails("gclid=xyz789", 0L, 0L);
    InstallReferrerClient client2 = okClient(details2);
    InstallReferrerManager.doCollect(client2, prefs, 5_000L);

    long newCreationTime = (Long) prefs.store.get("ir.$gclid_creation_time");
    assertThat(newCreationTime).isGreaterThan(originalCreationTime);
    assertThat(prefs.store.get("ir.$gclid")).isEqualTo("xyz789");
  }

  // -------------------------------------------------------------------------
  // AC14 — Referrer timestamps stored correctly
  // -------------------------------------------------------------------------

  @Test
  public void referrerTimestampsStoredCorrectly() throws Exception {
    ReferrerDetails details = mockDetails("utm_source=google", 1_710_000_000L, 1_710_000_030L);
    InstallReferrerClient client = okClient(details);

    InstallReferrerManager.doCollect(client, prefs, 5_000L);

    assertThat(prefs.store.get("ir.referrer_click_timestamp")).isEqualTo(1_710_000_000_000L);
    assertThat(prefs.store.get("ir.install_begin_timestamp")).isEqualTo(1_710_000_030_000L);
  }

  // -------------------------------------------------------------------------
  // AC15 — Raw referrer string stored
  // -------------------------------------------------------------------------

  @Test
  public void rawReferrerStringStored() throws Exception {
    String rawReferrer = "utm_source=google&utm_campaign=winter_sale&gclid=abc123";
    ReferrerDetails details = mockDetails(rawReferrer, 0L, 0L);
    InstallReferrerClient client = okClient(details);

    InstallReferrerManager.doCollect(client, prefs, 5_000L);

    assertThat(prefs.store.get("ir.install_referrer")).isEqualTo(rawReferrer);
  }

  // -------------------------------------------------------------------------
  // AC16 — Client always disconnected (no service leak)
  // -------------------------------------------------------------------------

  @Test
  public void clientAlwaysDisconnectedOnOk() throws Exception {
    ReferrerDetails details = mockDetails("utm_source=google", 0L, 0L);
    InstallReferrerClient client = okClient(details);

    InstallReferrerManager.doCollect(client, prefs, 5_000L);

    verify(client, times(1)).endConnection();
  }

  @Test
  public void clientAlwaysDisconnectedOnError() throws Exception {
    setUpSyncResponse(
        mockClient, InstallReferrerClient.InstallReferrerResponse.SERVICE_UNAVAILABLE);

    InstallReferrerManager.doCollect(mockClient, prefs, 5_000L);

    verify(mockClient, times(1)).endConnection();
  }

  @Test
  public void clientAlwaysDisconnectedOnTimeout() throws Exception {
    // Default mock: listener never fired → timeout path

    InstallReferrerManager.doCollect(mockClient, prefs, 200L);

    verify(mockClient, times(1)).endConnection();
  }

  // -------------------------------------------------------------------------
  // REG3 — TRACKED_ATTRIBUTION_KEY guard: collectAndStore() is idempotent
  // -------------------------------------------------------------------------

  @Test
  public void collectAndStoreIsIdempotentWhenGuardSet() {
    prefs.store.put(InstallReferrerManager.KEY_IR_COLLECTED, true);

    // collectAndStore() returns early without writing anything else
    InstallReferrerManager.collectAndStore(null, prefs, 5_000L);

    assertThat(prefs.store.size()).isEqualTo(1); // only the guard key
  }

  // -------------------------------------------------------------------------
  // getStoredProperties — returns empty map when not collected
  // -------------------------------------------------------------------------

  @Test
  public void getStoredPropertiesEmptyWhenNotCollected() {
    assertThat(InstallReferrerManager.getStoredProperties(prefs)).isEmpty();
  }

  // -------------------------------------------------------------------------
  // getStoredProperties — returns all stored fields
  // -------------------------------------------------------------------------

  @Test
  public void getStoredPropertiesReturnsAllFields() {
    prefs.store.put(InstallReferrerManager.KEY_IR_COLLECTED, true);
    prefs.store.put("ir.install_referrer", "utm_source=google&gclid=abc");
    prefs.store.put("ir.utm_source", "google");
    prefs.store.put("ir.referrer_click_timestamp", 1_710_000_000_000L);
    prefs.store.put("ir.$gclid", "abc");
    prefs.store.put("ir.$gclid_creation_time", 1_710_000_001_000L);
    prefs.store.put("ir.$gclid_campaign_id", "camp99");
    prefs.store.put("ir.$fbclid_ad_id", "ad42");
    prefs.store.put("ir.$fbclid_adset_id", "adset55");
    prefs.store.put("ir.$fbclid_campaign_id", "camp77");

    Map<String, Object> result = InstallReferrerManager.getStoredProperties(prefs);

    assertThat(result.get("install_referrer")).isEqualTo("utm_source=google&gclid=abc");
    assertThat(result.get("utm_source")).isEqualTo("google");
    assertThat(result.get("referrer_click_timestamp")).isEqualTo(1_710_000_000_000L);
    assertThat(result.get("$gclid")).isEqualTo("abc");
    assertThat(result.get("$gclid_creation_time")).isEqualTo(1_710_000_001_000L);
    assertThat(result.get("$gclid_campaign_id")).isEqualTo("camp99");
    assertThat(result.get("$fbclid_ad_id")).isEqualTo("ad42");
    assertThat(result.get("$fbclid_adset_id")).isEqualTo("adset55");
    assertThat(result.get("$fbclid_campaign_id")).isEqualTo("camp77");
  }

  // -------------------------------------------------------------------------
  // parseQueryParams — URL decoding
  // -------------------------------------------------------------------------

  @Test
  public void parseQueryParamsDecodesUrlEncoding() {
    Map<String, String> params =
        InstallReferrerManager.parseQueryParams("utm_campaign=winter%20sale&utm_source=google");

    assertThat(params.get("utm_campaign")).isEqualTo("winter sale");
    assertThat(params.get("utm_source")).isEqualTo("google");
  }

  @Test
  public void parseQueryParamsHandlesNullInput() {
    assertThat(InstallReferrerManager.parseQueryParams(null)).isEmpty();
  }

  @Test
  public void parseQueryParamsHandlesEmptyInput() {
    assertThat(InstallReferrerManager.parseQueryParams("")).isEmpty();
  }
}
