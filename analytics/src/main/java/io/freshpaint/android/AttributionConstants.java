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

import androidx.annotation.Nullable;

/**
 * Shared attribution constants used by Install Referrer and deep-link attribution Centralising the
 * click-id list here ensures both sources use the same set and prevents divergence.
 */
final class AttributionConstants {

  /**
   * Writes a {@code gacid} value into the caller's persistence layer (Install Referrer vs deep
   * link).
   */
  @FunctionalInterface
  interface GacidCampaignValueWriter {
    void write(String value);
  }

  private AttributionConstants() {}

  /** True if {@code s} is non-null and not empty. */
  static boolean nonEmpty(@Nullable String s) {
    return s != null && !s.isEmpty();
  }

  /**
   * Routes Google Ads {@code gacid} to the correct {@code $*_campaign_id} storage keys given which
   * click IDs are present. {@code gacid} maps to {@code $gclid_campaign_id} when {@code gclid} is
   * present, or when neither {@code wbraid} nor {@code gbraid} is present (legacy behaviour).
   *
   * <p>Callers supply {@link GacidCampaignValueWriter} callbacks because Install Referrer and
   * deep-link paths use different persistence helpers (e.g. deduplicated vs plain {@code
   * putString}).
   *
   * @param gacid non-null, non-empty Google Ads campaign identifier
   */
  static void routeGacidToGoogleCampaignIds(
      String gacid,
      boolean hasGclid,
      boolean hasWbraid,
      boolean hasGbraid,
      GacidCampaignValueWriter putGclidCampaignId,
      GacidCampaignValueWriter putWbraidCampaignId,
      GacidCampaignValueWriter putGbraidCampaignId) {
    if (hasGclid || (!hasWbraid && !hasGbraid)) {
      putGclidCampaignId.write(gacid);
    }
    if (hasWbraid) {
      putWbraidCampaignId.write(gacid);
    }
    if (hasGbraid) {
      putGbraidCampaignId.write(gacid);
    }
  }

  /**
   * Ad-platform click identifiers captured from the Play Install Referrer string and from deep-link
   * Intent URI query parameters. Each captured value is stored under the key {@code "$<id>"} with a
   * companion {@code "$<id>_creation_time"} (Unix milliseconds).
   *
   * <p>Includes web-parity IDs (e.g. {@code rdt_cid} alongside {@code rdtcid}, {@code sccid}
   * alongside {@code ScCid}) in addition to the original Android set.
   */
  static final String[] CLICK_IDS = {
    "gclid",
    "wbraid",
    "gbraid",
    "fbclid",
    "ttclid",
    "twclid",
    "msclkid",
    "irclickid",
    "epik",
    "srsltid",
    "li_fat_id",
    "ScCid",
    "obclickid",
    "mgcid",
    "criteo_campaign",
    "dclid",
    "nclk",
    "mktReqId",
    "rdtcid",
    "qclid",
    "sadclid",
    "amzn_source",
    "atclid",
    "patclid",
    "aleid",
    "cntr_auctionId",
    "ndclid",
    "gclsrc",
    "rdt_cid",
    "sapid",
    "sccid",
    "spclid",
    "ttdimp",
    "clid_src",
    "viant_clid"
  };
}
