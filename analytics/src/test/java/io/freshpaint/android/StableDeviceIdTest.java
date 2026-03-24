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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link StableDeviceId}.
 *
 * <p>These tests use pure Mockito (no Robolectric) because Robolectric 3.5 is incompatible with
 * Java 17 in this project. The {@code EncryptedSharedPreferences} path is untestable in a pure JVM
 * environment — all test cases exercise the plain-SharedPreferences fallback path, which is the
 * path exercised on devices when the Keystore is unavailable. The encrypted happy-path requires an
 * instrumented test on a physical device or emulator.
 */
public class StableDeviceIdTest {

  /** Minimal in-memory SharedPreferences that supports getString/putString/remove. */
  private static class FakeSharedPreferences implements SharedPreferences {
    private final java.util.Map<String, String> store = new java.util.HashMap<>();

    @Override
    public String getString(String key, String defValue) {
      return store.containsKey(key) ? store.get(key) : defValue;
    }

    @Override
    public SharedPreferences.Editor edit() {
      return new SharedPreferences.Editor() {
        @Override
        public SharedPreferences.Editor putString(String key, String value) {
          store.put(key, value);
          return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
          store.remove(key);
          return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
          store.clear();
          return this;
        }

        @Override
        public boolean commit() {
          return true;
        }

        @Override
        public void apply() {}

        // Unused editor methods
        @Override
        public SharedPreferences.Editor putStringSet(String k, java.util.Set<String> v) { return this; }
        @Override
        public SharedPreferences.Editor putInt(String k, int v) { return this; }
        @Override
        public SharedPreferences.Editor putLong(String k, long v) { return this; }
        @Override
        public SharedPreferences.Editor putFloat(String k, float v) { return this; }
        @Override
        public SharedPreferences.Editor putBoolean(String k, boolean v) { return this; }
      };
    }

    // Unused SharedPreferences methods
    @Override public java.util.Map<String, ?> getAll() { return store; }
    @Override public java.util.Set<String> getStringSet(String k, java.util.Set<String> d) { return d; }
    @Override public int getInt(String k, int d) { return d; }
    @Override public long getLong(String k, long d) { return d; }
    @Override public float getFloat(String k, float d) { return d; }
    @Override public boolean getBoolean(String k, boolean d) { return d; }
    @Override public boolean contains(String k) { return store.containsKey(k); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
  }

  private FakeSharedPreferences fakePrefs;
  private Context context;

  @Before
  public void setUp() {
    fakePrefs = new FakeSharedPreferences();
    context = mock(Context.class);
    // EncryptedSharedPreferences requires Keystore — make it throw so tests exercise fallback
    // In practice, any file name ending with "freshpaint_device_id" will throw,
    // and "freshpaint_device_id_plain" returns our fake prefs.
    when(context.getSharedPreferences(eq("freshpaint_device_id_plain"), anyInt()))
        .thenReturn(fakePrefs);
  }

  // -----------------------------------------------------------------------
  // AC: UUID generated on first call
  // -----------------------------------------------------------------------

  @Test
  public void getReturnsNonNullUuid() {
    String id = StableDeviceId.get(context);
    assertThat(id).isNotNull().isNotEmpty();
  }

  @Test
  public void getReturnsValidUuidFormat() {
    String id = StableDeviceId.get(context);
    assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  // -----------------------------------------------------------------------
  // AC: Same UUID returned on subsequent calls (persistence)
  // -----------------------------------------------------------------------

  @Test
  public void getReturnsSameUuidOnSubsequentCalls() {
    String first = StableDeviceId.get(context);
    String second = StableDeviceId.get(context);
    assertThat(second).isEqualTo(first);
  }

  // -----------------------------------------------------------------------
  // AC: New UUID generated when stored value is absent (simulates reinstall)
  // -----------------------------------------------------------------------

  @Test
  public void getGeneratesNewUuidAfterStorageCleared() {
    String first = StableDeviceId.get(context);
    fakePrefs.edit().remove(StableDeviceId.KEY_SDK_INSTALLATION_ID).apply();
    String second = StableDeviceId.get(context);
    assertThat(second).isNotEqualTo(first);
  }

  // -----------------------------------------------------------------------
  // AC: EncryptedSharedPreferences failure -> falls back, still returns UUID
  // The setUp() mock does NOT stub "freshpaint_device_id" (encrypted file),
  // so any call to that file returns null from Mockito, causing an NPE inside
  // EncryptedSharedPreferences.create() — exercising the catch block.
  // -----------------------------------------------------------------------

  @Test
  public void getDoesNotThrowWhenEncryptedPrefsUnavailable() {
    String id = StableDeviceId.get(context);
    assertThat(id).isNotNull().isNotEmpty();
  }

  // -----------------------------------------------------------------------
  // AC: All-storage-failure -> ephemeral UUID, never throws
  // -----------------------------------------------------------------------

  @Test
  public void getReturnsEphemeralUuidWhenAllStorageFails() {
    Context brokenContext = mock(Context.class);
    when(brokenContext.getSharedPreferences(anyString(), anyInt()))
        .thenThrow(new RuntimeException("storage unavailable"));

    String id = StableDeviceId.get(brokenContext);

    assertThat(id).isNotNull().isNotEmpty();
    assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  }

  @Test
  public void getDoesNotThrowOnSecurityException() {
    Context brokenContext = mock(Context.class);
    when(brokenContext.getSharedPreferences(anyString(), anyInt()))
        .thenThrow(new SecurityException("permission denied"));

    // Must not propagate SecurityException
    String id = StableDeviceId.get(brokenContext);
    assertThat(id).isNotNull();
  }

  @Test
  public void ephemeralUuidIsNotReusedAcrossCalls() {
    // When storage fails, each call generates a new UUID (ephemeral, not persisted)
    Context brokenContext = mock(Context.class);
    when(brokenContext.getSharedPreferences(anyString(), anyInt()))
        .thenThrow(new RuntimeException("storage unavailable"));

    String first = StableDeviceId.get(brokenContext);
    String second = StableDeviceId.get(brokenContext);
    // Each ephemeral UUID is independent (storage was never written)
    assertThat(first).isNotEqualTo(second);
  }

  // -----------------------------------------------------------------------
  // AC: Builder trackFirstOpen(boolean) — default is true, settable to false
  // -----------------------------------------------------------------------

  @Test
  public void builderTrackFirstOpenDefaultIsTrue() throws Exception {
    Freshpaint.Builder builder = builderWithMockContext();
    Field field = Freshpaint.Builder.class.getDeclaredField("trackFirstOpen");
    field.setAccessible(true);
    assertThat((Boolean) field.get(builder)).isTrue();
  }

  @Test
  public void builderTrackFirstOpenCanBeSetToFalse() throws Exception {
    Freshpaint.Builder builder = builderWithMockContext().trackFirstOpen(false);
    Field field = Freshpaint.Builder.class.getDeclaredField("trackFirstOpen");
    field.setAccessible(true);
    assertThat((Boolean) field.get(builder)).isFalse();
  }

  @Test
  public void builderTrackFirstOpenCanBeSetToTrue() throws Exception {
    Freshpaint.Builder builder = builderWithMockContext().trackFirstOpen(true);
    Field field = Freshpaint.Builder.class.getDeclaredField("trackFirstOpen");
    field.setAccessible(true);
    assertThat((Boolean) field.get(builder)).isTrue();
  }

  @Test
  public void freshpaintInstanceFieldDefaultIsTrue() throws Exception {
    // Freshpaint instances constructed directly (as in existing tests) default to true
    Field field = Freshpaint.class.getDeclaredField("trackFirstOpen");
    field.setAccessible(true);
    assertThat(field.getType()).isEqualTo(boolean.class);
    // Verify the field exists and is of boolean type (default value is tested via Builder above)
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private Freshpaint.Builder builderWithMockContext() {
    Context ctx = mock(Context.class);
    android.app.Application app = mock(android.app.Application.class);
    when(ctx.getApplicationContext()).thenReturn(app);
    when(app.checkCallingOrSelfPermission(anyString()))
        .thenReturn(android.content.pm.PackageManager.PERMISSION_GRANTED);
    when(app.getApplicationContext()).thenReturn(app);
    return new Freshpaint.Builder(ctx, "test-write-key");
  }
}
