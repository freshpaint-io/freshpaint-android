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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import io.freshpaint.android.Freshpaint;
import io.freshpaint.android.sample.R;
import io.github.inflationx.viewpump.ViewPumpContextWrapper;

abstract class BaseSampleActivity extends AppCompatActivity {

  protected void setupChrome(int selectedNavId, int titleRes, int subtitleRes) {
    WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
    LinearLayout rootContainer = findViewById(R.id.root_container);
    MaterialToolbar toolbar = findViewById(R.id.top_toolbar);
    applySystemInsets(rootContainer, toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setTitle(titleRes);
      getSupportActionBar().setSubtitle(subtitleRes);
    }

    BottomNavigationView bottomNavigation = findViewById(R.id.bottom_navigation);
    bottomNavigation.setSelectedItemId(selectedNavId);
    bottomNavigation.setOnItemSelectedListener(
        item -> {
          if (item.getItemId() == selectedNavId) {
            return true;
          }
          if (item.getItemId() == R.id.nav_home) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return true;
          }
          if (item.getItemId() == R.id.nav_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
            return true;
          }
          return false;
        });
  }

  private void applySystemInsets(View rootContainer, MaterialToolbar toolbar) {
    final int toolbarTopPadding = toolbar.getPaddingTop();
    ViewCompat.setOnApplyWindowInsetsListener(
        rootContainer,
        (view, windowInsets) -> {
          Insets systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
          toolbar.setPadding(
              toolbar.getPaddingLeft(),
              toolbarTopPadding + systemBars.top,
              toolbar.getPaddingRight(),
              toolbar.getPaddingBottom());
          return windowInsets;
        });
    ViewCompat.requestApplyInsets(rootContainer);
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.main, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == R.id.action_flush_toolbar) {
      SampleApp sampleApp = (SampleApp) getApplication();
      if (!sampleApp.isFreshpaintConfigured()) {
        Toast.makeText(this, R.string.write_key_missing_toast, Toast.LENGTH_LONG).show();
        return true;
      }
      Freshpaint freshpaint = sampleApp.getFreshpaint();
      freshpaint.flush();
      Toast.makeText(this, R.string.flush_success, Toast.LENGTH_SHORT).show();
      return true;
    }
    if (item.getItemId() == R.id.action_view_docs) {
      Intent intent =
          new Intent(
              Intent.ACTION_VIEW, Uri.parse("https://github.com/freshpaint-io/freshpaint-android"));
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

  /**
   * Returns the configured {@link Freshpaint} instance, or {@code null} after showing a toast if
   * the sample app has no write key.
   */
  protected @Nullable Freshpaint requireFreshpaint() {
    SampleApp sampleApp = (SampleApp) getApplication();
    if (!sampleApp.isFreshpaintConfigured()) {
      Toast.makeText(this, R.string.write_key_missing_toast, Toast.LENGTH_LONG).show();
      return null;
    }
    return sampleApp.getFreshpaint();
  }
}
