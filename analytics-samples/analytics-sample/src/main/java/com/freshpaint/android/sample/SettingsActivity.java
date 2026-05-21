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

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.content.ContextCompat;
import com.google.android.material.switchmaterial.SwitchMaterial;
import io.freshpaint.android.Freshpaint;
import io.freshpaint.android.sample.BuildConfig;
import io.freshpaint.android.sample.R;

public class SettingsActivity extends BaseSampleActivity {
  private TextView configurationBadge;
  private TextView configurationStatus;
  private TextView trackingStatus;
  private TextView trackingDetail;
  private SwitchMaterial trackingSwitch;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_settings);
    setupChrome(R.id.nav_settings, R.string.settings_screen_title, R.string.settings_title);

    configurationBadge = findViewById(R.id.configuration_badge);
    configurationStatus = findViewById(R.id.configuration_status);
    trackingStatus = findViewById(R.id.tracking_status);
    trackingDetail = findViewById(R.id.tracking_detail);
    trackingSwitch = findViewById(R.id.tracking_switch);

    renderConfigurationState();
    renderTrackingState(isTrackingEnabled());
    trackingSwitch.setOnCheckedChangeListener(
        (buttonView, isChecked) -> {
          if (!buttonView.isPressed()) {
            return;
          }
          onTrackingToggleChanged(isChecked);
        });
  }

  private void renderConfigurationState() {
    SampleApp sampleApp = (SampleApp) getApplication();
    if (sampleApp.isFreshpaintConfigured()) {
      configurationBadge.setText(R.string.sdk_connected);
      configurationBadge.setBackgroundResource(R.drawable.sample_status_positive);
      configurationBadge.setTextColor(ContextCompat.getColor(this, R.color.sample_success));
      configurationStatus.setText(R.string.configured_status);
    } else {
      configurationBadge.setText(R.string.sdk_not_connected);
      configurationBadge.setBackgroundResource(R.drawable.sample_status_warning);
      configurationBadge.setTextColor(ContextCompat.getColor(this, R.color.sample_warning));
      configurationStatus.setText(R.string.missing_write_key_status);
    }
  }

  private void onTrackingToggleChanged(boolean enabled) {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      trackingSwitch.setChecked(false);
      renderTrackingState(false);
      return;
    }

    freshpaint.optOut(!enabled);
    renderTrackingState(enabled);
    Toast.makeText(
            this,
            getString(
                enabled ? R.string.tracking_enabled_success : R.string.tracking_disabled_success),
            Toast.LENGTH_SHORT)
        .show();
  }

  private void renderTrackingState(boolean enabled) {
    trackingSwitch.setChecked(enabled);
    if (enabled) {
      trackingSwitch.setText(R.string.tracking_enabled);
      trackingStatus.setText(R.string.tracking_enabled);
      trackingStatus.setBackgroundResource(R.drawable.sample_status_positive);
      trackingStatus.setTextColor(ContextCompat.getColor(this, R.color.sample_success));
      trackingDetail.setText(R.string.tracking_enabled_detail);
    } else {
      trackingSwitch.setText(R.string.tracking_disabled);
      trackingStatus.setText(R.string.tracking_disabled);
      trackingStatus.setBackgroundResource(R.drawable.sample_status_warning);
      trackingStatus.setTextColor(ContextCompat.getColor(this, R.color.sample_warning));
      trackingDetail.setText(R.string.tracking_disabled_detail);
    }
  }

  private boolean isTrackingEnabled() {
    SampleApp sampleApp = (SampleApp) getApplication();
    if (!sampleApp.isFreshpaintConfigured()) {
      return false;
    }
    String tag = BuildConfig.FRESHPAINT_SAMPLE_WRITE_KEY.trim();
    SharedPreferences prefs = getSharedPreferences("analytics-android-" + tag, MODE_PRIVATE);
    return !prefs.getBoolean("opt-out", false);
  }
}
