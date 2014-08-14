package me.tfeng.sbt.plugins

import java.nio.charset.Charset

import scala.collection.mutable.Buffer

import org.webjars.WebJarAssetLocator
import org.webjars.WebJarAssetLocator.WEBJARS_PATH_PREFIX

import sbt.{AutoPlugin, Compile, Def}
import sbt.{File, IO}
import sbt.{SettingKey, TaskKey, filesToFinder, globFilter, rebase, richFile, singleFileFinder}
import sbt.ConfigKey.configurationToKey
import sbt.Keys.{baseDirectory, classDirectory, managedResourceDirectories, mappings, moduleName, packageBin, resourceGenerators, streams, unmanagedSourceDirectories, version}

object SbtDust extends AutoPlugin {

  import SbtDustKeys._

  override lazy val projectSettings = settings

  object Engine extends Enumeration {
    val Nashorn = Value
  }

  val engines = Map(Engine.Nashorn -> NashornDustJs)

  val dustJsFileName = "dust-full.min.js"

  lazy val settings = Seq(
      engine := Engine.Nashorn,
      templatesDirectory := "templates",
      dustToJs <<= dustToJsTask,
      resourceGenerators in Compile <+= dustToJs,
      mappings in (Compile, packageBin) ++= createWebJarMappings.value,
      unmanagedSourceDirectories in Compile += baseDirectory.value / templatesDirectory.value
  )

  object SbtDustKeys {
    lazy val dustToJs = TaskKey[Seq[File]]("dustToJs", "Compile dust templates to js in target folder")
    lazy val engine = SettingKey[Engine.Value]("engine", "Js engine to use (only Nashorn is supported for now)")
    lazy val templatesDirectory = SettingKey[String]("templates-dir", "Subdirectory under project root containing dust templates")
  }

  private val renderScript = ("{dust.render(name, JSON.parse(json), function(err, data) {"
        + "if (err) throw new Error(err); else writer.write(data, 0, data.length); });}");

  private def getWebJarsDirectory(moduleName: String, version: String, templatesDirectory: String) =
      s"${WEBJARS_PATH_PREFIX}/${moduleName}/${version}/" + templatesDirectory

  private def dustToJsTask = Def.task {
    val dustDirectory = baseDirectory.value / templatesDirectory.value

    def getDustTemplateName(dustFile: File) = {
      val name = dustFile.relativeTo(dustDirectory).get.toString()
      if (name.endsWith(".tl")) name.substring(0, name.length() - 3) else name
    }

    val log = streams.value.log
    val oldClassLoader = Thread.currentThread().getContextClassLoader()
    val newClassLoader = classOf[WebJarAssetLocator].getClassLoader()
    try {
      Thread.currentThread().setContextClassLoader(newClassLoader)
      val webJarAssetLocator = new WebJarAssetLocator()
      val dustjs = newClassLoader.getResourceAsStream(webJarAssetLocator.getFullPath(dustJsFileName))
      val dustJsEngine = engines.get(engine.value).get.getEngine(dustjs)
      val prefix = getWebJarsDirectory(moduleName.value, version.value, templatesDirectory.value)
      val jsDirectory = (classDirectory in Compile).value / prefix
      val all = Buffer[File]()
      val files = dustDirectory.descendantsExcept("*.tl", "").get.filter(
          file => {
            val jsFile = new File(jsDirectory, getDustTemplateName(file) + ".js")
            !jsFile.exists() || jsFile.lastModified() < file.lastModified()
          })
      dustJsEngine.compile(files,
          file => {
            log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
            getDustTemplateName(file)
          },
          (name: String, js: String) => {
            val file = new File(jsDirectory, name + ".js")
            IO.write(file, js, Charset.forName("utf8"), false)
            all += file
          })
      all.toSeq
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassLoader)
    }
  }

  private def createWebJarMappings = Def.task {
    val prefix = getWebJarsDirectory(moduleName.value, version.value, templatesDirectory.value)
    val jsDirectory = (classDirectory in Compile).value / prefix
    jsDirectory.descendantsExcept("*.js", "").get pair rebase(jsDirectory, prefix)
  }
}
