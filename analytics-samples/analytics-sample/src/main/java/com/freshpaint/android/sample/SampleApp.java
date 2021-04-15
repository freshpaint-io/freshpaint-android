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
package io.freshpaint.android.sample;

import android.app.Application;
import android.net.Uri;
import android.util.Log;

import io.freshpaint.android.Freshpaint;
import io.freshpaint.android.ConnectionFactory;
import io.freshpaint.android.ValueMap;

import java.io.IOException;
import java.net.HttpURLConnection;

import io.github.inflationx.calligraphy3.CalligraphyConfig;
import io.github.inflationx.calligraphy3.CalligraphyInterceptor;
import io.github.inflationx.viewpump.ViewPump;

public class SampleApp extends Application {

  // https://segment.com/segment-engineering/sources/android-test/settings/keys
  private static final String ANALYTICS_WRITE_KEY = "82ef97c4-8367-4d61-b0be-261498e9dd13";

  @Override
  public void onCreate() {
    super.onCreate();

    ViewPump.init(
        ViewPump.builder()
            .addInterceptor(
                new CalligraphyInterceptor(
                    new CalligraphyConfig.Builder()
                        .setDefaultFontPath("fonts/CircularStd-Book.otf")
                        .setFontAttrId(R.attr.fontPath)
                        .build()))
            .build());

    // Initialize a new instance of the Analytics client.
    Freshpaint.Builder builder =
        new Freshpaint.Builder(this, ANALYTICS_WRITE_KEY)
            .trackApplicationLifecycleEvents()
            .trackAttributionInformation()
                .connectionFactory(new ConnectionFactory() {
                    @Override protected HttpURLConnection openConnection(String url) throws IOException {
                        String path = Uri.parse(url).getPath();
                        // Replace YOUR_PROXY_HOST with the address of your proxy, e.g. https://aba64da6.ngrok.io.
                        return super.openConnection("https://057164602100797eccb988097f7fda9e.m.pipedream.net");
                    }
                })
            .defaultProjectSettings(
                new ValueMap()
                    .putValue(
                        "integrations",
                        new ValueMap()
                            .putValue(
                                "Adjust",
                                new ValueMap()
                                    .putValue("appToken", "<>")
                                    .putValue("trackAttributionData", true))))
            .recordScreenViews();

    // Set the initialized instance as a globally accessible instance.
    Freshpaint.setSingletonInstance(builder.build());

    // Now anytime you call Analytics.with, the custom instance will be returned.
    Freshpaint freshpaint = Freshpaint.with(this);

    // If you need to know when integrations have been initialized, use the onIntegrationReady
    // listener.
    freshpaint.onIntegrationReady(
        "Freshpaint",
        new Freshpaint.Callback() {
          @Override
          public void onReady(Object instance) {
            Log.d("Freshpaint Sample", "Freshpaint integration ready.");
          }
        });
  }
}
