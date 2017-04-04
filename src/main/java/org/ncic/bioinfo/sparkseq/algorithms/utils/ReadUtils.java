package org.ncic.bioinfo.sparkseq.algorithms.utils;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMReadGroupRecord;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordComparator;
import htsjdk.samtools.SAMRecordCoordinateComparator;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import org.apache.log4j.Logger;
import org.ncic.bioinfo.sparkseq.algorithms.data.basic.Pair;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.exceptions.ReviewedGATKException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Author: wbc
 */
public class ReadUtils {
    private final static Logger logger = Logger.getLogger(ReadUtils.class);

    private static final String OFFSET_OUT_OF_BOUNDS_EXCEPTION = "Offset cannot be greater than read length %d : %d";
    private static final String OFFSET_NOT_ZERO_EXCEPTION = "We ran past the end of the read and never found the offset, something went wrong!";

    private ReadUtils() {
    }

    private static final int DEFAULT_ADAPTOR_SIZE = 100;
    public static final int CLIPPING_GOAL_NOT_REACHED = -1;

    /**
     * A marker to tell which end of the read has been clipped
     */
    public enum ClippingTail {
        LEFT_TAIL,
        RIGHT_TAIL
    }

    /**
     * A HashMap of the SAM spec read flag names
     *
     * Note: This is not being used right now, but can be useful in the future
     */
    private static final Map<Integer, String> readFlagNames = new HashMap<Integer, String>();

    static {
        readFlagNames.put(0x1, "Paired");
        readFlagNames.put(0x2, "Proper");
        readFlagNames.put(0x4, "Unmapped");
        readFlagNames.put(0x8, "MateUnmapped");
        readFlagNames.put(0x10, "Forward");
        //readFlagNames.put(0x20, "MateForward");
        readFlagNames.put(0x40, "FirstOfPair");
        readFlagNames.put(0x80, "SecondOfPair");
        readFlagNames.put(0x100, "NotPrimary");
        readFlagNames.put(0x200, "NON-PF");
        readFlagNames.put(0x400, "Duplicate");
    }

    /**
     * This enum represents all the different ways in which a read can overlap an interval.
     *
     * NO_OVERLAP_CONTIG:
     * read and interval are in different contigs.
     *
     * NO_OVERLAP_LEFT:
     * the read does not overlap the interval.
     *
     *                        |----------------| (interval)
     *   <---------------->                      (read)
     *
     * NO_OVERLAP_RIGHT:
     * the read does not overlap the interval.
     *
     *   |----------------|                      (interval)
     *                        <----------------> (read)
     *
     * OVERLAP_LEFT:
     * the read starts before the beginning of the interval but ends inside of it
     *
     *          |----------------| (interval)
     *   <---------------->        (read)
     *
     * OVERLAP_RIGHT:
     * the read starts inside the interval but ends outside of it
     *
     *   |----------------|     (interval)
     *       <----------------> (read)
     *
     * OVERLAP_LEFT_AND_RIGHT:
     * the read starts before the interval and ends after the interval
     *
     *      |-----------|     (interval)
     *  <-------------------> (read)
     *
     * OVERLAP_CONTAINED:
     * the read starts and ends inside the interval
     *
     *  |----------------|     (interval)
     *     <-------->          (read)
     */
    public enum ReadAndIntervalOverlap {NO_OVERLAP_CONTIG, NO_OVERLAP_LEFT, NO_OVERLAP_RIGHT, NO_OVERLAP_HARDCLIPPED_LEFT, NO_OVERLAP_HARDCLIPPED_RIGHT, OVERLAP_LEFT, OVERLAP_RIGHT, OVERLAP_LEFT_AND_RIGHT, OVERLAP_CONTAINED}

    /**
     * is this base inside the adaptor of the read?
     *
     * There are two cases to treat here:
     *
     * 1) Read is in the negative strand => Adaptor boundary is on the left tail
     * 2) Read is in the positive strand => Adaptor boundary is on the right tail
     *
     * Note: We return false to all reads that are UNMAPPED or have an weird big insert size (probably due to mismapping or bigger event)
     *
     * @param read the read to test
     * @param basePos base position in REFERENCE coordinates (not read coordinates)
     * @return whether or not the base is in the adaptor
     */
    public static boolean isBaseInsideAdaptor(final GATKSAMRecord read, long basePos) {
        final int adaptorBoundary = read.getAdaptorBoundary();
        if (adaptorBoundary == CANNOT_COMPUTE_ADAPTOR_BOUNDARY || read.getInferredInsertSize() > DEFAULT_ADAPTOR_SIZE)
            return false;

        return read.getReadNegativeStrandFlag() ? basePos <= adaptorBoundary : basePos >= adaptorBoundary;
    }

    /**
     * Finds the adaptor boundary around the read and returns the first base inside the adaptor that is closest to
     * the read boundary. If the read is in the positive strand, this is the first base after the end of the
     * fragment (Picard calls it 'insert'), if the read is in the negative strand, this is the first base before the
     * beginning of the fragment.
     *
     * There are two cases we need to treat here:
     *
     * 1) Our read is in the reverse strand :
     *
     *     <----------------------| *
     *   |--------------------->
     *
     *   in these cases, the adaptor boundary is at the mate start (minus one)
     *
     * 2) Our read is in the forward strand :
     *
     *   |---------------------->   *
     *     <----------------------|
     *
     *   in these cases the adaptor boundary is at the start of the read plus the inferred insert size (plus one)
     *
     * @param read the read being tested for the adaptor boundary
     * @return the reference coordinate for the adaptor boundary (effectively the first base IN the adaptor, closest to the read.
     * CANNOT_COMPUTE_ADAPTOR_BOUNDARY if the read is unmapped or the mate is mapped to another contig.
     */
    public static int getAdaptorBoundary(final SAMRecord read) {
        if ( ! hasWellDefinedFragmentSize(read) ) {
            return CANNOT_COMPUTE_ADAPTOR_BOUNDARY;
        } else if ( read.getReadNegativeStrandFlag() ) {
            return read.getMateAlignmentStart() - 1;           // case 1 (see header)
        } else {
            final int insertSize = Math.abs(read.getInferredInsertSize());    // the inferred insert size can be negative if the mate is mapped before the read (so we take the absolute value)
            return read.getAlignmentStart() + insertSize + 1;  // case 2 (see header)
        }
    }

    public static int CANNOT_COMPUTE_ADAPTOR_BOUNDARY = Integer.MIN_VALUE;

    /**
     * Can the adaptor sequence of read be reliably removed from the read based on the alignment of
     * read and its mate?
     *
     * @param read the read to check
     * @return true if it can, false otherwise
     */
    public static boolean hasWellDefinedFragmentSize(final SAMRecord read) {
        if ( read.getInferredInsertSize() == 0 )
            // no adaptors in reads with mates in another chromosome or unmapped pairs
            return false;
        if ( ! read.getReadPairedFlag() )
            // only reads that are paired can be adaptor trimmed
            return false;
        if ( read.getReadUnmappedFlag() || read.getMateUnmappedFlag() )
            // only reads when both reads are mapped can be trimmed
            return false;
//        if ( ! read.getProperPairFlag() )
//            // note this flag isn't always set properly in BAMs, can will stop us from eliminating some proper pairs
//            // reads that aren't part of a proper pair (i.e., have strange alignments) can't be trimmed
//            return false;
        if ( read.getReadNegativeStrandFlag() == read.getMateNegativeStrandFlag() )
            // sanity check on getProperPairFlag to ensure that read1 and read2 aren't on the same strand
            return false;

        if ( read.getReadNegativeStrandFlag() ) {
            // we're on the negative strand, so our read runs right to left
            return read.getAlignmentEnd() > read.getMateAlignmentStart();
        } else {
            // we're on the positive strand, so our mate should be to our right (his start + insert size should be past our start)
            return read.getAlignmentStart() <= read.getMateAlignmentStart() + read.getInferredInsertSize();
        }
    }

    /**
     * is the read a 454 read?
     *
     * @param read the read to test
     * @return checks the read group tag PL for the default 454 tag
     */
    public static boolean is454Read(GATKSAMRecord read) {
        return NGSPlatform.fromRead(read) == NGSPlatform.LS454;
    }

    /**
     * is the read an IonTorrent read?
     *
     * @param read the read to test
     * @return checks the read group tag PL for the default ion tag
     */
    public static boolean isIonRead(GATKSAMRecord read) {
        return NGSPlatform.fromRead(read) == NGSPlatform.ION_TORRENT;
    }

    /**
     * is the read a SOLiD read?
     *
     * @param read the read to test
     * @return checks the read group tag PL for the default SOLiD tag
     */
    public static boolean isSOLiDRead(GATKSAMRecord read) {
        return NGSPlatform.fromRead(read) == NGSPlatform.SOLID;
    }

    /**
     * is the read a SLX read?
     *
     * @param read the read to test
     * @return checks the read group tag PL for the default SLX tag
     */
    public static boolean isIlluminaRead(GATKSAMRecord read) {
        return NGSPlatform.fromRead(read) == NGSPlatform.ILLUMINA;
    }

    /**
     * checks if the read has a platform tag in the readgroup equal to 'name'.
     * Assumes that 'name' is upper-cased.
     *
     * @param read the read to test
     * @param name the upper-cased platform name to test
     * @return whether or not name == PL tag in the read group of read
     */
    public static boolean isPlatformRead(GATKSAMRecord read, String name) {

        SAMReadGroupRecord readGroup = read.getReadGroup();
        if (readGroup != null) {
            Object readPlatformAttr = readGroup.getAttribute("PL");
            if (readPlatformAttr != null)
                return readPlatformAttr.toString().toUpperCase().contains(name);
        }
        return false;
    }


    /**
     * Returns the collections of reads sorted in coordinate order, according to the order defined
     * in the reads themselves
     *
     * @param reads
     * @return
     */
    public final static List<GATKSAMRecord> sortReadsByCoordinate(List<GATKSAMRecord> reads) {
        final SAMRecordComparator comparer = new SAMRecordCoordinateComparator();
        Collections.sort(reads, comparer);
        return reads;
    }

    /**
     * If a read starts in INSERTION, returns the first element length.
     *
     * Warning: If the read has Hard or Soft clips before the insertion this function will return 0.
     *
     * @param read
     * @return the length of the first insertion, or 0 if there is none (see warning).
     */
    public final static int getFirstInsertionOffset(SAMRecord read) {
        CigarElement e = read.getCigar().getCigarElement(0);
        if ( e.getOperator() == CigarOperator.I )
            return e.getLength();
        else
            return 0;
    }

    /**
     * If a read ends in INSERTION, returns the last element length.
     *
     * Warning: If the read has Hard or Soft clips after the insertion this function will return 0.
     *
     * @param read
     * @return the length of the last insertion, or 0 if there is none (see warning).
     */
    public final static int getLastInsertionOffset(SAMRecord read) {
        CigarElement e = read.getCigar().getCigarElement(read.getCigarLength() - 1);
        if ( e.getOperator() == CigarOperator.I )
            return e.getLength();
        else
            return 0;
    }

    /**
     * Determines what is the position of the read in relation to the interval.
     * Note: This function uses the UNCLIPPED ENDS of the reads for the comparison.
     * @param read the read
     * @param interval the interval
     * @return the overlap type as described by ReadAndIntervalOverlap enum (see above)
     */
    public static ReadAndIntervalOverlap getReadAndIntervalOverlapType(GATKSAMRecord read, GenomeLoc interval) {

        int sStart = read.getSoftStart();
        int sStop = read.getSoftEnd();
        int uStart = read.getUnclippedStart();
        int uStop = read.getUnclippedEnd();

        if ( !read.getReferenceName().equals(interval.getContig()) )
            return ReadAndIntervalOverlap.NO_OVERLAP_CONTIG;

        else if ( uStop < interval.getStart() )
            return ReadAndIntervalOverlap.NO_OVERLAP_LEFT;

        else if ( uStart > interval.getStop() )
            return ReadAndIntervalOverlap.NO_OVERLAP_RIGHT;

        else if ( sStop < interval.getStart() )
            return ReadAndIntervalOverlap.NO_OVERLAP_HARDCLIPPED_LEFT;

        else if ( sStart > interval.getStop() )
            return ReadAndIntervalOverlap.NO_OVERLAP_HARDCLIPPED_RIGHT;

        else if ( (sStart >= interval.getStart()) &&
                (sStop <= interval.getStop()) )
            return ReadAndIntervalOverlap.OVERLAP_CONTAINED;

        else if ( (sStart < interval.getStart()) &&
                (sStop > interval.getStop()) )
            return ReadAndIntervalOverlap.OVERLAP_LEFT_AND_RIGHT;

        else if ( (sStart < interval.getStart()) )
            return ReadAndIntervalOverlap.OVERLAP_LEFT;

        else
            return ReadAndIntervalOverlap.OVERLAP_RIGHT;
    }

    /**
     * Pre-processes the results of getReadCoordinateForReferenceCoordinate(GATKSAMRecord, int) to take care of
     * two corner cases:
     *
     * 1. If clipping the right tail (end of the read) getReadCoordinateForReferenceCoordinate and fall inside
     * a deletion return the base after the deletion. If clipping the left tail (beginning of the read) it
     * doesn't matter because it already returns the previous base by default.
     *
     * 2. If clipping the left tail (beginning of the read) getReadCoordinateForReferenceCoordinate and the
     * read starts with an insertion, and you're requesting the first read based coordinate, it will skip
     * the leading insertion (because it has the same reference coordinate as the following base).
     *
     * @param read
     * @param refCoord
     * @param tail
     * @return the read coordinate corresponding to the requested reference coordinate for clipping.
     */
    public static int getReadCoordinateForReferenceCoordinate(GATKSAMRecord read, int refCoord, ClippingTail tail) {
        return getReadCoordinateForReferenceCoordinate(read.getSoftStart(), read.getCigar(), refCoord, tail, false);
    }

    public static int getReadCoordinateForReferenceCoordinateUpToEndOfRead(GATKSAMRecord read, int refCoord, ClippingTail tail) {
        final int leftmostSafeVariantPosition = Math.max(read.getSoftStart(), refCoord);
        return getReadCoordinateForReferenceCoordinate(read.getSoftStart(), read.getCigar(), leftmostSafeVariantPosition, tail, false);
    }

    public static int getReadCoordinateForReferenceCoordinate(final int alignmentStart, final Cigar cigar, final int refCoord, final ClippingTail tail, final boolean allowGoalNotReached) {
        Pair<Integer, Boolean> result = getReadCoordinateForReferenceCoordinate(alignmentStart, cigar, refCoord, allowGoalNotReached);
        int readCoord = result.getFirst();

        // Corner case one: clipping the right tail and falls on deletion, move to the next
        // read coordinate. It is not a problem for the left tail because the default answer
        // from getReadCoordinateForReferenceCoordinate is to give the previous read coordinate.
        if (result.getSecond() && tail == ClippingTail.RIGHT_TAIL)
            readCoord++;

        // clipping the left tail and first base is insertion, go to the next read coordinate
        // with the same reference coordinate. Advance to the next cigar element, or to the
        // end of the read if there is no next element.
        final CigarElement firstElementIsInsertion = readStartsWithInsertion(cigar);
        if (readCoord == 0 && tail == ClippingTail.LEFT_TAIL && firstElementIsInsertion != null)
            readCoord = Math.min(firstElementIsInsertion.getLength(), cigar.getReadLength() - 1);

        return readCoord;
    }

    /**
     * Returns the read coordinate corresponding to the requested reference coordinate.
     *
     * WARNING: if the requested reference coordinate happens to fall inside or just before a deletion (or skipped region) in the read, this function
     * will return the last read base before the deletion (or skipped region). This function returns a
     * Pair(int readCoord, boolean fallsInsideOrJustBeforeDeletionOrSkippedRegion) so you can choose which readCoordinate to use when faced with
     * a deletion (or skipped region).
     *
     * SUGGESTION: Use getReadCoordinateForReferenceCoordinate(GATKSAMRecord, int, ClippingTail) instead to get a
     * pre-processed result according to normal clipping needs. Or you can use this function and tailor the
     * behavior to your needs.
     *
     * @param read
     * @param refCoord the requested reference coordinate
     * @return the read coordinate corresponding to the requested reference coordinate. (see warning!)
     */
    //TODO since we do not have contracts any more, should we check for the requirements in the method code?
    public static Pair<Integer, Boolean> getReadCoordinateForReferenceCoordinate(GATKSAMRecord read, int refCoord) {
        return getReadCoordinateForReferenceCoordinate(read.getSoftStart(), read.getCigar(), refCoord, false);
    }

    public static Pair<Integer, Boolean> getReadCoordinateForReferenceCoordinate(final int alignmentStart, final Cigar cigar, final int refCoord, final boolean allowGoalNotReached) {
        int readBases = 0;
        int refBases = 0;
        boolean fallsInsideDeletionOrSkippedRegion = false;
        boolean endJustBeforeDeletionOrSkippedRegion = false;
        boolean fallsInsideOrJustBeforeDeletionOrSkippedRegion = false;

        final int goal = refCoord - alignmentStart;  // The goal is to move this many reference bases
        if (goal < 0) {
            if (allowGoalNotReached) {
                return new Pair<Integer, Boolean>(CLIPPING_GOAL_NOT_REACHED, false);
            } else {
                throw new ReviewedGATKException("Somehow the requested coordinate is not covered by the read. Too many deletions?");
            }
        }
        boolean goalReached = refBases == goal;

        Iterator<CigarElement> cigarElementIterator = cigar.getCigarElements().iterator();
        while (!goalReached && cigarElementIterator.hasNext()) {
            final CigarElement cigarElement = cigarElementIterator.next();
            int shift = 0;

            if (cigarElement.getOperator().consumesReferenceBases() || cigarElement.getOperator() == CigarOperator.SOFT_CLIP) {
                if (refBases + cigarElement.getLength() < goal)
                    shift = cigarElement.getLength();
                else
                    shift = goal - refBases;

                refBases += shift;
            }
            goalReached = refBases == goal;

            if (!goalReached && cigarElement.getOperator().consumesReadBases())
                readBases += cigarElement.getLength();

            if (goalReached) {
                // Is this base's reference position within this cigar element? Or did we use it all?
                final boolean endsWithinCigar = shift < cigarElement.getLength();

                // If it isn't, we need to check the next one. There should *ALWAYS* be a next one
                // since we checked if the goal coordinate is within the read length, so this is just a sanity check.
                if (!endsWithinCigar && !cigarElementIterator.hasNext()) {
                    if (allowGoalNotReached) {
                        return new Pair<Integer, Boolean>(CLIPPING_GOAL_NOT_REACHED, false);
                    } else {
                        throw new ReviewedGATKException(String.format("Reference coordinate corresponds to a non-existent base in the read. This should never happen -- check read with alignment start: %s  and cigar: %s", alignmentStart, cigar));
                    }
                }

                CigarElement nextCigarElement = null;

                // if we end inside the current cigar element, we just have to check if it is a deletion (or skipped region)
                if (endsWithinCigar)
                    fallsInsideDeletionOrSkippedRegion = (cigarElement.getOperator() == CigarOperator.DELETION || cigarElement.getOperator() == CigarOperator.SKIPPED_REGION) ;

                    // if we end outside the current cigar element, we need to check if the next element is an insertion, deletion or skipped region.
                else {
                    nextCigarElement = cigarElementIterator.next();

                    // if it's an insertion, we need to clip the whole insertion before looking at the next element
                    if (nextCigarElement.getOperator() == CigarOperator.INSERTION) {
                        readBases += nextCigarElement.getLength();
                        if (!cigarElementIterator.hasNext()) {
                            if (allowGoalNotReached) {
                                return new Pair<Integer, Boolean>(CLIPPING_GOAL_NOT_REACHED, false);
                            } else {
                                throw new ReviewedGATKException(String.format("Reference coordinate corresponds to a non-existent base in the read. This should never happen -- check read with alignment start: %s  and cigar: %s", alignmentStart, cigar));
                            }
                        }

                        nextCigarElement = cigarElementIterator.next();
                    }

                    // if it's a deletion (or skipped region), we will pass the information on to be handled downstream.
                    endJustBeforeDeletionOrSkippedRegion = (nextCigarElement.getOperator() == CigarOperator.DELETION || nextCigarElement.getOperator() == CigarOperator.SKIPPED_REGION);
                }

                fallsInsideOrJustBeforeDeletionOrSkippedRegion = endJustBeforeDeletionOrSkippedRegion || fallsInsideDeletionOrSkippedRegion;

                // If we reached our goal outside a deletion (or skipped region), add the shift
                if (!fallsInsideOrJustBeforeDeletionOrSkippedRegion && cigarElement.getOperator().consumesReadBases())
                    readBases += shift;

                    // If we reached our goal just before a deletion (or skipped region) we need
                    // to add the shift of the current cigar element but go back to it's last element to return the last
                    // base before the deletion (or skipped region) (see warning in function contracts)
                else if (endJustBeforeDeletionOrSkippedRegion && cigarElement.getOperator().consumesReadBases())
                    readBases += shift - 1;

                    // If we reached our goal inside a deletion (or skipped region), or just between a deletion and a skipped region,
                    // then we must backtrack to the last base before the deletion (or skipped region)
                else if (fallsInsideDeletionOrSkippedRegion ||
                        (endJustBeforeDeletionOrSkippedRegion && nextCigarElement.getOperator().equals(CigarOperator.N)) ||
                        (endJustBeforeDeletionOrSkippedRegion && nextCigarElement.getOperator().equals(CigarOperator.D)))
                    readBases--;
            }
        }

        if (!goalReached) {
            if (allowGoalNotReached) {
                return new Pair<Integer, Boolean>(CLIPPING_GOAL_NOT_REACHED, false);
            } else {
                throw new ReviewedGATKException("Somehow the requested coordinate is not covered by the read. Alignment " + alignmentStart + " | " + cigar);
            }
        }

        return new Pair<Integer, Boolean>(readBases, fallsInsideOrJustBeforeDeletionOrSkippedRegion);
    }

    /**
     * Is a base inside a read?
     *
     * @param read                the read to evaluate
     * @param referenceCoordinate the reference coordinate of the base to test
     * @return true if it is inside the read, false otherwise.
     */
    public static boolean isInsideRead(final GATKSAMRecord read, final int referenceCoordinate) {
        return referenceCoordinate >= read.getAlignmentStart() && referenceCoordinate <= read.getAlignmentEnd();
    }

    /**
     * Is this read all insertion?
     *
     * @param read
     * @return whether or not the only element in the cigar string is an Insertion
     */
    public static boolean readIsEntirelyInsertion(GATKSAMRecord read) {
        for (CigarElement cigarElement : read.getCigar().getCigarElements()) {
            if (cigarElement.getOperator() != CigarOperator.INSERTION)
                return false;
        }
        return true;
    }

    /**
     * @see #readStartsWithInsertion(htsjdk.samtools.Cigar, boolean) with ignoreClipOps set to true
     */
    public static CigarElement readStartsWithInsertion(final Cigar cigarForRead) {
        return readStartsWithInsertion(cigarForRead, true);
    }

    /**
     * Checks if a read starts with an insertion.
     *
     * @param cigarForRead    the CIGAR to evaluate
     * @param ignoreSoftClipOps   should we ignore S operators when evaluating whether an I operator is at the beginning?  Note that H operators are always ignored.
     * @return the element if it's a leading insertion or null otherwise
     */
    public static CigarElement readStartsWithInsertion(final Cigar cigarForRead, final boolean ignoreSoftClipOps) {
        for ( final CigarElement cigarElement : cigarForRead.getCigarElements() ) {
            if ( cigarElement.getOperator() == CigarOperator.INSERTION )
                return cigarElement;

            else if ( cigarElement.getOperator() != CigarOperator.HARD_CLIP && ( !ignoreSoftClipOps || cigarElement.getOperator() != CigarOperator.SOFT_CLIP) )
                break;
        }
        return null;
    }

    /**
     * Returns the coverage distribution of a list of reads within the desired region.
     *
     * See getCoverageDistributionOfRead for information on how the coverage is calculated.
     *
     * @param list          the list of reads covering the region
     * @param startLocation the first reference coordinate of the region (inclusive)
     * @param stopLocation  the last reference coordinate of the region (inclusive)
     * @return an array with the coverage of each position from startLocation to stopLocation
     */
    public static int [] getCoverageDistributionOfReads(List<GATKSAMRecord> list, int startLocation, int stopLocation) {
        int [] totalCoverage = new int[stopLocation - startLocation + 1];

        for (GATKSAMRecord read : list) {
            int [] readCoverage = getCoverageDistributionOfRead(read, startLocation, stopLocation);
            totalCoverage = MathUtils.addArrays(totalCoverage, readCoverage);
        }

        return totalCoverage;
    }

    /**
     * Returns the coverage distribution of a single read within the desired region.
     *
     * Note: This function counts DELETIONS as coverage (since the main purpose is to downsample
     * reads for variant regions, and deletions count as variants)
     *
     * @param read          the read to get the coverage distribution of
     * @param startLocation the first reference coordinate of the region (inclusive)
     * @param stopLocation  the last reference coordinate of the region (inclusive)
     * @return an array with the coverage of each position from startLocation to stopLocation
     */
    public static int [] getCoverageDistributionOfRead(GATKSAMRecord read, int startLocation, int stopLocation) {
        int [] coverage = new int[stopLocation - startLocation + 1];
        int refLocation = read.getSoftStart();
        for (CigarElement cigarElement : read.getCigar().getCigarElements()) {
            switch (cigarElement.getOperator()) {
                case S:
                case M:
                case EQ:
                case N:
                case X:
                case D:
                    for (int i = 0; i < cigarElement.getLength(); i++) {
                        if (refLocation >= startLocation && refLocation <= stopLocation) {
                            coverage[refLocation - startLocation]++;
                        }
                        refLocation++;
                    }
                    break;

                case P:
                case I:
                case H:
                    break;
            }

            if (refLocation > stopLocation)
                break;
        }
        return coverage;
    }

    /**
     * Makes association maps for the reads and loci coverage as described below :
     *
     *  - First: locusToReadMap -- a HashMap that describes for each locus, which reads contribute to its coverage.
     *    Note: Locus is in reference coordinates.
     *    Example: Locus => {read1, read2, ..., readN}
     *
     *  - Second: readToLocusMap -- a HashMap that describes for each read what loci it contributes to the coverage.
     *    Note: Locus is a boolean array, indexed from 0 (= startLocation) to N (= stopLocation), with value==true meaning it contributes to the coverage.
     *    Example: Read => {true, true, false, ... false}
     *
     * @param readList      the list of reads to generate the association mappings
     * @param startLocation the first reference coordinate of the region (inclusive)
     * @param stopLocation  the last reference coordinate of the region (inclusive)
     * @return the two hashmaps described above
     */
    public static Pair<HashMap<Integer, HashSet<GATKSAMRecord>> , HashMap<GATKSAMRecord, Boolean[]>> getBothReadToLociMappings (List<GATKSAMRecord> readList, int startLocation, int stopLocation) {
        int arraySize = stopLocation - startLocation + 1;

        HashMap<Integer, HashSet<GATKSAMRecord>> locusToReadMap = new HashMap<Integer, HashSet<GATKSAMRecord>>(2*(stopLocation - startLocation + 1), 0.5f);
        HashMap<GATKSAMRecord, Boolean[]> readToLocusMap = new HashMap<GATKSAMRecord, Boolean[]>(2*readList.size(), 0.5f);

        for (int i = startLocation; i <= stopLocation; i++)
            locusToReadMap.put(i, new HashSet<GATKSAMRecord>()); // Initialize the locusToRead map with empty lists

        for (GATKSAMRecord read : readList) {
            readToLocusMap.put(read, new Boolean[arraySize]);       // Initialize the readToLocus map with empty arrays

            int [] readCoverage = getCoverageDistributionOfRead(read, startLocation, stopLocation);

            for (int i = 0; i < readCoverage.length; i++) {
                int refLocation = i + startLocation;
                if (readCoverage[i] > 0) {
                    // Update the hash for this locus
                    HashSet<GATKSAMRecord> readSet = locusToReadMap.get(refLocation);
                    readSet.add(read);

                    // Add this locus to the read hash
                    readToLocusMap.get(read)[refLocation - startLocation] = true;
                }
                else
                    // Update the boolean array with a 'no coverage' from this read to this locus
                    readToLocusMap.get(read)[refLocation-startLocation] = false;
            }
        }
        return new Pair<HashMap<Integer, HashSet<GATKSAMRecord>>, HashMap<GATKSAMRecord, Boolean[]>>(locusToReadMap, readToLocusMap);
    }

    /**
     * Create random read qualities
     *
     * @param length the length of the read
     * @return an array with randomized base qualities between 0 and 50
     */
    public static byte[] createRandomReadQuals(int length) {
        Random random = RandomGenerator.getRandomGenerator();
        byte[] quals = new byte[length];
        for (int i = 0; i < length; i++)
            quals[i] = (byte) random.nextInt(50);
        return quals;
    }

    /**
     * Create random read qualities
     *
     * @param length  the length of the read
     * @param allowNs whether or not to allow N's in the read
     * @return an array with randomized bases (A-N) with equal probability
     */
    public static byte[] createRandomReadBases(int length, boolean allowNs) {
        Random random = RandomGenerator.getRandomGenerator();
        int numberOfBases = allowNs ? 5 : 4;
        byte[] bases = new byte[length];
        for (int i = 0; i < length; i++) {
            switch (random.nextInt(numberOfBases)) {
                case 0:
                    bases[i] = 'A';
                    break;
                case 1:
                    bases[i] = 'C';
                    break;
                case 2:
                    bases[i] = 'G';
                    break;
                case 3:
                    bases[i] = 'T';
                    break;
                case 4:
                    bases[i] = 'N';
                    break;
                default:
                    throw new ReviewedGATKException("Something went wrong, this is just impossible");
            }
        }
        return bases;
    }

    public static GATKSAMRecord createRandomRead(int length) {
        return createRandomRead(length, true);
    }

    public static GATKSAMRecord createRandomRead(int length, boolean allowNs) {
        byte[] quals = ReadUtils.createRandomReadQuals(length);
        byte[] bbases = ReadUtils.createRandomReadBases(length, allowNs);
        return ArtificialSAMUtils.createArtificialRead(bbases, quals, bbases.length + "M");
    }


    public static String prettyPrintSequenceRecords ( SAMSequenceDictionary sequenceDictionary ) {
        String[] sequenceRecordNames = new String[sequenceDictionary.size()];
        int sequenceRecordIndex = 0;
        for (SAMSequenceRecord sequenceRecord : sequenceDictionary.getSequences())
            sequenceRecordNames[sequenceRecordIndex++] = sequenceRecord.getSequenceName();
        return Arrays.deepToString(sequenceRecordNames);
    }

    /**
     * Calculates the reference coordinate for a read coordinate
     *
     * @param read   the read
     * @param offset the base in the read (coordinate in the read)
     * @return the reference coordinate correspondent to this base
     */
    public static long getReferenceCoordinateForReadCoordinate(GATKSAMRecord read, int offset) {
        if (offset > read.getReadLength())
            throw new ReviewedGATKException(String.format(OFFSET_OUT_OF_BOUNDS_EXCEPTION, offset, read.getReadLength()));

        long location = read.getAlignmentStart();
        Iterator<CigarElement> cigarElementIterator = read.getCigar().getCigarElements().iterator();
        while (offset > 0 && cigarElementIterator.hasNext()) {
            CigarElement cigarElement = cigarElementIterator.next();
            long move = 0;
            if (cigarElement.getOperator().consumesReferenceBases())
                move = (long) Math.min(cigarElement.getLength(), offset);
            location += move;
            offset -= move;
        }
        if (offset > 0 && !cigarElementIterator.hasNext())
            throw new ReviewedGATKException(OFFSET_NOT_ZERO_EXCEPTION);

        return location;
    }

    /**
     * Creates a map with each event in the read (cigar operator) and the read coordinate where it happened.
     *
     * Example:
     *  D -> 2, 34, 75
     *  I -> 55
     *  S -> 0, 101
     *  H -> 101
     *
     * @param read the read
     * @return a map with the properties described above. See example
     */
    public static Map<CigarOperator, ArrayList<Integer>> getCigarOperatorForAllBases (GATKSAMRecord read) {
        Map<CigarOperator, ArrayList<Integer>> events = new HashMap<CigarOperator, ArrayList<Integer>>();

        int position = 0;
        for (CigarElement cigarElement : read.getCigar().getCigarElements()) {
            CigarOperator op = cigarElement.getOperator();
            if (op.consumesReadBases()) {
                ArrayList<Integer> list = events.get(op);
                if (list == null) {
                    list = new ArrayList<Integer>();
                    events.put(op, list);
                }
                for (int i = position; i < cigarElement.getLength(); i++)
                    list.add(position++);
            }
            else {
                ArrayList<Integer> list = events.get(op);
                if (list == null) {
                    list = new ArrayList<Integer>();
                    events.put(op, list);
                }
                list.add(position);
            }
        }
        return events;
    }

    /**
     * Given a read, outputs the read bases in a string format
     *
     * @param read the read
     * @return a string representation of the read bases
     */
    public static String convertReadBasesToString(GATKSAMRecord read) {
        String bases = "";
        for (byte b : read.getReadBases()) {
            bases += (char) b;
        }
        return bases.toUpperCase();
    }

    /**
     * Given a read, outputs the base qualities in a string format
     *
     * @param quals the read qualities
     * @return a string representation of the base qualities
     */
    public static String convertReadQualToString(byte[] quals) {
        String result = "";
        for (byte b : quals) {
            result += (char) (33 + b);
        }
        return result;
    }

    /**
     * Given a read, outputs the base qualities in a string format
     *
     * @param read the read
     * @return a string representation of the base qualities
     */
    public static String convertReadQualToString(GATKSAMRecord read) {
        return convertReadQualToString(read.getBaseQualities());
    }

    /**
     * Returns the reverse complement of the read bases
     *
     * @param bases the read bases
     * @return the reverse complement of the read bases
     */
    public static String getBasesReverseComplement(byte[] bases) {
        String reverse = "";
        for (int i = bases.length-1; i >=0; i--) {
            reverse += (char) BaseUtils.getComplement(bases[i]);
        }
        return reverse;
    }

    /**
     * Returns the reverse complement of the read bases
     *
     * @param read the read
     * @return the reverse complement of the read bases
     */
    public static String getBasesReverseComplement(GATKSAMRecord read) {
        return getBasesReverseComplement(read.getReadBases());
    }

    /**
     * Calculate the maximum read length from the given list of reads.
     * @param reads list of reads
     * @return      non-negative integer
     */
    public static int getMaxReadLength( final List<GATKSAMRecord> reads ) {
        if( reads == null ) { throw new IllegalArgumentException("Attempting to check a null list of reads."); }

        int maxReadLength = 0;
        for( final GATKSAMRecord read : reads ) {
            maxReadLength = Math.max(maxReadLength, read.getReadLength());
        }
        return maxReadLength;
    }
}