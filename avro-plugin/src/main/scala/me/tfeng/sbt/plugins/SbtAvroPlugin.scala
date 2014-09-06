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

import org.apache.avro.{Protocol, Schema}
import org.apache.avro.compiler.idl.Idl
import org.apache.avro.compiler.specific.{InternalSpecificCompiler, ProtocolClientGenerator}
import org.apache.avro.generic.GenericData.StringType
import sbt.{AutoPlugin, Compile}
import sbt.{SettingKey, globFilter, richFile, singleFileFinder, toGroupID}
import sbt.ConfigKey.configurationToKey
import sbt.{Def, IO}
import sbt.Keys.{baseDirectory, libraryDependencies, managedSourceDirectories, sourceGenerators, streams, target, unmanagedSourceDirectories}

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
object SbtAvro extends AutoPlugin {

  import SbtAvroKeys._

  override lazy val projectSettings = settings

  lazy val settings = Seq(
      schemataDirectories := Seq("schemata"),
      targetSchemataDirectory := (target.value.relativeTo(baseDirectory.value).get / "schemata").toString,
      stringType := StringType.CharSequence,
      specificCompilerClass := "org.apache.avro.compiler.specific.InternalSpecificCompiler",
      sourceGenerators in Compile ++= Seq(
          compileAvdlTask.taskValue,
          compileAvscTask.taskValue,
          compileAvprTask.taskValue
      ),
      unmanagedSourceDirectories in Compile ++= schemataDirectories.value.map(schemata => baseDirectory.value / schemata),
      managedSourceDirectories in Compile += baseDirectory.value / targetSchemataDirectory.value,
      libraryDependencies ++= Seq(
          "org.apache.avro" % "avro" % Versions.avro,
          "org.apache.avro" % "avro-ipc" % Versions.avro
      )
      // This depends on sbteclipse supporting a sbt 0.13.5.
      // classpathTransformerFactories += addJavaSourceDirectory
  )

  object SbtAvroKeys {
    lazy val schemataDirectories = SettingKey[Seq[String]]("schemata-dir", "Subdirectories under project root containing avro schemas")
    lazy val targetSchemataDirectory = SettingKey[String]("target-schemata-dir", "Target directory to store compiled avro schemas")
    lazy val stringType = SettingKey[StringType]("string-type", "Java type to be emitted for string schemas")
    lazy val specificCompilerClass = SettingKey[String]("specific-compiler-class", "Class name of the Avro specific compiler")
  }

  private def compileAvdlTask = Def.task {
    val destination = baseDirectory.value / targetSchemataDirectory.value
    val files = Buffer[File]()
    schemataDirectories.value.map(schemata => {
      val source = baseDirectory.value / schemata
      val avdlFiles = (source ** "*.avdl").get
      avdlFiles.foreach(file => {
        val idl = new Idl(file)
        try {
          streams.value.log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
          val protocol = idl.CompilationUnit()
          val protocolFile = destination / file.relativeTo(source).get.toString.replaceAll("(\\.avdl$)", ".avpr")
          IO.write(protocolFile, protocol.toString(true), Charset.forName("utf8"), false)

          val constructor = Class.forName(specificCompilerClass.value).getConstructor(classOf[Protocol])
          val compiler = constructor.newInstance(protocol).asInstanceOf[InternalSpecificCompiler]
          compiler.setStringType(stringType.value)
          compiler.compileToDestination(file, destination)
          files ++= JavaConversions.asScalaBuffer(compiler.getFiles(destination))

          val generator = new ProtocolClientGenerator(protocol, destination)
          files += generator.generate()
        } finally {
          idl.close()
        }
      })
    })
    files.toSeq
  }

  private def compileAvscTask = Def.task {
    val destination = baseDirectory.value / targetSchemataDirectory.value
    val schemaParser = new Schema.Parser()
    val files = Buffer[File]()
    schemataDirectories.value.map(schemata => {
      val source = baseDirectory.value / schemata
      val avscFiles = (source ** "*.avsc").get
      avscFiles.foreach(file => {
        streams.value.log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
        val schema = schemaParser.parse(file)
        val constructor = Class.forName(specificCompilerClass.value).getConstructor(classOf[Schema])
        val compiler = constructor.newInstance(schema).asInstanceOf[InternalSpecificCompiler]
        compiler.setStringType(stringType.value)
        compiler.compileToDestination(file, destination)
        files ++= JavaConversions.asScalaBuffer(compiler.getFiles(destination))
      })
    })
    files.toSeq
  }

  private def compileAvprTask = Def.task {
    val destination = baseDirectory.value / targetSchemataDirectory.value
    val files = Buffer[File]()
    schemataDirectories.value.map(schemata => {
      val source = baseDirectory.value / schemata
      val avprFiles = (source ** "*.avpr").get
      avprFiles.foreach(file => {
        streams.value.log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
        val protocol = Protocol.parse(file)
        val constructor = Class.forName(specificCompilerClass.value).getConstructor(classOf[Protocol])
        val compiler = constructor.newInstance(protocol).asInstanceOf[InternalSpecificCompiler]
        compiler.setStringType(stringType.value)
        compiler.compileToDestination(file, destination)
        files ++= JavaConversions.asScalaBuffer(compiler.getFiles(destination))

        val generator = new ProtocolClientGenerator(protocol, destination)
        files += generator.generate()
      })
    })
    files.toSeq
  }

  // This depends on sbteclipse supporting a sbt 0.13.5.
  /* private lazy val addJavaSourceDirectory = new EclipseTransformerFactory[RewriteRule] {
    override def createTransformer(ref: ProjectRef, state: State): Validation[RewriteRule] = {
      setting(targetSchemataDirectory in ref, state) map { targetSchemataDirectory =>
        new RewriteRule {
          override def transform(node: Node): Seq[Node] = node match {
            case elem if (elem.label == "classpath") =>
              val newChild = elem.child ++
                <classpathentry path={ targetSchemataDirectory } kind="src"></classpathentry>
              Elem(elem.prefix, "classpath", elem.attributes, elem.scope, false, newChild: _*)
            case other =>
              other
          }
        }
      }
    }
  } */
}
