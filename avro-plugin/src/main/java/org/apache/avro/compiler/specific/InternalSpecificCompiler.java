package org.apache.avro.compiler.specific;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.avro.Protocol;
import org.apache.avro.Schema;

public class InternalSpecificCompiler extends SpecificCompiler {

  private List<String> paths = new ArrayList<>();

  public InternalSpecificCompiler(Protocol protocol) {
    super(protocol);
  }

  public InternalSpecificCompiler(Schema schema) {
    super(schema);
  }

  public List<File> getFiles(File destinationDirectory) {
    return paths.stream().map(path -> new File(destinationDirectory, path))
        .collect(Collectors.toList());
  }

  @Override
  SpecificCompiler.OutputFile compile(Schema schema) {
    ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      OutputFile file = super.compile(schema);
      paths.add(file.path);
      return file;
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassLoader);
    }
  }

  @Override
  SpecificCompiler.OutputFile compileInterface(Protocol protocol) {
    ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      OutputFile file = super.compileInterface(protocol);
      paths.add(file.path);
      return file;
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassLoader);
    }
  }
}
