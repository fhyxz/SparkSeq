package org.ncic.bioinfo.sparkseq.algorithms.walker;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.SamContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.data.vcf.RODContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.data.vcf.RODNames;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLocParser;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.utils.reports.GATKReport;
import org.ncic.bioinfo.sparkseq.algorithms.walker.printreads.PrintReads;
import org.ncic.bioinfo.sparkseq.data.common.ReadGroupInfo;
import org.ncic.bioinfo.sparkseq.data.common.RefContigInfo;
import org.ncic.bioinfo.sparkseq.data.common.SamHeaderInfo;
import org.ncic.bioinfo.sparkseq.data.partition.VcfRecordPartition;
import org.ncic.bioinfo.sparkseq.transfer.GATKReportTransfer;
import org.ncic.bioinfo.sparkseq.transfer.SAMHeaderTransfer;
import org.ncic.bioinfo.sparkseq.transfer.SAMSequenceDictTransfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Author: wbc
 */
public class TestPrintReads extends AbstractTestCase {
    public void testPrintReads() {
        RefContigInfo refContigInfo = RefContigInfo.apply(getClass().getResource("/human_g1k_v37.dict").getFile());
        SamHeaderInfo headerInfo = SamHeaderInfo.sortedHeader(refContigInfo, null);
        headerInfo.addReadGroupInfo(ReadGroupInfo.apply("SRR504516", "sample1"));

        SAMSequenceDictionary samSequenceDictionary = SAMSequenceDictTransfer.transfer(refContigInfo);
        GenomeLocParser parser = new GenomeLocParser(samSequenceDictionary);

        List<SAMRecord> realignedReads = getRealignedReads(headerInfo);
        List<GATKSAMRecord> realignedGATKRecords = new ArrayList<>();
        for (SAMRecord record : realignedReads) {
            realignedGATKRecords.add(new GATKSAMRecord(record));
        }
        SamContentProvider samContentProvider = new SamContentProvider(realignedGATKRecords, SAMHeaderTransfer.transfer(headerInfo));

        RefContentProvider refContentProvider = getRefContentProvider(samSequenceDictionary);

        VcfRecordPartition vcfRecordPartition = loadVcfPartition("/mills_indel.vcf", "MILLS_INDEL", refContigInfo);
        RODContentProvider rodContentProvider = new RODContentProvider(RODNames.KNOWN_ALLELES, vcfRecordPartition, parser);
        java.util.List<RODContentProvider> rodContentProviders = new java.util.ArrayList<>();
        rodContentProviders.add(rodContentProvider);

        List<String> recalTableStrings = getRecalTableLines();
        GATKReport report = GATKReportTransfer.lines2Report(recalTableStrings);

        PrintReads printReads = new PrintReads(parser, refContentProvider,
                samContentProvider, rodContentProviders, report);

        printReads.run();

        List<GATKSAMRecord> resultReads = printReads.getResultRecords();

        List<SAMRecord> standardResult = getRecaledReads(headerInfo);

        Map<String, SAMRecord> readMap = readList2Map(standardResult);
        resultReads.forEach(record -> {
            SAMRecord standardRecord = readMap.get(record.getReadName() + record.getFlags());
            assertTrue(standardRecord.getCigarString().equals(record.getCigarString()));
            assertTrue(standardRecord.getBaseQualityString().equals(record.getBaseQualityString()));
        });
    }
}
