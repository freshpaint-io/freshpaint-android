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

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Minimal in-memory {@link SharedPreferences} for unit tests. Persists all edits immediately (no
 * commit/apply distinction). Supports {@code String}, {@code int}, {@code long}, and {@code
 * boolean} values.
 *
 * <p>Shared by {@link InstallReferrerManagerTest} and {@link FreshpaintInstallEventTest}.
 */
class FakeSharedPreferences implements SharedPreferences {

  final Map<String, Object> store = new HashMap<>();

  @Override
  public Map<String, ?> getAll() {
    return store;
  }

  @Override
  public String getString(String key, String def) {
    Object val = store.get(key);
    return val instanceof String ? (String) val : def;
  }

  @Override
  public Set<String> getStringSet(String k, Set<String> d) {
    return d;
  }

  @Override
  public int getInt(String key, int def) {
    Object val = store.get(key);
    return val instanceof Integer ? (Integer) val : def;
  }

  @Override
  public long getLong(String key, long def) {
    Object val = store.get(key);
    return val instanceof Long ? (Long) val : def;
  }

  @Override
  public float getFloat(String k, float d) {
    return d;
  }

  @Override
  public boolean getBoolean(String key, boolean def) {
    Object val = store.get(key);
    return val instanceof Boolean ? (Boolean) val : def;
  }

  @Override
  public boolean contains(String k) {
    return store.containsKey(k);
  }

  @Override
  public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

  @Override
  public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

  @Override
  public Editor edit() {
    return new Editor() {
      @Override
      public Editor putString(String key, String value) {
        store.put(key, value);
        return this;
      }

      @Override
      public Editor putStringSet(String k, Set<String> v) {
        return this;
      }

      @Override
      public Editor putInt(String key, int value) {
        store.put(key, value);
        return this;
      }

      @Override
      public Editor putLong(String key, long value) {
        store.put(key, value);
        return this;
      }

      @Override
      public Editor putFloat(String k, float v) {
        return this;
      }

      @Override
      public Editor putBoolean(String key, boolean value) {
        store.put(key, value);
        return this;
      }

      @Override
      public Editor remove(String key) {
        store.remove(key);
        return this;
      }

      @Override
      public Editor clear() {
        store.clear();
        return this;
      }

      @Override
      public boolean commit() {
        return true;
      }

      @Override
      public void apply() {
        // Changes already written by put* above.
      }
    };
  }
}
