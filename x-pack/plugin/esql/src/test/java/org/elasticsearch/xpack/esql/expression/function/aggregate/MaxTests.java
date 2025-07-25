/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.aggregate;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.AbstractAggregationTestCase;
import org.elasticsearch.xpack.esql.expression.function.MultiRowTestCaseSupplier;
import org.elasticsearch.xpack.esql.expression.function.TestCaseSupplier;
import org.elasticsearch.xpack.versionfield.Version;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.equalTo;

public class MaxTests extends AbstractAggregationTestCase {
    public MaxTests(@Name("TestCase") Supplier<TestCaseSupplier.TestCase> testCaseSupplier) {
        this.testCase = testCaseSupplier.get();
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() {
        var suppliers = new ArrayList<TestCaseSupplier>();

        Stream.of(
            MultiRowTestCaseSupplier.intCases(1, 1000, Integer.MIN_VALUE, Integer.MAX_VALUE, true),
            MultiRowTestCaseSupplier.longCases(1, 1000, Long.MIN_VALUE, Long.MAX_VALUE, true),
            MultiRowTestCaseSupplier.ulongCases(1, 1000, BigInteger.ZERO, UNSIGNED_LONG_MAX, true),
            MultiRowTestCaseSupplier.doubleCases(1, 1000, -Double.MAX_VALUE, Double.MAX_VALUE, true),
            MultiRowTestCaseSupplier.dateCases(1, 1000),
            MultiRowTestCaseSupplier.booleanCases(1, 1000),
            MultiRowTestCaseSupplier.ipCases(1, 1000),
            MultiRowTestCaseSupplier.versionCases(1, 1000),
            MultiRowTestCaseSupplier.stringCases(1, 1000, DataType.KEYWORD),
            MultiRowTestCaseSupplier.stringCases(1, 1000, DataType.TEXT)
        ).flatMap(List::stream).map(MaxTests::makeSupplier).collect(Collectors.toCollection(() -> suppliers));

        suppliers.addAll(
            List.of(
                // Folding
                new TestCaseSupplier(
                    List.of(DataType.INTEGER),
                    () -> new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(200), DataType.INTEGER, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.INTEGER,
                        equalTo(200)
                    )
                ),
                new TestCaseSupplier(
                    List.of(DataType.LONG),
                    () -> new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(200L), DataType.LONG, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.LONG,
                        equalTo(200L)
                    )
                ),
                new TestCaseSupplier(
                    List.of(DataType.UNSIGNED_LONG),
                    () -> new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(new BigInteger("200")), DataType.UNSIGNED_LONG, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.UNSIGNED_LONG,
                        equalTo(new BigInteger("200"))
                    )
                ),
                new TestCaseSupplier(
                    List.of(DataType.DOUBLE),
                    () -> new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(200.), DataType.DOUBLE, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.DOUBLE,
                        equalTo(200.)
                    )
                ),
                new TestCaseSupplier(
                    List.of(DataType.DATETIME),
                    () -> new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(200L), DataType.DATETIME, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.DATETIME,
                        equalTo(200L)
                    )
                ),
                new TestCaseSupplier(
                    List.of(DataType.DATE_NANOS),
                    () -> new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(200L), DataType.DATE_NANOS, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.DATE_NANOS,
                        equalTo(200L)
                    )
                ),
                new TestCaseSupplier(
                    List.of(DataType.BOOLEAN),
                    () -> new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(true), DataType.BOOLEAN, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.BOOLEAN,
                        equalTo(true)
                    )
                ),
                new TestCaseSupplier(
                    List.of(DataType.IP),
                    () -> new TestCaseSupplier.TestCase(
                        List.of(
                            TestCaseSupplier.TypedData.multiRow(
                                List.of(new BytesRef(InetAddressPoint.encode(InetAddresses.forString("127.0.0.1")))),
                                DataType.IP,
                                "field"
                            )
                        ),
                        "Max[field=Attribute[channel=0]]",
                        DataType.IP,
                        equalTo(new BytesRef(InetAddressPoint.encode(InetAddresses.forString("127.0.0.1"))))
                    )
                ),
                new TestCaseSupplier(List.of(DataType.KEYWORD), () -> {
                    var value = new BytesRef(randomAlphaOfLengthBetween(0, 50));
                    return new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(value), DataType.KEYWORD, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.KEYWORD,
                        equalTo(value)
                    );
                }),
                new TestCaseSupplier(List.of(DataType.TEXT), () -> {
                    var value = new BytesRef(randomAlphaOfLengthBetween(0, 50));
                    return new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(value), DataType.TEXT, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.KEYWORD,
                        equalTo(value)
                    );
                }),
                new TestCaseSupplier(List.of(DataType.VERSION), () -> {
                    var value = randomBoolean()
                        ? new Version(randomAlphaOfLengthBetween(1, 10)).toBytesRef()
                        : new Version(randomIntBetween(0, 100) + "." + randomIntBetween(0, 100) + "." + randomIntBetween(0, 100))
                            .toBytesRef();
                    return new TestCaseSupplier.TestCase(
                        List.of(TestCaseSupplier.TypedData.multiRow(List.of(value), DataType.VERSION, "field")),
                        "Max[field=Attribute[channel=0]]",
                        DataType.VERSION,
                        equalTo(value)
                    );
                })
            )
        );

        return parameterSuppliersFromTypedDataWithDefaultChecksNoErrors(suppliers, false);
    }

    @Override
    protected Expression build(Source source, List<Expression> args) {
        return new Max(source, args.get(0));
    }

    @SuppressWarnings("unchecked")
    private static TestCaseSupplier makeSupplier(TestCaseSupplier.TypedDataSupplier fieldSupplier) {
        return new TestCaseSupplier(fieldSupplier.name(), List.of(fieldSupplier.type()), () -> {
            var fieldTypedData = fieldSupplier.get();
            var expected = fieldTypedData.multiRowData()
                .stream()
                .map(v -> (Comparable<? super Comparable<?>>) v)
                .max(Comparator.naturalOrder())
                .orElse(null);

            return new TestCaseSupplier.TestCase(
                List.of(fieldTypedData),
                "Max[field=Attribute[channel=0]]",
                fieldSupplier.type(),
                equalTo(expected)
            );
        });
    }
}
