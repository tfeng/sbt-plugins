package me.tfeng.sbt.plugins

import java.nio.charset.Charset

import scala.collection.JavaConversions
import scala.collection.mutable.Buffer

import org.apache.avro.Protocol
import org.apache.avro.Schema
import org.apache.avro.compiler.idl.Idl
import org.apache.avro.compiler.specific.InternalSpecificCompiler
import org.apache.avro.generic.GenericData.StringType

import sbt._
import sbt.Keys._

object SbtAvro extends AutoPlugin {

  import SbtAvroKeys._

  override lazy val projectSettings = settings

  lazy val settings = Seq(
      schemataDirectory  := "schemata",
      stringType := StringType.String,
      sourceGenerators in Compile ++= Seq(
          compileAvdlTask.taskValue,
          compileAvscTask.taskValue,
          compileAvprTask.taskValue
      ),
      unmanagedSourceDirectories in Compile += baseDirectory.value / schemataDirectory.value,
      managedSourceDirectories in Compile += target.value / schemataDirectory.value,
      libraryDependencies ++= Seq(
          "org.apache.avro" % "avro" % Versions.avro,
          "org.apache.avro" % "avro-ipc" % Versions.avro
      )
  )

  object SbtAvroKeys {
    lazy val schemataDirectory = SettingKey[String]("schemata-dir", "Subdirectory under project root containing avro schemata")
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
}
