package me.tfeng.sbt.plugins

import java.io._

import scala.io.Source

import javax.script._
import sbt._

class NashornDustJsEngine(scriptEngine: ScriptEngine, dustFunction: Object) extends DustJsEngine {

  override def compile(files: Seq[File], nameGenerator: File => String,
      writer: (String, String) => _): Unit = {
    val invocable = scriptEngine.asInstanceOf[Invocable]
    files.foreach(file => {
      val name = nameGenerator(file)
      val content = Source.fromFile(file).mkString
      val js = invocable.invokeMethod(dustFunction, "compile", content, name).asInstanceOf[String]
      writer(name, js)
    })
  }
}

object NashornDustJs extends DustJs[NashornDustJsEngine] {

  def getEngine(dustJs: InputStream): NashornDustJsEngine = {
    val scriptEngine = new ScriptEngineManager getEngineByName("nashorn")
    scriptEngine.eval(new InputStreamReader(dustJs))
    new NashornDustJsEngine(scriptEngine, scriptEngine.eval("dust"))
  }
}
