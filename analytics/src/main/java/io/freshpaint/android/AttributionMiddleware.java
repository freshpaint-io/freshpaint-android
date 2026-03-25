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
 * {@code limit_ad_tracking}, and {@code device_id}. Values are read non-blockingly from the live
 * {@link AnalyticsContext.Device} — if GAID has not yet been fetched, {@code null} is used and
 * event dispatch is never blocked.
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
      AnalyticsContext.Device sourceDevice = analyticsContext.device();
      if (sourceDevice != null) {
        AnalyticsContext payloadContext = payload.context();
        if (payloadContext != null) {
          AnalyticsContext.Device payloadDevice = payloadContext.device();
          if (payloadDevice != null) {
            String gaid = sourceDevice.getString("advertisingId");
            boolean limitAdTracking = sourceDevice.getBoolean("limit_ad_tracking", false);
            String deviceId = sourceDevice.getString("id");
            if (gaid != null) {
              payloadDevice.put("advertisingId", gaid);
            }
            payloadDevice.put("limit_ad_tracking", limitAdTracking);
            if (deviceId != null) {
              payloadDevice.put("id", deviceId);
            }
          }
        }
      }
    } finally {
      chain.proceed(payload);
    }
  }
}
