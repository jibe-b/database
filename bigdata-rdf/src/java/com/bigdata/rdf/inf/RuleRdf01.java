/**

The Notice below must appear in each file of the Source Code of any
copy you distribute of the Licensed Product.  Contributors to any
Modifications may add their own copyright notices to identify their
own contributions.

License:

The contents of this file are subject to the CognitiveWeb Open Source
License Version 1.1 (the License).  You may not copy or use this file,
in either source code or executable form, except in compliance with
the License.  You may obtain a copy of the License from

  http://www.CognitiveWeb.org/legal/license/

Software distributed under the License is distributed on an AS IS
basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.  See
the License for the specific language governing rights and limitations
under the License.

Copyrights:

Portions created by or assigned to CognitiveWeb are Copyright
(c) 2003-2003 CognitiveWeb.  All Rights Reserved.  Contact
information for CognitiveWeb is available at

  http://www.CognitiveWeb.org

Portions Copyright (c) 2002-2003 Bryan Thompson.

Acknowledgements:

Special thanks to the developers of the Jabber Open Source License 1.0
(JOSL), from which this License was derived.  This License contains
terms that differ from JOSL.

Special thanks to the CognitiveWeb Open Source Contributors for their
suggestions and support of the Cognitive Web.

Modifications:

*/
package com.bigdata.rdf.inf;

import java.util.Iterator;
import java.util.Vector;

import com.bigdata.objndx.IEntryIterator;


public class RuleRdf01 extends Rule {

    public RuleRdf01(InferenceEngine store, Var u, Var v, Var x) {

        super(store, new Triple(v, store.rdfType, store.rdfProperty), //
                new Pred[] { //
                new Triple(u, v, x)
                });

    }
    
    public int apply() {

        int numAdded = 0;
        
        long startTime = System.currentTimeMillis();
        
        long[] predicates = collectPredicates();
        
        // countLiterals();
        
        long collectionTime = System.currentTimeMillis() - startTime;
        
        System.out.println( "rdf1 collected " + predicates.length + 
                            " predicates in " + collectionTime + " millis" );
        
        System.out.println( "rdf1 number of statements before: " + store.ndx_spo.getEntryCount());
        
        for ( int i = 0; i < predicates.length; i++ ) {
            
            long _s = predicates[i];
            long _p = store.rdfType.id;
            long _o = store.rdfProperty.id;
            
            byte[] spoKey = store.keyBuilder.statement2Key(_s, _p, _o);
            
            if ( !store.ndx_spo.contains(spoKey) ) {
                store.ndx_spo.insert(spoKey,null);
                store.ndx_pos.insert(store.keyBuilder.statement2Key(_p, _o, _s),null);
                store.ndx_osp.insert(store.keyBuilder.statement2Key(_p, _s, _p),null);
                numAdded++;
            }
            
        }
        
        System.out.println( "rdf1 number of statements after: " + store.ndx_spo.getEntryCount());
        
        return numAdded;

    }
    
    protected long[] collectPredicates() {
        
        Vector<SPO> stmts = new Vector<SPO>();
        
        long lastP = -1;
        
        IEntryIterator it = store.ndx_pos.entryIterator(); 
        
        while ( it.hasNext() ) {
            
            it.next();
            
            SPO stmt = new SPO(store.ndx_pos.keyOrder,store.keyBuilder,it.getKey());
            
            if ( stmt.p != lastP ) {
                
                lastP = stmt.p;
                
                stmts.add( stmt );
                
            }
            
        }
        
        int i = 0;
        
        long[] predicates = new long[stmts.size()];
        
        for ( Iterator<SPO> it2 = stmts.iterator(); it2.hasNext(); ) {
            
            SPO stmt = it2.next();
         
            predicates[i++] = stmt.p;
         
            // Value v = (Value) store.ndx_idTerm.lookup(store.keyBuilder.id2key(stmt.p));
         
            // System.err.println(((URI)v).getURI());
            
        }
        
        return predicates;
        
    }
/*
    public long[] collectPredicates2() {

        Vector<Long> predicates = new Vector<Long>();
        
        IEntryIterator it = store.ndx_termId.rangeIterator
            ( store.keyBuilder.uriStartKey(),
              store.keyBuilder.uriEndKey()
              );
        
        while ( it.hasNext() ) {
            
            it.next();
            
            long id = (Long)it.getValue();
            
            // Value v = (Value) store.ndx_idTerm.lookup(store.keyBuilder.id2key(id));
            
            int numStmts = store.ndx_pos.rangeCount
                (store.keyBuilder.statement2Key(id, 0, 0), 
                 store.keyBuilder.statement2Key(id+1, 0, 0)
                 );
            
            if ( numStmts > 0 ) {
                
                // System.err.println(((URI)v).getURI() + " : " + numStmts );
            
                predicates.add( id );
                
            }
            
        }
        
        int i = 0;
        
        long[] longs = new long[predicates.size()];
        
        for ( Iterator<Long> it2 = predicates.iterator(); it2.hasNext(); ) {
            
            longs[i++] = it2.next();
            
        }
        
        return longs;
        
    }
    
    private void countLiterals() {
        
        System.out.println( "number of literals: " + 
        store.ndx_termId.rangeCount
            ( store.keyBuilder.litStartKey(),
              store.keyBuilder.litEndKey()
              )
            );

        IEntryIterator it = store.ndx_termId.rangeIterator
            ( store.keyBuilder.litStartKey(),
              store.keyBuilder.litEndKey()
              );
        
        while ( it.hasNext() ) {
            
            it.next();
            
            long id = (Long)it.getValue();
            
            Value v = (Value) store.ndx_idTerm.lookup(store.keyBuilder.id2key(id));
            
            System.err.println(((Literal)v).getLabel());
            
        }
        
    }
*/
}