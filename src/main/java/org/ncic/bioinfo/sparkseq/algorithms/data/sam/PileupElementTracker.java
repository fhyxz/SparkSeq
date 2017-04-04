package org.ncic.bioinfo.sparkseq.algorithms.data.sam;

import org.apache.commons.collections.iterators.IteratorChain;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Author: wbc
 */
abstract class PileupElementTracker<PE extends PileupElement> implements Iterable<PE> {
    public abstract int size();

    /**
     * Iterate through the PEs here, but in any order, which may improve performance
     * if you don't care about the underlying order the reads are coming to you in.
     * @return an iteratable over all pileup elements in this tracker
     */
    public abstract Iterable<PE> unorderedIterable();

    /**
     * Same as @see #unorderedIterable but the actual iterator itself
     * @return
     */
    public Iterator<PE> unorderedIterator() { return unorderedIterable().iterator(); }

    public abstract PileupElementTracker<PE> copy();
}

class UnifiedPileupElementTracker<PE extends PileupElement> extends PileupElementTracker<PE> {
    private final List<PE> pileup;

    @Override
    public UnifiedPileupElementTracker<PE> copy() {
        UnifiedPileupElementTracker<PE> result = new UnifiedPileupElementTracker<PE>();
        for(PE element : pileup)
            result.add(element);
        return result;
    }

    public UnifiedPileupElementTracker() { pileup = new LinkedList<PE>(); }
    public UnifiedPileupElementTracker(List<PE> pileup) { this.pileup = pileup; }

    public void add(PE element) {
        pileup.add(element);
    }

    public PE get(int index) {
        return pileup.get(index);
    }

    public int size() {
        return pileup.size();
    }

    public Iterator<PE> iterator() { return pileup.iterator(); }
    public Iterable<PE> unorderedIterable() { return this; }
}

class PerSamplePileupElementTracker<PE extends PileupElement> extends PileupElementTracker<PE> {
    private final Map<String,PileupElementTracker<PE>> pileup;
    private int size = 0;

    public PerSamplePileupElementTracker() {
        pileup = new HashMap<String,PileupElementTracker<PE>>();
    }

    public PerSamplePileupElementTracker<PE> copy() {
        PerSamplePileupElementTracker<PE> result = new PerSamplePileupElementTracker<PE>();
        for (Map.Entry<String, PileupElementTracker<PE>> entry : pileup.entrySet())
            result.addElements(entry.getKey(), entry.getValue());

        return result;
    }

    /**
     * Gets a list of all the samples stored in this pileup.
     * @return List of samples in this pileup.
     */
    public Collection<String> getSamples() {
        return pileup.keySet();
    }

    public PileupElementTracker<PE> getElements(final String sample) {
        return pileup.get(sample);
    }

    public PileupElementTracker<PE> getElements(final Collection<String> selectSampleNames) {
        PerSamplePileupElementTracker<PE> result = new PerSamplePileupElementTracker<PE>();
        for (final String sample :  selectSampleNames) {
            result.addElements(sample, pileup.get(sample));
        }
        return result;
    }

    public void addElements(final String sample, PileupElementTracker<PE> elements) {
        pileup.put(sample,elements);
        size += elements.size();
    }

    public Iterator<PE> iterator() { return new MergingPileupElementIterator<PE>(this); }

    public int size() {
        return size;
    }


    public Iterable<PE> unorderedIterable() {
        return new Iterable<PE>() {
            @Override
            public Iterator<PE> iterator() {
                return new Iterator<PE>() {
                    final private IteratorChain chain = new IteratorChain();

                    { // initialize the chain with the unordered iterators of the per sample pileups
                        for ( PileupElementTracker<PE> pet : pileup.values() ) {
                            chain.addIterator(pet.unorderedIterator());
                        }
                    }
                    @Override
                    public boolean hasNext() { return chain.hasNext(); }
                    @Override
                    public PE next() { return (PE)chain.next(); }
                    @Override
                    public void remove() { throw new UnsupportedOperationException("Cannot remove"); }
                };
            }
        };
    }
}