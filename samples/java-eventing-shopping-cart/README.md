# Eventing example

This example showcases the following eventing features:

* Publishing of events from an Event Sourced Entity to Google Pub Sub, see PublishAdded/PublishRemoved in [topic-publisher.proto](../../protocols/example/eventing/shoppingcart/topic-publisher.proto)
* Reading of events from an Event Sourced Entity and forwarding to a ValueEntity, see ForwardAdded/ForwardRemoved in [to-product-popularity.proto](../../protocols/example/eventing/shoppingcart/to-product-popularity.proto)
* Reading of events from Google Pub Sub topic, see ProcessAdded/ProcessRemoved in [shopping-cart-analytics.proto](../../protocols/example/eventing/shoppingcart/shopping-cart-analytics.proto)


## Building

To build, at a minimum you need to generate and process sources, particularly when using an IDE.

```shell
mvn compile
```

## Running Locally

In order to run your application locally, you must run the Akka Serverless proxy. The included `docker-compose` file contains the configuration required to run the proxy for a locally running application.
It also contains the configuration to start a local Google Pub/Sub emulator that the Akka Serverless proxy will connect to.
To start the proxy, run the following command from this directory:


```shell
docker-compose up
```

> On Linux this requires Docker 20.10 or later (https://github.com/moby/moby/pull/40007),
> or for a `USER_FUNCTION_HOST` environment variable to be set manually.

```shell
docker-compose -f docker-compose.yml -f docker-compose.linux.yml up
```

To start the application locally, the `exec-maven-plugin` is used. Use the following command:

* Send an AddItem command:
  ```
  grpcurl --plaintext -d '{"cart_id": "cart1", "product_id": "akka-tshirt", "name": "Akka t-shirt", "quantity": 3}' localhost:9000  shopping.cart.api.ShoppingCartService/AddItem
  ```
    * This will be published to the `shopping-cart-events` topic (via `TopicPublisherAction`) and received by the `ShoppingCartAnalyticsAction`.
    * This will be converted to commands and sent to `ProductPopularityEntity` via `ToProductPopularityAction`
* Send a GetCart command:
  ```
  grpcurl --plaintext -d '{"cart_id": "cart1"}' localhost:9000  shopping.cart.api.ShoppingCartService/GetCart
  ```
* Send a RemoveItem command:
  ```
  grpcurl --plaintext -d '{"cart_id": "cart1", "product_id": "akka-tshirt", "quantity": -1}' localhost:9000 shopping.cart.api.ShoppingCartService/RemoveItem
* Check product popularity with:
  ```
  grpcurl --plaintext -d '{"productId": "akka-tshirt"}' localhost:9000  shopping.product.api.ProductPopularityService/GetPopularity
  ```
* Send a CheckoutCart command:
  ```
  grpcurl --plaintext -d '{"cart_id": "cart1"}' localhost:9000  shopping.cart.api.ShoppingCartService/CheckoutCart
  ```
* Find carts checked out after a given time:
  ```
  grpcurl --plaintext -d '{"timestamp": "1619446995774"}' localhost:9000  shopping.cart.view.ShoppingCartViewService/GetCheckedOutCarts
  ```
* Simulate a JSON message from an external system via Google Cloud Pub/Sub:
  (JSON sent to the Pub/Sub HTTP API in base64 encoding, it gets parsed into `com.akkaserverless.samples.eventing.shoppingcart.TopicMessage`)
  ```json
  {
    "operation": "add",
    "cartId": "cart-7539",
    "productId": "akkaserverless-tshirt",
    "name": "Akka Serverless T-Shirt",
    "quantity": 5
  }
  ```

  Sending the above message to the topic:
  ```
  curl -X POST \
    http://localhost:8085/v1/projects/test/topics/shopping-cart-json:publish \
    -H 'Content-Type: application/json' \
    -d '{
    "messages": [
      {
        "attributes": {
          "Content-Type": "application/json"
        },
        "data": "ewogICJvcGVyYXRpb24iOiAiYWRkIiwKICAidXNlcklkIjogInVzZXItNzUzOSIsCiAgInByb2R1Y3RJZCI6ICJha2thc2VydmVybGVzcy10c2hpcnQiLAogICJuYW1lIjogIkFra2EgU2VydmVybGVzcyBULVNoaXJ0IiwKICAicXVhbnRpdHkiOiA1Cn0K"
      }
    ]
  }'
  ```
* Send a message with CloudEvent metadata containing the protobuf serialized message `shopping.cart.api.TopicOperation`
  the message was encoded with
  ```java
  Base64.getEncoder()
      .encodeToString(
          ShoppingCartTopic.TopicOperation.newBuilder()
              .setOperation("add")
              .setCartId("cart-0156")
              .setProductId("akkaserverless-socks")
              .setName("Akka Serverless pair of socks")
              .setQuantity(2)
              .build()
              .toByteArray())
  ```

  Sending the above message to the topic:
  ```
  curl -X POST \
    http://localhost:8085/v1/projects/test/topics/shopping-cart-protobuf-cloudevents:publish \
    -H 'Content-Type: application/json' \
    -d '{
    "messages": [
      {
        "attributes": {
          "ce-specversion": "1.0",
          "Content-Type": "application/protobuf",
          "ce-type": "shopping.cart.api.TopicOperation"
        },
        "data": "CgNhZGQSCXVzZXItMDE1NhoUYWtrYXNlcnZlcmxlc3Mtc29ja3MiHUFra2EgU2VydmVybGVzcyBwYWlyIG9mIHNvY2tzKAI="
      }
    ]
  }'
  ```

## Setup with real Google Pub/Sub

Choose the Google Cloud project to use, this uses `akka-serverless-playground`

```shell
GCP_PROJECT_ID=akka-serverless-playground
gcloud auth login
gcloud projects list
gcloud config set project ${GCP_PROJECT_ID}
# create key
gcloud iam service-accounts create akka-serverless-broker
gcloud projects add-iam-policy-binding ${GCP_PROJECT_ID} \
    --member "serviceAccount:akka-serverless-broker@${GCP_PROJECT_ID}.iam.gserviceaccount.com" \
    --role "roles/pubsub.editor"
gcloud iam service-accounts keys create keyfile.json \
    --iam-account akka-serverless-broker@${GCP_PROJECT_ID}.iam.gserviceaccount.com
```

Comment out the whole `akkaserverless.proxy.eventing` section in `proxy/core/src/main/resources/dev-mode.conf` to fully rely on `reference.conf`.

```shell
export EVENTING_SUPPORT="google-pubsub"
export PUBSUB_PROJECT_ID=${GCP_PROJECT_ID}
export PUBSUB_APPLICATION_CREDENTIALS=${PWD}/keyfile.json
```

Create the topics (subscriptions are auto-created)

```shell
gcloud beta pubsub topics create shopping-cart-events
gcloud beta pubsub topics create shopping-cart-protobuf-cloudevents
gcloud beta pubsub topics create shopping-cart-json
```

## Running integration tests locally

Run the integration tests
```
mvn -Pit verify
```
