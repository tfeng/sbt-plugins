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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.avro.Schema.Parser;
import org.apache.avro.compiler.idl.NameTrackingIdl;
import org.apache.avro.compiler.idl.NameTrackingIdl.NameTrackingMap;
import org.apache.avro.compiler.idl.ParseException;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.node.ArrayNode;

import com.google.common.collect.ImmutableSet;

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
public class SchemaProcessor {

  public static class ParseResult {

    private final Map<File, Protocol> protocols;
    private final Map<File, Schema> schemas;

    private ParseResult(Map<File, Schema> schemas, Map<File, Protocol> protocols) {
      this.schemas = schemas;
      this.protocols = protocols;
    }

    public Map<File, Protocol> getProtocols() {
      return protocols;
    }

    public Map<File, Schema> getSchemas() {
      return schemas;
    }
  }

  private static final Set<String> PREDEFINED_TYPES = ImmutableSet.<String>builder()
      .addAll(Schema.PRIMITIVES.keySet())
      .add("array", "enum", "error", "fixed", "map", "record")
      .build();

  private static final Method PROTOCOL_PARSE_METHOD;

  static {
    try {
      PROTOCOL_PARSE_METHOD = Protocol.class.getDeclaredMethod("parse", JsonNode.class);
      PROTOCOL_PARSE_METHOD.setAccessible(true);
    } catch (Exception e) {
      throw new RuntimeException("Unable to get access to Protocol.parse(JsonNode) method", e);
    }
  }

  private final List<File> dependencyOrder = new ArrayList<>();

  private final Set<File> idlFiles;

  private final Map<File, Map<String, Boolean>> names = new HashMap<>();

  private final Set<File> protocolFiles;

  private final Set<File> schemaFiles;

  public SchemaProcessor(List<File> schemaFiles, List<File> externalSchemas,
      List<File> protocolFiles, List<File> idlFiles) throws IOException {
    this.schemaFiles = new HashSet<>(schemaFiles);
    this.protocolFiles = new HashSet<>(protocolFiles);
    this.idlFiles = new HashSet<>(idlFiles);
    parseSchemaFiles(schemaFiles);
    parseSchemaFiles(externalSchemas);
    parseProtocolFiles(protocolFiles);
    parseIdlFiles(idlFiles);
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

  public ParseResult parse() throws IOException {
    Map<File, Schema> schemas = new HashMap<>(schemaFiles.size());
    Map<File, Protocol> protocols = new HashMap<>(protocolFiles.size() + idlFiles.size());
    Map<String, Schema> types = new HashMap<>();
    Parser parser = new Parser();
    for (File file : dependencyOrder) {
      if (protocolFiles.contains(file)) {
        Protocol protocol = new Protocol(null, null);
        protocol.setTypes(types.values());
        JsonParser jsonParser = Schema.FACTORY.createJsonParser(file);
        JsonNode json = Schema.MAPPER.readTree(jsonParser);

        try {
          PROTOCOL_PARSE_METHOD.invoke(protocol, json);
        } catch (InvocationTargetException e) {
          throw new IOException("Unable to parse protocol file " + file, e.getTargetException());
        } catch (Exception e) {
          throw new RuntimeException("Unable to get access to Protocol.parse(JsonNode) method", e);
        }

        protocols.put(file, protocol);
        for (Schema type : protocol.getTypes()) {
          collectSchemas(type, types);
        }
      } else if (idlFiles.contains(file)) {
        NameTrackingIdl idl = new NameTrackingIdl(file);
        NameTrackingMap names = idl.getNames();
        names.putAll(types);

        Protocol protocol;
        try {
          protocol = idl.CompilationUnit();
        } catch (ParseException e) {
          throw new IOException("Unable to parse IDL file " + file, e);
        } finally {
          idl.close();
        }

        protocols.put(file, protocol);
        for (Schema type : protocol.getTypes()) {
          collectSchemas(type, types);
        }
      } else {
        Schema schema = parser.parse(file);
        collectSchemas(schema, types);
        if (schemaFiles.contains(file)) {
          schemas.put(file, schema);
        }
      }
    }
    return new ParseResult(schemas, protocols);
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

  private void collectNamesForProtocol(JsonNode protocol, Map<String, Boolean> nameStates) {
    String namespace = getText(protocol, "namespace");

    ArrayNode types = (ArrayNode) protocol.get("types");
    if (types != null) {
      for (JsonNode type : types) {
        collectNames(type, namespace, nameStates);
      }
    }

    JsonNode messages = protocol.get("messages");
    if (messages != null) {
      for (Iterator<String> i = messages.getFieldNames(); i.hasNext();) {
        JsonNode message = messages.get(i.next());

        JsonNode request = message.get("request");
        for (Iterator<JsonNode> iterator = request.getElements(); iterator.hasNext();) {
          JsonNode type = iterator.next().get("type");
          collectNames(type, namespace, nameStates);
        }

        JsonNode response = message.get("response");
        collectNames(response, namespace, nameStates);

        ArrayNode errors = (ArrayNode) message.get("errors");
        if (errors != null) {
          for (JsonNode error : errors) {
            collectNames(error, namespace, nameStates);
          }
        }
      }
    }
  }

  private void collectSchemas(Schema schema, Map<String, Schema> schemas) {
    switch (schema.getType()) {
    case RECORD:
      schemas.put(schema.getFullName(), schema);
      schema.getFields().forEach(field -> collectSchemas(field.schema(), schemas));
      break;
    case MAP:
      collectSchemas(schema.getValueType(), schemas);
      break;
    case ARRAY:
      collectSchemas(schema.getElementType(), schemas);
      break;
    case UNION:
      schema.getTypes().forEach(type -> collectSchemas(type, schemas));
      break;
    case ENUM:
    case FIXED:
      schemas.put(schema.getFullName(), schema);
      break;
    default:
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

  private void parseIdlFiles(List<File> idlFiles) throws IOException {
    for (File idlFile : idlFiles) {
      NameTrackingIdl idl = new NameTrackingIdl(idlFile);
      try {
        idl.CompilationUnit();
      } catch (ParseException e) {
        throw new IOException("Unable to parse IDL file " + idlFile, e);
      } finally {
        idl.close();
      }

      NameTrackingMap names = idl.getNames();
      Set<String> undefinedNames = names.getUndefinedNames();
      Map<String, Boolean> nameStates = new HashMap<>(names.size() + undefinedNames.size());
      for (String name : names.keySet()) {
        nameStates.put(name, true);
      }
      for (String undefinedName : undefinedNames) {
        nameStates.put(undefinedName, false);
      }

      this.names.put(idlFile, nameStates);
    }
  }

  private void parseProtocolFiles(List<File> protocolFiles) throws IOException {
    for (File protocolFile : protocolFiles) {
      JsonParser jsonParser = Schema.FACTORY.createJsonParser(protocolFile);
      JsonNode schema = Schema.MAPPER.readTree(jsonParser);
      Map<String, Boolean> nameStates = new HashMap<>();
      collectNamesForProtocol(schema, nameStates);
      names.put(protocolFile, nameStates);
    }
  }

  private void parseSchemaFiles(List<File> schemaFiles) throws IOException {
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
      if (!PREDEFINED_TYPES.contains(name)) {
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
