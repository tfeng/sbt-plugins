/**
 * Copyright 2016 Thomas Feng
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

import org.apache.avro.SchemaProcessor
import org.apache.avro.compiler.specific.{InternalSpecificCompiler, ProtocolClientGenerator}
import org.apache.avro.generic.GenericData.StringType
import sbt.Keys.{baseDirectory, cleanFiles, libraryDependencies, mappings, packageSrc, sourceGenerators, streams, unmanagedSourceDirectories}
import sbt.Project.inConfig
import sbt.{AutoPlugin, Compile, Def, IO, SettingKey, Test, filesToFinder, globFilter, rebase, richFile, singleFileFinder, toGroupID}

import scala.collection.JavaConversions
import scala.collection.mutable.Buffer

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
object Avro extends AutoPlugin {

  import Keys._

  override lazy val projectSettings = settings

  lazy val settings =
    Seq(
      stringType := StringType.String,
      libraryDependencies ++= Seq(
        "org.apache.avro" % "avro" % Versions.avro,
        "org.apache.avro" % "avro-ipc" % Versions.avro),
      externalSchemaDirectories := Seq(),
      removeAvroRemoteExceptions := true,
      extraSchemaClasses := Seq()) ++
    inConfig(Compile)(Seq(
      schemataDirectories := Seq(baseDirectory.value / "schemata"),
      targetSchemataDirectory := baseDirectory.value / "codegen",
      mappings in packageSrc ++= createSourceMappings.value,
      packageSrc <<= packageSrc dependsOn (compileTask),
      sourceGenerators ++= Seq(mkTargetDirectory.taskValue, compileTask.taskValue),
      unmanagedSourceDirectories ++=
        schemataDirectories.value ++
        Seq(targetSchemataDirectory.value))) ++
    inConfig(Test)(Seq(
      schemataDirectories := Seq(baseDirectory.value / "test" / "resources" / "schemata"),
      sourceGenerators ++= Seq(mkTargetDirectory.taskValue, compileTask.taskValue),
      unmanagedSourceDirectories += targetSchemataDirectory.value)) ++
    Seq(
      cleanFiles += (targetSchemataDirectory in Compile).value)

  private def mkTargetDirectory = Def.task {
    this.synchronized {
      (targetSchemataDirectory in Compile).value.mkdirs()
      (targetSchemataDirectory in Test).value.mkdirs()
      Seq.empty[File]
    }
  }

  private def compileTask = Def.task {
    this.synchronized {
      val destination = targetSchemataDirectory.value
      val files = Buffer[File]()
      val externalSchemas = Buffer[File]()
      externalSchemaDirectories.value.foreach(directory => {
        externalSchemas ++= (directory ** "*.avsc").get
      })

      schemataDirectories.value.map(schemata => {
        val source = schemata
        val processor = new SchemaProcessor(
          JavaConversions.seqAsJavaList((source ** "*.avsc").get),
          JavaConversions.seqAsJavaList(externalSchemas),
          JavaConversions.seqAsJavaList((source ** "*.avpr").get),
          JavaConversions.seqAsJavaList((source ** "*.avdl").get),
          stringType.value,
          JavaConversions.seqAsJavaList(extraSchemaClasses.value));
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
        val protocolFiles = JavaConversions.asScalaSet(protocols.entrySet())
        protocolFiles.foreach(entry => {
          val file = entry.getKey()
          val protocol = entry.getValue()
          if ("avdl".equals(file.ext)) {
            val protocolFile =
              destination / file.relativeTo(source).get.toString.replaceAll("(\\.avdl$)", ".avpr")
            IO.write(protocolFile, protocol.toString(true), Charset.forName("utf8"), false)
          }
          val definedNames = processor.definedNames(file)
          val compiler = new InternalSpecificCompiler(protocol, removeAvroRemoteExceptions.value)
          compiler.setDefinedNames(definedNames)
          val output = compiler.getOutputFile(destination)
          if (!output.exists() || output.lastModified() < file.lastModified()) {
            streams.value.log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
          }
          compiler.setStringType(stringType.value)
          compiler.compileToDestination(file, destination)
          files ++= JavaConversions.asScalaBuffer(compiler.getFiles(destination))
          val generator = new ProtocolClientGenerator(protocol, destination)
          generator.setStringType(stringType.value)
          files += generator.generate()
        })
      })

      files.toSeq
    }
  }

  private def createSourceMappings = Def.task {
    val directory = targetSchemataDirectory.value
    directory.descendantsExcept("*.java", "").get pair rebase(directory, "")
  }

  object Keys {
    lazy val schemataDirectories = SettingKey[Seq[File]]("schemata-dir", "Subdirectories under project root containing avro schemas")
    lazy val targetSchemataDirectory = SettingKey[File]("target-schemata-dir", "Target directory to store compiled avro schemas")
    lazy val stringType = SettingKey[StringType]("string-type", "Java type to be emitted for string schemas")
    lazy val externalSchemaDirectories = SettingKey[Seq[File]]("external-schemata-dirs", "Directories holding external schemas")
    lazy val removeAvroRemoteExceptions = SettingKey[Boolean]("remove-avro-remote-exceptions", "Whether to remove AvroRemoteException's in interfaces")
    lazy val extraSchemaClasses = SettingKey[Seq[String]]("extra-schema-classes", "Extra Java classes generated from Avro schemas that are used as dependencies");
  }
}
