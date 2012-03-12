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

package com.akiban.sql.optimizer.plan;

import com.akiban.ais.model.Column;
import com.akiban.ais.model.Index;
import com.akiban.ais.model.IndexColumn;
import com.akiban.server.rowdata.RowDef;
import com.akiban.util.Equality;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>A struct + builder for the combination of an Index and a set of conditions against which that index is pegged.
 * The conditions are generic for ease of testing, and this class defines an abstract method for answering whether
 * a given condition matches a given column; the rest of the logic doesn't depend on what the condition is.</p>
 *
 * <p>The expectation is that there are two subclasses of this: one for unit testing, and one for production.</p>
 * @param <C> the condition type.
 */
public abstract class MultiIndexCandidate<C> {
    private Index index;
    private List<C> pegged;
    private Set<C> unpegged;

    public MultiIndexCandidate(Index index, Collection<C> conditions) {
        this.index = index;
        pegged = new ArrayList<C>(index.getKeyColumns().size());
        unpegged = new HashSet<C>(conditions);
    }
    
    public void pegAll(List<? extends C> conditions) {
        for (C condition : conditions) {
            peg(condition, false);
        }
    }

    public boolean anyPegged() {
        return ! pegged.isEmpty();
    }
    
    public Set<C> getUnpeggedCopy() {
        return new HashSet<C>(unpegged);
    }
    
    public Collection<C> findPeggable() {
        List<C> results = null;
        IndexColumn nextToPeg = nextPegColumn();
        if (nextToPeg != null) {
            for (C condition : unpegged) {
                if (canBePegged(condition, nextToPeg)) {
                    if (results == null)
                        results = new ArrayList<C>(unpegged.size());
                    results.add(condition);
                }
            }
        }
        return results == null ? Collections.<C>emptyList() : results;
    }
    
    public List<Column> getUnpeggedColumns() {
        List<IndexColumn> keyColumns = index.getKeyColumns();
        List<IndexColumn> valueColumns = index.getValueColumns();

        int startAt = pegged.size();
        int endAt = keyColumns.size() + valueColumns.size();
        if (endAt - startAt == 0)
            return Collections.emptyList();

        List<Column> results = new ArrayList<Column>(endAt - startAt);
        int offset = 0;
        List<IndexColumn> indexColumnList = keyColumns;
        for (int i = startAt; i < endAt; ++i) {
            if (indexColumnList == keyColumns && i >= keyColumns.size()) {
                offset = keyColumns.size();
                indexColumnList = valueColumns;
            }
            Column column = indexColumnList.get(i-offset).getColumn();
            results.add(column);
        }
        return results;
    }

    public List<C> getPegged() {
        return pegged;
    }
    
    public void peg(C condition) {
        peg(condition, true);
    }
    
    public boolean canPeg(C condition) {
        IndexColumn nextToPeg = nextPegColumn();
        return nextToPeg != null && canBePegged(condition, nextToPeg);
    }
    
    private void peg(C condition, boolean checkIfInUnpegged) {
        assert canPeg(condition) : "can't peg " + condition;
        pegged.add(condition);
        boolean removedFromUnpegged = unpegged.remove(condition);
        assert (!checkIfInUnpegged) || removedFromUnpegged : condition;
    }

    public Index getIndex() {
        return index;
    }

    private IndexColumn nextPegColumn() {
        int alreadyPegged = pegged.size();
        if (alreadyPegged >= index.getKeyColumns().size())
            return null;
        return index.getKeyColumns().get(alreadyPegged);
    }
    
    protected abstract boolean columnsMatch(C condition, Column column);

    private boolean canBePegged(C condition, IndexColumn nextToPeg) {
        Column nextToPegColumn = nextToPeg.getColumn();
        return columnsMatch(condition, nextToPegColumn);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(index).append(" pegged to ");
        if (pegged == null || pegged.isEmpty())
            sb.append("nothing");
        else
            sb.append(pegged);
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        MultiIndexCandidate<?> other = (MultiIndexCandidate<?>) o;

        return Equality.areEqual(index, other.index) && Equality.areEqual(pegged, other.pegged);

    }

    @Override
    public int hashCode() {
        int result = index.hashCode();
        result = 31 * result + (pegged != null ? pegged.hashCode() : 0);
        return result;
    }

    public C getLastPegged() {
        if (pegged == null || pegged.isEmpty())
            throw new IllegalStateException("nothing pegged");
        return pegged.get(pegged.size()-1);
    }
}
