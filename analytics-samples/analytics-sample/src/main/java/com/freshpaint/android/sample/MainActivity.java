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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import io.freshpaint.android.Freshpaint;
import io.freshpaint.android.Options;
import io.freshpaint.android.Properties;
import io.freshpaint.android.Traits;
import io.freshpaint.android.sample.R;
import java.util.Locale;

public class MainActivity extends BaseSampleActivity {
  private static final String DEMO_DEEP_LINK =
      "freshpaint-sample://mmp-demo/install"
          + "?utm_source=tiktok"
          + "&utm_medium=paid_social"
          + "&utm_campaign=spring_launch"
          + "&utm_content=video_a"
          + "&utm_term=retargeting"
          + "&gclid=test-gclid-123"
          + "&fbclid=test-fbclid-456"
          + "&ttclid=test-ttclid-789"
          + "&campaign_id=cmp_2048"
          + "&adset_id=adset_99"
          + "&ad_id=creative_12"
          + "&fp_click_id=fp-click-42";

  private EditText userId;
  private EditText email;
  private EditText companyId;
  private EditText aliasId;
  private EditText deepLinkUri;
  private EditText customEventName;
  private EditText customPropertyKey;
  private EditText customPropertyValue;
  private TextView attributionStatus;
  private TextView latestAction;
  private TextView attributionBadge;

  /** Returns true if the string is null, or empty (when trimmed). */
  public static boolean isNullOrEmpty(String text) {
    return TextUtils.isEmpty(text) || text.trim().isEmpty();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    setupChrome(R.id.nav_home, R.string.home_screen_title, R.string.toolbar_subtitle);
    userId = findViewById(R.id.user_id);
    email = findViewById(R.id.user_email);
    companyId = findViewById(R.id.company_id);
    aliasId = findViewById(R.id.alias_id);
    deepLinkUri = findViewById(R.id.deep_link_uri);
    customEventName = findViewById(R.id.custom_event_name);
    customPropertyKey = findViewById(R.id.custom_property_key);
    customPropertyValue = findViewById(R.id.custom_property_value);
    attributionBadge = findViewById(R.id.attribution_badge);
    attributionStatus = findViewById(R.id.attribution_status);
    latestAction = findViewById(R.id.latest_action);

    seedDemoValues();
    renderAttributionState(getIntent());

    findViewById(R.id.action_launch_deep_link).setOnClickListener(v -> onLaunchDeepLinkClicked());
    findViewById(R.id.action_track_install_cta).setOnClickListener(v -> onTrackInstallCtaClicked());
    findViewById(R.id.action_identify).setOnClickListener(v -> onIdentifyButtonClicked());
    findViewById(R.id.action_identify_traits)
        .setOnClickListener(v -> onIdentifyTraitsButtonClicked());
    findViewById(R.id.action_group).setOnClickListener(v -> onGroupButtonClicked());
    findViewById(R.id.action_alias).setOnClickListener(v -> onAliasButtonClicked());
    findViewById(R.id.action_screen).setOnClickListener(v -> onScreenButtonClicked());
    findViewById(R.id.action_track_purchase)
        .setOnClickListener(v -> onTrackPurchaseButtonClicked());
    findViewById(R.id.action_track_custom).setOnClickListener(v -> onTrackCustomButtonClicked());
    findViewById(R.id.action_flush).setOnClickListener(v -> onFlushButtonClicked());
    findViewById(R.id.action_reset).setOnClickListener(v -> onResetButtonClicked());
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    renderAttributionState(intent);
  }

  private void seedDemoValues() {
    userId.setText("demo-user-android");
    email.setText("growth@freshpaint.io");
    companyId.setText("acme-mobile");
    aliasId.setText("demo-user-upgraded");
    deepLinkUri.setText(DEMO_DEEP_LINK);
    customEventName.setText("Sample CTA Clicked");
    customPropertyKey.setText("placement");
    customPropertyValue.setText("pricing_screen");
  }

  private void renderAttributionState(Intent intent) {
    Uri data = intent != null ? intent.getData() : null;
    if (data == null) {
      attributionBadge.setText(R.string.attribution_waiting);
      attributionBadge.setBackgroundResource(R.drawable.sample_status_warning);
      attributionStatus.setText(getString(R.string.no_deep_link_received));
    } else {
      attributionBadge.setText(R.string.attribution_ready);
      attributionBadge.setBackgroundResource(R.drawable.sample_status_positive);
      attributionStatus.setText(getString(R.string.deep_link_received_template, data.toString()));
    }
  }

  private void onIdentifyButtonClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    String id = userId.getText().toString();
    if (isNullOrEmpty(id)) {
      Toast.makeText(this, R.string.id_required, Toast.LENGTH_LONG).show();
      return;
    }

    Traits traits =
        new Traits()
            .putEmail(email.getText().toString().trim())
            .putName("Freshpaint Android Demo User")
            .putTitle("Mobile growth lead")
            .putValue("plan", "growth");

    freshpaint.identify(id, traits, buildOptions("identify_demo"));
    showSuccess(getString(R.string.identify_success_template, id));
  }

  private void onIdentifyTraitsButtonClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    Traits traits =
        new Traits()
            .putEmail(email.getText().toString().trim())
            .putName("Traits Only Demo User")
            .putTitle("Pre-auth visitor")
            .putValue("plan", "trial");
    freshpaint.identify(traits);
    showSuccess(getString(R.string.identify_traits_success));
  }

  private void onGroupButtonClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    String groupId = companyId.getText().toString().trim();
    if (isNullOrEmpty(groupId)) {
      Toast.makeText(this, R.string.group_required, Toast.LENGTH_LONG).show();
      return;
    }

    Traits groupTraits =
        new Traits()
            .putName("Acme Mobile")
            .putIndustry("mobile games")
            .putEmployees(180)
            .putValue("plan", "enterprise");

    freshpaint.group(groupId, groupTraits, buildOptions("group_demo"));
    showSuccess(getString(R.string.group_success_template, groupId));
  }

  private void onAliasButtonClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    String newAlias = aliasId.getText().toString().trim();
    if (isNullOrEmpty(newAlias)) {
      Toast.makeText(this, R.string.alias_required, Toast.LENGTH_LONG).show();
      return;
    }

    freshpaint.alias(newAlias, buildOptions("alias_demo"));
    showSuccess(getString(R.string.alias_success_template, newAlias));
  }

  private void onScreenButtonClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    Properties properties =
        new Properties()
            .putPath("/sample/mmp-lab")
            .putReferrer("freshpaint-sample://launcher")
            .putTitle("MMP Attribution Lab");
    freshpaint.screen(null, "MMP Attribution Lab", properties, buildOptions("screen_demo"));
    showSuccess(getString(R.string.screen_success));
  }

  private void onTrackInstallCtaClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    Properties properties =
        new Properties()
            .putValue("channel", "paid_social")
            .putValue("cta_name", "Install CTA")
            .putValue("demo_mode", "mmp_attribution")
            .putValue("expected_flow", "deep_link_then_app_install");
    freshpaint.track("Install CTA Clicked", properties, buildOptions("install_cta_demo"));
    showSuccess(getString(R.string.track_install_cta_success));
  }

  private void onTrackPurchaseButtonClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    String orderId = String.format(Locale.US, "order-%d", System.currentTimeMillis());
    Properties properties =
        new Properties()
            .putOrderId(orderId)
            .putCurrency("USD")
            .putRevenue(129.99)
            .putProductId("pro_annual")
            .putSku("fp-pro-annual")
            .putCategory("subscription")
            .putValue("source", "sample_checkout");
    freshpaint.track("Order Completed", properties, buildOptions("purchase_demo"));
    showSuccess(getString(R.string.purchase_success_template, orderId));
  }

  private void onTrackCustomButtonClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    String eventName = customEventName.getText().toString().trim();
    if (isNullOrEmpty(eventName)) {
      Toast.makeText(this, R.string.custom_event_required, Toast.LENGTH_LONG).show();
      return;
    }

    Properties properties = new Properties().putValue("demo_mode", "custom_event");
    String propertyKey = customPropertyKey.getText().toString().trim();
    String propertyValue = customPropertyValue.getText().toString().trim();
    if (!isNullOrEmpty(propertyKey) && !isNullOrEmpty(propertyValue)) {
      properties.putValue(propertyKey, propertyValue);
    }
    freshpaint.track(eventName, properties, buildOptions("custom_event_demo"));
    showSuccess(getString(R.string.custom_event_success_template, eventName));
  }

  private void onLaunchDeepLinkClicked() {
    String uri = deepLinkUri.getText().toString().trim();
    if (isNullOrEmpty(uri)) {
      Toast.makeText(this, R.string.deep_link_required, Toast.LENGTH_LONG).show();
      return;
    }

    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
    intent.setPackage(getPackageName());
    try {
      startActivity(intent);
    } catch (ActivityNotFoundException e) {
      Toast.makeText(this, R.string.deep_link_launch_failed, Toast.LENGTH_LONG).show();
    }
  }

  private void onFlushButtonClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    freshpaint.flush();
    showSuccess(getString(R.string.flush_success));
  }

  private void onResetButtonClicked() {
    Freshpaint freshpaint = requireFreshpaint();
    if (freshpaint == null) {
      return;
    }

    freshpaint.reset();
    showSuccess(getString(R.string.reset_success));
  }

  private Options buildOptions(String source) {
    return new Options()
        .putContext("sample_source", source)
        .putContext("sample_surface", "analytics-sample")
        .putContext("demo_focus", "mmp_attribution");
  }

  private void showSuccess(String message) {
    latestAction.setText(getString(R.string.latest_action_template, message));
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }
}
