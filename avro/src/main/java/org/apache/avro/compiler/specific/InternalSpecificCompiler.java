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

package org.apache.avro.compiler.specific;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.avro.Protocol;
import org.apache.avro.Schema;

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
public class InternalSpecificCompiler extends SpecificCompiler {

  protected class OutputFile extends SpecificCompiler.OutputFile {

    private List<OutputFile> dependentFiles = new ArrayList<>();

    public OutputFile() {
    }

    public OutputFile(SpecificCompiler.OutputFile outputFile) {
      path = outputFile.path;
      contents = outputFile.contents;
      outputCharacterEncoding = outputFile.outputCharacterEncoding;
    }

    public OutputFile(String path, String contents, String outputCharacterEncoding) {
      this.path = path;
      this.contents = contents;
      this.outputCharacterEncoding = outputCharacterEncoding;
    }

    public OutputFile(String name, String namespace, String contents,
        String outputCharacterEncoding) {
      this(makePath(name, namespace), contents, outputCharacterEncoding);
    }

    public void addDependentFile(SpecificCompiler.OutputFile file) {
      dependentFiles.add(new OutputFile(file.path, file.contents, file.outputCharacterEncoding));
    }

    public List<String> getAllPaths() {
      List<String> paths = new ArrayList<>(dependentFiles.size() + 1);
      dependentFiles.stream().forEach(file -> paths.addAll(file.getAllPaths()));
      paths.add(path);
      return paths;
    }

    public String getOutputCharacterEncoding() {
      return outputCharacterEncoding;
    }

    @Override
    public File writeToDestination(File src, File destDir) throws IOException {
      if (path == null) {
        return null;
      }
      for (OutputFile dependentFile : dependentFiles) {
        dependentFile.writeToDestination(src, destDir);
      }
      return super.writeToDestination(src, destDir);
    }
  }

  private static final Pattern AVRO_REMOTE_EXCEPTION_PATTERN =
      Pattern.compile("throws org.apache.avro.AvroRemoteException\\s*(;|,\\s*)");

  private Set<String> definedNames;

  private List<String> paths = new ArrayList<>();

  private Protocol protocol;

  private final boolean removeAvroRemoteExceptions;

  private Schema schema;

  public InternalSpecificCompiler(Protocol protocol, boolean removeAvroRemoteExceptions) {
    super(protocol);
    this.protocol = protocol;
    this.removeAvroRemoteExceptions = removeAvroRemoteExceptions;
  }

  public InternalSpecificCompiler(Schema schema) {
    super(schema);
    this.schema = schema;
    this.removeAvroRemoteExceptions = false;
  }

  public OutputFile ensureOutputFile(SpecificCompiler.OutputFile outputFile) {
    if (outputFile instanceof OutputFile) {
      return (OutputFile) outputFile;
    } else {
      return new OutputFile(outputFile);
    }
  }

  public List<File> getFiles(File destinationDirectory) {
    return paths.stream().map(path -> new File(destinationDirectory, path))
        .collect(Collectors.toList());
  }

  public File getOutputFile(File targetDirectory) {
    if (protocol != null) {
      String mangledName = mangle(protocol.getName());
      String path = makePath(mangledName, protocol.getNamespace());
      return new File(targetDirectory, path);
    } else {
      String mangledName = mangle(schema.getName());
      String path = makePath(mangledName, schema.getNamespace());
      return new File(targetDirectory, path);
    }
  }

  public void setDefinedNames(Set<String> definedNames) {
    this.definedNames = definedNames;
  }

  @Override
  protected OutputFile compile(Schema schema) {
    if (definedNames != null && !definedNames.contains(schema.getFullName())) {
      return new OutputFile();
    }
    ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      OutputFile file = compileSchemaInternal(schema);
      paths.addAll(file.getAllPaths());
      return file;
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassLoader);
    }
  }

  @Override
  protected OutputFile compileInterface(Protocol protocol) {
    ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      OutputFile file = compileInterfaceInternal(protocol);
      paths.addAll(file.getAllPaths());
      return file;
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassLoader);
    }
  }


  protected OutputFile compileInterfaceInternal(Protocol protocol) {
    SpecificCompiler.OutputFile outputFile = super.compileInterface(protocol);
    outputFile.contents = rewriteContents(outputFile.contents);
    return ensureOutputFile(outputFile);
  }

  protected OutputFile compileSchemaInternal(Schema schema) {
    return ensureOutputFile(super.compile(schema));
  }

  protected String rewriteContents(String contents) {
    if (removeAvroRemoteExceptions) {
      Matcher matcher = AVRO_REMOTE_EXCEPTION_PATTERN.matcher(contents);
      StringBuffer buffer = new StringBuffer();
      while(matcher.find()) {
        if (";".equals(matcher.group(1))) {
          matcher.appendReplacement(buffer, ";");
        } else {
          matcher.appendReplacement(buffer, "throws ");
        }
      }
      matcher.appendTail(buffer);
      return buffer.toString();
    } else {
      return contents;
    }
  }
}
