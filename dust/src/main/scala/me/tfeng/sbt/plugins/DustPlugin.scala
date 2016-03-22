/**
 * Copyright 2016 Thomas Feng
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

import java.nio.charset.Charset

import org.webjars.WebJarAssetLocator
import org.webjars.WebJarAssetLocator.WEBJARS_PATH_PREFIX
import sbt.Keys.{baseDirectory, classDirectory, mappings, moduleName, packageBin, resourceGenerators, streams, unmanagedSourceDirectories, version}
import sbt.Project.inConfig
import sbt.{AutoPlugin, Compile, Def, File, IO, SettingKey, TaskKey, filesToFinder, globFilter, rebase, richFile, singleFileFinder}

import scala.collection.mutable.Buffer

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
object Dust extends AutoPlugin {

  import Keys._

  override lazy val projectSettings = settings
  lazy val settings = inConfig(Compile)(Seq(
      engine := Engine.Nashorn,
      templatesDirectories := Seq("templates"),
      dustToJs <<= dustToJsTask,
      resourceGenerators <+= dustToJs,
      mappings in packageBin ++= createWebJarMappings.value,
      unmanagedSourceDirectories ++= templatesDirectories.value.map(templates => baseDirectory.value / templates)
  ))
  val engines = Map(Engine.Nashorn -> NashornDustJs)

  val dustJsFileName = "dust-full.min.js"
  private val renderScript = ("{dust.render(name, JSON.parse(json), function(err, data) {"
        + "if (err) throw new Error(err); else writer.write(data, 0, data.length); });}");

  private def dustToJsTask = Def.task {
    def getDustTemplateName(dustDirectory: File, dustFile: File) = {
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
      val files = Buffer[File]()
      templatesDirectories.value.map(templates => {
        val dustDirectory = baseDirectory.value / templates
        val prefix = getWebJarsDirectory(moduleName.value, version.value, templates)
        val jsDirectory = classDirectory.value / prefix
        val sources = dustDirectory.descendantsExcept("*.tl", "").get.filter(
            file => {
              val jsFile = new File(jsDirectory, getDustTemplateName(dustDirectory, file) + ".js")
              !jsFile.exists() || jsFile.lastModified() < file.lastModified()
            })
        dustJsEngine.compile(sources,
            file => {
              log.info("Compiling " + file.relativeTo(baseDirectory.value).get)
              getDustTemplateName(dustDirectory, file)
            },
            (name: String, js: String) => {
              val file = new File(jsDirectory, name + ".js")
              IO.write(file, js, Charset.forName("utf8"), false)
              files += file
            })
      })
      files.toSeq
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassLoader)
    }
  }

  private def createWebJarMappings = Def.task {
    val mappings = Buffer[(File, String)]()
    templatesDirectories.value.map(templates => {
      val prefix = getWebJarsDirectory(moduleName.value, version.value, templates)
      val jsDirectory = classDirectory.value / prefix
      mappings ++= jsDirectory.descendantsExcept("*.js", "").get pair rebase(jsDirectory, prefix)
    })
    mappings.toSeq
  }

  private def getWebJarsDirectory(moduleName: String, version: String, templatesDirectory: String) =
      s"${WEBJARS_PATH_PREFIX}/${moduleName}/${version}/" + templatesDirectory

  object Engine extends Enumeration {
    val Nashorn = Value
  }

  object Keys {
    lazy val dustToJs = TaskKey[Seq[File]]("dustToJs", "Compile dust templates to js in target folder")
    lazy val engine = SettingKey[Engine.Value]("engine", "Js engine to use (only Nashorn is supported for now)")
    lazy val templatesDirectories = SettingKey[Seq[String]]("templates-dir", "Subdirectories under project root containing dust templates")
  }
}
