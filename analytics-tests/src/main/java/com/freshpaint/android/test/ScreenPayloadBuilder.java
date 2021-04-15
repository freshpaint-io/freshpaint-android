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
package com.freshpaint.android.test;

import static com.freshpaint.android.Utils.createContext;
import static com.freshpaint.android.Utils.createTraits;

import com.freshpaint.android.AnalyticsContext;
import com.freshpaint.android.Options;
import com.freshpaint.android.Properties;
import com.freshpaint.android.Traits;
import com.freshpaint.android.integrations.ScreenPayload;

@Deprecated
public class ScreenPayloadBuilder {

  private AnalyticsContext context;
  private Traits traits;
  private String category;
  private String name;
  private Properties properties;
  private Options options;

  public ScreenPayloadBuilder context(AnalyticsContext context) {
    this.context = context;
    return this;
  }

  public ScreenPayloadBuilder traits(Traits traits) {
    this.traits = traits;
    return this;
  }

  public ScreenPayloadBuilder category(String category) {
    this.category = category;
    return this;
  }

  public ScreenPayloadBuilder name(String name) {
    this.name = name;
    return this;
  }

  public ScreenPayloadBuilder properties(Properties properties) {
    this.properties = properties;
    return this;
  }

  public ScreenPayloadBuilder options(Options options) {
    this.options = options;
    return this;
  }

  public ScreenPayload build() {
    if (traits == null) {
      traits = createTraits();
    }
    if (context == null) {
      context = createContext(traits);
    }
    if (options == null) {
      options = new Options();
    }
    if (category == null && name == null) {
      category = "foo";
      name = "bar";
    }
    if (properties == null) {
      properties = new Properties();
    }

    return new ScreenPayload.Builder()
        .category(category)
        .name(name)
        .properties(properties)
        .anonymousId(traits.anonymousId())
        .context(context)
        .integrations(options.integrations())
        .build();
  }
}
