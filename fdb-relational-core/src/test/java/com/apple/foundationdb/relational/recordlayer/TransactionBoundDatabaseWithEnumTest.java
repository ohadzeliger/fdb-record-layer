/*
 * TransactionBoundDatabaseWithEnumTest.java
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

package com.apple.foundationdb.relational.recordlayer;

import com.apple.foundationdb.record.RecordMetaData;
import com.apple.foundationdb.record.RecordMetaDataBuilder;
import com.apple.foundationdb.record.metadata.Key;
import com.apple.foundationdb.record.metadata.RecordTypeBuilder;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordContext;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStore;
import com.apple.foundationdb.record.provider.foundationdb.FDBRecordStoreBase;
import com.apple.foundationdb.record.provider.foundationdb.FormatVersion;
import com.apple.foundationdb.record.provider.foundationdb.SubspaceProvider;
import com.apple.foundationdb.relational.api.EmbeddedRelationalDriver;
import com.apple.foundationdb.relational.api.EmbeddedRelationalEngine;
import com.apple.foundationdb.relational.api.EmbeddedRelationalStruct;
import com.apple.foundationdb.relational.api.KeySet;
import com.apple.foundationdb.relational.api.Options;
import com.apple.foundationdb.relational.api.RelationalConnection;
import com.apple.foundationdb.relational.api.RelationalResultSet;
import com.apple.foundationdb.relational.api.RelationalStatement;
import com.apple.foundationdb.relational.api.Transaction;
import com.apple.foundationdb.relational.api.exceptions.RelationalException;
import com.apple.foundationdb.relational.transactionbound.TransactionBoundEmbeddedRelationalEngine;
import com.apple.foundationdb.relational.utils.ResultSetAssert;
import com.apple.foundationdb.relational.utils.SimpleDatabaseRule;
import com.apple.foundationdb.relational.utils.TestSchemas;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import javax.annotation.Nonnull;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.fail;

public class TransactionBoundDatabaseWithEnumTest {
    @RegisterExtension
    @Order(0)
    public static final RelationalExtension relational = new EmbeddedRelationalExtension();

    @RegisterExtension
    @Order(1)
    public final SimpleDatabaseRule dbRule = new SimpleDatabaseRule(relational, TransactionBoundDatabaseWithEnumTest.class, TestSchemas.playingCard());

    @RegisterExtension
    @Order(2)
    public final RelationalConnectionRule connRule = new RelationalConnectionRule(dbRule::getConnectionUri)
            .withOptions(Options.NONE)
            .withSchema("TEST_SCHEMA");

    @Test
    void simpleInsertAndSelect() throws RelationalException, SQLException {
        try (Transaction transaction = createTransaction(connRule)) {
            EmbeddedRelationalEngine engine = new TransactionBoundEmbeddedRelationalEngine();
            EmbeddedRelationalDriver driver = new EmbeddedRelationalDriver(engine);
            try (RelationalConnection conn = driver.connect(dbRule.getConnectionUri(), transaction, Options.NONE)) {
                conn.setSchema("TEST_SCHEMA");
                try (RelationalStatement statement = conn.createStatement()) {
                    statement.executeInsert("CARD", EmbeddedRelationalStruct.newBuilder()
                            .addLong("id", 1L)
                            .addObject("suit", "DIAMONDS")
                            .addLong("rank", 1L)
                            .build()
                    );
                }

                try (RelationalStatement statement = conn.createStatement()) {
                    try (RelationalResultSet resultSet = statement.executeScan("CARD", KeySet.EMPTY, Options.NONE)) {
                        Assertions.assertThat(resultSet.next()).isTrue();
                        Assertions.assertThat(resultSet.getString("suit")).isEqualTo("DIAMONDS");
                        Assertions.assertThat(resultSet.getLong("rank")).isEqualTo(1L);
                    }
                }
            }
        }
    }

    @Disabled // enable this test once we generate enum values in the DDL with correct numbers, see below for more information.
    void filterBySuit() throws RelationalException, SQLException {
        try (Transaction transaction = createTransaction(connRule)) {
            EmbeddedRelationalEngine engine = new TransactionBoundEmbeddedRelationalEngine();
            EmbeddedRelationalDriver driver = new EmbeddedRelationalDriver(engine);
            try (RelationalConnection conn = driver.connect(dbRule.getConnectionUri(), transaction, Options.NONE)) {
                conn.setSchema("TEST_SCHEMA");
                // enum values are created in the descriptor with the following numbers (see createRecordsDescriptor()):
                // SPADES => 1, HEARTS => 2, DIAMONDS => 3, CLUBS => 4
                // however when created from DDL, the plan generator creates the values with these numbers instead:
                // SPADES => 0, HEARTS => 1, DIAMONDS => 2, CLUBS => 3
                // which is apparently illegal according to proto3 rules:
                // The default value for the SearchRequest.corpus field is CORPUS_UNSPECIFIED because that is the first
                // value defined in the enum.
                // In proto3, the first value defined in an enum definition must have the value zero and should have
                // the name ENUM_TYPE_NAME_UNSPECIFIED or ENUM_TYPE_NAME_UNKNOWN. This is because:
                //  * There must be a zero value, so that we can use 0 as a numeric default value.
                //  * The zero value needs to be the first element, for compatibility with the proto2 semantics where the
                //  first enum value is the default unless a different value is explicitly specified.
                // It is also recommended that this first, default value have no semantic meaning other than “this value
                // was unspecified”.
                // this difference in numbers is causing inserted data to become corrupt, as well as returning incorrect
                // data upon querying, when the DDL and explicit descriptor definition and usage through transaction bound
                // connection have intermixed usage as done in this test case.
                try (RelationalStatement statement = conn.createStatement()) {
                    statement.execute("INSERT INTO Card VALUES (1, 'DIAMONDS', 42), (2, 'CLUBS', 44), (3, 'SPADES', 45)");
                    Assertions.assertThat(statement.execute("SELECT * FROM Card WHERE Card.suit = 'CLUBS'")).isTrue();
                    try (var resultSet = statement.getResultSet()) {
                        ResultSetAssert.assertThat(resultSet).hasNextRow()
                                .isRowExactly(2L, "CLUBS", 44L)
                                .hasNoNextRow();
                    }
                }
            }
        }
    }

    /**
     * Create a file descriptor for this test. Its contents are equivalent to the following proto file:
     *
     * <pre>
     *     syntax = "proto2";
     *
     *     message CARD {
     *         // Note that the enum definition is nested in the type, and also note that it doesn't
     *         // have a value at position 0. This is legal in proto2 enums, though not in proto3.
     *         enum Suit {
     *             CLUBS = 1;
     *             DIAMONDS = 2;
     *             HEARTS = 3;
     *             SPADES = 4;
     *         }
     *         optional int64 id = 1;
     *         optional Suit suit = 2;
     *         optional int32 rank = 3;
     *     }
     *
     *     message RecordTypeUnion {
     *         optional Card _Card = 1;
     *     }
     * </pre>
     *
     * @return a file descriptor to use for meta-data internally.
     */
    private static Descriptors.FileDescriptor createRecordsDescriptor() {
        DescriptorProtos.FileDescriptorProto fileDescriptorProto = DescriptorProtos.FileDescriptorProto.newBuilder()
                .setName("metadata_with_enum.proto")
                .setSyntax("proto2")
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                        .setName("CARD")
                        .addEnumType(DescriptorProtos.EnumDescriptorProto.newBuilder()
                                .setName("Suit")
                                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                        .setName("SPADES")
                                        .setNumber(1)
                                )
                                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                        .setName("HEARTS")
                                        .setNumber(2)
                                )
                                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                        .setName("DIAMONDS")
                                        .setNumber(3)
                                )
                                .addValue(DescriptorProtos.EnumValueDescriptorProto.newBuilder()
                                        .setName("CLUBS")
                                        .setNumber(4)
                                )
                        )
                        .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64)
                                .setName("id")
                                .setNumber(1)
                        )
                        .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_ENUM)
                                .setTypeName("Suit")
                                .setName("suit")
                                .setNumber(2)
                        )
                        .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT64)
                                .setName("rank")
                                .setNumber(3)
                        )
                )
                .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                        .setName("RecordTypeUnion")
                        .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL)
                                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_MESSAGE)
                                .setTypeName("CARD")
                                .setName("CARD")
                                .setNumber(1)
                        )
                )
                .build();

        try {
            return Descriptors.FileDescriptor.buildFrom(fileDescriptorProto, new Descriptors.FileDescriptor[0]);
        } catch (Descriptors.DescriptorValidationException e) {
            return fail("unable to build file descriptor", e);
        }
    }

    private static RecordMetaData createRecordMetaData() {
        RecordMetaDataBuilder metaDataBuilder = RecordMetaData.newBuilder();

        metaDataBuilder.setRecords(createRecordsDescriptor());
        RecordTypeBuilder cardTypeBuilder = metaDataBuilder.getRecordType("CARD");
        cardTypeBuilder.setPrimaryKey(Key.Expressions.concat(Key.Expressions.recordType(), Key.Expressions.field("id")));
        cardTypeBuilder.setRecordTypeKey(1L);
        cardTypeBuilder.setSinceVersion(2);

        metaDataBuilder.setVersion(2);
        return metaDataBuilder.build();
    }

    private static FDBRecordStoreBase<Message> getStore(@Nonnull EmbeddedRelationalConnection connection, @Nonnull FDBRecordContext context) throws RelationalException, SQLException {
        connection.setAutoCommit(false);
        connection.createNewTransaction();
        SubspaceProvider subspaceProvider = connection.getRecordLayerDatabase().loadRecordStore("TEST_SCHEMA", FDBRecordStoreBase.StoreExistenceCheck.ERROR_IF_NO_INFO_AND_NOT_EMPTY)
                .unwrap(FDBRecordStoreBase.class)
                .getSubspaceProvider();
        connection.rollback();
        connection.setAutoCommit(true);
        return FDBRecordStore.newBuilder()
                .setContext(context)
                .setMetaDataProvider(createRecordMetaData())
                .setFormatVersion(FormatVersion.getMaximumSupportedVersion())
                .setSubspaceProvider(subspaceProvider)
                .createOrOpen();
    }

    private Transaction createTransaction(RelationalConnectionRule connRule) throws RelationalException, SQLException {
        EmbeddedRelationalConnection connection = connRule.getUnderlyingEmbeddedConnection();
        FDBRecordContext context = TransactionBoundDatabaseTest.createNewContext(connection);
        FDBRecordStoreBase<Message> recordStore = getStore(connection, context);
        final var schemaTemplate = TransactionBoundDatabaseTest.getSchemaTemplate(connection);
        return new RecordStoreAndRecordContextTransaction(recordStore, context, schemaTemplate);
    }
}
