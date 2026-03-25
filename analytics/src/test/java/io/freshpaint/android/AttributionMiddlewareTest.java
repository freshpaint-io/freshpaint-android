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

import io.freshpaint.android.integrations.BasePayload;
import java.util.LinkedHashMap;
import org.junit.Test;

/**
 * Pure JVM unit tests for {@link AttributionMiddleware}.
 *
 * <p>No Robolectric — Robolectric 3.5 is incompatible with Java 17. {@link AttributionMiddleware}
 * has no real Android dependency; it only reads from {@link AnalyticsContext.Device} (a {@link
 * ValueMap} / {@link LinkedHashMap} subclass) and calls {@code chain.proceed(payload)}. {@link
 * AnalyticsContext} and {@link AnalyticsContext.Device} are constructed via their package-private
 * map-based constructors, which are accessible from this package.
 */
public class AttributionMiddlewareTest {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Build a source {@link AnalyticsContext} that already has device advertising fields populated.
   */
  private AnalyticsContext buildSourceContext(
      String deviceId, String gaid, boolean adTrackingEnabled) {
    LinkedHashMap<String, Object> deviceMap = new LinkedHashMap<>();
    if (deviceId != null) {
      deviceMap.put("id", deviceId);
    }
    if (gaid != null) {
      deviceMap.put("advertisingId", gaid);
      deviceMap.put("adTrackingEnabled", adTrackingEnabled);
    }
    deviceMap.put("limit_ad_tracking", !adTrackingEnabled);

    LinkedHashMap<String, Object> contextMap = new LinkedHashMap<>();
    contextMap.put("device", deviceMap);
    return new AnalyticsContext(contextMap);
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
   * M2 fix: source device has gaid + limit_ad_tracking=false + device_id → payload device must
   * receive all three values, including {@code "id"} (device_id).
   */
  @Test
  public void enrichesPayloadDeviceWithGaidAndLimitAdTracking() {
    AnalyticsContext sourceContext =
        buildSourceContext("test-device-id", "test-gaid-1234", /* adTrackingEnabled= */ true);

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
    // M2: device_id must also be propagated
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
        buildSourceContext("test-device-id", /* gaid= */ null, /* adTrackingEnabled= */ false);

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
}
