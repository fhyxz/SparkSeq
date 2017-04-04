package org.ncic.bioinfo.sparkseq.algorithms.utils;

import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import org.ncic.bioinfo.sparkseq.exceptions.ReviewedGATKException;

/**
 * Author: wbc
 */
final class MRUCachingSAMSequenceDictionary {
    /**
     * Our sequence dictionary
     */
    private final SAMSequenceDictionary dict;

    SAMSequenceRecord lastSSR = null;
    String lastContig = "";
    int lastIndex = -1;

    /**
     * Create a new MRUCachingSAMSequenceDictionary that provides information about sequences in dict
     *
     * @param dict a non-null, non-empty sequencing dictionary
     */
    public MRUCachingSAMSequenceDictionary(final SAMSequenceDictionary dict) {
        if (dict == null) throw new IllegalArgumentException("Dictionary cannot be null");
        if (dict.size() == 0)
            throw new IllegalArgumentException("Dictionary cannot have size zero");

        this.dict = dict;
    }

    /**
     * Get our sequence dictionary
     *
     * @return a non-null SAMSequenceDictionary
     */
    public SAMSequenceDictionary getDictionary() {
        return dict;
    }

    /**
     * Is contig present in the dictionary?  Efficiently caching.
     *
     * @param contig a non-null contig we want to test
     * @return true if contig is in dictionary, false otherwise
     */
    public final boolean hasContig(final String contig) {
        return contig.equals(lastContig) || dict.getSequence(contig) != null;
    }

    /**
     * Is contig index present in the dictionary?  Efficiently caching.
     *
     * @param contigIndex an integer offset that might map to a contig in this dictionary
     * @return true if contigIndex is in dictionary, false otherwise
     */
    public final boolean hasContigIndex(final int contigIndex) {
        return lastIndex == contigIndex || dict.getSequence(contigIndex) != null;
    }

    /**
     * Same as SAMSequenceDictionary.getSequence but uses a MRU cache for efficiency
     *
     * @param contig the contig name we want to get the sequence record of
     * @return the sequence record for contig
     * @throws ReviewedGATKException if contig isn't present in the dictionary
     */
    public final SAMSequenceRecord getSequence(final String contig) {
        if (isCached(contig))
            return lastSSR;
        else
            return updateCache(contig, -1);
    }

    /**
     * Same as SAMSequenceDictionary.getSequence but uses a MRU cache for efficiency
     *
     * @param index the contig index we want to get the sequence record of
     * @return the sequence record for contig
     * @throws ReviewedGATKException if contig isn't present in the dictionary
     */
    public final SAMSequenceRecord getSequence(final int index) {
        if (isCached(index))
            return lastSSR;
        else
            return updateCache(null, index);
    }

    /**
     * Same as SAMSequenceDictionary.getSequenceIndex but uses a MRU cache for efficiency
     *
     * @param contig the contig we want to get the sequence record of
     * @return the sequence record index for contig
     * @throws ReviewedGATKException if index isn't present in the dictionary
     */
    public final int getSequenceIndex(final String contig) {
        if (!isCached(contig)) {
            updateCache(contig, -1);
        }

        return lastIndex;
    }

    /**
     * Is contig the MRU cached contig?
     *
     * @param contig the contig to test
     * @return true if contig is the currently cached contig, false otherwise
     */
    protected boolean isCached(final String contig) {
        return contig.equals(lastContig);
    }

    /**
     * Is the contig index index the MRU cached index?
     *
     * @param index the contig index to test
     * @return true if contig index is the currently cached contig index, false otherwise
     */
    protected boolean isCached(final int index) {
        return lastIndex == index;
    }

    /**
     * The key algorithm.  Given a new record, update the last used record, contig
     * name, and index.
     *
     * @param contig the contig we want to look up.  If null, index is used instead
     * @param index  the contig index we want to look up.  Only used if contig is null
     * @return the SAMSequenceRecord for contig / index
     * @throws ReviewedGATKException if index isn't present in the dictionary
     */
    private SAMSequenceRecord updateCache(final String contig, int index) {
        SAMSequenceRecord rec = contig == null ? dict.getSequence(index) : dict.getSequence(contig);
        if (rec == null) {
            throw new ReviewedGATKException("BUG: requested unknown contig=" + contig + " index=" + index);
        } else {
            lastSSR = rec;
            lastContig = rec.getSequenceName();
            lastIndex = rec.getSequenceIndex();
            return rec;
        }
    }
}
