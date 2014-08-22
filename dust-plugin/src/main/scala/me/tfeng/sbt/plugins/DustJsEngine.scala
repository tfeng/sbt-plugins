package me.tfeng.sbt.plugins

import java.io.{File, InputStream}

abstract class DustJsEngine {

  def compile(files: Seq[File], nameGenerator: File => String, writer: (String, String) => _): Unit
}

trait DustJs[T] {

  def getEngine(dustJs: InputStream): T
}
