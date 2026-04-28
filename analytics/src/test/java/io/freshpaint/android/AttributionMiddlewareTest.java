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
   * source device has gaid + limit_ad_tracking=false + context id → payload device must
   * receive all three values, including {@code "id"}.
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
   * When a track event carries {@code limit_ad_tracking} in properties (as {@code Application Installed}
   * does) but the live device map has since been updated (e.g. GAID finished), properties must
   * match {@code context.device.limit_ad_tracking} so the payload is self-consistent.
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
  // android_id enrichment
  // ---------------------------------------------------------------------------

  /**
   * When the source device has a valid android_id, the middleware must propagate it to the payload
   * device at key {@code "android_id"}.
   */
  @Test
  public void androidIdPropagatedToPayloadDeviceWhenValid() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "dev-id",
            "gaid-1234",
            /* adTrackingEnabled= */ true,
            /* androidId= */ "test-android-id-abc");

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
    assertThat(resultDevice).containsEntry("android_id", "test-android-id-abc");
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
  // android_id does not conflict with context id or gaid
  // ---------------------------------------------------------------------------

  /**
   * When all three fields (android_id, context id, gaid) are set, the middleware must propagate all
   * three independently — no field overwrites another.
   */
  @Test
  public void androidIdDoesNotConflictWithDeviceIdOrGaid() {
    AnalyticsContext sourceContext =
        buildSourceContext(
            "keystore-uuid",
            "test-gaid-5678",
            /* adTrackingEnabled= */ true,
            /* androidId= */ "valid-android-id");

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
    // All three must coexist at their own distinct keys
    assertThat(resultDevice).containsEntry("id", "keystore-uuid");
    assertThat(resultDevice).containsEntry("advertisingId", "test-gaid-5678");
    assertThat(resultDevice).containsEntry("android_id", "valid-android-id");
  }

  // ---------------------------------------------------------------------------
  // collectDeviceID=false suppresses android_id
  // ---------------------------------------------------------------------------

  /**
   * When {@code collectDeviceID=false}, {@link AnalyticsContext#putDevice} must NOT capture {@code
   * android_id} — the hardware-identifier opt-out governs both {@code device.id} and {@code
   * android_id}. Context is never accessed in this path (the Settings.Secure read is gated), so a
   * mock Context is safe to pass.
   */
  @Test
  public void putDevice_collectDeviceIdFalse_doesNotCaptureAndroidId() {
    Traits traits = Traits.create();
    AnalyticsContext ctx = Utils.createContext(traits);
    // context is not accessed when collectDeviceID=false (Settings.Secure block is skipped)
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
