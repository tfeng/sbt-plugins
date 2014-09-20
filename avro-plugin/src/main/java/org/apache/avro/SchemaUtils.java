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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
public class SchemaUtils {

  public static List<File> sortSchemas(List<File> schemaFiles) throws Exception {
    Map<File, Map<String, Boolean>> map = new HashMap<>();
    for (File schemaFile : schemaFiles) {
      JsonParser jsonParser = Schema.FACTORY.createJsonParser(schemaFile);
      JsonNode schema = Schema.MAPPER.readTree(jsonParser);
      Map<String, Boolean> names = new HashMap<>();
      collectNames(schema, names);
      map.put(schemaFile, names);
    }
    List<File> order = sortDependency(map);
    validateOrder(map, order);
    return order;
  }

  private static void collectNames(JsonNode schema, Map<String, Boolean> names) {
    if (schema.isTextual()) {
      recordName(names, schema.getTextValue(), false);
    } else if (schema.isObject()) {
      String type = getText(schema, "type");
      if ("record".equals(type) || "error".equals(type) || "enum".equals(type)
          || "fixed".equals(type)) {
        String namespace = getText(schema, "namespace");
        String name = getText(schema, "name");
        String fullName = namespace == null ? name : namespace + "." + name;
        boolean defined = false;
        if ("record".equals(type) || "error".equals(type)) {
          JsonNode fieldsNode = schema.get("fields");
          defined = fieldsNode != null;
          if (defined) {
            for (JsonNode field : fieldsNode) {
              JsonNode fieldTypeNode = field.get("type");
              collectNames(fieldTypeNode, names);
            }
          }
        } else if ("enum".equals(type)) {
          defined = schema.get("symbols") != null;
        } else if ("fixed".equals("symbols")) {
          defined = true;
        }
        recordName(names, fullName, defined);
      } else if ("array".equals(type)) {
        collectNames(schema.get("items"), names);
      } else if ("map".equals(type)) {
        collectNames(schema.get("values"), names);
      }
    } else if (schema.isArray()) {
      for (JsonNode typeNode : schema) {
        collectNames(typeNode, names);
      }
    }
  }

  private static String getText(JsonNode node, String key) {
    JsonNode child = node.get(key);
    return child != null ? child.getTextValue() : null;
  }

  private static void recordName(Map<String, Boolean> names, String name, boolean defined) {
    if (!Schema.PRIMITIVES.containsKey(name)) {
      Boolean value = names.get(name);
      if (value == null || !value && defined) {
        names.put(name, defined);
      }
    }
  }

  private static List<File> sortDependency(Map<File, Map<String, Boolean>> map) {
    Comparator<File> comparator = (file1, file2) -> {
      Map<String, Boolean> map1 = map.get(file1);
      Map<String, Boolean> map2 = map.get(file2);
      for (Entry<String, Boolean> entry : map1.entrySet()) {
        Boolean value = map2.get(entry.getKey());
        if (!entry.getValue()) {
          if (value != null && value) {
            return 1;
          }
        } else {
          if (value != null && !value) {
            return -1;
          }
        }
      }
      return 0;
    };

    List<File> order = new ArrayList<>(map.size());
    File[] files = map.keySet().toArray(new File[map.size()]);
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
      order.add(min);
    }
    return order;
  }

  private static void validateOrder(Map<File, Map<String, Boolean>> map, List<File> order) {
    Set<String> definedNames = new HashSet<>();
    for (File file : order) {
      Map<String, Boolean> names = map.get(file);
      for (Entry<String, Boolean> entry : names.entrySet()) {
        boolean defined = entry.getValue();
        if (defined) {
          definedNames.add(entry.getKey());
        } else if (!definedNames.contains(entry.getKey())) {
          throw new RuntimeException("Name " + entry.getKey() + " in " + file + " is not defined");
        }
      }
    }
  }
}
