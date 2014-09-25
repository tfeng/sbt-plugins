/**
 * Copyright 2014 Thomas Feng
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.tfeng.sbt.plugins

import java.io.File
import java.nio.charset.Charset
import scala.collection.JavaConversions
import scala.collection.mutable.Buffer
import org.apache.avro.{Protocol, Schema, SchemaProcessor}
import org.apache.avro.compiler.idl.Idl
import org.apache.avro.compiler.specific.{InternalSpecificCompiler, ProtocolClientGenerator}
import org.apache.avro.generic.GenericData.StringType
import sbt.{AutoPlugin, Compile, Def, IO, SettingKey, Test, filesToFinder, globFilter, rebase, richFile, singleFileFinder, toGroupID}
import sbt.Keys.{baseDirectory, libraryDependencies, sourceGenerators, mappings, packageSrc, streams, target, unmanagedSourceDirectories}
import sbt.Project.inConfig

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
object SbtAvro extends AutoPlugin {

  import Keys._

  override lazy val projectSettings = settings

  lazy val settings =
    Seq(
        stringType := StringType.CharSequence,
        libraryDependencies ++= Seq(
            "org.apache.avro" % "avro" % Versions.avro,
            "org.apache.avro" % "avro-ipc" % Versions.avro),
        externalSchemaDirectories := Seq()
    ) ++
    inConfig(Compile)(Seq(
        schemataDirectories := Seq("schemata"),
        targetSchemataDirectory := (target.value.relativeTo(baseDirectory.value).get / "schemata").toString,
        mappings in packageSrc ++= createSourceMappings.value,
        packageSrc <<= packageSrc dependsOn(compileTask),
        sourceGenerators ++= Seq(mkTargetDirectory.taskValue, compileTask.taskValue),
        unmanagedSourceDirectories ++=
          schemataDirectories.value.map(schemata => baseDirectory.value / schemata) ++
          Seq(baseDirectory.value / targetSchemataDirectory.value)
    )) ++
    inConfig(Test)(Seq(
        schemataDirectories := Seq("test/resources/schemata"),
        targetSchemataDirectory := (target.value.relativeTo(baseDirectory.value).get / "test-schemata").toString,
        sourceGenerators ++= Seq(mkTargetDirectory.taskValue, compileTask.taskValue),
        unmanagedSourceDirectories += baseDirectory.value / targetSchemataDirectory.value
    ))

  object Keys {
    lazy val schemataDirectories = SettingKey[Seq[String]]("schemata-dir", "Subdirectories under project root containing avro schemas")
    lazy val targetSchemataDirectory = SettingKey[String]("target-schemata-dir", "Target directory to store compiled avro schemas")
    lazy val stringType = SettingKey[StringType]("string-type", "Java type to be emitted for string schemas")
    lazy val externalSchemaDirectories = SettingKey[Seq[File]]("external-schemata-dirs", "Directories holding external schemas")
  }

  private def mkTargetDirectory = Def.task {
    this.synchronized {
      (baseDirectory.value / (targetSchemataDirectory in Compile).value).mkdirs()
      (baseDirectory.value / (targetSchemataDirectory in Test).value).mkdirs()
      Seq.empty[File]
    }
  }

  private def compileTask = Def.task {
    this.synchronized {
      val destination = baseDirectory.value / targetSchemataDirectory.value
      val files = Buffer[File]()
      val externalSchemas = Buffer[File]()
      externalSchemaDirectories.value.foreach(directory => {
        externalSchemas ++= (directory ** "*.avsc").get
      })

      schemataDirectories.value.map(schemata => {
        val source = baseDirectory.value / schemata
        val processor = new SchemaProcessor(
            JavaConversions.asJavaList((source ** "*.avsc").get),
            JavaConversions.asJavaList(externalSchemas),
            JavaConversions.asJavaList((source ** "*.avpr").get),
            JavaConversions.asJavaList((source ** "*.avdl").get));
        val parseResult = processor.parse()

        val schemas = parseResult.getSchemas()
        val schemaFiles = JavaConversions.asScalaSet(schemas.entrySet())
        schemaFiles.foreach(entry => {
          val file = entry.getKey()
          val schema = entry.getValue()
          val definedNames = processor.definedNames(file)
          val compiler = new InternalSpecificCompiler(schema)
          compiler.setDefinedNames(definedNames)
          val output = compiler.getOutputFile(destination)
          if (!output.exists() || output.lastModified() < file.lastModified()) {
            streams.value.log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
          }
          compiler.setStringType(stringType.value)
          compiler.compileToDestination(file, destination)
          files ++= JavaConversions.asScalaBuffer(compiler.getFiles(destination))
        })

        val protocols = parseResult.getProtocols()
        val protocolFoles = JavaConversions.asScalaSet(protocols.entrySet())
        protocolFoles.foreach(entry => {
          val file = entry.getKey()
          val protocol = entry.getValue()
          val definedNames = processor.definedNames(file)
          val compiler = new InternalSpecificCompiler(protocol)
          compiler.setDefinedNames(definedNames)
          val output = compiler.getOutputFile(destination)
          if (!output.exists() || output.lastModified() < file.lastModified()) {
            streams.value.log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
          }
          compiler.setStringType(stringType.value)
          compiler.compileToDestination(file, destination)
          files ++= JavaConversions.asScalaBuffer(compiler.getFiles(destination))
          val generator = new ProtocolClientGenerator(protocol, destination)
          files += generator.generate()
        })
      })

      files.toSeq
    }
  }

  private def createSourceMappings = Def.task {
    val directory = baseDirectory.value / targetSchemataDirectory.value
    directory.descendantsExcept("*.java", "").get pair rebase(directory, "")
  }
}
