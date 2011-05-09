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

package com.akiban.server.api;

import com.akiban.ais.model.TableName;

public interface HapiPredicate {
    TableName getTableName();

    String getColumnName();

    Operator getOp();

    String getValue();

    public enum Operator {
        EQ("="),
        @Deprecated NE("!="),
        GT(">"),
        GTE(">="),
        LT("<"),
        LTE("<=")
        ;

        final private String toString;
        Operator(String toString) {
            this.toString = toString;
        }

        @Override
        public String toString() {
            return toString;
        }

        public boolean lowerBound()
        {
            return this == GT || this == GTE;
        }

        public boolean upperBound()
        {
            return this == LT || this == LTE;
        }

        public boolean inclusive()
        {
            return this == EQ || this == LTE || this == GTE;
        }
    }
}
