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

package com.akkaserverless.javasdk.impl.valueentity

import com.akkaserverless.javasdk.EntityId
import com.akkaserverless.javasdk.valueentity.{CommandContext, CommandHandler, ValueEntity}
import com.akkaserverless.testkit.TestProtocol
import com.akkaserverless.testkit.valueentity.ValueEntityMessages
import com.google.protobuf.Empty
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import java.util.Optional

import scala.collection.mutable
import scala.reflect.ClassTag

import com.akkaserverless.protocol.component.Failure
import com.akkaserverless.protocol.value_entity.ValueEntityStreamOut

class ValueEntitiesImplSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  import ValueEntitiesImplSpec._
  import ShoppingCart.Item
  import ShoppingCart.Protocol._
  import ValueEntityMessages._

  private val service: TestEntityService = ShoppingCart.testService
  private val protocol: TestProtocol = TestProtocol(service.port)

  override def afterAll(): Unit = {
    protocol.terminate()
    service.terminate()
  }

  "EntityImpl" should {
    "fail when first message is not init" in {
      service.expectLogError("Terminating entity due to unexpected failure") {
        val entity = protocol.valueEntity.connect()
        entity.send(command(1, "cart", "command"))
        val message = entity.expectNext()
        val failure = message.failure.get
        failure.description should startWith("Protocol error: Expected init message for Value Entity")
        entity.expectClosed()
      }
    }

    "fail when entity is sent multiple init" in {
      service.expectLogError("Terminating entity [cart] due to unexpected failure") {
        val entity = protocol.valueEntity.connect()
        entity.send(init(ShoppingCart.Name, "cart"))
        entity.send(init(ShoppingCart.Name, "cart"))
        entity.expect(failure("Protocol error: Value entity already inited"))
        entity.expectClosed()
      }
    }

    "fail when service doesn't exist" in {
      service.expectLogError("Terminating entity [foo] due to unexpected failure") {
        val entity = protocol.valueEntity.connect()
        entity.send(init(serviceName = "DoesNotExist", entityId = "foo"))
        entity.expect(failure("Protocol error: Service not found: DoesNotExist"))
        entity.expectClosed()
      }
    }

    "fail when command entity id is incorrect" in {
      service.expectLogError("Terminating entity [cart2] due to unexpected failure for command [foo]") {
        val entity = protocol.valueEntity.connect()
        entity.send(init(ShoppingCart.Name, "cart1"))
        entity.send(command(1, "cart2", "foo"))
        entity.expect(failure(1, "Protocol error: Receiving Value entity is not the intended recipient of command"))
        entity.expectClosed()
      }
    }

    "fail when command payload is missing" in {
      service.expectLogError("Terminating entity [cart] due to unexpected failure for command [foo]") {
        val entity = protocol.valueEntity.connect()
        entity.send(init(ShoppingCart.Name, "cart"))
        entity.send(command(1, "cart", "foo", payload = None))
        entity.expect(failure(1, "Protocol error: No command payload for Value entity"))
        entity.expectClosed()
      }
    }

    "fail when entity is sent empty message" in {
      service.expectLogError("Terminating entity [cart] due to unexpected failure") {
        val entity = protocol.valueEntity.connect()
        entity.send(init(ShoppingCart.Name, "cart"))
        entity.send(EmptyInMessage)
        entity.expect(failure("Protocol error: Value entity received empty/unknown message"))
        entity.expectClosed()
      }
    }

    "fail when command handler does not exist" in {
      service.expectLogError("Terminating entity [cart] due to unexpected failure for command [foo]") {
        val entity = protocol.valueEntity.connect()
        entity.send(init(ShoppingCart.Name, "cart"))
        entity.send(command(1, "cart", "foo"))
        entity.expect(failure(1, s"No command handler found for command [foo] on ${ShoppingCart.TestCartClass}"))
        entity.expectClosed()
      }
    }

    "fail action when command handler uses context fail" in {
      service.expectLogError(
        "Fail invoked for command [AddItem] for Value entity [cart]: Cannot add negative quantity of item [foo]"
      ) {
        val entity = protocol.valueEntity.connect()
        entity.send(init(ShoppingCart.Name, "cart"))
        entity.send(command(1, "cart", "AddItem", addItem("foo", "bar", -1)))
        entity.expect(actionFailure(1, "Cannot add negative quantity of item [foo]"))
        entity.send(command(2, "cart", "GetCart"))
        entity.expect(reply(2, EmptyCart)) // check update-then-fail doesn't change entity state

        entity.passivate()
        val reactivated = protocol.valueEntity.connect()
        reactivated.send(init(ShoppingCart.Name, "cart"))
        reactivated.send(command(1, "cart", "GetCart"))
        reactivated.expect(reply(1, EmptyCart))
        reactivated.passivate()
      }
    }

    "fail when command handler throws exception" in {
      service.expectLogError("Terminating entity [cart] due to unexpected failure for command [RemoveItem]") {
        val entity = protocol.valueEntity.connect()
        entity.send(init(ShoppingCart.Name, "cart"))
        entity.send(command(1, "cart", "RemoveItem", removeItem("foo")))
        entity.expect(
          failure(
            1,
            "Value entity unexpected failure: java.lang.RuntimeException: Boom: foo"
          )
        )
        entity.expectClosed()
      }
    }

    "manage entities with expected update commands" in {
      val entity = protocol.valueEntity.connect()
      entity.send(init(ShoppingCart.Name, "cart"))
      entity.send(command(1, "cart", "GetCart"))
      entity.expect(reply(1, EmptyCart))
      entity.send(command(2, "cart", "AddItem", addItem("abc", "apple", 1)))
      entity.expect(reply(2, EmptyJavaMessage, update(domainCart(Item("abc", "apple", 1)))))
      entity.send(command(3, "cart", "AddItem", addItem("abc", "apple", 2)))
      entity.expect(reply(3, EmptyJavaMessage, update(domainCart(Item("abc", "apple", 3)))))
      entity.send(command(4, "cart", "GetCart"))
      entity.expect(reply(4, cart(Item("abc", "apple", 3))))
      entity.send(command(5, "cart", "AddItem", addItem("123", "banana", 4)))
      entity.expect(reply(5, EmptyJavaMessage, update(domainCart(Item("abc", "apple", 3), Item("123", "banana", 4)))))

      entity.passivate()
      val reactivated = protocol.valueEntity.connect()
      reactivated.send(
        init(ShoppingCart.Name, "cart", state(domainCart(Item("abc", "apple", 3), Item("123", "banana", 4))))
      )
      reactivated.send(command(1, "cart", "AddItem", addItem("abc", "apple", 1)))
      reactivated.expect(
        reply(1, EmptyJavaMessage, update(domainCart(Item("abc", "apple", 4), Item("123", "banana", 4))))
      )
      reactivated.send(command(1, "cart", "GetCart"))
      reactivated.expect(reply(1, cart(Item("abc", "apple", 4), Item("123", "banana", 4))))
      reactivated.passivate()
    }

    "manage entities with expected delete commands" in {
      val entity = protocol.valueEntity.connect()
      entity.send(init(ShoppingCart.Name, "cart"))
      entity.send(command(1, "cart", "GetCart"))
      entity.expect(reply(1, EmptyCart))
      entity.send(command(2, "cart", "AddItem", addItem("abc", "apple", 1)))
      entity.expect(reply(2, EmptyJavaMessage, update(domainCart(Item("abc", "apple", 1)))))
      entity.send(command(3, "cart", "AddItem", addItem("abc", "apple", 2)))
      entity.expect(reply(3, EmptyJavaMessage, update(domainCart(Item("abc", "apple", 3)))))
      entity.send(command(4, "cart", "RemoveCart", removeCart("cart")))
      entity.expect(reply(4, EmptyJavaMessage, delete()))
      entity.send(command(5, "cart", "GetCart"))
      entity.expect(reply(5, EmptyCart))
      entity.passivate()
    }
  }
}

object ValueEntitiesImplSpec {
  object ShoppingCart {

    import com.example.valueentity.shoppingcart.ShoppingCartApi
    import com.example.valueentity.shoppingcart.domain.ShoppingCartDomain

    val Name: String = ShoppingCartApi.getDescriptor.findServiceByName("ShoppingCartService").getFullName

    def testService: TestEntityService = service[TestCart]

    def service[T: ClassTag]: TestEntityService =
      TestEntity.service[T](
        ShoppingCartApi.getDescriptor.findServiceByName("ShoppingCartService"),
        ShoppingCartDomain.getDescriptor
      )

    case class Item(id: String, name: String, quantity: Int)

    object Protocol {
      import scala.jdk.CollectionConverters._

      val EmptyCart: ShoppingCartApi.Cart = ShoppingCartApi.Cart.newBuilder.build

      def cart(items: Item*): ShoppingCartApi.Cart =
        ShoppingCartApi.Cart.newBuilder.addAllItems(lineItems(items)).build

      def lineItems(items: Seq[Item]): java.lang.Iterable[ShoppingCartApi.LineItem] =
        items.sortBy(_.id).map(item => lineItem(item.id, item.name, item.quantity)).asJava

      def lineItem(id: String, name: String, quantity: Int): ShoppingCartApi.LineItem =
        ShoppingCartApi.LineItem.newBuilder.setProductId(id).setName(name).setQuantity(quantity).build

      def addItem(id: String, name: String, quantity: Int): ShoppingCartApi.AddLineItem =
        ShoppingCartApi.AddLineItem.newBuilder.setProductId(id).setName(name).setQuantity(quantity).build

      def removeItem(id: String): ShoppingCartApi.RemoveLineItem =
        ShoppingCartApi.RemoveLineItem.newBuilder.setProductId(id).build

      def removeCart(id: String): ShoppingCartApi.RemoveShoppingCart =
        ShoppingCartApi.RemoveShoppingCart.newBuilder.setCartId(id).build

      def domainLineItems(items: Seq[Item]): java.lang.Iterable[ShoppingCartDomain.LineItem] =
        items.sortBy(_.id).map(item => domainLineItem(item.id, item.name, item.quantity)).asJava

      def domainLineItem(id: String, name: String, quantity: Int): ShoppingCartDomain.LineItem =
        ShoppingCartDomain.LineItem.newBuilder.setProductId(id).setName(name).setQuantity(quantity).build

      def domainCart(items: Item*): ShoppingCartDomain.Cart =
        ShoppingCartDomain.Cart.newBuilder.addAllItems(domainLineItems(items)).build
    }

    val TestCartClass: Class[_] = classOf[TestCart]

    @ValueEntity(entityType = "valuebased-entity-shopping-cart")
    class TestCart(@EntityId val entityId: String) {
      import scala.jdk.CollectionConverters._
      import scala.jdk.OptionConverters._

      @CommandHandler
      def getCart(ctx: CommandContext[ShoppingCartDomain.Cart]): ShoppingCartApi.Cart =
        ctx.getState.toScala
          .map { c =>
            val items = c.getItemsList.asScala.map(i => Item(i.getProductId, i.getName, i.getQuantity)).toSeq
            Protocol.cart(items: _*)
          }
          .getOrElse(Protocol.EmptyCart)

      @CommandHandler
      def addItem(item: ShoppingCartApi.AddLineItem, ctx: CommandContext[ShoppingCartDomain.Cart]): Empty = {
        // update and then fail on negative quantities, for testing atomicity
        val cart = updateCart(item, asMap(ctx.getState))
        val items =
          cart.values
            .map(
              i =>
                ShoppingCartDomain.LineItem
                  .newBuilder()
                  .setProductId(i.id)
                  .setName(i.name)
                  .setQuantity(i.quantity)
                  .build
            )
        ctx.updateState(ShoppingCartDomain.Cart.newBuilder().addAllItems(items.toList.asJava).build())
        if (item.getQuantity <= 0) ctx.fail(s"Cannot add negative quantity of item [${item.getProductId}]")
        Empty.getDefaultInstance
      }

      @CommandHandler
      def removeItem(item: ShoppingCartApi.RemoveLineItem, ctx: CommandContext[ShoppingCartDomain.Cart]): Empty = {
        if (true) throw new RuntimeException("Boom: " + item.getProductId) // always fail for testing
        Empty.getDefaultInstance
      }

      @CommandHandler
      def removeCart(item: ShoppingCartApi.RemoveShoppingCart, ctx: CommandContext[ShoppingCartDomain.Cart]): Empty = {
        ctx.deleteState()
        Empty.getDefaultInstance
      }

      private def updateCart(item: ShoppingCartApi.AddLineItem,
                             cart: mutable.Map[String, Item]): mutable.Map[String, Item] = {
        val currentQuantity = cart.get(item.getProductId).map(_.quantity).getOrElse(0)
        cart.update(
          item.getProductId,
          Item(item.getProductId, item.getName, currentQuantity + item.getQuantity)
        )
        cart
      }

      private def asMap(cart: Optional[ShoppingCartDomain.Cart]): mutable.Map[String, Item] = {
        val map = cart.toScala match {
          case Some(c) =>
            c.getItemsList.asScala
              .map(i => i.getProductId -> Item(i.getProductId, i.getName, i.getQuantity))
              .toMap
          case None => Map.empty
        }

        mutable.Map(map.toSeq: _*)
      }
    }
  }
}
