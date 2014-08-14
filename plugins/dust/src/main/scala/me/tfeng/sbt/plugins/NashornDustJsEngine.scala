package me.tfeng.sbt.plugins

import java.io.{InputStream, InputStreamReader}
import java.nio.charset.Charset

import javax.script.{Invocable, ScriptEngine, ScriptEngineManager}
import sbt.{File, IO}

class NashornDustJsEngine(scriptEngine: ScriptEngine, dustFunction: Object) extends DustJsEngine {

  override def compile(files: Seq[File], nameGenerator: File => String,
      writer: (String, String) => _): Unit = {
    val invocable = scriptEngine.asInstanceOf[Invocable]
    files.foreach(file => {
      val name = nameGenerator(file)
      val content = IO.read(file, Charset.forName("utf8"))
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
