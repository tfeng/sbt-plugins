package me.tfeng.sbt.plugins

import java.io.File
import sbt._
import sbt.Keys._
import com.typesafe.sbt.web.SbtWeb.autoImport._
import org.webjars.WebJarAssetLocator
import org.webjars.WebJarAssetLocator.WEBJARS_PATH_PREFIX
import scala.collection.mutable.Buffer
import java.util.regex.Pattern
import java.util.ServiceLoader
import org.webjars.urlprotocols.UrlProtocolHandler
import org.webjars.urlprotocols.JarUrlProtocolHandler
import java.nio.charset.Charset

object SbtDust extends AutoPlugin {

  import SbtDustKeys._
  import WebKeys._

  override lazy val projectSettings = settings

  object Engine extends Enumeration {
    val Nashorn = Value
  }

  val engines = Map(Engine.Nashorn -> NashornDustJs)

  val dustJsFileName = "dust-full.min.js"

  val dustPluginDir = "dust-plugin"

  lazy val settings = Seq(
      engine := Engine.Nashorn,
      dustDirs <<= sourceDirectories in Assets,
      dustToJs <<= dustToJsTask.dependsOn(WebKeys.nodeModules in Assets),
      sourceGenerators in Assets <+= dustToJs,
      mappings in (Compile, packageBin) ++= createWebJarMappings.value)

  object SbtDustKeys {
    lazy val dustToJs = TaskKey[Seq[File]]("compileDust", "Compile *.tl to js in target folder")
    lazy val engine = SettingKey[Engine.Value]("engine", "Js engine to use (nashorn or node)")
    lazy val dustDirs = SettingKey[Seq[File]]("dust-dirs", "Source directories of dust templates")
  }

  val renderScript = ("{dust.render(name, JSON.parse(json), function(err, data) {"
        + "if (err) throw new Error(err); else writer.write(data, 0, data.length); });}");

  private def dustToJsTask = Def.task {
    val log = streams.value.log
    val oldClassLoader = Thread.currentThread().getContextClassLoader()
    val newClassLoader = classOf[WebJarAssetLocator].getClassLoader()
    val dir = sourceManaged.value / dustPluginDir
    try {
      Thread.currentThread().setContextClassLoader(newClassLoader)
      val webJarAssetLocator = new WebJarAssetLocator()
      val dustjs = newClassLoader.getResourceAsStream(webJarAssetLocator.getFullPath(dustJsFileName))
      val all = Buffer[File]()
      dustDirs.value.foreach(dustDir => {
        log.info("Compiling dust templates in " + dustDir.relativeTo(baseDirectory.value).get)
        val files = dustDir.descendantsExcept("*.tl", "").get
        val dustJsEngine = engines.get(engine.value).get.getEngine(dustjs)
        dustJsEngine.compile(files,
            file => {
              val name = file.relativeTo(dustDir).get.toString()
              if (name.endsWith(".tl")) name.substring(0, name.length() - 3) else name
            },
            (name: String, js: String) => {
              val file = new File(dir, name + ".js")
              IO.write(file, js, Charset.forName("utf8"), false)
              log.info("Generated " + file.relativeTo(dir).get)
              all += file
            })
      })
      all.toSeq
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassLoader)
    }
  }

  private def createWebJarMappings = Def.task {
    val prefix = s"${WEBJARS_PATH_PREFIX}/${moduleName.value}/${version.value}/"
    (mappings in (Compile, packageBin)).value flatMap {
      case (file, path) => {
        val relative = file.relativeTo(sourceManaged.value / dustPluginDir)
        if (relative.isDefined) {
          Some(file -> (prefix + relative.get.toString()))
        } else {
          None
        }
      }
    }
  }
}
