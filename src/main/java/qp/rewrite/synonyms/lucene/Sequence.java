/**
 * 
 */
package qp.rewrite.synonyms.lucene;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.UnicodeUtil;
import org.apache.lucene.util.fst.FST;

import qp.model.BooleanQuery;
import qp.model.DisjunctionMaxClause;
import qp.model.DisjunctionMaxQuery;
import qp.model.Term;
import qp.model.BooleanQuery.Operator;
import qp.model.SubQuery.Occur;

/**
 * @author rene
 *
 */
public class Sequence {
    
    final FST.Arc<BytesRef> arc;
    final List<Term> terms;
    final BytesRef output;
    
    public Sequence(FST.Arc<BytesRef> arc, List<Term> terms, BytesRef output) {
        this.arc = arc;
        this.terms = terms;
        this.output = output;
    }
    
    public void addOutputs(Map<DisjunctionMaxQuery, Set<DisjunctionMaxClause>> addenda, SynonymMap map, ByteArrayDataInput bytesReader) {
        
        BytesRef finalOutput = map.fst.outputs.add(output, arc.nextFinalOutput);
        
        bytesReader.reset(finalOutput.bytes, finalOutput.offset, finalOutput.length);
        
        BytesRef scratchBytes = new BytesRef();
        CharsRef scratchChars = new CharsRef();
        

        final int code = bytesReader.readVInt();
         //final boolean keepOrig = (code & 0x1) == 0;
        final int count = code >>> 1;
        
        // iterate over all possible outputs
        for (int outputIDX = 0; outputIDX < count; outputIDX++) {
                
            map.words.get(bytesReader.readVInt(), scratchBytes);
            UnicodeUtil.UTF8toUTF16(scratchBytes, scratchChars);
            
            boolean replacementIsMultiTerm = false;
            // ignore ' ' at beginning and end
            for (int i = 1; i < scratchChars.length - 1 && !replacementIsMultiTerm; i++) {
                replacementIsMultiTerm = scratchChars.charAt(i) == ' ';
            }
            
            // iterate through all input terms
            for (Term term: terms) {
                
                // FIXME fix parent type of term to always DMQ?
                DisjunctionMaxQuery currentDmq = (DisjunctionMaxQuery) term.getParentQuery();
                
                BooleanQuery add = new BooleanQuery(currentDmq, Operator.AND, Occur.SHOULD);
                
                if (replacementIsMultiTerm) {
                    BooleanQuery replaceSeq = new BooleanQuery(add, Operator.AND, Occur.MUST);
                    
                    int start = 0;
                    for (int i = 0; i < scratchChars.length; i++) {
                        if (scratchChars.charAt(i) == ' ' && (i > start)) {
                            DisjunctionMaxQuery newDmq = new DisjunctionMaxQuery(replaceSeq, Occur.MUST);
                            newDmq.addClause(new Term(newDmq, new String(scratchChars.chars, start, i - start)));
                            replaceSeq.addClause(newDmq);
                            start = i + 1;
                        }
                    }
                    
                    if (start < scratchChars.length) {
                        DisjunctionMaxQuery newDmq = new DisjunctionMaxQuery(replaceSeq, Occur.MUST);
                        newDmq.addClause(new Term(newDmq, new String(scratchChars.chars, start, scratchChars.length - start)));
                        replaceSeq.addClause(newDmq);
                    }
                    
                    add.addClause(replaceSeq);
                    
                } else {
                    
                    DisjunctionMaxQuery replaceDmq = new DisjunctionMaxQuery(add, Occur.MUST);
                    replaceDmq.addClause(new Term(replaceDmq, new String(scratchChars.chars, 0, scratchChars.length)));
                    add.addClause(replaceDmq);
                }
                
                BooleanQuery neq = new BooleanQuery(add, Operator.AND, Occur.MUST_NOT);
                
                for (Term negTerm: terms) {
                    DisjunctionMaxQuery neqDmq = new DisjunctionMaxQuery(neq, Occur.MUST);
                    neqDmq.addClause(negTerm.clone(neqDmq));
                    neq.addClause(neqDmq);
                }
                
                add.addClause(neq);
                
                Set<DisjunctionMaxClause> adds = addenda.get(currentDmq);
                if (adds == null) {
                    adds = new LinkedHashSet<>();
                    addenda.put(currentDmq, adds);
                }
                
                adds.add(add);
            
            }
            
         }
    }

}
