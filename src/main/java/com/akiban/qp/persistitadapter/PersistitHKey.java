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

package com.akiban.qp.persistitadapter;

import com.persistit.Exchange;
import com.persistit.Key;
import com.persistit.KeyState;

class PersistitHKey
{
    // PersistitHKey interface

    public void readKey(Exchange exchange)
    {
        Key exchangeKey = exchange.getKey();
        key = new KeyState(exchangeKey);
        size = exchangeKey.getEncodedSize();
    }

    public boolean prefixOf(PersistitHKey that)
    {
        // Move into KeyState?
        if (this.size < that.size) {
            byte[] thisBytes = this.key.getBytes();
            byte[] thatBytes = that.key.getBytes();
            for (int i = 0; i < this.size; i++) {
                if (thisBytes[i] != thatBytes[i]) {
                    return false;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public PersistitHKey copy()
    {
        return new PersistitHKey(this);
    }

    public PersistitHKey()
    {}

    // For use by this class

    private PersistitHKey(PersistitHKey hKey)
    {
        this.key = new KeyState(hKey.key.getBytes());
        this.size = hKey.size;
    }

    // Object state

    private KeyState key;
    private int size;
}
