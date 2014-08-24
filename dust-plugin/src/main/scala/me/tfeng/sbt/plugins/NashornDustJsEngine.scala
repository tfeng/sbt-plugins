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

import java.io.{InputStream, InputStreamReader}
import java.nio.charset.Charset

import javax.script.{Invocable, ScriptEngine, ScriptEngineManager}
import sbt.{File, IO}

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
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
