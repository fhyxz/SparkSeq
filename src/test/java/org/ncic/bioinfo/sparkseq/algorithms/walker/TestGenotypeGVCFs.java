package org.ncic.bioinfo.sparkseq.algorithms.walker;

import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeader;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.SamContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.data.vcf.RODContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.data.vcf.header.StandardWGSVCFHeader;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLocParser;
import org.ncic.bioinfo.sparkseq.algorithms.walker.genotypegvcfs.GenotypeGVCFs;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.ActiveRegionFinder;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.ActiveRegionMapData;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.HaplotypeCaller;
import org.ncic.bioinfo.sparkseq.data.basic.VcfRecord;
import org.ncic.bioinfo.sparkseq.data.common.ReadGroupInfo;
import org.ncic.bioinfo.sparkseq.data.common.RefContigInfo;
import org.ncic.bioinfo.sparkseq.data.common.SamHeaderInfo;
import org.ncic.bioinfo.sparkseq.transfer.SAMHeaderTransfer;
import org.ncic.bioinfo.sparkseq.transfer.SAMSequenceDictTransfer;
import org.ncic.bioinfo.sparkseq.transfer.VC2VcfRecordTransfer;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Author: wbc
 */
public class TestGenotypeGVCFs extends AbstractTestCase {
/*
    public void testGenotypeGVCFs() {

        RefContigInfo refContigInfo = RefContigInfo.apply(getClass().getResource("/human_g1k_v37.dict").getFile());
        SamHeaderInfo headerInfo = SamHeaderInfo.sortedHeader(refContigInfo, null);
        headerInfo.addReadGroupInfo(ReadGroupInfo.apply("SRR504516", "sample1"));

        SAMSequenceDictionary samSequenceDictionary = SAMSequenceDictTransfer.transfer(refContigInfo);
        GenomeLocParser parser = new GenomeLocParser(samSequenceDictionary);

        List<SAMRecord> recaledReads = getRecaledReads(headerInfo);
        List<GATKSAMRecord> realignedGATKRecords = new ArrayList<>();
        for (SAMRecord record : recaledReads) {
            realignedGATKRecords.add(new GATKSAMRecord(record));
        }
        SamContentProvider samContentProvider = new SamContentProvider(realignedGATKRecords, SAMHeaderTransfer.transfer(headerInfo));

        RefContentProvider refContentProvider = getRefContentProvider(samSequenceDictionary);

        java.util.List<RODContentProvider> rodContentProviders = new java.util.ArrayList<>();

        ActiveRegionFinder activeRegionFinder = new ActiveRegionFinder(parser, refContentProvider, samContentProvider, rodContentProviders, true);

        activeRegionFinder.run();

        List<ActiveRegionMapData> activeRegionMapDataList = activeRegionFinder.getResultActiveRegions();

        HaplotypeCaller haplotypeCaller = new HaplotypeCaller(
                parser, refContentProvider, samContentProvider,
                rodContentProviders, activeRegionMapDataList, true);
        haplotypeCaller.run();

        List<VariantContext> gvcfs = haplotypeCaller.getResultVCFRecords();

        GenotypeGVCFs genotypeGVCFs = new GenotypeGVCFs(parser, refContentProvider, samContentProvider, gvcfs);

        genotypeGVCFs.run();

        List<VariantContext> result = genotypeGVCFs.getResultVcfRecords();
        List<VcfRecord> standardResult = getVcf(refContigInfo);

        VCFHeader header = StandardWGSVCFHeader.getHeader();
        VC2VcfRecordTransfer transfer = new VC2VcfRecordTransfer(header, refContigInfo);
        List<VcfRecord> transferedResult = result.stream()
                .map(record -> transfer.transfer(record))
                .collect(Collectors.toList());
        assertEquals(transferedResult.size(), standardResult.size());
        for (int i = 0; i < transferedResult.size(); i++) {
            VcfRecord record1 = standardResult.get(i);
            VcfRecord record2 = transferedResult.get(i);
            assertEquals(record1.position(), record2.position());
            assertEquals(record1.ref(), record2.ref());
            assertEquals(record1.alt(), record2.alt());
            assertEquals(record1.getQualString(), record2.getQualString());
        }
    }*/
}
