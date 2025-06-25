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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;
import io.freshpaint.android.Freshpaint;
import io.freshpaint.android.sample.R;
import io.github.inflationx.viewpump.ViewPumpContextWrapper;

public class MainActivity extends Activity {
  private EditText userId;

  /** Returns true if the string is null, or empty (when trimmed). */
  public static boolean isNullOrEmpty(String text) {
    return TextUtils.isEmpty(text) || text.trim().length() == 0;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setContentView(R.layout.activity_main);

    // Initialize views
    userId = findViewById(R.id.user_id);

    // Set up click listeners with lambdas
    findViewById(R.id.action_track_a).setOnClickListener(v -> onButtonAClicked());
    findViewById(R.id.action_track_b).setOnClickListener(v -> onButtonBClicked());
    findViewById(R.id.action_identify).setOnClickListener(v -> onIdentifyButtonClicked());
    findViewById(R.id.action_flush).setOnClickListener(v -> onFlushButtonClicked());
  }

  private void onButtonAClicked() {
    Freshpaint.with(this).track("This is an Android Event");
  }

  private void onButtonBClicked() {
    Freshpaint.with(this).track("Button B Clicked");
  }

  private void onIdentifyButtonClicked() {
    String id = userId.getText().toString();
    if (isNullOrEmpty(id)) {
      Toast.makeText(this, R.string.id_required, Toast.LENGTH_LONG).show();
    } else {
      Freshpaint.with(this).identify(id);
    }
  }

  private void onFlushButtonClicked() {
    Freshpaint.with(this).flush();
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    int id = item.getItemId();
    if (id == R.id.action_view_docs) {
      Intent intent =
          new Intent(
              Intent.ACTION_VIEW,
              Uri.parse("https://segment.com/docs/tutorials/quickstart-android/"));
      try {
        startActivity(intent);
      } catch (ActivityNotFoundException e) {
        Toast.makeText(this, R.string.no_browser_available, Toast.LENGTH_LONG).show();
      }
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  protected void attachBaseContext(Context newBase) {
    super.attachBaseContext(ViewPumpContextWrapper.wrap(newBase));
  }
}
