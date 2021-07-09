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

package com.lightbend.akkasls.codegen
package java

import com.google.common.base.Charsets
import org.bitbucket.inkytonik.kiama.output.PrettyPrinterTypes.Document

import _root_.java.nio.file.{Files, Path}
import com.lightbend.akkasls.codegen.ModelBuilder.EventSourcedEntity
import com.lightbend.akkasls.codegen.ModelBuilder.ValueEntity

/**
 * Responsible for generating Java source from an entity model
 */
object EntityServiceSourceGenerator {
  import SourceGenerator._

  /**
   * Generate Java source from entities where the target source and test source directories have no existing source.
   * Note that we only generate tests for entities where we are successful in generating an entity. The user may
   * not want a test otherwise.
   *
   * Also generates a main source file if it does not already exist.
   *
   * Impure.
   */
  def generate(
      entity: ModelBuilder.Entity,
      service: ModelBuilder.EntityService,
      sourceDirectory: Path,
      testSourceDirectory: Path,
      integrationTestSourceDirectory: Path,
      generatedSourceDirectory: Path,
      mainClassPackageName: String,
      mainClassName: String
  ): Iterable[Path] = {
    val packageName = entity.fqn.parent.javaPackage
    val className = entity.fqn.name
    val packagePath = packageAsPath(packageName)

    val implClassName = className + "Impl"
    val implSourcePath =
      sourceDirectory.resolve(packagePath.resolve(implClassName + ".java"))

    val interfaceClassName = "Abstract" + className
    val interfaceSourcePath =
      generatedSourceDirectory.resolve(packagePath.resolve(interfaceClassName + ".java"))

    val _ = interfaceSourcePath.getParent.toFile.mkdirs()
    val _ = Files.write(
      interfaceSourcePath,
      interfaceSource(service, entity, packageName, className).layout.getBytes(
        Charsets.UTF_8
      )
    )

    if (!implSourcePath.toFile.exists()) {
      // We're going to generate an entity - let's see if we can generate its test...
      val testClassName = className + "Test"
      val testSourcePath =
        testSourceDirectory.resolve(packagePath.resolve(testClassName + ".java"))
      val testSourceFiles = if (!testSourcePath.toFile.exists()) {
        val _ = testSourcePath.getParent.toFile.mkdirs()
        val _ = Files.write(
          testSourcePath,
          testSource(service, entity, packageName, implClassName, testClassName).layout
            .getBytes(
              Charsets.UTF_8
            )
        )
        List(testSourcePath)
      } else {
        List.empty
      }

      // ...and then its integration test
      val integrationTestClassName = className + "IntegrationTest"
      val integrationTestSourcePath =
        integrationTestSourceDirectory
          .resolve(packagePath.resolve(integrationTestClassName + ".java"))
      val integrationTestSourceFiles = if (!integrationTestSourcePath.toFile.exists()) {
        val _ = integrationTestSourcePath.getParent.toFile.mkdirs()
        val _ = Files.write(
          integrationTestSourcePath,
          integrationTestSource(
            mainClassPackageName,
            mainClassName,
            service,
            entity,
            packageName,
            integrationTestClassName
          ).layout
            .getBytes(
              Charsets.UTF_8
            )
        )
        List(integrationTestSourcePath)
      } else {
        List.empty
      }

      // Now we generate the entity
      val _ = implSourcePath.getParent.toFile.mkdirs()
      val _ = Files.write(
        implSourcePath,
        source(
          service,
          entity,
          packageName,
          implClassName,
          interfaceClassName,
          entity.entityType
        ).layout.getBytes(
          Charsets.UTF_8
        )
      )

      List(implSourcePath, interfaceSourcePath) ++ testSourceFiles ++ integrationTestSourceFiles
    } else {
      List(interfaceSourcePath)
    }
  }

  private[codegen] def source(
      service: ModelBuilder.EntityService,
      entity: ModelBuilder.Entity,
      packageName: String,
      className: String,
      interfaceClassName: String,
      entityType: String
  ): Document = {
    entity match {
      case eventSourcedEntity: EventSourcedEntity =>
        eventSourcedEntitySource(service, eventSourcedEntity, packageName, className, interfaceClassName, entityType)
      case valueEntity: ValueEntity =>
        valueEntitySource(service, valueEntity, packageName, className, interfaceClassName, entityType)
    }
  }

  private[codegen] def eventSourcedEntitySource(
      service: ModelBuilder.EntityService,
      entity: ModelBuilder.EventSourcedEntity,
      packageName: String,
      className: String,
      interfaceClassName: String,
      entityType: String
  ): Document = {
    val messageTypes = service.commands.toSeq
        .flatMap(command => Seq(command.inputType, command.outputType)) ++
      entity.state.toSeq.map(_.fqn) ++ entity.events.map(_.fqn)

    val imports = (messageTypes
      .filterNot(_.parent.javaPackage == packageName)
      .map(typeImport) ++
    Seq(
      "com.akkaserverless.javasdk.EntityId",
      "com.akkaserverless.javasdk.eventsourcedentity.EventSourcedEntityBase"
    )).distinct.sorted

    pretty(
      initialisedCodeComment <> line <> line <>
      "package" <+> packageName <> semi <> line <>
      line <>
      ssep(
        imports.map(pkg => "import" <+> pkg <> semi),
        line
      ) <> line <>
      line <>
      "/** An event sourced entity. */" <> line <>
      "@EventSourcedEntity" <> parens(
        "entityType" <+> equal <+> dquotes(entityType)
      )
      <> line <>
      `class`("public", s"$className extends $interfaceClassName") {
        "@SuppressWarnings" <> parens(dquotes("unused")) <> line <>
        "private" <+> "final" <+> "String" <+> "entityId" <> semi <> line <>
        line <>
        constructor(
          "public",
          className,
          List("@EntityId" <+> "String" <+> "entityId")
        ) {
          "this.entityId" <+> equal <+> "entityId" <> semi
        } <> line <>
        line <>
        (entity.state match {
          case Some(state) =>
            "@Override" <>
            line <>
            method(
              "public",
              qualifiedType(state.fqn),
              "snapshot",
              List.empty,
              emptyDoc
            ) {
              "// TODO: produce state snapshot here" <> line <>
              "return" <+> qualifiedType(
                state.fqn
              ) <> dot <> "newBuilder().build()" <> semi
            } <> line <>
            line <>
            "@Override" <>
            line <>
            method(
              "public",
              "void",
              "handleSnapshot",
              List(
                qualifiedType(state.fqn) <+> "snapshot"
              ),
              emptyDoc
            ) {
              "// TODO: restore state from snapshot here" <> line
            } <> line <> line
          case _ => emptyDoc
        }) <>
        ssep(
          service.commands.toSeq.map { command =>
            "@Override" <>
            line <>
            method(
              "public",
              "Effect" <> angles(qualifiedType(command.outputType)),
              lowerFirst(command.fqn.name),
              List(
                qualifiedType(command.inputType) <+> "command",
                text("CommandContext")
                <+> "ctx"
              ),
              emptyDoc
            ) {
              "return effects().failure" <> parens(notImplementedError("command", command.fqn)) <> semi
            }
          },
          line <> line
        ) <>
        (entity match {
          case ModelBuilder.EventSourcedEntity(_, _, _, events) =>
            line <>
            line <>
            ssep(
              events.toSeq.map { event =>
                "@Override" <>
                line <>
                method(
                  "public",
                  "void",
                  lowerFirst(event.fqn.name),
                  List(
                    qualifiedType(event.fqn) <+> "event"
                  ),
                  emptyDoc
                ) {
                  "throw new RuntimeException" <> parens(
                    notImplementedError("event", event.fqn)
                  ) <> semi
                }
              },
              line <> line
            )
          case _ => emptyDoc
        })
      }
    )
  }

  private[codegen] def valueEntitySource(
      service: ModelBuilder.EntityService,
      entity: ModelBuilder.ValueEntity,
      packageName: String,
      className: String,
      interfaceClassName: String,
      entityType: String
  ): Document = {
    val messageTypes = service.commands.toSeq
        .flatMap(command => Seq(command.inputType, command.outputType)) ++ Seq(entity.state.fqn)

    val imports = (messageTypes
      .filterNot(_.parent.javaPackage == packageName)
      .map(typeImport) ++
    Seq(
      "com.akkaserverless.javasdk.EntityId",
      "com.akkaserverless.javasdk.Reply",
      "com.akkaserverless.javasdk.valueentity.*"
    )).distinct.sorted

    pretty(
      initialisedCodeComment <> line <> line <>
      "package" <+> packageName <> semi <> line <>
      line <>
      ssep(
        imports.map(pkg => "import" <+> pkg <> semi),
        line
      ) <> line <>
      line <>
      "/** A value entity. */" <> line <>
      "@ValueEntity" <> parens(
        "entityType" <+> equal <+> dquotes(entityType)
      )
      <> line <>
      `class`("public", s"$className extends $interfaceClassName") {
        "@SuppressWarnings" <> parens(dquotes("unused")) <> line <>
        "private" <+> "final" <+> "String" <+> "entityId" <> semi <> line <>
        line <>
        constructor(
          "public",
          className,
          List("@EntityId" <+> "String" <+> "entityId")
        ) {
          "this.entityId" <+> equal <+> "entityId" <> semi
        } <> line <>
        line <>
        "@Override" <>
        line <>
        method(
          "public",
          qualifiedType(entity.state.fqn),
          "emptyState",
          Nil,
          emptyDoc
        )(
          "return" <+> qualifiedType(entity.state.fqn) <> ".getDefaultInstance();"
        ) <>
        line <>
        line <>
        ssep(
          service.commands.toSeq.map { command =>
            "@Override" <>
            line <>
            method(
              "public",
              "Effect" <> angles(qualifiedType(command.outputType)),
              lowerFirst(command.fqn.name),
              List(
                qualifiedType(entity.state.fqn) <+> "currentState",
                qualifiedType(command.inputType) <+> "command"
              ),
              emptyDoc
            ) {
              "return effects().error" <> parens(
                "\"The command handler for `" + command.fqn.name + "` is not implemented, yet\""
              ) <> semi
            }
          },
          line <> line
        )
      }
    )
  }

  private[codegen] def interfaceSource(
      service: ModelBuilder.EntityService,
      entity: ModelBuilder.Entity,
      packageName: String,
      className: String
  ): Document =
    entity match {
      case eventSourcedEntity: ModelBuilder.EventSourcedEntity =>
        abstractEventSourcedEntity(service, eventSourcedEntity, packageName, className)
      case valueEntity: ModelBuilder.ValueEntity =>
        abstractValueEntity(service, valueEntity, packageName, className)
    }

  private[codegen] def abstractValueEntity(
      service: ModelBuilder.EntityService,
      entity: ModelBuilder.ValueEntity,
      packageName: String,
      className: String
  ): Document = {
    val messageTypes = service.commands.toSeq
        .flatMap(command => Seq(command.inputType, command.outputType)) ++ Seq(entity.state.fqn)

    val imports = (messageTypes
      .filterNot(_.parent.javaPackage == packageName)
      .map(typeImport) ++
    Seq(
      "com.akkaserverless.javasdk.EntityId",
      "com.akkaserverless.javasdk.Reply",
      "com.akkaserverless.javasdk.valueentity.*"
    )).distinct.sorted

    pretty(
      managedCodeComment <> line <> line <>
      "package" <+> packageName <> semi <> line <>
      line <>
      ssep(
        imports.map(pkg => "import" <+> pkg <> semi),
        line
      ) <> line <>
      line <>
      "/** A value entity. */"
      <> line <>
      `class`("public abstract", "Abstract" + className, "ValueEntityBase<" + qualifiedType(entity.state.fqn) + ">") {
        line <>
        ssep(
          service.commands.toSeq.map { command =>
            "@CommandHandler" <>
            line <>
            abstractMethod(
              "public",
              "Effect" <> angles(qualifiedType(command.outputType)),
              lowerFirst(command.fqn.name),
              List(
                qualifiedType(entity.state.fqn) <+> "currentState",
                qualifiedType(command.inputType) <+> "command"
              )
            ) <> semi
          },
          line <> line
        )
      }
    )
  }

  private[codegen] def abstractEventSourcedEntity(
      service: ModelBuilder.EntityService,
      entity: ModelBuilder.EventSourcedEntity,
      packageName: String,
      className: String
  ): Document = {
    val messageTypes = service.commands.toSeq
        .flatMap(command => Seq(command.inputType, command.outputType)) ++ entity.state.toSeq
        .map(_.fqn) ++ entity.events.map(_.fqn)

    val imports = (messageTypes
      .filterNot(_.parent.javaPackage == packageName)
      .map(typeImport) ++
    Seq(
      "com.akkaserverless.javasdk.eventsourcedentity.EventSourcedEntityBase"
    )).distinct.sorted

    pretty(
      managedCodeComment <> line <> line <>
      "package" <+> packageName <> semi <> line <>
      line <>
      ssep(
        imports.map(pkg => "import" <+> pkg <> semi),
        line
      ) <> line <>
      line <>
      "/** An event sourced entity. */"
      <> line <>
      `class`("public abstract", "Abstract" + className) {
        line <>
        (entity.state match {
          case Some(state) =>
            "@Snapshot" <>
            line <>
            abstractMethod(
              "public",
              qualifiedType(state.fqn),
              "snapshot",
              List.empty
            ) <> semi <> line <>
            line <>
            "@SnapshotHandler" <>
            line <>
            abstractMethod(
              "public",
              "void",
              "handleSnapshot",
              List(
                qualifiedType(state.fqn) <+> "snapshot"
              )
            ) <> semi <> line <>
            line
          case _ => emptyDoc
        }) <>
        ssep(
          service.commands.toSeq.map { command =>
            "@CommandHandler" <>
            line <>
            abstractMethod(
              "public",
              "Effect" <> angles(qualifiedType(command.outputType)),
              lowerFirst(command.fqn.name),
              List(
                qualifiedType(command.inputType) <+> "command",
                text("CommandContext")
                <+> "ctx"
              )
            ) <> semi
          },
          line <> line
        ) <>
        line <>
        line <>
        ssep(
          entity.events.toSeq.map { event =>
            "@EventHandler" <>
            line <>
            abstractMethod(
              "public",
              "void",
              lowerFirst(event.fqn.name),
              List(
                qualifiedType(event.fqn) <+> "event"
              )
            ) <> semi
          },
          line <> line
        )
      }
    )
  }

  private[codegen] def testSource(
      service: ModelBuilder.EntityService,
      entity: ModelBuilder.Entity,
      packageName: String,
      implClassName: String,
      testClassName: String
  ): Document = {
    val messageTypes =
      service.commands.flatMap(command => Seq(command.inputType, command.outputType)) ++ (entity match {
        case _: ModelBuilder.EventSourcedEntity =>
          Seq.empty
        case ModelBuilder.ValueEntity(_, _, state) => Seq(state.fqn)
      })

    val imports = (messageTypes.toSeq
      .filterNot(_.parent.javaPackage == packageName)
      .map(typeImport) ++ Seq(
      "org.junit.Test",
      "org.mockito.*"
    ) ++ (entity match {
      case _: EventSourcedEntity =>
        Seq("com.akkaserverless.javasdk.eventsourcedentity.CommandContext")
      case _: ValueEntity =>
        Seq("com.akkaserverless.javasdk.valueentity.CommandContext")
    })).distinct.sorted

    pretty(
      initialisedCodeComment <> line <> line <>
      "package" <+> packageName <> semi <> line <>
      line <>
      ssep(
        imports.map(pkg => "import" <+> pkg <> semi),
        line
      ) <> line <>
      line <>
      "import" <+> "static" <+> "org.junit.Assert.assertThrows" <> semi <> line <>
      line <>
      `class`("public", testClassName) {
        "private" <+> "String" <+> "entityId" <+> equal <+> """"entityId1"""" <> semi <> line <>
        "private" <+> implClassName <+> "entity" <> semi <> line <>
        "private" <+> (entity match {
          case ModelBuilder.ValueEntity(_, _, state) =>
            "CommandContext" <> angles(qualifiedType(state.fqn))
          case _ =>
            "CommandContext"
        }) <+> "context" <+> equal <+> "Mockito.mock(CommandContext.class)" <> semi <> line <>
        line <>
        ssep(
          service.commands.toSeq.map {
            command =>
              "@Test" <> line <>
              method(
                "public",
                "void",
                lowerFirst(command.fqn.name) + "Test",
                List.empty,
                emptyDoc
              ) {
                "entity" <+> equal <+> "new" <+> implClassName <> parens(
                  "entityId"
                ) <> semi <> line <>
                line <>
                "// TODO: write your mock here" <> line <>
                "// Mockito.when(context.[...]).thenReturn([...]);" <> line <>
                line <>
                "// TODO: set fields in command, and update assertions to verify implementation" <> line <>
                "//" <+> "assertEquals" <> parens(
                  "[expected]" <> comma <>
                  line <> "//" <> indent("entity") <> dot <> lowerFirst(
                    command.fqn.name
                  ) <> lparen <>
                  qualifiedType(
                    command.inputType
                  ) <> dot <> "newBuilder().build(), context"
                ) <> semi <> line <>
                "//" <+> rparen <> semi <>
                (entity match {
                  case _: ModelBuilder.EventSourcedEntity =>
                    line <>
                    line <>
                    "// TODO: if you wish to verify events:" <> line <>
                    "//" <> indent("Mockito.verify(context).emit(event)") <> semi
                  case _ => emptyDoc
                })
              }
          },
          line <> line
        )
      }
    )
  }

  private[codegen] def integrationTestSource(
      mainClassPackageName: String,
      mainClassName: String,
      service: ModelBuilder.EntityService,
      entity: ModelBuilder.Entity,
      packageName: String,
      testClassName: String
  ): Document = {
    val serviceName = service.fqn.name

    val messageTypes =
      entity match {
        case _: ModelBuilder.EventSourcedEntity =>
          Seq.empty
        case ModelBuilder.ValueEntity(_, _, state) => Seq(state.fqn)
      }

    val imports = messageTypes
        .filterNot(_.parent.javaPackage == packageName)
        .map(typeImport) ++
      List(mainClassPackageName + "." + mainClassName) ++
      List(service.fqn.parent.javaPackage + "." + serviceName + "Client") ++
      Seq(
        "com.akkaserverless.javasdk.testkit.junit.AkkaServerlessTestkitResource",
        "org.junit.ClassRule",
        "org.junit.Test"
      ).distinct.sorted

    pretty(
      initialisedCodeComment <> line <> line <>
      "package" <+> packageName <> semi <> line <>
      line <>
      ssep(
        imports.map(pkg => "import" <+> pkg <> semi),
        line
      ) <> line <>
      line <>
      "import" <+> "static" <+> "java.util.concurrent.TimeUnit.*" <> semi <> line <>
      line <>
      """// Example of an integration test calling our service via the Akka Serverless proxy""" <> line <>
      """// Run all test classes ending with "IntegrationTest" using `mvn verify -Pit`""" <> line <>
      `class`("public", testClassName) {
        line <>
        "/**" <> line <>
        " * The test kit starts both the service container and the Akka Serverless proxy." <> line <>
        " */" <> line <>
        "@ClassRule" <> line <>
        field(
          "public" <+> "static" <+> "final",
          "AkkaServerlessTestkitResource",
          "testkit",
          assignmentSeparator = Some(" ")
        ) {
          "new" <+> "AkkaServerlessTestkitResource" <> parens(mainClassName + ".SERVICE") <> semi
        } <> line <>
        line <>
        "/**" <> line <>
        " * Use the generated gRPC client to call the service through the Akka Serverless proxy." <> line <>
        " */" <> line <>
        field(
          "private" <+> "final",
          serviceName + "Client",
          "client",
          assignmentSeparator = None
        )(emptyDoc) <> semi <> line <>
        line <>
        constructor(
          "public",
          testClassName,
          List.empty
        ) {
          "client" <+> equal <+> serviceName <> "Client" <> dot <> "create" <> parens(
            ssep(
              List(
                "testkit" <> dot <> "getGrpcClientSettings" <> parens(
                  emptyDoc
                ),
                "testkit" <> dot <> "getActorSystem" <> parens(emptyDoc)
              ),
              comma <> space
            )
          ) <> semi
        } <> line <>
        line <>
        ssep(
          service.commands.toSeq.map {
            command =>
              "@Test" <> line <>
              method(
                "public",
                "void",
                lowerFirst(command.fqn.name) + "OnNonExistingEntity",
                List.empty,
                "throws" <+> "Exception" <> space
              ) {
                "// TODO: set fields in command, and provide assertions to match replies" <> line <>
                "//" <+> "client" <> dot <> lowerFirst(command.fqn.name) <> parens(
                  qualifiedType(
                    command.inputType
                  ) <> dot <> "newBuilder().build()"
                ) <> line <>
                "//" <+> indent(
                  dot <> "toCompletableFuture" <> parens(emptyDoc) <> dot <> "get" <> parens(
                    ssep(List("2", "SECONDS"), comma <> space)
                  ) <> semi,
                  8
                )
              }
          },
          line <> line
        )
      }
    )
  }
}
