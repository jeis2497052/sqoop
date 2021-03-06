/**
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

package org.apache.sqoop.orm;

import java.io.IOException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.avro.LogicalType;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.Schema.Type;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.sqoop.SqoopOptions;
import org.apache.sqoop.manager.ConnManager;
import org.apache.sqoop.avro.AvroUtil;

import org.apache.sqoop.config.ConfigurationConstants;
import org.codehaus.jackson.node.NullNode;

/**
 * Creates an Avro schema to represent a table from a database.
 */
public class AvroSchemaGenerator {

  public static final Log LOG =
      LogFactory.getLog(AvroSchemaGenerator.class.getName());

  /**
   * Map precision to the number bytes needed for binary conversion.
   * @see <a href="https://github.com/apache/hive/blob/release-1.1/ql/src/java/org/apache/hadoop/hive/ql/io/parquet/serde/ParquetHiveSerDe.java#L90">Apache Hive</a>.
   */
  public static final int MAX_PRECISION = 38;
  public static final int PRECISION_TO_BYTE_COUNT[] = new int[MAX_PRECISION];
  static {
    for (int prec = 1; prec <= MAX_PRECISION; prec++) {
      // Estimated number of bytes needed.
      PRECISION_TO_BYTE_COUNT[prec - 1] = (int)
          Math.ceil((Math.log(Math.pow(10, prec) - 1) / Math.log(2) + 1) / 8);
    }
  }

  private final SqoopOptions options;
  private final ConnManager connManager;
  private final String tableName;

  private final String DEFAULT_SCHEMA_NAME = "AutoGeneratedSchema";

  public AvroSchemaGenerator(final SqoopOptions opts, final ConnManager connMgr,
      final String table) {
    this.options = opts;
    this.connManager = connMgr;
    this.tableName = table;
  }

  // Backward compatible method SQOOP-2597
  public Schema generate() throws IOException {
    return generate(null);
  }

  public Schema generate(String schemaNameOverride) throws IOException {
    ClassWriter classWriter = new ClassWriter(options, connManager,
        tableName, null);
    Map<String, List<Integer>> columnInfo = classWriter.getColumnInfo();
    Map<String, Integer> columnTypes = classWriter.getColumnTypes();
    String[] columnNames = classWriter.getColumnNames(columnTypes);

    List<Field> fields = new ArrayList<Field>();
    for (String columnName : columnNames) {
      String cleanedCol = AvroUtil.toAvroIdentifier(ClassWriter.toJavaIdentifier(columnName));
      List<Integer> columnInfoList = columnInfo.get(columnName);
      int sqlType = columnInfoList.get(0);
      Integer precision = columnInfoList.get(1);
      Integer scale = columnInfoList.get(2);
      Schema avroSchema = toAvroSchema(sqlType, columnName, precision, scale);
      Field field = new Field(cleanedCol, avroSchema, null,  NullNode.getInstance());
      field.addProp("columnName", columnName);
      field.addProp("sqlType", Integer.toString(sqlType));
      fields.add(field);
    }

    TableClassName tableClassName = new TableClassName(options);
    String shortClassName = tableName == null ? DEFAULT_SCHEMA_NAME : tableClassName.getShortClassForTable(tableName);
    String avroTableName = (tableName == null ? TableClassName.QUERY_RESULT : tableName);
    String avroName = schemaNameOverride != null ? schemaNameOverride :
        (shortClassName == null ? avroTableName : shortClassName);
    String avroNamespace = tableClassName.getPackageForTable();

    String doc = "Sqoop import of " + avroTableName;
    Schema schema = Schema.createRecord(avroName, doc, avroNamespace, false);
    schema.setFields(fields);
    schema.addProp("tableName", avroTableName);
    return schema;
  }

  /**
   * Will create union, because each type is assumed to be nullable.
   *
   * @param sqlType Original SQL type (might be overridden by user)
   * @param columnName Column name from the query
   * @param precision Fixed point precision
   * @param scale Fixed point scale
   * @return Schema
   */
  public Schema toAvroSchema(int sqlType, String columnName, Integer precision, Integer scale) {
    List<Schema> childSchemas = new ArrayList<Schema>();
    childSchemas.add(Schema.create(Schema.Type.NULL));
    if (options.getConf().getBoolean(ConfigurationConstants.PROP_ENABLE_AVRO_LOGICAL_TYPE_DECIMAL, false)
        && isLogicalType(sqlType)) {
      childSchemas.add(
          toAvroLogicalType(columnName, sqlType, precision, scale)
              .addToSchema(Schema.create(Type.BYTES))
      );
    } else {
      childSchemas.add(Schema.create(toAvroType(columnName, sqlType)));
    }
    return Schema.createUnion(childSchemas);
  }

  public Schema toAvroSchema(int sqlType) {
    return toAvroSchema(sqlType, null, null, null);
  }

  private Type toAvroType(String columnName, int sqlType) {
    Properties mapping = options.getMapColumnJava();

    if (mapping.containsKey(columnName)) {
      String type = mapping.getProperty(columnName);
      if (LOG.isDebugEnabled()) {
        LOG.info("Overriding type of column " + columnName + " to " + type);
      }

      if (type.equalsIgnoreCase("INTEGER")) { return Type.INT; }
      if (type.equalsIgnoreCase("LONG")) { return Type.LONG; }
      if (type.equalsIgnoreCase("BOOLEAN")) { return Type.BOOLEAN; }
      if (type.equalsIgnoreCase("FLOAT")) { return Type.FLOAT; }
      if (type.equalsIgnoreCase("DOUBLE")) { return Type.DOUBLE; }
      if (type.equalsIgnoreCase("STRING")) { return Type.STRING; }
      if (type.equalsIgnoreCase("BYTES")) { return Type.BYTES; }

      // Mapping was not found
      throw new IllegalArgumentException("Cannot convert to AVRO type " + type);
    }

    return connManager.toAvroType(tableName, columnName, sqlType);
  }

  private LogicalType toAvroLogicalType(String columnName, int sqlType, Integer precision, Integer scale) {
    return connManager.toAvroLogicalType(tableName, columnName, sqlType, precision, scale);
  }

  private static boolean isLogicalType(int sqlType) {
    switch(sqlType) {
      case Types.DECIMAL:
      case Types.NUMERIC:
        return true;
      default:
        return false;
    }
  }
}
