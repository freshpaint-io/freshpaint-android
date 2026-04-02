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
package com.freshpaint.android.sample;

import android.app.Application;
import android.util.Log;
import io.freshpaint.android.Freshpaint;
import io.freshpaint.android.sample.BuildConfig;
import io.github.inflationx.calligraphy3.CalligraphyConfig;
import io.github.inflationx.calligraphy3.CalligraphyInterceptor;
import io.github.inflationx.viewpump.ViewPump;

public class SampleApp extends Application {
  private Freshpaint freshpaint;

  @Override
  public void onCreate() {
    super.onCreate();

    ViewPump.init(
        ViewPump.builder()
            .addInterceptor(
                new CalligraphyInterceptor(
                    new CalligraphyConfig.Builder()
                        .setDefaultFontPath("fonts/CircularStd-Book.otf")
                        .build()))
            .build());

    String writeKey = BuildConfig.FRESHPAINT_SAMPLE_WRITE_KEY.trim();
    if (writeKey.isEmpty()) {
      Log.w(
          "Freshpaint Sample",
          "Freshpaint write key missing. Set freshpaint.sample.writeKey in Gradle or "
              + "FRESHPAINT_SAMPLE_WRITE_KEY in the environment.");
      return;
    }

    Freshpaint.Builder builder =
        new Freshpaint.Builder(this, writeKey)
            .trackApplicationLifecycleEvents()
            .trackAttributionInformation()
            .trackDeepLinks()
            .recordScreenViews()
            .sessionTimeoutSeconds(120)
            .logLevel(Freshpaint.LogLevel.VERBOSE);

    freshpaint = builder.build();
    Freshpaint.setSingletonInstance(freshpaint);

    freshpaint.onIntegrationReady(
        "Freshpaint", instance -> Log.d("Freshpaint Sample", "Freshpaint integration ready."));
  }

  public boolean isFreshpaintConfigured() {
    return freshpaint != null;
  }

  public Freshpaint getFreshpaint() {
    return freshpaint;
  }
}
