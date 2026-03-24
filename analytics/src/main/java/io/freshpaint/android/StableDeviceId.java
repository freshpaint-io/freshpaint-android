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

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;
import java.util.UUID;

/**
 * Generates and persists a stable SDK installation ID backed by EncryptedSharedPreferences
 * (Android Keystore). Falls back to plain SharedPreferences if the Keystore is unavailable or
 * locked. Never throws.
 */
class StableDeviceId {

  static final String KEY_SDK_INSTALLATION_ID = "freshpaint.sdk_installation_id";
  private static final String ENCRYPTED_PREFS_FILE = "freshpaint_device_id";
  private static final String PLAIN_PREFS_FILE = "freshpaint_device_id_plain";

  private StableDeviceId() {}

  /**
   * Returns the stable SDK installation ID for this device. Generates and persists one on first
   * call. Returns a non-persisted UUID if all storage options fail. Never throws.
   */
  @NonNull
  static String get(@NonNull Context context) {
    // Try EncryptedSharedPreferences first (Keystore-backed)
    try {
      String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
      SharedPreferences prefs =
          EncryptedSharedPreferences.create(
              ENCRYPTED_PREFS_FILE,
              masterKeyAlias,
              context,
              EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
              EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
      return getOrCreate(prefs);
    } catch (Throwable ignored) {
      // Keystore unavailable, locked, or class failed to initialize — fall through
    }

    // Fallback: plain SharedPreferences
    try {
      SharedPreferences prefs =
          context.getSharedPreferences(PLAIN_PREFS_FILE, Context.MODE_PRIVATE);
      return getOrCreate(prefs);
    } catch (Throwable ignored) {
      // Storage completely unavailable — return ephemeral UUID
    }

    return UUID.randomUUID().toString();
  }

  @NonNull
  private static String getOrCreate(@NonNull SharedPreferences prefs) {
    String id = prefs.getString(KEY_SDK_INSTALLATION_ID, null);
    if (id == null) {
      id = UUID.randomUUID().toString();
      prefs.edit().putString(KEY_SDK_INSTALLATION_ID, id).apply();
    }
    return id;
  }
}
