package org.ncic.bioinfo.sparkseq.algorithms.data.sam.filter;

import htsjdk.samtools.SAMRecord;

/**
 * Author: wbc
 */
public class NotPrimaryAlignmentFilter extends Filter{

    @Override
    public boolean filterOut(SAMRecord read) {
        return read.getNotPrimaryAlignmentFlag();
    }

}
