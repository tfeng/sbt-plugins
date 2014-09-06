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

package org.apache.avro.compiler.specific;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;

import org.apache.avro.Protocol;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
public class ProtocolClientGenerator extends SpecificCompiler {

  private static final String TEMPLATE = "templates/protocol-client.vm";

  private static final VelocityEngine VELOCITY_ENGINE = new VelocityEngine();

  static {
    VELOCITY_ENGINE.addProperty("resource.loader", "class");
    VELOCITY_ENGINE.addProperty("class.resource.loader.class",
        "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
    VELOCITY_ENGINE.setProperty("runtime.log.logsystem.class",
        "org.apache.velocity.runtime.log.NullLogChute");
  }

  private final Protocol protocol;

  private final File targetDirectory;

  public ProtocolClientGenerator(Protocol protocol, File targetDirectory) {
    super(protocol);

    this.protocol = protocol;
    this.targetDirectory = targetDirectory;
  }

  public File generate() throws IOException {
    String mangledName = mangle(protocol.getName() + "Client");
    String path = makePath(mangledName, protocol.getNamespace());
    File outputFile = new File(targetDirectory, path);
    outputFile.getParentFile().mkdirs();

    Writer writer = new FileWriter(outputFile);
    try {
      renderTemplate(writer);
      return outputFile;
    } finally {
      writer.close();
    }
  }

  public void renderTemplate(Writer writer) {
    ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

      VelocityContext context = new VelocityContext();
      context.put("protocol", protocol);
      context.put("this", this);

      Template template = VELOCITY_ENGINE.getTemplate(TEMPLATE);
      template.merge(context, writer);
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassLoader);
    }
  }
}
