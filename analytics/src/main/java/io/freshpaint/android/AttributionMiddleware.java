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

import io.freshpaint.android.integrations.BasePayload;
import io.freshpaint.android.integrations.TrackPayload;

/**
 * A {@link Middleware} that enriches every outgoing event with attribution fields from the live
 * {@link AnalyticsContext.Device}, read non-blockingly — event dispatch is never blocked.
 *
 * <p>{@code advertisingId} (GAID) and {@code android_id} are mutually exclusive by the time this
 * middleware runs: {@link GetAdvertisingIdWorker} writes at most one of them to the device based on
 * the resolved {@code limit_ad_tracking} state and GAID availability.
 *
 * <p>For {@link TrackPayload}s that already include {@code limit_ad_tracking} in {@code properties}
 * (e.g. {@code Application Installed}), this value is overwritten to match {@code
 * context.device.limit_ad_tracking} so it cannot disagree with the device block after the GAID
 * worker has updated the context.
 *
 * <p>For {@code Application Installed} only, {@code properties.advertisingId} is set or updated
 * from the live device map when available at dispatch time, reconciling a snapshot taken before the
 * GAID worker completed.
 */
class AttributionMiddleware implements Middleware {

  /** Must match {@link TrackPayload}'s properties key (same package boundary). */
  private static final String TRACK_PROPERTIES_KEY = "properties";

  /** Event name for first-open install track; {@code properties.advertisingId} is reconciled. */
  private static final String APP_INSTALL_EVENT = "Application Installed";

  private final AnalyticsContext analyticsContext;

  AttributionMiddleware(AnalyticsContext analyticsContext) {
    this.analyticsContext = analyticsContext;
  }

  @Override
  public void intercept(Chain chain) {
    BasePayload payload = chain.payload();
    try {
      // Note: all early returns below still reach chain.proceed() via the finally block.
      AnalyticsContext.Device sourceDevice = analyticsContext.device();
      if (sourceDevice == null) return;

      AnalyticsContext payloadContext = payload.context();
      if (payloadContext == null) return;

      AnalyticsContext.Device payloadDevice = payloadContext.device();
      if (payloadDevice == null) return;

      // analyticsContext.device() always returns the same Device instance (memoized via
      // ValueMap.coerceToValueMap identity branch). synchronized(sourceDevice) therefore
      // shares the same monitor as synchronized putAdvertisingInfo(), eliminating the race
      // between GetAdvertisingIdWorker writes and middleware reads.
      synchronized (sourceDevice) {
        String gaid = sourceDevice.getString(AnalyticsContext.Device.DEVICE_ADVERTISING_ID_KEY);
        // Default true: if the GAID worker has not run yet, conservatively treat ad tracking as
        // limited rather than sending limit_ad_tracking=false to MMP backends prematurely.
        boolean limitAdTracking =
            sourceDevice.getBoolean(AnalyticsContext.Device.DEVICE_LIMIT_AD_TRACKING_KEY, true);
        String deviceId = sourceDevice.getString(AnalyticsContext.Device.DEVICE_ID_KEY);
        if (gaid != null) {
          payloadDevice.put(AnalyticsContext.Device.DEVICE_ADVERTISING_ID_KEY, gaid);
        }
        payloadDevice.put(AnalyticsContext.Device.DEVICE_LIMIT_AD_TRACKING_KEY, limitAdTracking);
        if (deviceId != null) {
          payloadDevice.put(AnalyticsContext.Device.DEVICE_ID_KEY, deviceId);
        }

        if (payload instanceof TrackPayload) {
          TrackPayload trackPayload = (TrackPayload) payload;
          Properties trackProps = trackPayload.properties();
          Properties merged = null;
          if (trackProps.containsKey(AnalyticsContext.Device.DEVICE_LIMIT_AD_TRACKING_KEY)) {
            merged = mutablePropertiesCopy(trackProps);
            merged.putValue(AnalyticsContext.Device.DEVICE_LIMIT_AD_TRACKING_KEY, limitAdTracking);
          }
          if (APP_INSTALL_EVENT.equals(trackPayload.event()) && gaid != null) {
            if (merged == null) {
              merged = mutablePropertiesCopy(trackProps);
            }
            merged.putValue(AnalyticsContext.Device.DEVICE_ADVERTISING_ID_KEY, gaid);
          }
          if (merged != null) {
            trackPayload.put(TRACK_PROPERTIES_KEY, merged);
          }
        }
      }
    } finally {
      chain.proceed(payload);
    }
  }

  private static Properties mutablePropertiesCopy(Properties trackProps) {
    Properties copy = new Properties(trackProps.size() + 2);
    copy.putAll(trackProps);
    return copy;
  }
}
