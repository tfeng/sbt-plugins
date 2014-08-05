package me.tfeng.sbt.plugins

import java.io.File
import sbt._
import sbt.Keys._
import com.typesafe.sbt.web.SbtWeb.autoImport._

object SbtDust extends AutoPlugin {

  import SbtDustKeys._

  override lazy val projectSettings = settings

  object Engine extends Enumeration {
    val Nashorn = Value
  }

  val engines = Map(Engine.Nashorn -> NashornDustJs)

  val dustJsFileName = "dust-full.min.js"

  val dustJsUrl = new URL("https://raw.githubusercontent.com/linkedin/dustjs/master/dist/" + dustJsFileName)

  val sbtDustDir = "sbt-dust"

  val resourcesDir = "resources"

  lazy val settings = Seq(
      engine := Engine.Nashorn,
      downloadDir := "web",
      dustDirs <<= sourceDirectories in Assets,
      jsDir := "dustjs",
      compileDust <<= compileDustTask,
      resourceGenerators in Compile <+= compileDust,
      unmanagedResourceDirectories in Compile <+= resourceManaged { _ / sbtDustDir / resourcesDir })

  object SbtDustKeys {
    lazy val compileDust = TaskKey[Seq[File]]("compileDust", "Compile *.tl to js in target folder")
    lazy val engine = SettingKey[Engine.Value]("engine", "Js engine to use (nashorn or node)")
    lazy val dustDirs = SettingKey[Seq[File]]("dust-dirs", "Source directories of dust templates")
    lazy val downloadDir = SettingKey[String]("download-dir", "Directory in target folder where dust.js is downloaded")
    lazy val jsDir = SettingKey[String]("js-dir", "Directory in target folder where *.js files are generated from dust")
  }

  val renderScript = ("{dust.render(name, JSON.parse(json), function(err, data) {"
        + "if (err) throw new Error(err); else writer.write(data, 0, data.length); });}");

  private def getDustTemplateName(dust: File, root: File) = {
    val name = dust.relativeTo(root).get.toString()
    if (name.endsWith(".tl")) name.substring(0, name.length() - 3) else name
  }

  private def compileDustTask = (streams, engine, resourceManaged, downloadDir, baseDirectory, dustDirs, jsDir) map {
    (streams, engine, resourceManaged, downloadDir, baseDirectory, dustDirs, jsDir) => {
      val log = streams.log
      val dustJsFile = resourceManaged / sbtDustDir / downloadDir / dustJsFileName

      if (dustJsFile.exists()) {
        log.info("Skipping downloading dust.js at " + dustJsFile.relativeTo(baseDirectory).get)
      } else {
        log.info("Downloading dust.js at " + dustJsFile.relativeTo(baseDirectory).get)
        IO.download(dustJsUrl, dustJsFile)
      }

      val dir = resourceManaged / "sbt-dust" / "resources" / jsDir
      dustDirs.foreach(dustDir => {
        log.info("Compiling dust templates in " + dustDir.relativeTo(baseDirectory).get)
        val files = dustDir.descendantsExcept("*.tl", "").get
        val dustJsEngine = engines.get(engine).get.getEngine(resourceManaged / sbtDustDir / downloadDir / dustJsFileName)
        dustJsEngine.compile(files,
            file => getDustTemplateName(file, dustDir),
            (name: String, js: String) => {
              val file = new File(dir, name + ".js")
              file.getParentFile().mkdirs()
              log.info("Generating " + file.relativeTo(dir).get)
              scala.tools.nsc.io.File(file).writeAll(js)
            })
      })
      Seq(dir)
    }
  }
}
