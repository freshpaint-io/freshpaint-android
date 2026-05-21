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
import static org.robolectric.annotation.Config.NONE;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.provider.Settings.Secure;
import io.freshpaint.android.integrations.Logger;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = NONE)
public class GetAdvertisingIdWorkerTest {

  @Test
  public void getAdvertisingIdAmazonFireOSLimitAdTracking0() throws Exception {
    Context context = RuntimeEnvironment.application;
    ContentResolver contentResolver = context.getContentResolver();
    Secure.putInt(contentResolver, "limit_ad_tracking", 0);
    Secure.putString(contentResolver, "advertising_id", "df07c7dc-cea7-4a89-b328-810ff5acb15d");

    CountDownLatch latch = new CountDownLatch(1);
    Traits traits = Traits.create();
    AnalyticsContext analyticsContext = AnalyticsContext.create(context, traits, true);

    GetAdvertisingIdWorker worker =
        new GetAdvertisingIdWorker(
            analyticsContext,
            latch,
            Logger.with(Freshpaint.LogLevel.VERBOSE),
            context,
            /* collectDeviceId= */ true);
    worker.run();
    latch.await();

    assertThat(analyticsContext.device())
        .containsEntry("advertisingId", "df07c7dc-cea7-4a89-b328-810ff5acb15d");
    assertThat(analyticsContext.device()).containsEntry("adTrackingEnabled", true);
    assertThat(analyticsContext.device()).containsEntry("limit_ad_tracking", false);
    assertThat(analyticsContext.device()).doesNotContainKey("android_id");
  }

  @Test
  public void getAdvertisingIdAmazonFireOSLimitAdTracking1() throws Exception {
    Context context = RuntimeEnvironment.application;
    ContentResolver contentResolver = context.getContentResolver();
    Secure.putInt(contentResolver, "limit_ad_tracking", 1);

    CountDownLatch latch = new CountDownLatch(1);
    Traits traits = Traits.create();
    AnalyticsContext analyticsContext = AnalyticsContext.create(context, traits, true);

    GetAdvertisingIdWorker worker =
        new GetAdvertisingIdWorker(
            analyticsContext,
            latch,
            Logger.with(Freshpaint.LogLevel.VERBOSE),
            context,
            /* collectDeviceId= */ true);
    worker.run();
    latch.await();

    assertThat(analyticsContext.device()).doesNotContainKey("advertisingId");
    assertThat(analyticsContext.device()).doesNotContainKey("android_id");
    assertThat(analyticsContext.device()).containsEntry("adTrackingEnabled", false);
    assertThat(analyticsContext.device()).containsEntry("limit_ad_tracking", true);
  }

  @Test
  public void bothPathsFailLatchStillCountedDown() throws Exception {
    // GPS not available (no GMS), Amazon Fire also fails because limit_ad_tracking setting is not
    // set — latch is still counted down without hanging.
    Context context = RuntimeEnvironment.application;
    // Don't set limit_ad_tracking → Settings.Secure.getInt throws exception
    CountDownLatch latch = new CountDownLatch(1);
    Traits traits = Traits.create();
    AnalyticsContext analyticsContext = AnalyticsContext.create(context, traits, true);

    GetAdvertisingIdWorker worker =
        new GetAdvertisingIdWorker(
            analyticsContext,
            latch,
            Logger.with(Freshpaint.LogLevel.VERBOSE),
            context,
            /* collectDeviceId= */ true);
    worker.run();

    assertThat(latch.getCount()).isEqualTo(0);
    // When both GAID sources fail, conservative default: no identifiers, limit_ad_tracking=true.
    assertThat(analyticsContext.device()).doesNotContainKey("advertisingId");
    assertThat(analyticsContext.device()).doesNotContainKey("android_id");
    assertThat(analyticsContext.device()).containsEntry("adTrackingEnabled", false);
    assertThat(analyticsContext.device()).containsEntry("limit_ad_tracking", true);
  }

  @Test
  public void androidIdFallback_whenGaidUnavailableAndTrackingAllowed_collectDeviceIdTrue()
      throws Exception {
    // limit_ad_tracking=0 (allowed), no advertising_id set → GAID unavailable fallback path.
    Context context = RuntimeEnvironment.application;
    ContentResolver contentResolver = context.getContentResolver();
    Secure.putInt(contentResolver, "limit_ad_tracking", 0);
    // advertising_id NOT set → Secure.getString returns null → gaid=null, adTrackingEnabled=true
    Secure.putString(contentResolver, Settings.Secure.ANDROID_ID, "test-android-id-fallback");

    CountDownLatch latch = new CountDownLatch(1);
    Traits traits = Traits.create();
    AnalyticsContext analyticsContext = AnalyticsContext.create(context, traits, true);

    GetAdvertisingIdWorker worker =
        new GetAdvertisingIdWorker(
            analyticsContext,
            latch,
            Logger.with(Freshpaint.LogLevel.VERBOSE),
            context,
            /* collectDeviceId= */ true);
    worker.run();
    latch.await();

    assertThat(analyticsContext.device()).doesNotContainKey("advertisingId");
    assertThat(analyticsContext.device()).containsEntry("android_id", "test-android-id-fallback");
    assertThat(analyticsContext.device()).containsEntry("adTrackingEnabled", true);
    assertThat(analyticsContext.device()).containsEntry("limit_ad_tracking", false);
  }

  @Test
  public void androidIdFallback_notCaptured_whenCollectDeviceIdFalse() throws Exception {
    // Same scenario as above but collectDeviceId=false → android_id must not be stored.
    Context context = RuntimeEnvironment.application;
    ContentResolver contentResolver = context.getContentResolver();
    Secure.putInt(contentResolver, "limit_ad_tracking", 0);
    Secure.putString(contentResolver, Settings.Secure.ANDROID_ID, "test-android-id-fallback");

    CountDownLatch latch = new CountDownLatch(1);
    Traits traits = Traits.create();
    AnalyticsContext analyticsContext = AnalyticsContext.create(context, traits, false);

    GetAdvertisingIdWorker worker =
        new GetAdvertisingIdWorker(
            analyticsContext,
            latch,
            Logger.with(Freshpaint.LogLevel.VERBOSE),
            context,
            /* collectDeviceId= */ false);
    worker.run();
    latch.await();

    assertThat(analyticsContext.device()).doesNotContainKey("advertisingId");
    assertThat(analyticsContext.device()).doesNotContainKey("android_id");
    assertThat(analyticsContext.device()).containsEntry("adTrackingEnabled", true);
    assertThat(analyticsContext.device()).containsEntry("limit_ad_tracking", false);
  }
}
