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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import io.freshpaint.android.integrations.BasePayload;
import io.freshpaint.android.integrations.TrackPayload;
import java.util.Date;
import java.util.LinkedHashMap;
import org.junit.Test;

public class AttributionMiddlewareTest {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Build a source {@link AnalyticsContext} that already has device advertising fields populated.
   *
   * @param androidId optional Android ID value; if non-null (and not a placeholder), stored at
   *     {@code "android_id"} in the device map.
   */
  private AnalyticsContext buildSourceContext(
      String deviceId, String gaid, boolean adTrackingEnabled, String androidId) {
    LinkedHashMap<String, Object> deviceMap = new LinkedHashMap<>();
    LinkedHashMap<String, Object> contextMap = new LinkedHashMap<>();
    contextMap.put("device", deviceMap);
    AnalyticsContext sourceContext = new AnalyticsContext(contextMap);

    AnalyticsContext.Device device = sourceContext.device();
    if (deviceId != null) {
      device.put(AnalyticsContext.Device.DEVICE_ID_KEY, deviceId);
    }
    device.putAdvertisingInfo(gaid, adTrackingEnabled);
    device.putAndroidId(androidId);
    return sourceContext;
  }

  /**
   * Build a payload {@link AnalyticsContext} that has a device section with just the device id —
   * simulating what the SDK sets before events are dispatched.
   */
  private AnalyticsContext buildPayloadContext(String deviceId) {
    LinkedHashMap<String, Object> payloadDeviceMap = new LinkedHashMap<>();
    if (deviceId != null) {
      payloadDeviceMap.put("id", deviceId);
    }

    LinkedHashMap<String, Object> payloadContextMap = new LinkedHashMap<>();
    payloadContextMap.put("device", payloadDeviceMap);
    return new AnalyticsContext(payloadContextMap);
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  /**
   * source device has gaid + limit_ad_tracking=false + context id → payload device must receive all
   * three values, including {@code "id"}.
   */
  @Test
  public void enrichesPayloadDeviceWithGaidAndLimitAdTracking() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "test-device-id",
            "test-gaid-1234",
            /* adTrackingEnabled= */ true,
            /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("test-device-id");

    BasePayload payload = mock(BasePayload.class);
    when(payload.context()).thenReturn(payloadContext);

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(payload);

    AttributionMiddleware middleware = new AttributionMiddleware(sourceContext);
    middleware.intercept(chain);

    verify(chain).proceed(payload);

    AnalyticsContext.Device resultDevice = payloadContext.device();
    assertThat(resultDevice).isNotNull();
    assertThat(resultDevice).containsEntry("advertisingId", "test-gaid-1234");
    assertThat(resultDevice).containsEntry("limit_ad_tracking", false);
    // context device id must also be propagated
    assertThat(resultDevice).containsEntry("id", "test-device-id");
  }

  /**
   * When GAID has not yet been fetched (null advertisingId in source device), chain.proceed() must
   * still be called exactly once and no advertisingId key should appear in the result device.
   */
  @Test
  public void nullGaidDoesNotBlockChainProceed() {
    // No gaid — simulates pre-fetch state
    AnalyticsContext sourceContext =
        buildSourceContext(
            "test-device-id",
            /* gaid= */ null,
            /* adTrackingEnabled= */ false,
            /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("test-device-id");

    BasePayload payload = mock(BasePayload.class);
    when(payload.context()).thenReturn(payloadContext);

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(payload);

    AttributionMiddleware middleware = new AttributionMiddleware(sourceContext);
    middleware.intercept(chain);

    verify(chain).proceed(payload);

    AnalyticsContext.Device resultDevice = payloadContext.device();
    assertThat(resultDevice).isNotNull();
    // No GAID should be injected when it hasn't been fetched
    assertThat(resultDevice).doesNotContainKey("advertisingId");
    // limit_ad_tracking is always written (defaults to true when adTracking disabled)
    assertThat(resultDevice).containsEntry("limit_ad_tracking", true);
  }

  /**
   * When a track event carries {@code limit_ad_tracking} in properties (as {@code Application
   * Installed} does) but the live device map has since been updated (e.g. GAID finished),
   * properties must match {@code context.device.limit_ad_tracking} so the payload is
   * self-consistent.
   */
  @Test
  public void syncsLimitAdTrackingInTrackPropertiesWhenKeyPresent() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "test-device-id",
            "test-gaid-1234",
            /* adTrackingEnabled= */ true,
            /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("test-device-id");

    LinkedHashMap<String, Object> propsMap = new LinkedHashMap<>();
    propsMap.put("limit_ad_tracking", true);

    TrackPayload trackPayload =
        new TrackPayload.Builder()
            .event("Application Installed")
            .anonymousId("anon")
            .timestamp(new Date(0))
            .context(payloadContext)
            .properties(propsMap)
            .build();

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(trackPayload);

    AttributionMiddleware middleware = new AttributionMiddleware(sourceContext);
    middleware.intercept(chain);

    verify(chain).proceed(trackPayload);
    assertThat(trackPayload.properties().get("limit_ad_tracking")).isEqualTo(false);
    assertThat(trackPayload.context().device()).containsEntry("limit_ad_tracking", false);
    assertThat(trackPayload.properties().get("advertisingId")).isEqualTo("test-gaid-1234");
    assertThat(trackPayload.context().device()).containsEntry("advertisingId", "test-gaid-1234");
  }

  @Test
  public void syncsStaleAdvertisingIdInAppInstallPropertiesWhenSourceHasAdvertisingId() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "test-device-id",
            "resolved-gaid",
            /* adTrackingEnabled= */ true,
            /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("test-device-id");

    LinkedHashMap<String, Object> propsMap = new LinkedHashMap<>();
    propsMap.put("limit_ad_tracking", true);
    propsMap.put("advertisingId", "stale-gaid");

    TrackPayload trackPayload =
        new TrackPayload.Builder()
            .event("Application Installed")
            .anonymousId("anon")
            .timestamp(new Date(0))
            .context(payloadContext)
            .properties(propsMap)
            .build();

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(trackPayload);

    new AttributionMiddleware(sourceContext).intercept(chain);

    verify(chain).proceed(trackPayload);
    assertThat(trackPayload.properties().get("advertisingId")).isEqualTo("resolved-gaid");
  }

  /**
   * Race-condition scenario: the install-event snapshot ran before the GAID worker completed, so it
   * captured {@code android_id} as fallback. By dispatch time the worker has resolved GAID. The
   * reconciliation path must add {@code advertisingId} AND remove the stale {@code android_id} so
   * both identifiers never appear together in {@code properties}.
   */
  @Test
  public void reconciliationRemovesAndroidIdWhenGaidResolvesAfterSnapshot() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "test-device-id",
            "resolved-gaid",
            /* adTrackingEnabled= */ true,
            /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("test-device-id");

    LinkedHashMap<String, Object> propsMap = new LinkedHashMap<>();
    propsMap.put("limit_ad_tracking", true);
    propsMap.put("android_id", "snapshot-android-id");

    TrackPayload trackPayload =
        new TrackPayload.Builder()
            .event("Application Installed")
            .anonymousId("anon")
            .timestamp(new Date(0))
            .context(payloadContext)
            .properties(propsMap)
            .build();

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(trackPayload);

    new AttributionMiddleware(sourceContext).intercept(chain);

    verify(chain).proceed(trackPayload);
    assertThat(trackPayload.properties().get("advertisingId")).isEqualTo("resolved-gaid");
    assertThat(trackPayload.properties()).doesNotContainKey("android_id");
    assertThat(trackPayload.context().device()).containsEntry("advertisingId", "resolved-gaid");
  }

  @Test
  public void addsAdvertisingIdToAppInstallPropertiesWhenAbsentButResolved() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "test-device-id", "fresh-gaid", /* adTrackingEnabled= */ true, /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("test-device-id");

    LinkedHashMap<String, Object> propsMap = new LinkedHashMap<>();
    propsMap.put("limit_ad_tracking", true);

    TrackPayload trackPayload =
        new TrackPayload.Builder()
            .event("Application Installed")
            .anonymousId("anon")
            .timestamp(new Date(0))
            .context(payloadContext)
            .properties(propsMap)
            .build();

    assertThat(trackPayload.properties().containsKey("advertisingId")).isFalse();

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(trackPayload);

    new AttributionMiddleware(sourceContext).intercept(chain);

    verify(chain).proceed(trackPayload);
    assertThat(trackPayload.properties().get("advertisingId")).isEqualTo("fresh-gaid");
  }

  @Test
  public void doesNotInjectAdvertisingIdIntoNonAppInstallTrackProperties() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "test-device-id", "my-gaid", /* adTrackingEnabled= */ true, /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("test-device-id");

    LinkedHashMap<String, Object> propsMap = new LinkedHashMap<>();
    propsMap.put("limit_ad_tracking", true);

    TrackPayload trackPayload =
        new TrackPayload.Builder()
            .event("Some Other Event")
            .anonymousId("anon")
            .timestamp(new Date(0))
            .context(payloadContext)
            .properties(propsMap)
            .build();

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(trackPayload);

    new AttributionMiddleware(sourceContext).intercept(chain);

    verify(chain).proceed(trackPayload);
    assertThat(trackPayload.properties().containsKey("advertisingId")).isFalse();
  }

  /**
   * Even when the source AnalyticsContext has no device section at all, chain.proceed() must be
   * called exactly once — the middleware must never swallow the event.
   */
  @Test
  public void chainProceedCalledExactlyOnceWhenSourceDeviceIsNull() {
    // Empty context — no device key
    AnalyticsContext sourceContext = new AnalyticsContext(new LinkedHashMap<String, Object>());

    BasePayload payload = mock(BasePayload.class);

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(payload);

    AttributionMiddleware middleware = new AttributionMiddleware(sourceContext);
    middleware.intercept(chain);

    verify(chain).proceed(payload);
  }

  /**
   * When payload.context() returns null, chain.proceed() must still be called exactly once. Covers
   * the second early-exit guard in intercept().
   */
  @Test
  public void chainProceedCalledExactlyOnceWhenPayloadContextIsNull() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "device-id", "gaid-1234", /* adTrackingEnabled= */ true, /* androidId= */ null);

    BasePayload payload = mock(BasePayload.class);
    when(payload.context()).thenReturn(null);

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(payload);

    AttributionMiddleware middleware = new AttributionMiddleware(sourceContext);
    middleware.intercept(chain);

    verify(chain).proceed(payload);
  }

  /**
   * When the payload context has no device section (device() returns null), chain.proceed() must
   * still be called exactly once. Covers the third early-exit guard in intercept().
   */
  @Test
  public void chainProceedCalledExactlyOnceWhenPayloadDeviceIsNull() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "device-id", "gaid-1234", /* adTrackingEnabled= */ true, /* androidId= */ null);

    // Payload context with no "device" key → payloadContext.device() returns null
    AnalyticsContext payloadContext = new AnalyticsContext(new LinkedHashMap<String, Object>());

    BasePayload payload = mock(BasePayload.class);
    when(payload.context()).thenReturn(payloadContext);

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(payload);

    AttributionMiddleware middleware = new AttributionMiddleware(sourceContext);
    middleware.intercept(chain);

    verify(chain).proceed(payload);
  }

  // ---------------------------------------------------------------------------
  // Identifier mutual exclusion (enforced by GetAdvertisingIdWorker, verified at middleware)
  // ---------------------------------------------------------------------------

  /**
   * When GAID is available in the source device (as set by the worker), {@code android_id} must be
   * absent from the payload device. The worker guarantees these are mutually exclusive; the
   * middleware confirms the invariant holds through dispatch.
   */
  @Test
  public void androidIdAbsentFromPayloadWhenGaidPresent() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "dev-id", "gaid-1234", /* adTrackingEnabled= */ true, /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("dev-id");

    BasePayload payload = mock(BasePayload.class);
    when(payload.context()).thenReturn(payloadContext);

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(payload);

    AttributionMiddleware middleware = new AttributionMiddleware(sourceContext);
    middleware.intercept(chain);

    verify(chain).proceed(payload);

    AnalyticsContext.Device resultDevice = payloadContext.device();
    assertThat(resultDevice).isNotNull();
    assertThat(resultDevice).containsEntry("advertisingId", "gaid-1234");
    assertThat(resultDevice).doesNotContainKey("android_id");
  }

  /**
   * When the source device has no android_id (null passed to putAndroidId, which stores nothing),
   * the middleware must NOT write an {@code "android_id"} key to the payload device.
   */
  @Test
  public void androidIdAbsentFromPayloadDeviceWhenNull() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "dev-id", "gaid-1234", /* adTrackingEnabled= */ true, /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("dev-id");

    BasePayload payload = mock(BasePayload.class);
    when(payload.context()).thenReturn(payloadContext);

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(payload);

    AttributionMiddleware middleware = new AttributionMiddleware(sourceContext);
    middleware.intercept(chain);

    verify(chain).proceed(payload);

    AnalyticsContext.Device resultDevice = payloadContext.device();
    assertThat(resultDevice).isNotNull();
    assertThat(resultDevice).doesNotContainKey("android_id");
  }

  // ---------------------------------------------------------------------------
  // GAID and device.id coexist; android_id absent when GAID present
  // ---------------------------------------------------------------------------

  /**
   * When GAID is present, the payload device has {@code id} and {@code advertisingId} but NOT
   * {@code android_id} — the worker ensures these are mutually exclusive.
   */
  @Test
  public void gaidTakesPrecedenceOverAndroidIdAndDeviceIdCoexists() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "keystore-uuid",
            "test-gaid-5678",
            /* adTrackingEnabled= */ true,
            /* androidId= */ null);

    AnalyticsContext payloadContext = buildPayloadContext("keystore-uuid");

    BasePayload payload = mock(BasePayload.class);
    when(payload.context()).thenReturn(payloadContext);

    Middleware.Chain chain = mock(Middleware.Chain.class);
    when(chain.payload()).thenReturn(payload);

    AttributionMiddleware middleware = new AttributionMiddleware(sourceContext);
    middleware.intercept(chain);

    verify(chain).proceed(payload);

    AnalyticsContext.Device resultDevice = payloadContext.device();
    assertThat(resultDevice).isNotNull();
    assertThat(resultDevice).containsEntry("id", "keystore-uuid");
    assertThat(resultDevice).containsEntry("advertisingId", "test-gaid-5678");
    assertThat(resultDevice).doesNotContainKey("android_id");
  }

  // ---------------------------------------------------------------------------
  // putDevice never captures android_id (capture is the worker's responsibility)
  // ---------------------------------------------------------------------------

  /**
   * {@link AnalyticsContext#putDevice} must never write {@code android_id} — capture was moved to
   * {@link GetAdvertisingIdWorker} so the decision is deferred until the {@code limit_ad_tracking}
   * state is known.
   */
  @Test
  public void putDevice_doesNotCaptureAndroidId() {
    Traits traits = Traits.create();
    AnalyticsContext ctx = Utils.createContext(traits);
    ctx.putDevice(mock(Context.class), /* collectDeviceID= */ false);
    assertThat(ctx.device()).isNotNull();
    assertThat(ctx.device()).doesNotContainKey("android_id");
  }

  // ---------------------------------------------------------------------------
  // Device.putAndroidId() placeholder filtering
  // (Pure-JVM tests: Device is a LinkedHashMap subclass, no Robolectric required.
  // Long-term home is AnalyticsContextTest once Robolectric is upgraded — FU1.)
  // ---------------------------------------------------------------------------

  @Test
  public void putAndroidId_validId_stored() {
    AnalyticsContext.Device device = new AnalyticsContext.Device();
    device.putAndroidId("abc123valid");
    assertThat(device).containsEntry("android_id", "abc123valid");
  }

  @Test
  public void putAndroidId_placeholder9774_filtered() {
    AnalyticsContext.Device device = new AnalyticsContext.Device();
    device.putAndroidId("9774d56d682e549c");
    assertThat(device).doesNotContainKey("android_id");
  }

  @Test
  public void putAndroidId_placeholder0000_filtered() {
    AnalyticsContext.Device device = new AnalyticsContext.Device();
    device.putAndroidId("0000000000000000");
    assertThat(device).doesNotContainKey("android_id");
  }

  @Test
  public void putAndroidId_unknown_filtered() {
    AnalyticsContext.Device device = new AnalyticsContext.Device();
    device.putAndroidId("unknown");
    assertThat(device).doesNotContainKey("android_id");
  }

  @Test
  public void putAndroidId_empty_filtered() {
    AnalyticsContext.Device device = new AnalyticsContext.Device();
    device.putAndroidId("");
    assertThat(device).doesNotContainKey("android_id");
  }

  @Test
  public void putAndroidId_null_filtered() {
    AnalyticsContext.Device device = new AnalyticsContext.Device();
    device.putAndroidId(null);
    assertThat(device).doesNotContainKey("android_id");
  }
}
