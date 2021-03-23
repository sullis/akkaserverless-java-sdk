/*
 * Copyright 2019 Lightbend Inc.
 */

package com.akkaserverless.javasdk.impl.view

import java.util.Optional

import com.akkaserverless.javasdk._
import com.akkaserverless.javasdk.impl.AnySupport
import com.akkaserverless.javasdk.impl.ResolvedServiceMethod
import com.akkaserverless.javasdk.impl.ResolvedType
import com.akkaserverless.javasdk.view._
import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => ScalaPbAny}
import com.google.protobuf.{Any => JavaPbAny}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import shopping.cart.view.ShoppingCartViewModel

class AnnotationBasedViewSupportSpec extends AnyWordSpec with Matchers {
  trait BaseContext extends Context {
    override def serviceCallFactory(): ServiceCallFactory = new ServiceCallFactory {
      override def lookup[T](serviceName: String, methodName: String, messageType: Class[T]): ServiceCallRef[T] =
        throw new NoSuchElementException
    }
  }

  object MockContext extends ViewContext with BaseContext {
    override def viewId(): String = "foo"
  }

  class MockHandlerContext(override val commandName: String = "ProcessAdded",
                           override val sourceEntityId: Optional[String] = Optional.of("entity-1"),
                           override val state: Optional[JavaPbAny] = Optional.empty[JavaPbAny])
      extends HandlerContext
      with StateContext
      with BaseContext {
    override def viewId(): String = "foo"
    override def metadata(): Metadata = ???
  }

  object StateResolvedType extends ResolvedType[State] {
    override def typeClass: Class[State] = classOf[State]
    override def typeUrl: String = AnySupport.DefaultTypeUrlPrefix + "/state"
    override def parseFrom(bytes: ByteString): State = State(bytes.toStringUtf8)
    override def toByteString(value: State): ByteString = ByteString.copyFromUtf8(value.value)
  }

  object StringResolvedType extends ResolvedType[String] {
    override def typeClass: Class[String] = classOf[String]
    override def typeUrl: String = AnySupport.DefaultTypeUrlPrefix + "/string"
    override def parseFrom(bytes: ByteString): String = bytes.toStringUtf8
    override def toByteString(value: String): ByteString = ByteString.copyFromUtf8(value)
  }

  case class State(value: String)

  val anySupport = new AnySupport(Array(ShoppingCartViewModel.getDescriptor), this.getClass.getClassLoader)
  val serviceDescriptor = ShoppingCartViewModel.getDescriptor.findServiceByName("ShoppingCartViewService")

  def method(name: String = "ProcessAdded"): ResolvedServiceMethod[String, State] =
    ResolvedServiceMethod(serviceDescriptor.findMethodByName(name), StringResolvedType, StateResolvedType)

  def methodWithTwoStateParameters(name: String = "ProcessAdded"): ResolvedServiceMethod[State, State] =
    ResolvedServiceMethod(serviceDescriptor.findMethodByName(name), StateResolvedType, StateResolvedType)

  def create(behavior: AnyRef, methods: ResolvedServiceMethod[_, _]*): ViewHandler =
    new AnnotationBasedViewSupport(behavior.getClass,
                                   methods.map(m => m.descriptor.getName -> m).toMap,
                                   Some((_: ViewCreationContext) => behavior))
      .create(MockContext)

  def create(clazz: Class[_]): ViewHandler =
    new AnnotationBasedViewSupport(clazz, Map.empty[String, ResolvedServiceMethod[_, _]], factory = None)
      .create(MockContext)

  def command(str: String): JavaPbAny =
    ScalaPbAny.toJavaProto(ScalaPbAny(StringResolvedType.typeUrl, StringResolvedType.toByteString(str)))

  def state(value: String): JavaPbAny =
    ScalaPbAny.toJavaProto(ScalaPbAny(StateResolvedType.typeUrl, StateResolvedType.toByteString(State(value))))

  def decodeState(any: JavaPbAny): State = {
    any.getTypeUrl should ===(StateResolvedType.typeUrl)
    StateResolvedType.parseFrom(any.getValue)
  }

  "View annotation support" should {
    "support view construction" when {

      "there is a noarg constructor" in {
        create(classOf[NoArgConstructorTest])
      }

      "there is a constructor with a ViewCreationContext parameter" in {
        create(classOf[CreationContextArgConstructorTest])
      }

      "fail if the constructor contains an unsupported parameter" in {
        a[RuntimeException] should be thrownBy create(classOf[UnsupportedConstructorParameter])
      }

    }

    "support handlers" when {

      "no arg handler" in {
        val handler = create(new {
          @Handler
          def processAdded() = State("blah")
        }, method())
        decodeState(handler.handle(command("nothing"), new MockHandlerContext).get) should ===(State("blah"))
      }

      "msg arg handler" in {
        val handler = create(new {
          @Handler
          def processAdded(msg: String) = State(msg)
        }, method())
        decodeState(handler.handle(command("blah"), new MockHandlerContext).get) should ===(State("blah"))
      }

      "msg and state arg handler" in {
        val handler = create(new {
          @Handler
          def processAdded(msg: String, state: State): State = State(s"${state.value}-$msg")
        }, method())
        decodeState(handler.handle(command("blah"), new MockHandlerContext(state = Optional.of(state("a")))).get) should ===(
          State("a-blah")
        )
      }

      "msg and state arg handler invoked with null state" in {
        val handler = create(new {
          @Handler
          def processAdded(msg: String, state: State): State =
            if (state == null) State(msg)
            else State(s"${state.value}-$msg")
        }, method())
        decodeState(handler.handle(command("blah"), new MockHandlerContext(state = Optional.empty())).get) should ===(
          State("blah")
        )
      }

      "msg and Optional state arg handler" in {
        val handler = create(new {
          @Handler
          def processAdded(msg: String, state: Optional[State]): State =
            if (state.isPresent) State(s"${state.get.value}-$msg")
            else State(msg)
        }, method())
        decodeState(handler.handle(command("blah"), new MockHandlerContext(state = Optional.of(state("a")))).get) should ===(
          State("a-blah")
        )
      }

      "msg and Optional state arg handler invoked with empty state" in {
        val handler = create(new {
          @Handler
          def processAdded(msg: String, state: Optional[State]): State =
            if (state.isPresent) State(s"${state.get.value}-$msg")
            else State(msg)
        }, method())
        decodeState(handler.handle(command("blah"), new MockHandlerContext(state = Optional.empty())).get) should ===(
          State("blah")
        )
      }

      "msg and state of same type" in {
        // first parameter is used as the msg
        val handler = create(new {
          @Handler
          def processAdded(msg: State, state: State): State = State(s"${state.value}-${msg.value}")
        }, methodWithTwoStateParameters())
        decodeState(handler.handle(state("blah"), new MockHandlerContext(state = Optional.of(state("a")))).get) should ===(
          State("a-blah")
        )
      }

      "fail if msg and state arg handler in other order" in {
        a[RuntimeException] should be thrownBy create(new {
          @Handler
          def processAdded(state: State, msg: String): State = State(s"${state.value}-$msg")
        }, method())
      }

      "msg and ctx arg handler" in {
        val handler = create(
          new {
            @Handler
            def processAdded(msg: String, ctx: HandlerContext): State = {
              ctx.commandName() should ===("ProcessAdded")
              State(msg)
            }
          },
          method()
        )
        decodeState(handler.handle(command("blah"), new MockHandlerContext).get) should ===(State("blah"))
      }

      "msg, state and ctx arg handler" in {
        val handler = create(
          new {
            @Handler
            def processAdded(msg: String, state: State, ctx: HandlerContext): State = {
              ctx.commandName() should ===("ProcessAdded")
              State(s"${state.value}-$msg")
            }
          },
          method()
        )
        decodeState(handler.handle(command("blah"), new MockHandlerContext(state = Optional.of(state("a")))).get) should ===(
          State("a-blah")
        )
      }

      "fail if there's a bad context type" in {
        a[RuntimeException] should be thrownBy create(new {
          @Handler
          def processAdded(msg: String, ctx: BaseContext) =
            State(msg)
        }, method())
      }

      "fail if there's two handlers for the same command" in {
        a[RuntimeException] should be thrownBy create(new {
          @Handler
          def processAdded(msg: String, ctx: HandlerContext) =
            State(msg)
          @Handler
          def processAdded(msg: String) =
            State(msg)
        }, method())
      }

      "fail if there's no rpc with that name" in {
        a[RuntimeException] should be thrownBy create(new {
          @Handler
          def wrongName(msg: String) =
            State(msg)
        }, method())
      }

      "unwrap exceptions" in {
        val handler = create(new {
          @Handler
          def processAdded(): State = throw new RuntimeException("foo")
        }, method())
        val ex = the[RuntimeException] thrownBy handler.handle(command("nothing"), new MockHandlerContext)
        ex.getMessage should ===("foo")
      }

      "fail if there's a action handler" in {
        val ex = the[RuntimeException] thrownBy create(new {
            @com.akkaserverless.javasdk.action.Handler
            def processAdded(msg: String) =
              State(msg)
          }, method())
        ex.getMessage should include("Did you mean")
        ex.getMessage should include(classOf[Handler].getName)
      }

      "fail if there's a value entity command handler" in {
        val ex = the[RuntimeException] thrownBy create(new {
            @com.akkaserverless.javasdk.valueentity.CommandHandler
            def processAdded(msg: String) =
              State(msg)
          }, method())
        ex.getMessage should include("not allowed")
      }

    }

  }
}

import org.scalatest.matchers.should.Matchers._

@View
private class NoArgConstructorTest() {}

@View
private class CreationContextArgConstructorTest(ctx: ViewCreationContext) {
  ctx.viewId should ===("foo")
}

@View
private class UnsupportedConstructorParameter(foo: String)
