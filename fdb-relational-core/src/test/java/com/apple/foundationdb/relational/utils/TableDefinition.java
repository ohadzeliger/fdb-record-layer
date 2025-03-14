/*
 * TableDefinition.java
 *
 * This source file is part of the FoundationDB open source project
 *
 * Copyright 2021-2024 Apple Inc. and the FoundationDB project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.apple.foundationdb.relational.utils;

import java.util.List;
import java.util.Map;

public class TableDefinition extends TypeDefinition {
    private final List<String> pks;

    public TableDefinition(String name, List<String> columns, List<String> pks) {
        super(name, columns);
        this.pks = pks;
    }

    public TableDefinition(String name, Map<String, String> columns, List<String> pks) {
        super(name, columns);
        this.pks = pks;
    }

    List<String> getPrimaryKeyColumns() {
        return pks;
    }

    @Override
    protected String columnTypeDefinition() {
        String pkDef = ", PRIMARY KEY (" + String.join(",", pks) + ")";
        return super.columnTypeDefinition().replace(")", pkDef + ")");
    }
}
