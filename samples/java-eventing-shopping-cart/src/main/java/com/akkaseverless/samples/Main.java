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

// tag::ToProductPopularityAction[]
// tag::RegisterEventSourcedEntity[]
package com.akkaseverless.samples;

import com.akkaserverless.javasdk.AkkaServerless;
// end::RegisterEventSourcedEntity[]
// end::ToProductPopularityAction[]
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shopping.cart.ShoppingCartAnalyticsAction;
import shopping.cart.ShoppingCartTopicAction;
import shopping.cart.ShoppingCartView;
import shopping.cart.TopicPublisherAction;
import shopping.cart.actions.EventsToTopicPublisher;
import shopping.cart.actions.ShoppingCartAnalytics;
import shopping.cart.api.ShoppingCartTopic;
import shopping.cart.api.ShoppingCartApi;
import shopping.cart.domain.ShoppingCartDomain;
import shopping.cart.view.ShoppingCartViewModel;
import shopping.product.ToProductPopularityAction;
import shopping.product.actions.ToProductPopularity;
import shopping.product.api.ProductPopularityApi;
import shopping.product.domain.ProductPopularityDomain;
// tag::ToProductPopularityAction[]
// tag::RegisterEventSourcedEntity[]


import static com.akkaseverless.samples.MainComponentRegistrations.withGeneratedComponentsAdded;

public final class Main {

  private static final Logger LOG = LoggerFactory.getLogger(Main.class);

  public static final AkkaServerless SERVICE =
      withGeneratedComponentsAdded(new AkkaServerless())
          // consume shopping cart events emitted from the ShoppingCartEntity
          // and publish as is to 'shopping-cart-events' topic
          .registerAction(
              TopicPublisherAction.class,
              EventsToTopicPublisher.getDescriptor()
                  .findServiceByName("EventsToTopicPublisherService"))
          .registerAction(
              ShoppingCartTopicAction.class,
              ShoppingCartTopic.getDescriptor().findServiceByName("ShoppingCartTopicService"))

          // consume shopping cart events published to 'shopping-cart-events' topic
          .registerAction(
              ShoppingCartAnalyticsAction.class,
              ShoppingCartAnalytics.getDescriptor()
                  .findServiceByName("ShoppingCartAnalyticsService"))

          // consume shopping cart events emitted from the ShoppingCartEntity
          // and send as commands to ProductPopularityEntity
          // tag::ToProductPopularityAction[]
          .registerAction(
              ToProductPopularityAction.class,
              ToProductPopularity.getDescriptor().findServiceByName("ToProductPopularityService"))

          // view of the shopping carts
          .registerView(
              ShoppingCartView.class,
              shopping.cart.view.ShoppingCartViewModel.getDescriptor()
                  .findServiceByName("ShoppingCartViewService"),
              "carts",
              ShoppingCartDomain.getDescriptor(),
              ShoppingCartViewModel.getDescriptor());
  // tag::ToProductPopularityAction[]
  // tag::RegisterEventSourcedEntity[]

  public static void main(String[] args) throws Exception {
    LOG.info("starting the Akka Serverless service");
    SERVICE.start().toCompletableFuture().get();
  }
}
// end::ToProductPopularityAction[]
// end::RegisterEventSourcedEntity[]
