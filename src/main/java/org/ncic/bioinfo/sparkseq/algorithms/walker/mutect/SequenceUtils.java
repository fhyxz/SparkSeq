package org.ncic.bioinfo.sparkseq.algorithms.walker.mutect;

import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMRecord;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.PileupElement;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.ReadBackedPileup;
import org.ncic.bioinfo.sparkseq.algorithms.utils.BaseUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLoc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Author: wbc
 */
public class SequenceUtils {

    public static String createSequenceContext(RefContentProvider refContentProvider, ReferenceContext ref, int size) {
        // create a context of 3 bases before, then 'x' then three bases after
        GenomeLoc refLoc = ref.getLocus();
        GenomeLoc left = new GenomeLoc(refLoc.getContig(), refLoc.getContigIndex(), refLoc.getStart() - size, refLoc.getStart() - 1);
        GenomeLoc right = new GenomeLoc(refLoc.getContig(), refLoc.getContigIndex(), refLoc.getStop() + 1, refLoc.getStop() + size);
        ReferenceContext leftContext = refContentProvider.getReferenceContext(left);
        ReferenceContext rightContext = refContentProvider.getReferenceContext(right);

        StringBuilder sb = new StringBuilder(7);

        for(byte b : leftContext.getBases()) {
            sb.append(Character.toUpperCase((char)b));
        }
        sb.append('x');
        for(byte b : rightContext.getBases()) {
            sb.append(Character.toUpperCase((char)b));
        }
        return sb.toString();
    }

    public static int[] getStrandContingencyTable(ReadBackedPileup forwardPile, ReadBackedPileup reversePile, byte ref, byte alt) {
        // Construct a 2x2 contingency table of
        //            forward     reverse
        //      REF    a       b
        //      MUT    c       d
        //
        // and return an array of {a,b,c,d}

        int refIdx = BaseUtils.simpleBaseToBaseIndex(ref);
        int altIdx = BaseUtils.simpleBaseToBaseIndex(alt);

        int[] forwardBaseCounts = forwardPile.getBaseCounts();
        int[] reverseBaseCounts = reversePile.getBaseCounts();

        return new int[]{forwardBaseCounts[refIdx], reverseBaseCounts[refIdx], forwardBaseCounts[altIdx], reverseBaseCounts[altIdx]};
    }


    public static List<Integer> getForwardOffsetsInRead(ReadBackedPileup p) {
        return getOffsetsInRead(p, true);
    }

    public static List<Integer> getReverseOffsetsInRead(ReadBackedPileup p) {
        return getOffsetsInRead(p, false);
    }

    public static List<Integer> getOffsetsInRead(ReadBackedPileup p, boolean useForwardOffsets) {
        List<Integer> positions = new ArrayList<Integer>();
        for(PileupElement pe : p) {

            positions.add(
                    Math.abs((p.getLocation().getStart() - (useForwardOffsets?pe.getRead().getAlignmentStart():pe.getRead().getAlignmentEnd())))
            );
        }

        return positions;
    }

    // TODO: see if this is cheaper with a GATKSAMRecord...
    public static boolean isReadHeavilySoftClipped(SAMRecord rec, float threshold) {
        int total = 0;
        int clipped = 0;
        for(CigarElement ce : rec.getCigar().getCigarElements()) {
            total += ce.getLength();
            if (ce.getOperator() == CigarOperator.SOFT_CLIP) {
                clipped += ce.getLength();
            }
        }

        return ((float) clipped / (float)total >= threshold);
    }

}
