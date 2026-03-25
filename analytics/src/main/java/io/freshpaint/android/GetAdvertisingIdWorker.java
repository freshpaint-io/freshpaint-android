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

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings.Secure;
import android.util.Pair;
import io.freshpaint.android.integrations.Logger;
import java.util.concurrent.CountDownLatch;

/**
 * A {@link Runnable} that fetches the advertising info and attaches it to the given {@link
 * AnalyticsContext} instance. Submitted to an {@link java.util.concurrent.ExecutorService} instead
 * of using the deprecated {@link android.os.AsyncTask}.
 */
class GetAdvertisingIdWorker implements Runnable {

  private final AnalyticsContext analyticsContext;
  private final CountDownLatch latch;
  private final Logger logger;
  private final Context context;

  GetAdvertisingIdWorker(
      AnalyticsContext analyticsContext, CountDownLatch latch, Logger logger, Context context) {
    this.analyticsContext = analyticsContext;
    this.latch = latch;
    this.logger = logger;
    this.context = context;
  }

  private Pair<String, Boolean> getGooglePlayServicesAdvertisingID() throws Exception {
    Object advertisingInfo =
        Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient")
            .getMethod("getAdvertisingIdInfo", Context.class)
            .invoke(null, context);
    Boolean isLimitAdTrackingEnabled =
        (Boolean)
            advertisingInfo
                .getClass()
                .getMethod("isLimitAdTrackingEnabled")
                .invoke(advertisingInfo);

    if (isLimitAdTrackingEnabled) {
      logger.debug(
          "Not collecting advertising ID because isLimitAdTrackingEnabled (Google Play Services) is true.");
      return Pair.create(null, false);
    }

    String advertisingId =
        (String) advertisingInfo.getClass().getMethod("getId").invoke(advertisingInfo);
    return Pair.create(advertisingId, true);
  }

  private Pair<String, Boolean> getAmazonFireAdvertisingID() throws Exception {
    ContentResolver contentResolver = context.getContentResolver();

    // Ref: http://prateeks.link/2uGs6bf
    // limit_ad_tracking != 0 indicates user wants to limit ad tracking.
    boolean limitAdTracking = Secure.getInt(contentResolver, "limit_ad_tracking") != 0;

    if (limitAdTracking) {
      logger.debug(
          "Not collecting advertising ID because limit_ad_tracking (Amazon Fire OS) is true.");
      return Pair.create(null, false);
    }

    String advertisingId = Secure.getString(contentResolver, "advertising_id");
    return Pair.create(advertisingId, true);
  }

  @Override
  public void run() {
    Pair<String, Boolean> info = null;
    try {
      try {
        info = getGooglePlayServicesAdvertisingID();
      } catch (Exception e) {
        logger.error(e, "Unable to collect advertising ID from Google Play Services.");
      }
      if (info == null) {
        try {
          info = getAmazonFireAdvertisingID();
        } catch (Exception e) {
          logger.error(e, "Unable to collect advertising ID from Amazon Fire OS.");
        }
      }
      if (info == null) {
        logger.debug(
            "Unable to collect advertising ID from Amazon Fire OS and Google Play Services.");
        return;
      }
      AnalyticsContext.Device device = analyticsContext.device();
      if (device == null) {
        logger.debug("Not collecting advertising ID because context.device is null.");
        return;
      }
      device.putAdvertisingInfo(info.first, info.second);
    } finally {
      latch.countDown();
    }
  }
}
