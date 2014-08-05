package me.tfeng.sbt.plugins

import java.io.File

import sbt._

abstract class DustJsEngine {

  def compile(files: Seq[File], nameGenerator: File => String, writer: (String, String) => _): Unit
}

trait DustJs[T] {

  def getEngine(dustJs: File): T
}
