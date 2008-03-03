/*

Copyright (C) SYSTAP, LLC 2006-2007.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/
package com.bigdata.sparse;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import com.bigdata.btree.AbstractBTree;
import com.bigdata.btree.IIndex;
import com.bigdata.btree.IKeyBuilder;
import com.bigdata.btree.ITuple;
import com.bigdata.btree.ITupleIterator;
import com.bigdata.btree.IIndexProcedure.ISimpleIndexProcedure;
import com.bigdata.journal.AbstractJournal;
import com.bigdata.journal.ITimestampService;
import com.bigdata.repo.BigdataRepository;
import com.bigdata.service.ClientIndexView;
import com.bigdata.sparse.ValueType.AutoIncCounter;

/**
 * A client-side class that knows how to use an {@link IIndex} to provide an
 * efficient data model in which a logical row is stored as one or more entries
 * in the {@link IIndex}. Operations are provided for atomic read and write of
 * logical row. While the scan operations are always consistent (they will never
 * reveal data from a row that undergoing concurrent modification), they do NOT
 * cause concurrent atomic row writes to block. This means that rows that would
 * be visited by a scan MAY be modified before the scan reaches those rows and
 * the client will see the updates.
 * <p>
 * The {@link SparseRowStore} requires that you declare the {@link KeyType} for
 * primary key so that it may impose a consistent total ordering over the
 * generated keys in the index.
 * <p>
 * There is no intrinsic reason why column values must be strongly typed.
 * Therefore, by default column values are loosely typed. However, column values
 * MAY be constrained by a {@link Schema}.
 * <p>
 * This class builds keys using the sparse row store design pattern. Each
 * logical row is modeled as an ordered set of index entries whose keys are
 * formed as:
 * </p>
 * 
 * <pre>
 *                                             
 *                            [schemaName][primaryKey][columnName][timestamp]
 *                                             
 * </pre>
 * 
 * <p>
 * 
 * and the values are the value for a given column for that primary key.
 * 
 * </p>
 * 
 * <p>
 * 
 * Timestamps are either generated by the application, in which case they define
 * the semantics of a write-write conflict, or on write by the index. In the
 * latter case, write-write conflicts never arise. Regardless of how timestamps
 * are generated, the use of the timestamp in the <em>key</em> requires that
 * applications specify filters that are applied during row scans to limit the
 * data points actually returned as part of the row. For example, only returning
 * the most recent column values no later than a given timestamp for all columns
 * for some primary key.
 * 
 * </p>
 * 
 * <p>
 * 
 * For example, assuming records with the following columns
 * 
 * <ul>
 * <li>Id</li>
 * <li>Name</li>
 * <li>Employer</li>
 * <li>DateOfHire</li>
 * </ul>
 * 
 * would be represented as a series of index entries as follows:
 * 
 * </p>
 * 
 * <pre>
 *                                             
 *                            [employee][12][DateOfHire][t0] : [4/30/02]
 *                            [employee][12][DateOfHire][t1] : [4/30/05]
 *                            [employee][12][Employer][t0]   : [SAIC]
 *                            [employee][12][Employer][t1]   : [SYSTAP]
 *                            [employee][12][Id][t0]         : [12]
 *                            [employee][12][Name][t0]       : [Bryan Thompson]
 *                                             
 * </pre>
 * 
 * <p>
 * 
 * In order to read the logical row whose last update was <code>t0</code>,
 * the caller would specify <code>t0</code> as the timestamp of interest. The
 * values read in this example would be {&lt;DateOfHire, t0, 4/30/02&gt;,
 * &lt;Employer, t0, SAIC&gt;, &lt;Id, t0, 12&gt;, &lt;Name, t0, Bryan
 * Thompson&gt;}.
 * </p>
 * <p>
 * Likewise, in order to read the logical row whose last update was
 * &lt;code&gt;t1&lt;/code&gt; the caller would specify
 * &lt;code&gt;t1&lt;/code&gt; as the timestamp of interest. The values read in
 * this example would be {&lt;DateOfHire, t1, 4/30/05&gt;, &lt;Employer, t0,
 * SYSTAP&gt;, &lt;Id, t0, 12&gt;, &lt;Name, t0, Bryan Thompson&gt;}. Notice
 * that values written at &lt;code&gt;t0&lt;/code&gt; and not overwritten or
 * deleted by &lt;code&gt;t1&lt;/code&gt; are present in the resulting logical
 * row.
 * </p>
 * <p>
 * Note: The constant {@link #MAX_TIMESTAMP} is commonly used to read the most
 * current row from the sparse row store since its value is greater or equal to
 * any valid timestamp.
 * </p>
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 * 
 * @todo add an atomic delete for all current property values as of a called
 *       given timestamp?
 * 
 * @todo We do not have a means to decode a primary key that is Unicode (or
 *       variable length) ??? Is this true ???
 * 
 * @todo support byte[] as a primary key type.
 * 
 * @todo I am not sure that the timestamp filtering mechanism is of much use.
 *       You can't really filter out the low end for a property value since a
 *       property might have been bound once and never rebound nor deleted
 *       thereafter. Likewise you can't really filter out the upper bound for a
 *       property value since you generally are interested in the current value
 *       for a property. Also, the returned
 *       {@link ITPS timestamped property sets} make it relatively easy to find
 *       the value of interest.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class SparseRowStore {

    protected static final Logger log = Logger.getLogger(SparseRowStore.class);

    /**
     * True iff the {@link #log} level is INFO or less.
     */
    final protected boolean INFO = log.getEffectiveLevel().toInt() <= Level.INFO
            .toInt();

    /**
     * True iff the {@link #log} level is DEBUG or less.
     */
    final protected boolean DEBUG = log.getEffectiveLevel().toInt() <= Level.DEBUG
            .toInt();

    static final String UTF8 = "UTF-8";
    
    private final IIndex ndx;

    /**
     * The backing index.
     */
    public IIndex getIndex() {
        
        return ndx;
        
    }
    
    /**
     * The maximum value for a timestamp. This may be used to read the most
     * current logical row from the sparse row store since the value is the
     * largest timestamp that can be written into the index.
     */
    public static final long MAX_TIMESTAMP = Long.MAX_VALUE;
    
    /**
     * A value which indicates that the timestamp will be assigned by the server -
     * unique timestamps are NOT guarenteed with this constant.
     * 
     * @see #AUTO_TIMESTAMP_UNIQUE
     */
    public static final long AUTO_TIMESTAMP = -1L;
    
    /**
     * A value which indicates that a unique timestamp will be assigned by
     * the server.
     * 
     * @see #AUTO_TIMESTAMP
     */
    public static final long AUTO_TIMESTAMP_UNIQUE = 0L;

    /**
     * Create a client-side abstraction that treats and {@link IIndex} as a
     * {@link SparseRowStore}.
     * 
     * @param ndx
     *            The index.
     */
    public SparseRowStore(IIndex ndx) {

        if (ndx == null)
            throw new IllegalArgumentException();

        this.ndx = ndx;
        
    }

    /**
     * Read the most recent logical row from the index.
     * 
     * @param keyBuilder
     *            An object used to construct keys for the index.
     * 
     * @param schema
     *            The {@link Schema} governing the logical row.
     * 
     * @param primaryKey
     *            The primary key that identifies the logical row.
     * 
     * @return The data in that row -or- <code>null</code> if there was no row
     *         for that primary key.
     */
    public Map<String,Object> read(IKeyBuilder keyBuilder, Schema schema, Object primaryKey) {

        final long timestamp = Long.MAX_VALUE;
        
        final INameFilter filter = null;
        
        TPS tps = (TPS) read(keyBuilder, schema, primaryKey, timestamp, filter );

        if (tps == null) {

            return null;

        }

        return tps.asMap(timestamp,filter);

    }
    
    /**
     * Read a logical row from the index.
     * 
     * @param keyBuilder
     *            An object used to construct keys for the index.
     * 
     * @param schema
     *            The {@link Schema} governing the logical row.
     *            
     * @param primaryKey
     *            The primary key that identifies the logical row.
     * 
     * @param timestamp
     *            Property values whose timestamps are not greater than this
     *            value will be retrieved. Use {@link Long#MAX_VALUE} to read
     *            the most current property values.
     * 
     * @param filter
     *            An optional filter that may be used to select values for
     *            property names accepted by the filter.
     * 
     * @return The data in that row -or- <code>null</code> if there was no row
     *         for that primary key.
     * 
     * @see ITimestampPropertySet#asMap(), return the most current bindings.
     * @see ITimestampPropertySet#asMap(long)), return the most current bindings
     *      as of the specified timestamp.
     * 
     * @todo consider semantics where {@link Long#MAX_VALUE} returns ONLY the
     *       current bindings rather than all data available for that primary
     *       key or define another value such as CURRENT_ROW to obtain only the
     *       current row. The filtering should be applied on the server side to
     *       reduce the network traffic.
     */
    public ITPS read(IKeyBuilder keyBuilder, Schema schema,
            Object primaryKey, long timestamp, INameFilter filter) {
        
        final AtomicRead proc = new AtomicRead(schema, primaryKey, timestamp,
                filter);
        
        if(ndx instanceof ClientIndexView) {

            /*
             * Remote index.
             */

            final byte[] key = schema.fromKey(keyBuilder, primaryKey).getKey();

            // Submit the atomic read operation.
            return (TPS) ((ClientIndexView)ndx).submit(key, proc);

        } else {

            /*
             * Local index.
             */
            
            return (ITPS) proc.apply(ndx);
            
        }
        
    }
    
    /**
     * Atomic write with atomic read of the post-update state of the logical
     * row.
     * <p>
     * Note: In order to cause a column value for row to be deleted you MUST
     * specify a <code>null</code> column value for that column.
     * <p>
     * Note: If the caller specified a <i>timestamp</i>, then that timestamp is
     * used by the atomic read. If the timestamp was assigned by the server,
     * then the server assigned timestamp is used by the atomic read.
     * 
     * @param keyBuilder
     *            An object used to construct keys for the index. *
     * 
     * @param schema
     *            The {@link Schema} governing the logical row.
     * 
     * @param propertySet
     *            The column names and values for that row.
     * 
     * @param timestamp
     *            The timestamp to use for the row -or-
     *            <code>#AUTO_TIMESTAMP</code> if the timestamp will be
     *            auto-generated by the data service.
     * 
     * @param filter
     *            An optional filter used to select the property values that
     *            will be returned (this has no effect on the atomic write).
     * 
     * @return The result of an atomic read on the post-update state of the
     *         logical row.
     * 
     * @see ITPS#getTimestamp()
     * 
     * @todo the atomic read back may be overkill. When you need the data is
     *       means that you only do one RPC rather than two. When you do not
     *       need the data is is just more network traffic and more complexity
     *       in this method signature. You can get pretty much the same result
     *       by doing an atomic read after the fact using the timestamp assigned
     *       by the server to the row (pretty much in the sense that it is
     *       possible for another write to explictly specify the same timestamp
     *       and hence overwrite your data).
     * 
     * @todo the timestamp could be an {@link ITimestampService} with an
     *       implementation that always returns a caller-given constant, another
     *       that uses the local system clock, another that uses the system
     *       clock but ensures that it never hands off the same timestamp twice
     *       in a row, and another than resolves the global timestamp service.
     *       <p>
     *       it is also possible that the timestamp behavior should be defined
     *       by the {@link Schema} and therefore factored out of this method
     *       signature.
     * 
     * @todo modify the atomic write to not overwrite the primary key each time?
     *       There is really no reason to do that - it just adds data to the
     *       index, but be careful to not delete the primary key when applying a
     *       history policy during a compacting merge. (One reason to write the
     *       primary key each time is that the timestamp on the primary key
     *       tells you the timestamp of each row revision. The {@link BigdataRepository}
     *       currently depends on this feature.)
     */
    public ITPS write(IKeyBuilder keyBuilder, Schema schema,
            Map<String, Object> propertySet, long timestamp, INameFilter filter) {

        final AtomicWriteRead proc = new AtomicWriteRead(schema, propertySet,
                timestamp, filter);
        
        if(ndx instanceof ClientIndexView) {

            /*
             * Remote index.
             */

            final Object primaryKey = propertySet.get(schema.getPrimaryKey());

            final byte[] key = schema.fromKey(keyBuilder, primaryKey).getKey();

            return (TPS) ((ClientIndexView) ndx).submit(key, proc);

        } else {

            /*
             * Local index.
             */
            
            return (ITPS) proc.apply(ndx);
            
        }
        
    }

    /**
     * A logical row scan.
     * 
     * @param keyBuilder
     *            An object used to build keys for the backing index.
     * @param schema
     *            The {@link Schema} governing the logical row.
     * @param fromKey
     *            The value of the primary key for lower bound (inclusive) of
     *            the key range -or- <code>null</code> iff there is no lower
     *            bound.
     * @param toKey
     *            The value of the primary key for upper bound (exclusive) of
     *            the key range -or- <code>null</code> iff there is no lower
     *            bound.
     * @param capacity
     *            When non-zero, this is the maximum #of logical rows that will
     *            be buffered.
     * @param timestamp
     *            The property values whose timestamp is larger than this value
     *            will be ignored (i.e., the maximum timestamp that will be
     *            returned for a property value). Use {@link #MAX_TIMESTAMP} to
     *            obtain all values for a property, including the most recent.
     * @param filter
     *            An optional filter used to select the property(s) of interest.
     * 
     * @return An iterator visiting each logical row in the specified key range.
     * 
     * FIXME implement logical row scan. This may require a modification to how
     * we do key range scans since each logical row read needs to be atomic.
     * While rows will not be split across index partitions, it is possible that
     * the iterator would otherwise stop when it had N index entries rather than
     * N logical rows, thereby requiring a restart of the iterator from the
     * successor of the last fully read logical row. One way to handle that is
     * to make the limit a function that can be interpreted on the data service
     * in terms of index entries or some other abstraction -- in this case the
     * #of logical rows. (A logical row ends when the primary key changes.)
     */
    public Iterator<ITPS> rangeQuery(IKeyBuilder keyBuilder, Schema schema,
            Object fromKey, Object toKey, int capacity, long timestamp,
            INameFilter filter) {
        
        throw new UnsupportedOperationException();
        
    }
    
    /**
     * Atomic read of the logical row associated with some {@link Schema} and
     * primary key.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    protected static class AtomicRead implements ISimpleIndexProcedure, Externalizable {

        /**
         * 
         */
        private static final long serialVersionUID = 7240920229720302721L;

        protected static final Logger log = Logger.getLogger(SparseRowStore.class);

        /**
         * True iff the {@link #log} level is INFO or less.
         */
        final protected boolean INFO = log.getEffectiveLevel().toInt() <= Level.INFO
                .toInt();

        /**
         * True iff the {@link #log} level is DEBUG or less.
         */
        final protected boolean DEBUG = log.getEffectiveLevel().toInt() <= Level.DEBUG
                .toInt();

        protected Schema schema;
        protected Object primaryKey;
        protected long timestamp;
        protected INameFilter filter;
        
        /**
         * Constructor for an atomic write/read operation.
         * 
         * @param schema
         *            The schema governing the property set.
         * @param primaryKey
         *            The value of the primary key (identifies the logical row
         *            to be read).
         * @param timestamp
         *            A timestamp to obtain the value for the named property
         *            whose timestamp does not exceed <i>timestamp</i> -or-
         *            {@link SparseRowStore#MAX_TIMESTAMP} to obtain the most
         *            recent value for the property.
         * @param filter
         *            An optional filter used to restrict the property values
         *            that will be returned.
         */
        public AtomicRead(Schema schema, Object primaryKey, long timestamp,
                INameFilter filter) {
            
            if (schema == null)
                throw new IllegalArgumentException("No schema");

            if (primaryKey == null)
                throw new IllegalArgumentException("No primary key");

            this.schema = schema;
            
            this.primaryKey = primaryKey;
            
            this.timestamp = timestamp;

            this.filter = filter;
            
        }

        /**
         * Atomic read.
         * 
         * @return A {@link TPS} instance containing the selected data from the
         *         logical row identified by the {@link #primaryKey} -or-
         *         <code>null</code> iff the primary key was NOT FOUND in the
         *         index. I.e., iff there are NO entries for that primary key
         *         regardless of whether or not they were selected.
         */
        public Object apply(IIndex ndx) {

            return atomicRead(ndx, schema, primaryKey, timestamp, filter);
            
        }

        /**
         * Return the thread-local key builder configured for the data service
         * on which this procedure is being run.
         * 
         * @param ndx The index.
         * 
         * @return The {@link IKeyBuilder}.
         */
        protected IKeyBuilder getKeyBuilder(IIndex ndx) {

            return ((AbstractJournal) ((AbstractBTree) ndx).getStore()).getKeyBuilder();

        }
                
        /**
         * Atomic read on the index.
         * 
         * @param ndx
         *            The index on which the data are stored.
         * @param schema
         *            The schema governing the row.
         * @param primaryKey
         *            The primary key identifies the logical row of interest.
         * @param timestamp
         *            A timestamp to obtain the value for the named property
         *            whose timestamp does not exceed <i>timestamp</i> -or-
         *            {@link SparseRowStore#MAX_TIMESTAMP} to obtain the most
         *            recent value for the property.
         * @param filter
         *            An optional filter used to select the values for property
         *            names accepted by that filter.
         * 
         * @return The logical row for that primary key.
         */
        protected TPS atomicRead(IIndex ndx, Schema schema, Object primaryKey,
                long timestamp, INameFilter filter) {

            final IKeyBuilder keyBuilder = getKeyBuilder(ndx);
            
            final byte[] fromKey = schema.fromKey(keyBuilder,primaryKey).getKey(); 

            final byte[] toKey = schema.toKey(keyBuilder,primaryKey).getKey();
            
            if (DEBUG) {
                log.info("read: fromKey=" + Arrays.toString(fromKey));
                log.info("read:   toKey=" + Arrays.toString(toKey));
            }

            // Result set object.
            
            final TPS tps = new TPS(schema, timestamp);

            /*
             * Scan all entries within the fromKey/toKey range populating [tps]
             * as we go.
             */

            final ITupleIterator itr = ndx.rangeIterator(fromKey, toKey);

            // #of entries scanned for that primary key.
            int nscanned = 0;
            
            while(itr.hasNext()) {
                
                final ITuple tuple = itr.next();
                
                final byte[] key = tuple.getKey();

                final byte[] val = tuple.getValue();
                
                nscanned++;
                
                /*
                 * Decode the key so that we can get the column name. We have the
                 * advantage of knowing the last byte in the primary key. Since the
                 * fromKey was formed as [schema][primaryKey], the length of the
                 * fromKey is the index of the 1st byte in the column name.
                 */

                final KeyDecoder keyDecoder = new KeyDecoder(schema,key,fromKey.length);

                // The column name.
                final String col = keyDecoder.col;
                
                if (filter != null && !filter.accept(col)) {

                    // Skip property names that have been filtered out.
                    
                    log.debug("Skipping property: name="+col);

                    continue;
                    
                }
                
                /*
                 * Skip column values having a timestamp strictly greater than
                 * the given value.
                 */
                final long columnValueTimestamp = keyDecoder.getTimestamp();
                {

                    if (columnValueTimestamp > timestamp) {

                        if (DEBUG) {

                            log.debug("Ignoring newer revision: col=" + col
                                    + ", timestamp=" + columnValueTimestamp);
                            
                        }
                        
                        continue;

                    }

                }
                
                /*
                 * Decode the value. A [null] indicates a deleted property
                 * value.
                 */
                
                final Object v = ValueType.decode(val);
                
                /*
                 * Add to the representation of the row.
                 */

                tps.set(col, columnValueTimestamp, v);
                
                log.info("Read: name=" + col + ", timestamp="
                        + columnValueTimestamp + ", value=" + v);

            }

            if (nscanned == 0) {
                
                /*
                 * Return null iff there are no column values for that primary
                 * key.
                 */
                
                log.info("No data for primaryKey: " + primaryKey);
            
                // Note: [null] return since no data for the primary key.
                
                return null;
                
            }
            
            // Note: MAY be empty.
            
            return tps;
            
        }
        
        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            
            final short version = in.readShort();
            
            if(version!=VERSION0) {
                
                throw new IOException("Unknown version="+version);
                
            }

            schema = (Schema) in.readObject();
            
            primaryKey = in.readObject();
            
            timestamp = in.readLong();
            
            filter = (INameFilter) in.readObject();
            
            
        }

        public void writeExternal(ObjectOutput out) throws IOException {

            out.writeShort(VERSION0);
            
            out.writeObject(schema);
            
            out.writeObject(primaryKey);
            
            out.writeLong(timestamp);
            
            out.writeObject(filter);
            
        }

        private final static transient short VERSION0 = 0x0;
        
    }
    
    /**
     * Atomic write on a logical row. All property values written will have the
     * same timestamp. An atomic read is performed as part of the procedure so
     * that the caller may obtain a consistent view of the post-update state of
     * the logical row. The server-assigned timestamp written may be obtained
     * from the returned {@link ITPS} object.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    protected static class AtomicWriteRead extends AtomicRead {

        /**
         * 
         */
        private static final long serialVersionUID = 7481235291210326044L;

        private Map<String,Object> propertySet;
        
        /**
         * Constructor for an atomic write/read operation.
         * 
         * @param schema
         *            The schema governing the property set.
         * @param propertySet
         *            The property set. An entry bound to a <code>null</code>
         *            value will cause the corresponding binding to be "deleted"
         *            in the index.
         * @param timestamp
         *            The timestamp to be assigned to the property values by an
         *            atomic write -or- either
         *            {@link SparseRowStore#AUTO_TIMESTAMP} or
         *            {@link SparseRowStore#AUTO_TIMESTAMP_UNIQUE} if the
         *            timestamp will be assigned by the server.
         * @param filter
         *            An optional filter used to restrict the property values
         *            that will be returned.
         */
        public AtomicWriteRead(Schema schema, Map<String, Object> propertySet,
                long timestamp, INameFilter filter) {
            
            super(schema, propertySet.get(schema.getPrimaryKey()), timestamp,
                    filter);

            if (propertySet.get(schema.getPrimaryKey()) == null) {

                throw new IllegalArgumentException(
                        "No value for primary key: name="
                                + schema.getPrimaryKey());

            }

            /*
             * Validate the column name productions.
             */

            final Iterator<String> itr = propertySet.keySet().iterator();

            while (itr.hasNext()) {

                final String col = itr.next();

                // validate the column name production.
                NameChecker.assertColumnName(col);

            }

            this.propertySet = propertySet;
            
        }
        
        /**
         * If a property set was specified then do an atomic write of the
         * property set. Regardless, an atomic read of the property set is then
         * performed and the results of that atomic read are returned to the
         * caller.
         * 
         * @return The set of tuples for the primary key as a {@link TPS}
         *         instance.
         */
        public Object apply(IIndex ndx) {

            /*
             * Choose the timestamp.
             * 
             * When auto-timestamping is used the timestamp is assigned by the
             * data service.
             * 
             * Note: Timestamps can be locally generated on the server since
             * they must be consistent solely within a row, and all revisions of
             * column values for the same row will always be in the same index
             * partition and hence on the same server. The only way in which
             * time could go backward is if there is a failover to another
             * server for the partition and the other server has a different
             * clock time. If the server clocks are kept synchronized then this
             * should not be a problem.
             * 
             * Note: Revisions written with the same timestamp as a pre-existing
             * column value will overwrite the existing column value rather that
             * causing new revisions with their own distinct timestamp to be
             * written. There is therefore a choice for "auto" vs "auto-unique"
             * for timestamps.
             */
            long timestamp = this.timestamp;
            
            if (timestamp == AUTO_TIMESTAMP) {

                timestamp = System.currentTimeMillis();
                
            } else if (timestamp == AUTO_TIMESTAMP_UNIQUE) {

                timestamp = ((AbstractJournal)((AbstractBTree)ndx).getStore()).nextTimestamp();
                
            }
            
            atomicWrite(ndx, schema, primaryKey, propertySet, timestamp);

            /*
             * Note: Read uses whatever timestamp was selected above!
             */

            return atomicRead(ndx, schema, propertySet.get(schema
                    .getPrimaryKey()), timestamp, filter);
            
        }

        protected void atomicWrite(IIndex ndx, Schema schema,
                Object primaryKey, Map<String, Object> propertySet,
                long timestamp) {

            log.info("Schema=" + schema + ", primaryKey="
                    + schema.getPrimaryKey() + ", value=" + primaryKey
                    + ", ntuples=" + propertySet.size());
            
            final IKeyBuilder keyBuilder = getKeyBuilder(ndx);

            final Iterator<Map.Entry<String, Object>> itr = propertySet
                    .entrySet().iterator();
            
            while(itr.hasNext()) {
                
                final Map.Entry<String, Object> entry = itr.next();
                
                final String col = entry.getKey();

                Object value = entry.getValue();
                
                if (value instanceof AutoIncCounter) {

                    value = Integer.valueOf(inc(ndx, schema, primaryKey,
                            timestamp, col));
                    
                }
                
                // encode the schema name and the primary key.
                schema.fromKey(keyBuilder, primaryKey);
                
                /*
                 * The column name. Note that the column name is NOT stored with
                 * Unicode compression so that we can decode it without loss.
                 */
                try {
                    
                    keyBuilder.append(col.getBytes(UTF8)).appendNul();
                    
                } catch(UnsupportedEncodingException ex) {
                    
                    throw new RuntimeException(ex);
                    
                }
                
                keyBuilder.append(timestamp);

                byte[] key = keyBuilder.getKey();
                
                // encode the value.
                byte[] val = ValueType.encode( value );

                ndx.insert(key,val);
                
            }

        }
        
        /**
         * This is a bit heavy weight, but what it does is read the current
         * state of the logical row so that we can find the previous value(s)
         * for the counter column.
         */
        protected int inc(IIndex ndx, Schema schema, Object primaryKey,
                long timestamp, final String col) {
            
            final TPS tps = atomicRead(ndx, schema, primaryKey, timestamp,
                    new INameFilter() {

                public boolean accept(String name) {
                    
                    return name.equals(col);

                }
                
            });
            
            /*
             * Locate the previous non-null value for the counter column and
             * then add one to that value. If there is no previous non-null
             * value then we start the counter at zero(0).
             */
            
            int counter = 0;
            
            {

                final Iterator<ITPV> vals = tps.iterator();

                while (vals.hasNext()) {
                    
                    final ITPV val = vals.next();
                    
                    if (val.getValue() != null) {
                        
                        try {

                            counter = (Integer) val.getValue();
                            
                            log.info("Previous value: name=" + col
                                    + ", counter=" + counter + ", timestamp="
                                    + val.getTimestamp());
                            
                            counter++;
                            
                        } catch(ClassCastException ex) {
                            
                            log.warn("Non-Integer value: schema="+schema+", name="+col);

                            continue;
                            
                        }
                        
                    }
                    
                }

            }
        
            // outcome of the auto-inc counter.
            
            log.info("Auto-increment: name="+col+", counter="+counter);
            
            return counter;

        }
        
        public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            
            super.readExternal(in);
            
            final short version = in.readShort();
            
            if (version != VERSION0)
                throw new IOException("Unknown version=" + version);

            /*
             * De-serialize into a property set using a tree map so that the
             * index write operations will be fully ordered.
             */
            
            propertySet = new TreeMap<String, Object>();
                
            // #of property values.
            final int n = in.readInt();

            log.info("Reading "+n+" property values");

            for(int i=0; i<n; i++) {

                final String name = in.readUTF();
                
                final Object value = in.readObject();

                propertySet.put(name,value);
                
                log.info("name=" + name + ", value=" + value);
                
            }
            
        }
        
        public void writeExternal(ObjectOutput out) throws IOException {

            super.writeExternal(out);
            
            // serialization version.
            out.writeShort(VERSION0);
                        
            // #of property values
            out.writeInt(propertySet.size());
            
            /*
             * write property values
             */
            
            Iterator<Map.Entry<String,Object>> itr = propertySet.entrySet().iterator();
            
            while(itr.hasNext()) {
                
                Map.Entry<String,Object> entry = itr.next();
                
                out.writeUTF(entry.getKey());
                
                out.writeObject(entry.getValue());
                                
            }
            
        }

        /**
         * 
         */
        private static final short VERSION0 = 0x0;

    }
        
}
