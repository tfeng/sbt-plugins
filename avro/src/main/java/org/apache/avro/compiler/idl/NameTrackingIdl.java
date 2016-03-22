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

package org.apache.avro.compiler.idl;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;

import org.apache.avro.Schema;
import org.apache.avro.Schema.Type;

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
public class NameTrackingIdl extends Idl {

  public static class NameTrackingMap extends LinkedHashMap<String, Schema> {

    private static final Schema DEFAULT_SCHEMA = Schema.create(Type.STRING);

    private static final long serialVersionUID = 1L;

    private Set<String> undefinedNames = new HashSet<>();

    @Override
    public Schema get(Object name) {
      if (containsKey(name)) {
        return super.get(name);
      } else {
        undefinedNames.add((String) name);
        return DEFAULT_SCHEMA;
      }
    }

    public Set<String> getUndefinedNames() {
      return undefinedNames;
    }

    @Override
    public Schema put(String name, Schema schema) {
      undefinedNames.remove(name);
      return super.put(name, schema);
    }
  }

  public NameTrackingIdl(File inputFile) throws IOException {
    super(inputFile);
    initNames();
  }

  public NameTrackingIdl(File inputFile, ClassLoader resourceLoader) throws IOException {
    super(inputFile, resourceLoader);
    initNames();
  }

  public NameTrackingIdl(IdlTokenManager tm) {
    super(tm);
    initNames();
  }

  public NameTrackingIdl(InputStream stream) {
    super(stream);
    initNames();
  }

  public NameTrackingIdl(InputStream stream, String encoding) {
    super(stream, encoding);
    initNames();
  }

  public NameTrackingIdl(Reader stream) {
    super(stream);
    initNames();
  }

  public NameTrackingMap getNames() {
    if (names instanceof NameTrackingMap) {
      return (NameTrackingMap) names;
    } else {
      throw new RuntimeException("Import statements cannot be used in IDL file, when implicit "
          + "dependency is enabled");
    }
  }

  private void initNames() {
    names = new NameTrackingMap();
  }
}
