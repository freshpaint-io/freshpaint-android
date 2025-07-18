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
package io.freshpaint.android.integrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Date;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.assertj.core.data.MapEntry;
import org.junit.Before;
import org.junit.Test;

public class BasePayloadTest {

  private List<BasePayload.Builder<? extends BasePayload, ? extends BasePayload.Builder<?, ?>>>
      builders;

  @Before
  public void setUp() {
    builders =
        ImmutableList.of(
            new AliasPayload.Builder().previousId("previousId").userId("userId"),
            new TrackPayload.Builder().event("event"),
            new ScreenPayload.Builder().name("name"),
            new GroupPayload.Builder().groupId("groupId"),
            new IdentifyPayload.Builder().traits(ImmutableMap.<String, Object>of("foo", "bar")));
  }

  @Test
  public void channelIsSet() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload = builder.userId("user_id").build();
      Assertions.assertThat(payload)
          .containsEntry(BasePayload.CHANNEL_KEY, BasePayload.Channel.mobile);
    }
  }

  @Test
  public void nullTimestampThrows() {
    for (int i = 1; i < builders.size(); i++) {
      BasePayload.Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.timestamp(null);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("timestamp == null");
      }
    }
  }

  @Test
  public void timestamp() {
    Date timestamp = new Date();
    for (BasePayload.Builder builder : builders) {
      BasePayload payload = builder.userId("user_id").timestamp(timestamp).build();
      assertThat(payload.timestamp()).isEqualTo(timestamp);
      Assertions.assertThat(payload).containsKey(BasePayload.TIMESTAMP_KEY);
    }
  }

  @Test
  public void type() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload = builder.userId("user_id").build();
      assertThat(payload.type())
          .isIn(
              BasePayload.Type.alias,
              BasePayload.Type.track,
              BasePayload.Type.screen,
              BasePayload.Type.group,
              BasePayload.Type.identify);
      Assertions.assertThat(payload).containsKey(BasePayload.TYPE_KEY);
    }
  }

  @Test
  public void anonymousId() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload = builder.anonymousId("anonymous_id").build();
      assertThat(payload.anonymousId()).isEqualTo("anonymous_id");
      Assertions.assertThat(payload).containsEntry(BasePayload.ANONYMOUS_ID_KEY, "anonymous_id");
    }
  }

  @Test
  public void invalidUserIdThrows() {
    for (int i = 1; i < builders.size(); i++) {
      BasePayload.Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.userId(null);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("userId cannot be null or empty");
      }

      try {
        //noinspection CheckResult
        builder.userId("");
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("userId cannot be null or empty");
      }
    }
  }

  @Test
  public void userId() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload = builder.userId("user_id").build();
      assertThat(payload.userId()).isEqualTo("user_id");
      Assertions.assertThat(payload).containsEntry(BasePayload.USER_ID_KEY, "user_id");
    }
  }

  @Test
  public void requiresUserIdOrAnonymousId() {
    for (int i = 1; i < builders.size(); i++) {
      BasePayload.Builder builder = builders.get(i);
      try {
        //noinspection CheckResult
        builder.build();
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("either userId or anonymousId is required");
      }
    }
  }

  @Test
  public void invalidMessageIdThrows() {
    for (int i = 1; i < builders.size(); i++) {
      BasePayload.Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.messageId(null);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("messageId cannot be null or empty");
      }

      try {
        //noinspection CheckResult
        builder.messageId("");
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("messageId cannot be null or empty");
      }
    }
  }

  @Test
  public void messageId() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload = builder.userId("user_id").messageId("message_id").build();
      assertThat(payload.messageId()).isEqualTo("message_id");
      Assertions.assertThat(payload).containsEntry(BasePayload.MESSAGE_ID, "message_id");
    }
  }

  @Test
  public void messageIdIsGenerated() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload = builder.userId("user_id").build();
      assertThat(payload.messageId()).isNotEmpty();
      Assertions.assertThat(payload).containsKey(BasePayload.MESSAGE_ID);
    }
  }

  @Test
  public void nullContextThrows() {
    for (int i = 1; i < builders.size(); i++) {
      BasePayload.Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.context(null);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("context == null");
      }
    }
  }

  @Test
  public void context() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload =
          builder.userId("user_id").context(ImmutableMap.of("foo", "bar")).build();
      Assertions.assertThat(payload.context()).containsExactly(MapEntry.entry("foo", "bar"));
      Assertions.assertThat(payload)
          .containsEntry(BasePayload.CONTEXT_KEY, ImmutableMap.of("foo", "bar"));
    }
  }

  @Test
  public void invalidIntegrationKeyThrows() {
    for (int i = 1; i < builders.size(); i++) {
      BasePayload.Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.integration(null, false);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("key cannot be null or empty");
      }

      try {
        //noinspection CheckResult,ConstantConditions
        builder.integration("", true);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("key cannot be null or empty");
      }
    }
  }

  @Test
  public void invalidIntegrationOption() {
    for (int i = 1; i < builders.size(); i++) {
      BasePayload.Builder builder = builders.get(i);

      try {
        //noinspection CheckResult,ConstantConditions
        builder.integration(null, ImmutableMap.of("foo", "bar"));
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("key cannot be null or empty");
      }

      try {
        //noinspection CheckResult,ConstantConditions
        builder.integration("", ImmutableMap.of("foo", "bar"));
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("key cannot be null or empty");
      }

      try {
        //noinspection CheckResult,ConstantConditions
        builder.integration("foo", null);
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("options cannot be null or empty");
      }

      try {
        //noinspection CheckResult,ConstantConditions
        builder.integration("bar", ImmutableMap.of());
        fail();
      } catch (NullPointerException e) {
        assertThat(e).hasMessage("options cannot be null or empty");
      }
    }
  }

  @Test
  public void integrations() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload =
          builder.userId("user_id").integrations(ImmutableMap.of("foo", "bar")).build();
      Assertions.assertThat(payload.integrations()).containsExactly(MapEntry.entry("foo", "bar"));
      Assertions.assertThat(payload)
          .containsEntry(BasePayload.INTEGRATIONS_KEY, ImmutableMap.of("foo", "bar"));
    }
  }

  @Test
  public void integration() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload = builder.userId("user_id").integration("foo", false).build();
      Assertions.assertThat(payload.integrations()).containsExactly(MapEntry.entry("foo", false));
    }
  }

  @Test
  public void integrationOptions() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload =
          builder.userId("user_id").integration("foo", ImmutableMap.of("bar", true)).build();
      Assertions.assertThat(payload.integrations())
          .containsExactly(MapEntry.entry("foo", ImmutableMap.of("bar", true)));
    }
  }

  @Test
  public void putValue() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload = builder.userId("user_id").build().putValue("foo", "bar");
      Assertions.assertThat(payload).containsEntry("foo", "bar");
    }
  }

  @Test
  public void builderCopy() {
    for (BasePayload.Builder builder : builders) {
      BasePayload payload =
          builder.userId("user_id").build().toBuilder().userId("a_new_user_id").build();
      assertThat(payload.userId()).isEqualTo("a_new_user_id");
    }
  }
}
