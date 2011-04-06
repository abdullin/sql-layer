/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.server.itests.qp;

import com.akiban.ais.model.GroupTable;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.IndexColumn;
import com.akiban.ais.model.UserTable;
import com.akiban.qp.expression.*;
import com.akiban.qp.persistitadapter.PersistitAdapter;
import com.akiban.qp.persistitadapter.PersistitGroupRow;
import com.akiban.qp.physicaloperator.Cursor;
import com.akiban.qp.physicaloperator.Executable;
import com.akiban.qp.physicaloperator.PhysicalOperator;
import com.akiban.qp.expression.IndexBound;
import com.akiban.qp.row.Row;
import com.akiban.qp.rowtype.IndexRowType;
import com.akiban.qp.rowtype.RowType;
import com.akiban.qp.rowtype.Schema;
import com.akiban.server.InvalidOperationException;
import com.akiban.server.RowDef;
import com.akiban.server.api.dml.scan.NewRow;
import com.akiban.server.api.dml.scan.NiceRow;
import com.akiban.server.itests.ApiTestBase;
import com.akiban.server.store.PersistitStore;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.akiban.qp.expression.API.*;
import static com.akiban.qp.physicaloperator.API.*;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

public class PhysicalOperatorIT extends ApiTestBase
{
    @Before
    public void before() throws InvalidOperationException
    {
        customer = createTable(
            "schema", "customer",
            "cid int not null key",
            "name varchar(20)," +
            "index(name)");
        order = createTable(
            "schema", "order",
            "oid int not null key",
            "cid int",
            "salesman varchar(20)",
            "constraint __akiban_oc foreign key __akiban_oc(cid) references customer(cid)",
            "index(salesman)");
        item = createTable(
            "schema", "item",
            "iid int not null key",
            "oid int",
            "constraint __akiban_io foreign key __akiban_io(oid) references order(oid)");
        schema = new Schema(rowDefCache().ais());
        customerRowType = schema.userTableRowType(userTable(customer));
        orderRowType = schema.userTableRowType(userTable(order));
        itemRowType = schema.userTableRowType(userTable(item));
        customerNameIndexRowType = schema.indexRowType(index(customer, "name"));
        orderSalesmanIndexRowType = schema.indexRowType(index(order, "salesman"));
        coi = groupTable(customer);
        db = new NewRow[]{createNewRow(customer, 1L, "xyz"),
                          createNewRow(customer, 2L, "abc"),
                          createNewRow(order, 11L, 1L, "ori"),
                          createNewRow(order, 12L, 1L, "david"),
                          createNewRow(order, 21L, 2L, "tom"),
                          createNewRow(order, 22L, 2L, "jack"),
                          createNewRow(item, 111L, 11L),
                          createNewRow(item, 112L, 11L),
                          createNewRow(item, 121L, 12L),
                          createNewRow(item, 122L, 12L),
                          createNewRow(item, 211L, 21L),
                          createNewRow(item, 212L, 21L),
                          createNewRow(item, 221L, 22L),
                          createNewRow(item, 222L, 22L)};
        writeRows(db);
        adapter = new PersistitAdapter(schema, (PersistitStore) store(), session());
    }

    @Test
    public void testGroupScan() throws Exception
    {
        PhysicalOperator groupScan = groupScan_Default(adapter, coi);
        Executable executable = new Executable(adapter, groupScan);
        Row[] expected = new Row[]{row(customerRowType, 1L, "xyz"),
                                   row(orderRowType, 11L, 1L, "ori"),
                                   row(itemRowType, 111L, 11L),
                                   row(itemRowType, 112L, 11L),
                                   row(orderRowType, 12L, 1L, "david"),
                                   row(itemRowType, 121L, 12L),
                                   row(itemRowType, 122L, 12L),
                                   row(customerRowType, 2L, "abc"),
                                   row(orderRowType, 21L, 2L, "tom"),
                                   row(itemRowType, 211L, 21L),
                                   row(itemRowType, 212L, 21L),
                                   row(orderRowType, 22L, 2L, "jack"),
                                   row(itemRowType, 221L, 22L),
                                   row(itemRowType, 222L, 22L)
        };
        compareRows(expected, executable);
    }

    @Test
    public void testSelect()
    {
        PhysicalOperator groupScan = groupScan_Default(adapter, coi);
        Expression cidEq2 = compare(field(0), EQ, literal(2L));
        PhysicalOperator select = select_HKeyOrdered(groupScan, customerRowType, cidEq2);
        Row[] expected = new Row[]{row(customerRowType, 2L, "abc"),
                                   row(orderRowType, 21L, 2L, "tom"),
                                   row(itemRowType, 211L, 21L),
                                   row(itemRowType, 212L, 21L),
                                   row(orderRowType, 22L, 2L, "jack"),
                                   row(itemRowType, 221L, 22L),
                                   row(itemRowType, 222L, 22L)};
        compareRows(expected, new Executable(adapter, select));
    }

    @Test
    public void testFlatten()
    {
        PhysicalOperator groupScan = groupScan_Default(adapter, coi);
        PhysicalOperator flatten = flatten_HKeyOrdered(groupScan, customerRowType, orderRowType);
        RowType flattenType = flatten.rowType();
        Row[] expected = new Row[]{row(flattenType, 1L, "xyz", 11L, 1L, "ori"),
                                   row(itemRowType, 111L, 11L),
                                   row(itemRowType, 112L, 11L),
                                   row(flattenType, 1L, "xyz", 12L, 1L, "david"),
                                   row(itemRowType, 121L, 12L),
                                   row(itemRowType, 122L, 12L),
                                   row(flattenType, 2L, "abc", 21L, 2L, "tom"),
                                   row(itemRowType, 211L, 21L),
                                   row(itemRowType, 212L, 21L),
                                   row(flattenType, 2L, "abc", 22L, 2L, "jack"),
                                   row(itemRowType, 221L, 22L),
                                   row(itemRowType, 222L, 22L)};
        compareRows(expected, new Executable(adapter, flatten));
    }

    @Test
    public void testTwoFlattens()
    {
        PhysicalOperator groupScan = groupScan_Default(adapter, coi);
        PhysicalOperator flattenCO = flatten_HKeyOrdered(groupScan, customerRowType, orderRowType);
        PhysicalOperator flattenCOI = flatten_HKeyOrdered(flattenCO, flattenCO.rowType(), itemRowType);
        RowType flattenCOIType = flattenCOI.rowType();
        Row[] expected = new Row[]{row(flattenCOIType, 1L, "xyz", 11L, 1L, "ori", 111L, 11L),
                                   row(flattenCOIType, 1L, "xyz", 11L, 1L, "ori", 112L, 11L),
                                   row(flattenCOIType, 1L, "xyz", 12L, 1L, "david", 121L, 12L),
                                   row(flattenCOIType, 1L, "xyz", 12L, 1L, "david", 122L, 12L),
                                   row(flattenCOIType, 2L, "abc", 21L, 2L, "tom", 211L, 21L),
                                   row(flattenCOIType, 2L, "abc", 21L, 2L, "tom", 212L, 21L),
                                   row(flattenCOIType, 2L, "abc", 22L, 2L, "jack", 221L, 22L),
                                   row(flattenCOIType, 2L, "abc", 22L, 2L, "jack", 222L, 22L)};
        compareRows(expected, new Executable(adapter, flattenCOI));
    }

    @Test
    public void testIndexScan1()
    {
        System.out.println("customer hkeys, by customer.name");
        PhysicalOperator indexScan = indexScan_Default(index(customer, "name"));
        // TODO: Can't compare rows, because we can't yet obtain fields from index rows. So compare hkeys instead
        String[] expected = new String[]{"{1,(long)2}",
                                         "{1,(long)1}"};
        compareRenderedHKeys(expected, new Executable(adapter, indexScan));
    }

    @Test
    public void testIndexScan2()
    {
        System.out.println("order hkeys, by order.salesman");
        PhysicalOperator indexScan = indexScan_Default(index(order, "salesman"));
        // TODO: Can't compare rows, because we can't yet obtain fields from index rows. So compare hkeys instead
        String[] expected = new String[]{"{1,(long)1,2,(long)12}",
                                         "{1,(long)2,2,(long)22}",
                                         "{1,(long)1,2,(long)11}",
                                         "{1,(long)2,2,(long)21}"};
        compareRenderedHKeys(expected, new Executable(adapter, indexScan));
    }

    @Test
    public void testIndexLookup()
    {
        PhysicalOperator indexScan = indexScan_Default(index(order, "salesman"));
        PhysicalOperator indexLookup = indexLookup_Default(indexScan,
                                                           coi,
                                                           Collections.<RowType>emptyList());
        Row[] expected = new Row[]{row(orderRowType, 12L, 1L, "david"),
                                   row(itemRowType, 121L, 12L),
                                   row(itemRowType, 122L, 12L),
                                   row(orderRowType, 22L, 2L, "jack"),
                                   row(itemRowType, 221L, 22L),
                                   row(itemRowType, 222L, 22L),
                                   row(orderRowType, 11L, 1L, "ori"),
                                   row(itemRowType, 111L, 11L),
                                   row(itemRowType, 112L, 11L),
                                   row(orderRowType, 21L, 2L, "tom"),
                                   row(itemRowType, 211L, 21L),
                                   row(itemRowType, 212L, 21L)};
        compareRows(expected, new Executable(adapter, indexLookup));
    }

    @Test
    public void testIndexLookupWithOneAncestor()
    {
        PhysicalOperator indexScan = indexScan_Default(index(order, "salesman"));
        PhysicalOperator indexLookup = indexLookup_Default(indexScan,
                                                           coi,
                                                           Arrays.asList(customerRowType));
        Row[] expected = new Row[]{row(customerRowType, 1L, "xyz"),
                                   row(orderRowType, 12L, 1L, "david"),
                                   row(itemRowType, 121L, 12L),
                                   row(itemRowType, 122L, 12L),
                                   row(customerRowType, 2L, "abc"),
                                   row(orderRowType, 22L, 2L, "jack"),
                                   row(itemRowType, 221L, 22L),
                                   row(itemRowType, 222L, 22L),
                                   row(customerRowType, 1L, "xyz"),
                                   row(orderRowType, 11L, 1L, "ori"),
                                   row(itemRowType, 111L, 11L),
                                   row(itemRowType, 112L, 11L),
                                   row(customerRowType, 2L, "abc"),
                                   row(orderRowType, 21L, 2L, "tom"),
                                   row(itemRowType, 211L, 21L),
                                   row(itemRowType, 212L, 21L)};
        compareRows(expected, new Executable(adapter, indexLookup));
    }

    @Test
    public void testIndexLookupWithTwoAncestors()
    {
        PhysicalOperator indexScan = indexScan_Default(index(item, "oid"));
        PhysicalOperator indexLookup = indexLookup_Default(indexScan,
                                                           coi,
                                                           Arrays.asList(customerRowType, orderRowType));
        Row[] expected = new Row[]{row(customerRowType, 1L, "xyz"),
                                   row(orderRowType, 11L, 1L, "ori"),
                                   row(itemRowType, 111L, 11L),
                                   row(customerRowType, 1L, "xyz"),
                                   row(orderRowType, 11L, 1L, "ori"),
                                   row(itemRowType, 112L, 11L),
                                   row(customerRowType, 1L, "xyz"),
                                   row(orderRowType, 12L, 1L, "david"),
                                   row(itemRowType, 121L, 12L),
                                   row(customerRowType, 1L, "xyz"),
                                   row(orderRowType, 12L, 1L, "david"),
                                   row(itemRowType, 122L, 12L),
                                   row(customerRowType, 2L, "abc"),
                                   row(orderRowType, 21L, 2L, "tom"),
                                   row(itemRowType, 211L, 21L),
                                   row(customerRowType, 2L, "abc"),
                                   row(orderRowType, 21L, 2L, "tom"),
                                   row(itemRowType, 212L, 21L),
                                   row(customerRowType, 2L, "abc"),
                                   row(orderRowType, 22L, 2L, "jack"),
                                   row(itemRowType, 221L, 22L),
                                   row(customerRowType, 2L, "abc"),
                                   row(orderRowType, 22L, 2L, "jack"),
                                   row(itemRowType, 222L, 22L)};
        compareRows(expected, new Executable(adapter, indexLookup));
    }

    @Test
    public void testRestrictedIndexScan()
    {
        PhysicalOperator indexScan = indexScan_Default(index(order, "salesman"));
        IndexBound lo = indexBound(orderSalesmanIndexRowType.keyType(), row(order, 2, "jack"));
        IndexBound hi = indexBound(orderSalesmanIndexRowType.keyType(), row(order, 2, "tom"));
        IndexKeyRange range = indexKeyRange(lo, true, hi, false);
        // TODO: Can't compare rows, because we can't yet obtain fields from index rows. So compare hkeys instead
        String[] expected = new String[]{"{1,(long)2,2,(long)22}",
                                         "{1,(long)1,2,(long)11}"};
        compareRenderedHKeys(expected, new Executable(adapter, indexScan).bind(indexScan, range));
    }

    @Test
    public void testRestrictedIndexLookup()
    {
        PhysicalOperator indexScan = indexScan_Default(index(order, "salesman"));
        IndexBound tom = indexBound(orderSalesmanIndexRowType.keyType(), row(order, 2, "tom"));
        IndexKeyRange matchTom = indexKeyRange(tom, true, tom, true);
        PhysicalOperator indexLookup = indexLookup_Default(indexScan,
                                                           coi,
                                                           Collections.<RowType>emptyList());
        Row[] expected = new Row[]{row(orderRowType, 21L, 2L, "tom"),
                                   row(itemRowType, 211L, 21L),
                                   row(itemRowType, 212L, 21L)};
        compareRows(expected, new Executable(adapter, indexLookup).bind(indexScan, matchTom));

    }

    private GroupTable groupTable(int userTableId)
    {
        RowDef userTableRowDef = rowDefCache().rowDef(userTableId);
        return userTableRowDef.table().getGroup().getGroupTable();
    }

    private UserTable userTable(int userTableId)
    {
        RowDef userTableRowDef = rowDefCache().rowDef(userTableId);
        return userTableRowDef.userTable();
    }

    private Index index(int userTableId, String... searchIndexColumnNamesArray)
    {
        UserTable userTable = userTable(userTableId);
        for (Index index : userTable.getIndexesIncludingInternal()) {
            List<String> indexColumnNames = new ArrayList<String>();
            for (IndexColumn indexColumn : index.getColumns()) {
                indexColumnNames.add(indexColumn.getColumn().getName());
            }
            List<String> searchIndexColumnNames = Arrays.asList(searchIndexColumnNamesArray);
            if (searchIndexColumnNames.equals(indexColumnNames)) {
                return index;
            }
        }
        return null;
    }

    private Row row(RowType rowType, Object... fields)
    {
        return new TestRow(rowType, fields);
    }

    private Row row(int tableId, Object... values /* alternating field position and value */)
    {
        NiceRow niceRow = new NiceRow(order);
        int i = 0;
        while (i < values.length) {
            int position = (Integer) values[i++];
            Object value = values[i++];
            niceRow.put(position, value);
        }
        return PersistitGroupRow.newPersistitGroupRow(adapter, niceRow.toRowData());
    }

    private void compareRows(Row[] expected, Executable query)
    {
        Cursor cursor = query.cursor();
        int count;
        try {
            cursor.open();
            count = 0;
            List<Row> actualRows = new ArrayList<Row>(); // So that result is viewable in debugger
            while (cursor.next()) {
                Row actualRow = cursor.currentRow();
                assertTrue(equal(expected[count], actualRow));
                count++;
                actualRows.add(actualRow);
            }
        } finally {
            cursor.close();
        }
        assertEquals(expected.length, count);
    }

    private void compareRenderedHKeys(String[] expected, Executable query)
    {
        Cursor cursor = query.cursor();
        int count;
        try {
            cursor.open();
            count = 0;
            List<Row> actualRows = new ArrayList<Row>(); // So that result is viewable in debugger
            while (cursor.next()) {
                Row actualRow = cursor.currentRow();
                assertEquals(expected[count], actualRow.hKey().toString());
                count++;
                actualRows.add(actualRow);
            }
        } finally {
            cursor.close();
        }
        assertEquals(expected.length, count);
    }

    private boolean equal(Row expected, Row actual)
    {
        boolean equal = expected.rowType().nFields() == actual.rowType().nFields();
        for (int i = 0; equal && i < actual.rowType().nFields(); i++) {
            Object expectedField = expected.field(i);
            Object actualField = actual.field(i);
            equal = expectedField.equals(actualField);
        }
        return equal;
    }

    private int customer;
    private int order;
    private int item;
    private RowType customerRowType;
    private RowType orderRowType;
    private RowType itemRowType;
    private IndexRowType customerNameIndexRowType;
    private IndexRowType orderSalesmanIndexRowType;
    private GroupTable coi;
    private Schema schema;
    private NewRow[] db;
    PersistitAdapter adapter;
}
