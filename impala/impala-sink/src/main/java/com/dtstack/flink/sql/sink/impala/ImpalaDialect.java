/*
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

package com.dtstack.flink.sql.sink.impala;

import com.dtstack.flink.sql.sink.impala.table.ImpalaTableInfo;
import com.dtstack.flink.sql.sink.rdb.dialect.JDBCDialect;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.common.typeinfo.TypeInformation;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Date: 2020/1/17
 * Company: www.dtstack.com
 *
 * @author maqi
 */
public class ImpalaDialect implements JDBCDialect {
    private static final long serialVersionUID = 1L;

    private static final String IMPALA_PARTITION_KEYWORD = "partition";

    private TypeInformation[] fieldTypes;

    private List<String> primaryKeys;

    private String storeType;

    public ImpalaDialect(TypeInformation[] fieldTypes,
                         List<String> primaryKeys,
                         String storeType) {
        this.fieldTypes = fieldTypes;
        this.primaryKeys = primaryKeys;
        this.storeType = storeType;
    }

    @Override
    public boolean canHandle(String url) {
        return url.startsWith("jdbc:impala:");
    }

    @Override
    public Optional<String> defaultDriverName() {
        return Optional.of("com.cloudera.impala.jdbc41.Driver");
    }

    @Override
    public String quoteIdentifier(String identifier) {
        return identifier;
    }

    @Override
    public String getUpdateStatement(String tableName, String[] fieldNames, String[] conditionFields) {
        //跳过primary key字段
        String setClause = Arrays.stream(fieldNames)
                .filter(f -> !CollectionUtils.isNotEmpty(primaryKeys) || !primaryKeys.contains(f))
                .map(f -> quoteIdentifier(f) + "=?")
                .collect(Collectors.joining(", "));
        String conditionClause = Arrays.stream(conditionFields)
                .map(f -> quoteIdentifier(f) + "=?")
                .collect(Collectors.joining(" AND "));
        return "UPDATE " + quoteIdentifier(tableName) +
                " SET " + setClause +
                " WHERE " + conditionClause;
    }

    @Override
    public String getInsertIntoStatement(String schema, String tableName, String[] fieldNames, String[] partitionFields) {

        String schemaInfo = StringUtils.isEmpty(schema) ? "" : quoteIdentifier(schema) + ".";

        List<String> partitionFieldsList = Objects.isNull(partitionFields) ? Lists.newArrayList() : Arrays.asList(partitionFields);

        if (storeType.equalsIgnoreCase(ImpalaTableInfo.KUDU_TYPE)) {
            return buildKuduInsertSql(schemaInfo, tableName, fieldNames, fieldTypes);
        }

        String columns = Arrays.stream(fieldNames)
                .filter(f -> !partitionFieldsList.contains(f))
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));

        String placeholders = Arrays.stream(fieldTypes)
                .map(f -> {
                    if (String.class.getName().equals(f.getTypeClass().getName())) {
                        return "cast( ? as string)";
                    }
                    return "?";
                })
                .collect(Collectors.joining(", "));

        String partitionFieldStr = partitionFieldsList.stream()
                .map(field -> field.replaceAll("\"", "'"))
                .collect(Collectors.joining(", "));

        String partitionStatement = StringUtils.isEmpty(partitionFieldStr) ? "" : " " + IMPALA_PARTITION_KEYWORD + "(" + partitionFieldStr + ")";

        return "INSERT INTO " + schemaInfo + quoteIdentifier(tableName) +
                "(" + columns + ")" + partitionStatement + " VALUES (" + placeholders + ")";
    }

    private String buildKuduInsertSql(String schemaInfo, String tableName, String[] fieldNames, TypeInformation[] fieldTypes) {  // kudu表的Insert语句
        String columns = Arrays.stream(fieldNames)
                .map(this::quoteIdentifier)
                .collect(Collectors.joining(", "));
        String placeholders = Arrays.stream(fieldTypes)
                .map(f -> {
                    if (String.class.getName().equals(f.getTypeClass().getName())) {
                        return "cast( ? as string)";
                    }
                    return "?";
                })
                .collect(Collectors.joining(", "));
        return "INSERT INTO " + schemaInfo + quoteIdentifier(tableName) +
                "(" + columns + ")" + " VALUES (" + placeholders + ")";
    }
}
