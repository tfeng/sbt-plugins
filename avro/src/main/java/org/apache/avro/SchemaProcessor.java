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

package org.apache.avro;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
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

import org.apache.avro.Protocol.Message;
import org.apache.avro.Schema.Parser;
import org.apache.avro.compiler.idl.NameTrackingIdl;
import org.apache.avro.compiler.idl.NameTrackingIdl.NameTrackingMap;
import org.apache.avro.compiler.idl.ParseException;
import org.apache.avro.generic.GenericData.StringType;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.codehaus.jackson.node.TextNode;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;

/**
 * @author Thomas Feng (huining.feng@gmail.com)
 */
public class SchemaProcessor {

  public class DependencyComparator implements Comparator<File> {

    @Override
    public int compare(File file1, File file2) {
      Map<String, Boolean> nameStates1 = names.get(file1);
      Map<String, Boolean> nameStates2 = names.get(file2);
      int result = 0;
      for (Entry<String, Boolean> entry : nameStates1.entrySet()) {
        Boolean value = nameStates2.get(entry.getKey());
        if (!entry.getValue()) {
          if (value != null && value) {
            if (result == -1) {
              throw new RuntimeException("These two files mutually depend on each other: " + file1 + " and " + file2);
            } else {
              result = 1;
            }
          }
        } else if (entry.getValue()) {
          if (value != null && !value) {
            if (result == 1) {
              throw new RuntimeException("These two files mutually depend on each other: " + file1 + " and " + file2);
            } else {
              result = -1;
            }
          }
        }
      }
      return result;
    }
  }

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

  private static String STRING_PROP = "avro.java.string";

  static {
    try {
      PROTOCOL_PARSE_METHOD = Protocol.class.getDeclaredMethod("parse", JsonNode.class);
      PROTOCOL_PARSE_METHOD.setAccessible(true);
    } catch (Exception e) {
      throw new RuntimeException("Unable to get access to Protocol.parse(JsonNode) method", e);
    }
  }

  private final DependencyComparator dependencyComparator = new DependencyComparator();

  private final List<File> dependencyOrder = new ArrayList<>();

  private List<Schema> extraSchemas = new ArrayList<>();

  private final Set<File> idlFiles;

  private final Map<File, Map<String, Boolean>> names = new HashMap<>();

  private final Set<File> protocolFiles;

  private final Set<File> schemaFiles;

  private StringType stringType;

  public SchemaProcessor(List<File> schemaFiles, List<File> externalSchemas, List<File> protocolFiles,
      List<File> idlFiles, StringType stringType, List<String> extraSchemaClasses) throws IOException {
    this.schemaFiles = new HashSet<>(schemaFiles);
    this.protocolFiles = new HashSet<>(protocolFiles);
    this.idlFiles = new HashSet<>(idlFiles);
    this.stringType = stringType;
    addExtraSchemas(extraSchemaClasses);
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
    for (File file : dependencyOrder) {
      if (protocolFiles.contains(file)) {
        Protocol protocol = new Protocol(null, null);
        protocol.setTypes(types.values());
        JsonParser jsonParser = Schema.FACTORY.createJsonParser(file);
        JsonNode json = Schema.MAPPER.readTree(jsonParser);
        processImports(json, null, types);

        try {
          PROTOCOL_PARSE_METHOD.invoke(protocol, json);
        } catch (InvocationTargetException e) {
          throw new IOException("Unable to parse protocol file " + file, e.getTargetException());
        } catch (Exception e) {
          throw new RuntimeException("Unable to get access to Protocol.parse(JsonNode) method", e);
        }

        addStringProperties(protocol);
        protocols.put(file, protocol);
        for (Schema type : protocol.getTypes()) {
          collectSchemas(type, types);
        }
      } else if (idlFiles.contains(file)) {
        NameTrackingIdl idl = new NameTrackingIdl(file);
        NameTrackingMap names = idl.getNames();
        names.putAll(types);
        addExtraSchemasToIdl(idl);

        Protocol protocol;
        try {
          protocol = idl.CompilationUnit();
        } catch (ParseException e) {
          throw new IOException("Unable to parse IDL file " + file, e);
        } finally {
          idl.close();
        }

        addStringProperties(protocol);
        protocols.put(file, protocol);
        for (Schema type : protocol.getTypes()) {
          collectSchemas(type, types);
        }
      } else {
        JsonParser jsonParser = Schema.FACTORY.createJsonParser(file);
        JsonNode json = Schema.MAPPER.readTree(jsonParser);
        processImports(json, null, types);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        JsonGenerator jsonGenerator = Schema.FACTORY.createJsonGenerator(outputStream);
        Schema.MAPPER.writeTree(jsonGenerator, json);

        ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
        Parser parser = new Parser();
        parser.addTypes(types);
        Schema schema = parser.parse(inputStream);
        addStringProperties(schema);
        collectSchemas(schema, types);
        if (schemaFiles.contains(file)) {
          schemas.put(file, schema);
        }
      }
    }
    return new ParseResult(schemas, protocols);
  }

  private void addExtraSchemas(List<String> extraSchemaClasses) throws IOException {
    ClassLoader classLoader = SchemaProcessor.class.getClassLoader();
    for (String extraSchemaClass : extraSchemaClasses) {
      try {
        Class<?> clazz = classLoader.loadClass(extraSchemaClass);
        Field field = clazz.getField("SCHEMA$");
        Schema schema = (Schema) field.get(null);
        extraSchemas.add(schema);
      } catch (Exception e) {
        throw new IOException("Unable to load extra schema class " + extraSchemaClass);
      }
    }
  }

  private void addExtraSchemasToIdl(NameTrackingIdl idl) {
    NameTrackingMap nameMap = idl.getNames();
    for (Schema extraSchema : extraSchemas) {
      nameMap.put(extraSchema.getFullName(), extraSchema);
    }
  }

  private void addStringProperties(Protocol protocol) {
    for (Schema type : protocol.getTypes()) {
      addStringProperties(type);
    }
    for (Message message : protocol.getMessages().values()) {
      addStringProperties(message.getRequest());
      addStringProperties(message.getResponse());
      addStringProperties(message.getErrors());
    }
  }

  private void addStringProperties(Schema schema) {
    switch (schema.getType()) {
    case STRING:
      if (schema.getProp(STRING_PROP) == null) {
        schema.addProp(STRING_PROP, stringType.name());
      }
      break;
    case RECORD:
      schema.getFields().forEach(field -> addStringProperties(field.schema()));
      break;
    case MAP:
      addStringProperties(schema.getValueType());
      break;
    case ARRAY:
      addStringProperties(schema.getElementType());
      break;
    case UNION:
      schema.getTypes().forEach(type -> addStringProperties(type));
      break;
    default:
    }
  }

  private void collectNames(JsonNode schema, String namespace, Map<String, Boolean> nameStates) {
    if (schema.isTextual()) {
      recordName(nameStates, schema.getTextValue(), namespace, false);
    } else if (schema.isObject()) {
      String type = getText(schema, "type");
      if ("record".equals(type) || "error".equals(type) || "enum".equals(type) || "fixed".equals(type)) {
        String newNamespace = getText(schema, "namespace");
        if (newNamespace != null) {
          namespace = newNamespace;
        }

        String name = getText(schema, "name");
        int lastDotIndex = name.lastIndexOf('.');
        if (lastDotIndex >= 0) {
          namespace = name.substring(0, lastDotIndex);
        }

        ArrayNode imports = (ArrayNode) schema.get("imports");
        if (imports != null) {
          for (Iterator<JsonNode> importNodes = imports.getElements(); importNodes.hasNext();) {
            recordName(nameStates, importNodes.next().getTextValue(), namespace, false);
          }
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
        } else if ("fixed".equals(type)) {
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
    dependencyOrder.clear();

    HashMultimap<File, File> dependencies = HashMultimap.create();
    Set<File> files = new HashSet<>(names.keySet());
    for (File file1 : files) {
      for (File file2 : files) {
        if (!file1.equals(file2)) {
          if (dependencyComparator.compare(file1, file2) > 0) {
            dependencies.put(file1, file2); // file1 depends on file2.
          }
        }
      }
    }

    while (!files.isEmpty()) {
      File nextFile = null;
      for (File file : files) {
        if (dependencies.get(file).isEmpty()) {
          nextFile = file;
          break;
        }
      }
      if (nextFile == null) {
        throw new RuntimeException("Unable to sort schema files topologically; "
            + "remaining files that contain dependency cycle(s) are these: "
            + dependencies.keySet());
      }
      files.remove(nextFile);
      dependencies.removeAll(nextFile);
      for (Iterator<Entry<File, File>> iterator = dependencies.entries().iterator(); iterator.hasNext();) {
        if (nextFile.equals(iterator.next().getValue())) {
          iterator.remove();
        }
      }
      dependencyOrder.add(nextFile);
    }
  }

  private String getFullName(String name, String namespace) {
    if (name.indexOf('.') >= 0 || namespace == null) {
      return name;
    } else {
      return namespace + "." + name;
    }
  }

  private String getText(JsonNode node, String key) {
    JsonNode child = node.get(key);
    return child != null ? child.getTextValue() : null;
  }

  private void parseIdlFiles(List<File> idlFiles) throws IOException {
    for (File idlFile : idlFiles) {
      NameTrackingIdl idl = new NameTrackingIdl(idlFile);
      addExtraSchemasToIdl(idl);

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

  private void processImports(JsonNode schema, String namespace, Map<String, Schema> types) throws IOException {
    if (schema.isObject()) {
      String type = getText(schema, "type");
      if ("record".equals(type) || "error".equals(type)) {
        String newNamespace = getText(schema, "namespace");
        if (newNamespace != null) {
          namespace = newNamespace;
        }

        String name = getText(schema, "name");
        int lastDotIndex = name.lastIndexOf('.');
        if (lastDotIndex >= 0) {
          namespace = name.substring(0, lastDotIndex);
        }

        String fullName = getFullName(name, namespace);

        JsonNode imports = schema.get("imports");
        if (imports != null) {
          for (JsonNode importNode : imports) {
            String importedSchemaName = getFullName(importNode.getTextValue(), namespace);
            Schema importedSchema = types.get(importedSchemaName);
            if (importedSchema == null) {
              throw new IOException("Unable to load imported schema " + importedSchemaName);
            }
            JsonNode importedSchemaNode = schemaToJson(importedSchema);
            removeTypeDefinitions(null, importedSchemaNode);
            String importedSchemaType = getText(importedSchemaNode, "type");

            if ("record".equals(importedSchemaType) || "error".equals(importedSchemaType)) {
              ArrayNode fieldsNode = (ArrayNode) schema.get("fields");
              if (fieldsNode == null) {
                fieldsNode = Schema.MAPPER.createArrayNode();
                ((ObjectNode) schema).put("fields", fieldsNode);
              }

              JsonNode importedFields = importedSchemaNode.get("fields");
              if (importedFields != null) {
                int i = 0;
                for (JsonNode importedField : importedFields) {
                  fieldsNode.insert(i++, importedField);
                }
              }
            } else {
              throw new IOException("Cannot import schema " + importedSchemaName + " of type " + importedSchemaType
                  + " into " + fullName + " of type " + type);
            }
          }
        }

        JsonNode fieldsNode = schema.get("fields");
        if (fieldsNode != null) {
          for (JsonNode field : fieldsNode) {
            JsonNode fieldTypeNode = field.get("type");
            processImports(fieldTypeNode, namespace, types);
          }
        }
      } else if ("array".equals(type)) {
        processImports(schema.get("items"), namespace, types);
      } else if ("map".equals(type)) {
        processImports(schema.get("values"), namespace, types);
      }
    } else if (schema.isArray()) {
      for (JsonNode typeNode : schema) {
        processImports(typeNode, namespace, types);
      }
    }
  }

  private void recordName(Map<String, Boolean> nameStates, String name, String namespace, Boolean defined) {
    if (defined != null) {
      if (!PREDEFINED_TYPES.contains(name)) {
        String fullName = getFullName(name, namespace);
        Boolean value = nameStates.get(fullName);
        if (value == null || !value && defined) {
          nameStates.put(fullName, defined);
        }
      }
    }
  }

  private void removeTypeDefinitions(String namespace, JsonNode schema) {
    if (schema == null) {
      return;
    }
    String type = getText(schema, "type");
    if ("record".equals(type) || "error".equals(type)) {
      TextNode namespaceNode = (TextNode) schema.get("namespace");
      String newNamespace = namespaceNode == null ? namespace : namespaceNode.getTextValue();
      ArrayNode fieldsNode = (ArrayNode) schema.get("fields");
      if (fieldsNode != null) {
        for (JsonNode fieldNode : fieldsNode) {
          JsonNode fieldTypeNode = fieldNode.get("type");
          if (fieldTypeNode.isObject()) {
            String fieldType = getText(fieldTypeNode, "name");
            if (fieldType != null) {
              ((ObjectNode) fieldNode).put("type", new TextNode(getFullName(fieldType, newNamespace)));
              continue;
            }
          }
          removeTypeDefinitions(newNamespace, fieldTypeNode);
        }
      }
    } else if ("array".equals(type)) {
      JsonNode itemsNode = schema.get("items");
      if (itemsNode != null) {
        String itemType = getText(itemsNode, "name");
        if (itemType != null) {
          ((ObjectNode) schema).put("items", new TextNode(itemType));
          return;
        }
      }
      removeTypeDefinitions(namespace, itemsNode);
    } else if ("map".equals(type)) {
      JsonNode valuesNode = schema.get("values");
      if (valuesNode != null) {
        String valueType = getText(valuesNode, "name");
        if (valueType != null) {
          ((ObjectNode) schema).put("values", new TextNode(valueType));
          return;
        }
      }
      removeTypeDefinitions(namespace, valuesNode);
    }
  }

  private JsonNode schemaToJson(Schema schema) throws IOException {
    JsonParser jsonParser = Schema.FACTORY.createJsonParser(schema.toString());
    return jsonParser.readValueAsTree();
  }
}
