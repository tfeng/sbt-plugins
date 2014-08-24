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
import scala.xml.{Elem, Node}
import scala.xml.transform.RewriteRule

import org.apache.avro.{Protocol, Schema}
import org.apache.avro.compiler.idl.Idl
import org.apache.avro.compiler.specific.InternalSpecificCompiler
import org.apache.avro.generic.GenericData.StringType

import com.typesafe.sbteclipse.core.{Validation, setting}
import com.typesafe.sbteclipse.core.EclipsePlugin.EclipseKeys.classpathTransformerFactories
import com.typesafe.sbteclipse.core.EclipsePlugin.EclipseTransformerFactory

import sbt.{AutoPlugin, Compile, Def}
import sbt.{ProjectRef, SettingKey, State, globFilter, richFile, singleFileFinder, toGroupID}
import sbt.ConfigKey.configurationToKey
import sbt.IO
import sbt.Keys.{baseDirectory, libraryDependencies, managedSourceDirectories, sourceGenerators, streams, target, unmanagedSourceDirectories}

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
object SbtAvro extends AutoPlugin {

  import SbtAvroKeys._

  override lazy val projectSettings = settings

  lazy val settings = Seq(
      schemataDirectory  := "schemata",
      targetSchemataDirectory := (target.value.relativeTo(baseDirectory.value).get / schemataDirectory.value).toString,
      stringType := StringType.String,
      sourceGenerators in Compile ++= Seq(
          compileAvdlTask.taskValue,
          compileAvscTask.taskValue,
          compileAvprTask.taskValue
      ),
      unmanagedSourceDirectories in Compile += baseDirectory.value / schemataDirectory.value,
      managedSourceDirectories in Compile += baseDirectory.value / targetSchemataDirectory.value,
      libraryDependencies ++= Seq(
          "org.apache.avro" % "avro" % Versions.avro,
          "org.apache.avro" % "avro-ipc" % Versions.avro
      ),
      classpathTransformerFactories += addJavaSourceDirectory
  )

  object SbtAvroKeys {
    lazy val schemataDirectory = SettingKey[String]("schemata-dir", "Subdirectory under project root containing avro schemata")
    lazy val targetSchemataDirectory = SettingKey[String]("target-schemata-dir", "Target directory to store compiled avro schemata")
    lazy val stringType = SettingKey[StringType]("string-type", "Java type to be emitted for string schemas")
  }

  private def compileAvdlTask = Def.task {
    val source = baseDirectory.value / schemataDirectory.value
    val destination = target.value / schemataDirectory.value
    val avdlFiles = (source ** "*.avdl").get
    val all = Buffer[File]()
    avdlFiles.foreach(file => {
      val idl = new Idl(file)
      try {
        streams.value.log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
        val protocol = idl.CompilationUnit()
        val protocolFile = destination / file.relativeTo(source).get.toString.replaceAll("(\\.avdl$)", ".avpr")
        IO.write(protocolFile, protocol.toString(true), Charset.forName("utf8"), false)

        val compiler = new InternalSpecificCompiler(protocol)
        compiler.setStringType(stringType.value)
        compiler.compileToDestination(file, destination)
        all ++= JavaConversions.asScalaBuffer(compiler.getFiles(destination))
      } finally {
        idl.close()
      }
    })
    all.toSeq
  }

  private def compileAvscTask = Def.task {
    val source = baseDirectory.value / schemataDirectory.value
    val destination = target.value / schemataDirectory.value
    val avscFiles = (source ** "*.avsc").get
    val schemaParser = new Schema.Parser()
    val all = Buffer[File]()
    avscFiles.foreach(file => {
      streams.value.log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
      val schema = schemaParser.parse(file)
      val compiler = new InternalSpecificCompiler(schema)
      compiler.setStringType(stringType.value)
      compiler.compileToDestination(file, destination)
      all ++= JavaConversions.asScalaBuffer(compiler.getFiles(destination))
    })
    all.toSeq
  }

  private def compileAvprTask = Def.task {
    val source = baseDirectory.value / schemataDirectory.value
    val destination = target.value / schemataDirectory.value
    val avprFiles = (source ** "*.avpr").get
    val all = Buffer[File]()
    avprFiles.foreach(file => {
      streams.value.log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
      val protocol = Protocol.parse(file)
      val compiler = new InternalSpecificCompiler(protocol)
      compiler.setStringType(stringType.value)
      compiler.compileToDestination(file, destination)
      all ++= JavaConversions.asScalaBuffer(compiler.getFiles(destination))
    })
    all.toSeq
  }

  private lazy val addJavaSourceDirectory = new EclipseTransformerFactory[RewriteRule] {
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
  }
}
