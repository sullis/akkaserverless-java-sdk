/*
 * Copyright 2019 Lightbend Inc.
 */

package com.akkaserverless.javasdk.valueentity;

/**
 * Low level interface for handling commands on a value based entity.
 *
 * <p>Generally, this should not be needed, instead, a class annotated with the {@link
 * com.akkaserverless.javasdk.valueentity.ValueEntity @ValueEntity} and similar annotations should
 * be used.
 */
public interface ValueEntityFactory {
  /**
   * Create an entity handler for the given context.
   *
   * @param context The context.
   * @return The handler for the given context.
   */
  ValueEntityHandler create(ValueEntityContext context);
}
