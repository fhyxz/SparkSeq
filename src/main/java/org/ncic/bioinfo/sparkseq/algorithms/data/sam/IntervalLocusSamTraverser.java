package org.ncic.bioinfo.sparkseq.algorithms.data.sam;

import org.apache.commons.collections.CollectionUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.filter.FilterUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLoc;
import org.ncic.bioinfo.sparkseq.exceptions.GATKException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Author: wbc
 */
public class IntervalLocusSamTraverser {

    private static final int OVERLAP_LEN = 100;
    private final List<GenomeLoc> intervals;
    private final LocusSamTraverser locusSamTraverser;

    public IntervalLocusSamTraverser(SamContentProvider samContentProvider,
                                     GenomeLoc refLocus,
                                     List<GenomeLoc> rawTraverseIntervals) {
        this(samContentProvider, refLocus, rawTraverseIntervals, new FilterUtils());
    }

    public IntervalLocusSamTraverser(SamContentProvider samContentProvider,
                                     GenomeLoc refLocus,
                                     List<GenomeLoc> rawTraverseIntervals,
                                     FilterUtils filterUtils) {
        this.intervals = getOverlappedIntervals(refLocus, rawTraverseIntervals);
        this.locusSamTraverser = new LocusSamTraverser(samContentProvider, refLocus, filterUtils);
        rewind();
    }

    private List<GenomeLoc> getOverlappedIntervals(GenomeLoc refLocus, List<GenomeLoc> rawTraverseIntervals) {
        // 如果没有intervals，就把整个refLocus作为遍历对象
        if (CollectionUtils.isEmpty(rawTraverseIntervals)) {
            List<GenomeLoc> singleGenomeLoc = new ArrayList<>(1);
            singleGenomeLoc.add(refLocus);
            return singleGenomeLoc;
        }

        List<GenomeLoc> rawOverlappedIntervals = rawTraverseIntervals.stream()
                .filter(interval -> interval.overlapsP(refLocus))
                .map(interval -> new GenomeLoc(interval.getContig(), interval.getContigIndex(),
                        (interval.getStart() - OVERLAP_LEN) < refLocus.getStart() ? refLocus.getStart() : (interval.getStart() - OVERLAP_LEN),
                        (interval.getStop() + OVERLAP_LEN) > refLocus.getStop() ? refLocus.getStop() : (interval.getStop() + OVERLAP_LEN)))
                .collect(Collectors.toList());

        Collections.sort(rawOverlappedIntervals,
                (interval1, interval2) -> interval1.getStart() - interval2.getStart());

        //处理intervals粘连的情况
        List<GenomeLoc> finalIntervals = new ArrayList<>();
        GenomeLoc tmpInterval = null;
        for (GenomeLoc interval : rawOverlappedIntervals) {
            if (tmpInterval == null) {
                tmpInterval = interval;
            } else {
                if (tmpInterval.overlapsP(interval)) {
                    tmpInterval = new GenomeLoc(tmpInterval.getContig(), tmpInterval.getContigIndex(),
                            Integer.min(tmpInterval.getStart(), interval.getStart()),
                            Integer.max(tmpInterval.getStop(), interval.getStop()));
                } else {
                    finalIntervals.add(tmpInterval);
                    tmpInterval = interval;
                }
            }
        }
        if (tmpInterval != null) {
            finalIntervals.add(tmpInterval);
        }

        return finalIntervals;
    }

    private int curIntervalIdx = -1;
    private int curCoordinate = -1;

    public void rewind() {
        curIntervalIdx = 0;
        curCoordinate = intervals.get(curIntervalIdx).getStart();
    }

    public boolean hasNext() {
        return curIntervalIdx < intervals.size() - 1 ||
                (curIntervalIdx == intervals.size() - 1 && curCoordinate <= intervals.get(intervals.size() - 1).getStop());
    }

    public AlignmentContext next() {
        if (!hasNext()) {
            throw new GATKException("Out of bound when traverse sam content provider");
        }

        locusSamTraverser.forwardToCoordinate(curCoordinate);
        AlignmentContext alignmentContext = locusSamTraverser.next();

        // 更新curCoordinate
        if (curCoordinate == intervals.get(curIntervalIdx).getStop()) {
            curIntervalIdx++;
            if (curIntervalIdx < intervals.size()) {    // 如果有下一个interval，更新coordinate
                curCoordinate = intervals.get(curIntervalIdx).getStart();
            }
        } else {
            curCoordinate++;
        }

        return alignmentContext;
    }
}
