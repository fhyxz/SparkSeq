package org.ncic.bioinfo.sparkseq.algorithms.utils.downsampling;

import org.ncic.bioinfo.sparkseq.algorithms.utils.ReadUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Author: wbc
 */
public class DownsamplingUtils {
    private DownsamplingUtils() { }

    /**
     * Level the coverage of the reads in each sample to no more than downsampleTo reads, no reducing
     * coverage at any read start to less than minReadsPerAlignmentStart
     *
     * This algorithm can be used to handle the situation where you have lots of coverage in some interval, and
     * want to reduce the coverage of the big peak down without removing the many reads at the edge of this
     * interval that are in fact good
     *
     * This algorithm separately operates on the reads for each sample independently.
     *
     * @param reads a sorted list of reads
     * @param downsampleTo the targeted number of reads we want from reads per sample
     * @param minReadsPerAlignmentStart don't reduce the number of reads starting at a specific alignment start
     *                                  to below this.  That is, if this value is 2, we'll never reduce the number
     *                                  of reads starting at a specific start site to less than 2
     * @return a sorted list of reads
     */
    public static List<GATKSAMRecord> levelCoverageByPosition(final List<GATKSAMRecord> reads, final int downsampleTo, final int minReadsPerAlignmentStart) {
        if ( reads == null ) throw new IllegalArgumentException("reads must not be null");

        final List<GATKSAMRecord> downsampled = new ArrayList<GATKSAMRecord>(reads.size());

        final Map<String, Map<Integer, List<GATKSAMRecord>>> readsBySampleByStart = partitionReadsBySampleAndStart(reads);
        for ( final Map<Integer, List<GATKSAMRecord>> readsByPosMap : readsBySampleByStart.values() ) {
            final LevelingDownsampler<List<GATKSAMRecord>, GATKSAMRecord> downsampler = new LevelingDownsampler<List<GATKSAMRecord>, GATKSAMRecord>(downsampleTo, minReadsPerAlignmentStart);
            downsampler.submit(readsByPosMap.values());
            downsampler.signalEndOfInput();
            for ( final List<GATKSAMRecord> downsampledReads : downsampler.consumeFinalizedItems())
                downsampled.addAll(downsampledReads);
        }

        return ReadUtils.sortReadsByCoordinate(downsampled);
    }

    /**
     * Build the data structure mapping for each sample -> (position -> reads at position)
     *
     * Note that the map position -> reads isn't ordered in any meaningful way
     *
     * @param reads a list of sorted reads
     * @return a map containing the list of reads at each start location, for each sample independently
     */
    private static Map<String, Map<Integer, List<GATKSAMRecord>>> partitionReadsBySampleAndStart(final List<GATKSAMRecord> reads) {
        final Map<String, Map<Integer, List<GATKSAMRecord>>> readsBySampleByStart = new LinkedHashMap<String, Map<Integer, List<GATKSAMRecord>>>();

        for ( final GATKSAMRecord read : reads ) {
            Map<Integer, List<GATKSAMRecord>> readsByStart = readsBySampleByStart.get(read.getReadGroup().getSample());

            if ( readsByStart == null ) {
                readsByStart = new LinkedHashMap<Integer, List<GATKSAMRecord>>();
                readsBySampleByStart.put(read.getReadGroup().getSample(), readsByStart);
            }

            List<GATKSAMRecord> readsAtStart = readsByStart.get(read.getAlignmentStart());
            if ( readsAtStart == null ) {
                readsAtStart = new LinkedList<GATKSAMRecord>();
                readsByStart.put(read.getAlignmentStart(), readsAtStart);
            }

            readsAtStart.add(read);
        }

        return readsBySampleByStart;
    }
}