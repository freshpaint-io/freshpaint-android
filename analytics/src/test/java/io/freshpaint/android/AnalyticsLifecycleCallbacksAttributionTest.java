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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageInfo;
import androidx.lifecycle.LifecycleOwner;
import io.freshpaint.android.integrations.Logger;
import org.junit.Test;
import org.mockito.InOrder;

/**
 * Pure-JVM tests for the combined executor task in {@link
 * AnalyticsActivityLifecycleCallbacks#onCreate}. No Robolectric required.
 *
 * <p>{@code trackAttributionInformation()} runs before {@code
 * trackApplicationLifecycleEvents()} when attribution is enabled.
 *
 * <p>{@code trackApplicationLifecycleEvents()} fires directly (not via executor) and {@code
 * trackAttributionInformation()} is never called when attribution is disabled.
 *
 * <p>when {@code trackAttributionInformation()} throws, {@code
 * trackApplicationLifecycleEvents()} still fires and the exception is logged.
 */ 
public class AnalyticsLifecycleCallbacksAttributionTest {

  private AnalyticsActivityLifecycleCallbacks buildCallbacks(
      Freshpaint freshpaint, boolean trackAttribution) {
    return new AnalyticsActivityLifecycleCallbacks.Builder()
        .analytics(freshpaint)
        .analyticsExecutor(new TestUtils.SynchronousExecutor())
        .shouldTrackApplicationLifecycleEvents(true)
        .trackAttributionInformation(trackAttribution)
        .trackDeepLinks(false)
        .shouldRecordScreenViews(false)
        .packageInfo(new PackageInfo())
        .build();
  }

  // -------------------------------------------------------------------------
  // trackAttributionInformation executes before trackApplicationLifecycleEvents
  // -------------------------------------------------------------------------

  /**
   * When {@code trackAttributionInformation == true}, {@code onCreate()} submits a single Runnable
   * that calls {@code trackAttributionInformation()} first, then {@code
   * trackApplicationLifecycleEvents()}. With a synchronous executor both calls complete before
   * {@code onCreate()} returns, allowing InOrder verification.
   */
  @Test
  public void onCreate_withAttribution_runsAttributionBeforeLifecycleEvents() {
    Freshpaint mockFreshpaint = mock(Freshpaint.class);
    AnalyticsActivityLifecycleCallbacks callbacks = buildCallbacks(mockFreshpaint, true);

    callbacks.onCreate(mock(LifecycleOwner.class));

    InOrder order = inOrder(mockFreshpaint);
    order.verify(mockFreshpaint).trackAttributionInformation();
    order.verify(mockFreshpaint).trackApplicationLifecycleEvents();
  }

  // -------------------------------------------------------------------------
  // attribution throws → lifecycle events still fire; exception is logged
  // -------------------------------------------------------------------------

  /**
   * When {@code trackAttributionInformation()} throws a {@link RuntimeException}, the catch block
   * must log the error via {@code freshpaint.getLogger().error(...)} and then {@code
   * trackApplicationLifecycleEvents()} must still be called unconditionally.
   */
  @Test
  public void onCreate_attributionThrows_lifecycleEventsStillFire() {
    Freshpaint mockFreshpaint = mock(Freshpaint.class);
    Logger mockLogger = mock(Logger.class);
    when(mockFreshpaint.getLogger()).thenReturn(mockLogger);
    doThrow(new RuntimeException("simulated attribution failure"))
        .when(mockFreshpaint)
        .trackAttributionInformation();

    AnalyticsActivityLifecycleCallbacks callbacks = buildCallbacks(mockFreshpaint, true);
    callbacks.onCreate(mock(LifecycleOwner.class));

    // Lifecycle events must fire despite the attribution exception.
    verify(mockFreshpaint).trackApplicationLifecycleEvents();
    // Exception must be logged — not silently dropped.
    verify(mockLogger)
        .error(
            org.mockito.ArgumentMatchers.any(RuntimeException.class),
            org.mockito.ArgumentMatchers.anyString());
  }

  // -------------------------------------------------------------------------
  // without attribution, lifecycle events fire synchronously; no attribution call
  // -------------------------------------------------------------------------

  /**
   * When {@code trackAttributionInformation == false}, {@code onCreate()} calls {@code
   * trackApplicationLifecycleEvents()} directly on the calling thread without involving the
   * executor, and never calls {@code trackAttributionInformation()}.
   */
  @Test
  public void onCreate_withoutAttribution_callsLifecycleEventsDirectlyNoAttribution() {
    Freshpaint mockFreshpaint = mock(Freshpaint.class);
    AnalyticsActivityLifecycleCallbacks callbacks = buildCallbacks(mockFreshpaint, false);

    callbacks.onCreate(mock(LifecycleOwner.class));

    verify(mockFreshpaint).trackApplicationLifecycleEvents();
    verify(mockFreshpaint, never()).trackAttributionInformation();
  }
}
