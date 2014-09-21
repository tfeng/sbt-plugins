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

package org.apache.avro;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.avro.Schema.Parser;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.JsonParser;

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
public class SchemaProcessor {

  private final List<File> dependencyOrder = new ArrayList<>();

  private final Map<File, Map<String, Boolean>> names = new HashMap<>();

  private final Set<File> schemaFiles;

  public SchemaProcessor(List<File> schemaFiles, List<File> externalSchemas)
      throws JsonParseException, IOException {
    this.schemaFiles = new HashSet<>(schemaFiles);
    parseSchemaFiles(schemaFiles);
    parseSchemaFiles(externalSchemas);
    computeDependencyOrder();
  }

  public Set<String> definedNames(File file) {
    Map<String, Boolean> nameStates = names.get(file);
    if (nameStates == null) {
      return Collections.emptySet();
    } else {
      return nameStates.entrySet().stream()
          .filter(entry -> entry.getValue())
          .map(entry -> entry.getKey())
          .collect(Collectors.toSet());
    }
  }

  public Map<File, Schema> parse() throws IOException {
    Map<File, Schema> result = new HashMap<>(schemaFiles.size());
    Parser parser = new Parser();
    for (File file : dependencyOrder) {
      Schema schema = parser.parse(file);
      if (schemaFiles.contains(file)) {
        result.put(file, schema);
      }
    }
    return result;
  }

  private void collectNames(JsonNode schema, String namespace, Map<String, Boolean> nameStates) {
    if (schema.isTextual()) {
      recordName(nameStates, schema.getTextValue(), namespace, false);
    } else if (schema.isObject()) {
      String type = getText(schema, "type");
      if ("record".equals(type) || "error".equals(type) || "enum".equals(type)
          || "fixed".equals(type)) {
        String newNamespace = getText(schema, "namespace");
        if (newNamespace != null) {
          namespace = newNamespace;
        }
        String name = getText(schema, "name");
        int lastDotIndex = name.lastIndexOf('.');
        if (lastDotIndex >= 0) {
          namespace = name.substring(0, lastDotIndex);
        }

        Boolean defined = null;
        if ("record".equals(type) || "error".equals(type)) {
          JsonNode fieldsNode = schema.get("fields");
          defined = fieldsNode != null;
          if (fieldsNode != null) {
            for (JsonNode field : fieldsNode) {
              JsonNode fieldTypeNode = field.get("type");
              collectNames(fieldTypeNode, namespace, nameStates);
            }
          }
        } else if ("enum".equals(type)) {
          defined = schema.get("symbols") != null;
        } else if ("fixed".equals("symbols")) {
          defined = true;
        }
        recordName(nameStates, name, namespace, defined);
      } else if ("array".equals(type)) {
        collectNames(schema.get("items"), namespace, nameStates);
      } else if ("map".equals(type)) {
        collectNames(schema.get("values"), namespace, nameStates);
      }
    } else if (schema.isArray()) {
      for (JsonNode typeNode : schema) {
        collectNames(typeNode, namespace, nameStates);
      }
    }
  }

  private void computeDependencyOrder() {
    Comparator<File> comparator = (file1, file2) -> {
      Map<String, Boolean> nameStates1 = names.get(file1);
      Map<String, Boolean> nameStates2 = names.get(file2);
      for (Entry<String, Boolean> entry : nameStates1.entrySet()) {
        Boolean value = nameStates2.get(entry.getKey());
        if (!entry.getValue()) {
          if (value != null && value) {
            return 1;
          }
        } else if (entry.getValue()) {
          if (value != null && !value) {
            return -1;
          }
        }
      }
      return 0;
    };

    dependencyOrder.clear();
    File[] files = names.keySet().toArray(new File[names.size()]);
    for (int i = 0; i < files.length; i++) {
      File min = files[i];
      for (int j = i + 1; j < files.length; j++) {
        int result = comparator.compare(min, files[j]);
        if (result > 0) {
          min = files[j];
          files[j] = files[i];
          files[i] = min;
        }
      }
      dependencyOrder.add(min);
    }

    validateDependencyOrder();
  }

  private String getText(JsonNode node, String key) {
    JsonNode child = node.get(key);
    return child != null ? child.getTextValue() : null;
  }

  private void parseSchemaFiles(List<File> schemaFiles) throws JsonParseException, IOException {
    for (File schemaFile : schemaFiles) {
      JsonParser jsonParser = Schema.FACTORY.createJsonParser(schemaFile);
      JsonNode schema = Schema.MAPPER.readTree(jsonParser);
      Map<String, Boolean> nameStates = new HashMap<>();
      collectNames(schema, null, nameStates);
      names.put(schemaFile, nameStates);
    }
  }

  private void recordName(Map<String, Boolean> nameStates, String name, String namespace,
      Boolean defined) {
    if (defined != null) {
      if (!Schema.PRIMITIVES.containsKey(name) && !"enum".equals(name)) {
        String fullName;
        if (name.indexOf('.') >= 0 || namespace == null) {
          fullName = name;
        } else {
          fullName = namespace + "." + name;
        }
        Boolean value = nameStates.get(fullName);
        if (value == null || !value && defined) {
          nameStates.put(fullName, defined);
        }
      }
    }
  }

  private void validateDependencyOrder() {
    Set<String> definedNames = new HashSet<>();
    for (File file : dependencyOrder) {
      Map<String, Boolean> nameStates = names.get(file);
      for (Entry<String, Boolean> entry : nameStates.entrySet()) {
        if (entry.getValue()) {
          definedNames.add(entry.getKey());
        } else if (!definedNames.contains(entry.getKey())) {
          throw new RuntimeException("Name " + entry.getKey() + " in " + file + " is not defined");
        }
      }
    }
  }
}
