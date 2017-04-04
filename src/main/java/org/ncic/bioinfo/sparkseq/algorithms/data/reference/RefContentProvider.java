package org.ncic.bioinfo.sparkseq.algorithms.data.reference;

import htsjdk.samtools.SAMSequenceDictionary;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLoc;
import org.ncic.bioinfo.sparkseq.data.partition.FastaPartition;

/**
 * 提供reference上的base信息，封装FastaPartition
 * <p>
 * Author: wbc
 */
public class RefContentProvider {

    /**
     * 一个contig上的字符组
     */
    private final byte[] content;

    /**
     * contig的Id
     */
    private final int contigId;

    /**
     * contig的name
     */
    private final String contigName;

    /**
     * safe overalp只用于取reference的数据
     */
    private final int safeOverlappedStartCoordinate;
    private final int safeOverlappedEndCoordinate;

    private final int overlappedStartCoordinate;
    private final int overlappedEndCoordinate;

    private final int originStartCoordinate;
    private final int originEndCoordinate;

    private final SAMSequenceDictionary samSequenceDictionary;

    public RefContentProvider(SAMSequenceDictionary samSequenceDictionary,
                              FastaPartition fastaPartition) {
        contigId = fastaPartition.contigId();
        contigName = fastaPartition.contigName();
        String rawContent = fastaPartition.content();
        int contentLen = rawContent.length();
        content = new byte[contentLen];

        // 对于ref中不是AGCTN的base，全部换成N
        for (int i = 0; i < contentLen; i++) {
            byte base = (byte) rawContent.charAt(i);
            if (base == 'A' || base == 'G' || base == 'C' || base == 'T') {
                content[i] = base;
            } else {
                content[i] = 'N';
            }
        }

        safeOverlappedStartCoordinate = fastaPartition.safeOverlappedStart();
        safeOverlappedEndCoordinate = fastaPartition.safeOverlappedEnd();

        overlappedStartCoordinate = fastaPartition.overlappedStart();
        overlappedEndCoordinate = fastaPartition.overlappedEnd();

        originStartCoordinate = fastaPartition.originStart();
        originEndCoordinate = fastaPartition.originEnd();

        this.samSequenceDictionary = samSequenceDictionary;
    }

    /**
     * 获取partition负责的interval，是overlapped
     *
     * @return
     */
    public GenomeLoc getLocus() {
        return new GenomeLoc(contigName, contigId, overlappedStartCoordinate, overlappedEndCoordinate);
    }

    /**
     * 在截取时会判断是否超出长度，所以返回的长度可能小于locus的长度
     *
     * @param locus
     * @return
     */
    public ReferenceContext getReferenceContext(GenomeLoc locus) {
        int start = locus.getStart() - safeOverlappedStartCoordinate;
        if (start < 0) {
            start = 0;
        }
        int end = locus.getStop() - safeOverlappedStartCoordinate;
        if (end >= content.length) {
            end = content.length - 1;
        }
        if(end-start+1 <=0) {
            int a = 0;
        }
        byte[] basesCache = new byte[end - start + 1];
        int idx = 0;
        for (int i = start; i <= end; i++) {
            basesCache[idx] = content[i];
            idx++;
        }

        GenomeLoc newLocus = new GenomeLoc(locus.getContig(), locus.getContigIndex(),
                start + safeOverlappedStartCoordinate, end + safeOverlappedStartCoordinate);
        return new ReferenceContext(newLocus, contigId, basesCache);
    }

    public ReferenceContext getReferenceContext(GenomeLoc locus, int overlapLength) {
        GenomeLoc newLocus = new GenomeLoc(locus.getContig(), locus.getContigIndex(),
                locus.getStart() - overlapLength, locus.getStop() + overlapLength);
        return getReferenceContext(newLocus);
    }

    public SAMSequenceDictionary getSamSequenceDictionary() {
        return samSequenceDictionary;
    }

    public int getOriginStartCoordinate() {
        return originStartCoordinate;
    }

    public int getOriginEndCoordinate() {
        return originEndCoordinate;
    }
}
