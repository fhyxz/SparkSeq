package org.ncic.bioinfo.sparkseq.algorithms.walker.indelrealigner;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordComparator;
import htsjdk.samtools.SAMRecordCoordinateComparator;
import htsjdk.samtools.SamPairUtil;
import org.apache.log4j.Logger;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLoc;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLocParser;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.exceptions.UserException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Author: wbc
 */
public class ConstrainedMateFixingManager {

    protected static final Logger logger = Logger.getLogger(ConstrainedMateFixingManager.class);
    private static final boolean DEBUG = false;

    /**
     * How often do we check whether we want to emit reads?
     */
    protected final static int EMIT_FREQUENCY = 1000;

    /**
     * How much could a single read move in position from its original position?
     */
    final int MAX_POS_MOVE_ALLOWED;

    /**
     * How many reads should we store in memory before flushing the queue?
     */
    final int MAX_RECORDS_IN_MEMORY;

    /**
     * how we order our SAM records
     */
    private final SAMRecordComparator comparer = new SAMRecordCoordinateComparator();

    /**
     * The place where we ultimately write out our records
     */
    private List<SAMRecord> resultRecords = new ArrayList<>();

    /**
     * what is the maximum isize of a pair of reads that can move?  Reads with isize > this value
     * are assumes to not be allowed to move in the incoming read stream.
     */
    final int maxInsertSizeForMovingReadPairs;
    final int initialCapacity = 5000;

    final GenomeLocParser genomeLocParser;
    private GenomeLoc lastLocFlushed = null;

    int counter = 0;

    /**
     * read.name -> records
     */
    HashMap<String, SAMRecordHashObject> forMateMatching = new HashMap<String, SAMRecordHashObject>();
    PriorityQueue<SAMRecord> waitingReads = new PriorityQueue<SAMRecord>(initialCapacity, comparer);

    private SAMRecord remove(PriorityQueue<SAMRecord> queue) {
        SAMRecord first = queue.poll();
        if (first == null)
            throw new UserException("Error caching SAM record -- priority queue is empty, and yet there was an attempt to poll it -- which is usually caused by malformed SAM/BAM files in which multiple identical copies of a read are present.");
        return first;
    }

    private static class SAMRecordHashObject {
        public SAMRecord record;
        public boolean wasModified;

        public SAMRecordHashObject(SAMRecord record, boolean wasModified) {
            this.record = record;
            this.wasModified = wasModified;
        }
    }

    /**
     * @param genomeLocParser                 the GenomeLocParser object
     * @param maxInsertSizeForMovingReadPairs max insert size allowed for moving pairs
     * @param maxMoveAllowed                  max positional move allowed for any read
     * @param maxRecordsInMemory              max records to keep in memory
     */
    public ConstrainedMateFixingManager(final GenomeLocParser genomeLocParser,
                                        final int maxInsertSizeForMovingReadPairs,
                                        final int maxMoveAllowed,
                                        final int maxRecordsInMemory) {
        this.genomeLocParser = genomeLocParser;
        this.maxInsertSizeForMovingReadPairs = maxInsertSizeForMovingReadPairs;
        this.MAX_POS_MOVE_ALLOWED = maxMoveAllowed;
        this.MAX_RECORDS_IN_MEMORY = maxRecordsInMemory;

        //timer.start();
        //lastProgressPrintTime = timer.currentTime();
    }

    public int getNReadsInQueue() {
        return waitingReads.size();
    }

    /**
     * For testing purposes only
     *
     * @return the list of reads currently in the queue
     */
    protected List<SAMRecord> getReadsInQueueForTesting() {
        return new ArrayList<SAMRecord>(waitingReads);
    }

    public boolean canMoveReads(GenomeLoc earliestPosition) {
        if (DEBUG)
            logger.info("Refusing to realign? " + earliestPosition + " vs. " + lastLocFlushed);

        return lastLocFlushed == null ||
                lastLocFlushed.compareContigs(earliestPosition) != 0 ||
                lastLocFlushed.distance(earliestPosition) > maxInsertSizeForMovingReadPairs;
    }

    private boolean noReadCanMoveBefore(int pos, SAMRecord addedRead) {
        return pos + 2 * MAX_POS_MOVE_ALLOWED < addedRead.getAlignmentStart();
    }

    public void addRead(SAMRecord newRead, boolean readWasModified) {
        addRead(newRead, readWasModified, true);
    }

    public void addReads(List<GATKSAMRecord> newReads, Set<GATKSAMRecord> modifiedReads) {
        for (GATKSAMRecord newRead : newReads)
            addRead(newRead, modifiedReads.contains(newRead), false);
    }

    protected void addRead(SAMRecord newRead, boolean readWasModified, boolean canFlush) {
        if (DEBUG)
            logger.info("New read pos " + newRead.getAlignmentStart() + " OP = " + newRead.getAttribute("OP") + " " + readWasModified);

        //final long curTime = timer.currentTime();
        //if ( curTime - lastProgressPrintTime > PROGRESS_PRINT_FREQUENCY ) {
        //    lastProgressPrintTime = curTime;
        //    System.out.println("WaitingReads.size = " + waitingReads.size() + ", forMateMatching.size = " + forMateMatching.size());
        //}

        // if the new read is on a different contig or we have too many reads, then we need to flush the queue and clear the map
        boolean tooManyReads = getNReadsInQueue() >= MAX_RECORDS_IN_MEMORY;
        if ((canFlush && tooManyReads) || (getNReadsInQueue() > 0 && !waitingReads.peek().getReferenceIndex().equals(newRead.getReferenceIndex()))) {
            if (DEBUG)
                logger.warn("Flushing queue on " + (tooManyReads ? "too many reads" : ("move to new contig: " + newRead.getReferenceName() + " from " + waitingReads.peek().getReferenceName())) + " at " + newRead.getAlignmentStart());

            while (getNReadsInQueue() > 1) {
                // emit to disk
                writeRead(remove(waitingReads));
            }

            SAMRecord lastRead = remove(waitingReads);
            lastLocFlushed = (lastRead.getReferenceIndex() == -1) ? null : genomeLocParser.createGenomeLoc(lastRead);
            writeRead(lastRead);

            if (!tooManyReads)
                forMateMatching.clear();
            else
                purgeUnmodifiedMates();
        }

        // fix mates, as needed
        // Since setMateInfo can move reads, we potentially need to remove the mate, and requeue
        // it to ensure proper sorting
        if (newRead.getReadPairedFlag() && !newRead.getNotPrimaryAlignmentFlag()) {
            SAMRecordHashObject mate = forMateMatching.get(newRead.getReadName());
            if (mate != null) {
                // 1. Frustratingly, Picard's setMateInfo() method unaligns (by setting the reference contig
                // to '*') read pairs when both of their flags have the unmapped bit set.  This is problematic
                // when trying to emit reads in coordinate order because all of a sudden we have reads in the
                // middle of the bam file that now belong at the end - and any mapped reads that get emitted
                // after them trigger an exception in the writer.  For our purposes, because we shouldn't be
                // moving read pairs when they are both unmapped anyways, we'll just not run fix mates on them.
                // 2. Furthermore, when reads get mapped to the junction of two chromosomes (e.g. MT since it
                // is actually circular DNA), their unmapped bit is set, but they are given legitimate coordinates.
                // The Picard code will come in and move the read all the way back to its mate (which can be
                // arbitrarily far away).  However, we do still want to move legitimately unmapped reads whose
                // mates are mapped, so the compromise will be that if the mate is still in the queue then we'll
                // move the read and otherwise we won't.
                boolean doNotFixMates = newRead.getReadUnmappedFlag() && (mate.record.getReadUnmappedFlag() || !waitingReads.contains(mate.record));
                if (!doNotFixMates) {

                    boolean reQueueMate = mate.record.getReadUnmappedFlag() && !newRead.getReadUnmappedFlag();
                    if (reQueueMate) {
                        // the mate was unmapped, but newRead was mapped, so the mate may have been moved
                        // to be next-to newRead, so needs to be reinserted into the waitingReads queue
                        // note -- this must be called before the setMateInfo call below
                        if (!waitingReads.remove(mate.record))
                            // we must have hit a region with too much depth and flushed the queue
                            reQueueMate = false;
                    }

                    // we've already seen our mate -- set the mate info and remove it from the map
                    // Via Nils Homer:
                    //   There will be two SamPairUtil.setMateInfo functions.  The default will not update the mate
                    //   cigar tag; in fact, it will remove it if it is present.  An alternative SamPairUtil.setMateInfo
                    //   function takes a boolean as an argument ("addMateCigar") and will add/update the mate cigar if
                    //   set to true.  This is the one you want to use.
                    SamPairUtil.setMateInfo(mate.record, newRead, null, true);
                    if (reQueueMate) waitingReads.add(mate.record);
                }

                forMateMatching.remove(newRead.getReadName());
            } else if (pairedReadIsMovable(newRead)) {
                forMateMatching.put(newRead.getReadName(), new SAMRecordHashObject(newRead, readWasModified));
            }
        }

        waitingReads.add(newRead);

        if (++counter % EMIT_FREQUENCY == 0) {
            while (!waitingReads.isEmpty()) { // there's something in the queue
                SAMRecord read = waitingReads.peek();

                if (noReadCanMoveBefore(read.getAlignmentStart(), newRead) &&
                        (!pairedReadIsMovable(read)                               // we won't try to move such a read
                                || noReadCanMoveBefore(read.getMateAlignmentStart(), newRead))) { // we're already past where the mate started

                    // remove reads from the map that we have emitted -- useful for case where the mate never showed up
                    if (!read.getNotPrimaryAlignmentFlag())
                        forMateMatching.remove(read.getReadName());

                    if (DEBUG)
                        logger.warn(String.format("EMIT!  At %d: read %s at %d with isize %d, mate start %d, op = %s",
                                newRead.getAlignmentStart(), read.getReadName(), read.getAlignmentStart(),
                                read.getInferredInsertSize(), read.getMateAlignmentStart(), read.getAttribute("OP")));
                    // emit to disk
                    writeRead(remove(waitingReads));
                } else {
                    if (DEBUG)
                        logger.warn(String.format("At %d: read %s at %d with isize %d couldn't be emited, mate start %d",
                                newRead.getAlignmentStart(), read.getReadName(), read.getAlignmentStart(), read.getInferredInsertSize(), read.getMateAlignmentStart()));
                    break;
                }
            }

            if (DEBUG)
                logger.warn(String.format("At %d: Done with emit cycle", newRead.getAlignmentStart()));
        }
    }

    private void writeRead(SAMRecord read) {
        resultRecords.add(read);
    }

    /**
     * @param read the read
     * @return true if the read shouldn't be moved given the constraints of this SAMFileWriter
     */
    public boolean iSizeTooBigToMove(SAMRecord read) {
        return iSizeTooBigToMove(read, maxInsertSizeForMovingReadPairs);               // we won't try to move such a read
    }

    public static boolean iSizeTooBigToMove(SAMRecord read, int maxInsertSizeForMovingReadPairs) {
        return (read.getReadPairedFlag() && !read.getMateUnmappedFlag() && !read.getReferenceName().equals(read.getMateReferenceName())) // maps to different chromosomes
                || Math.abs(read.getInferredInsertSize()) > maxInsertSizeForMovingReadPairs;     // we won't try to move such a read
    }

    private void purgeUnmodifiedMates() {
        HashMap<String, SAMRecordHashObject> forMateMatchingCleaned = new HashMap<String, SAMRecordHashObject>();
        for (Map.Entry<String, SAMRecordHashObject> entry : forMateMatching.entrySet()) {
            if (entry.getValue().wasModified)
                forMateMatchingCleaned.put(entry.getKey(), entry.getValue());
        }

        forMateMatching.clear(); // explicitly clear the memory
        forMateMatching = forMateMatchingCleaned;
    }

    private boolean pairedReadIsMovable(SAMRecord read) {
        return read.getReadPairedFlag()                                          // we're a paired read
                && (!read.getReadUnmappedFlag() || !read.getMateUnmappedFlag())  // at least one read is mapped
                && !iSizeTooBigToMove(read);                                     // insert size isn't too big

    }

    public void close() {
        // write out all of the remaining reads
        while (!waitingReads.isEmpty()) { // there's something in the queue
            writeRead(remove(waitingReads));
        }
    }

    public List<SAMRecord> getResultRecords() {
        return resultRecords;
    }
}