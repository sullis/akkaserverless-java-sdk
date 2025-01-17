/*
 * Copyright 2021 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.akkaserverless.javasdk.replicatedentity;

import com.akkaserverless.javasdk.EffectContext;
import com.akkaserverless.javasdk.MetadataContext;

import java.util.function.Consumer;

/**
 * Context for a stream cancelled event.
 *
 * <p>This is sent to callbacks registered by {@link StreamedCommandContext#onCancel(Consumer)}.
 */
public interface StreamCancelledContext
    extends ReplicatedEntityContext, EffectContext, MetadataContext {
  /**
   * The id of the command that the stream was for.
   *
   * @return The ID of the command.
   */
  long commandId();
}
