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

/**
 * A {@link Middleware} that enriches every outgoing event with attribution fields: {@code gaid},
 * {@code limit_ad_tracking}, {@code device_id}, and {@code android_id}. Values are read
 * non-blockingly from the live {@link AnalyticsContext.Device} — if GAID has not yet been fetched,
 * {@code null} is used and event dispatch is never blocked.
 */
class AttributionMiddleware implements Middleware {

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
        String androidId = sourceDevice.getString(AnalyticsContext.Device.DEVICE_ANDROID_ID_KEY);
        if (androidId != null) {
          payloadDevice.put(AnalyticsContext.Device.DEVICE_ANDROID_ID_KEY, androidId);
        }
      }
    } finally {
      chain.proceed(payload);
    }
  }
}
