package org.ncic.bioinfo.sparkseq.algorithms.data.vcf;

import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFCodec;
import htsjdk.variant.vcf.VCFHeader;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLocParser;
import org.ncic.bioinfo.sparkseq.data.partition.VcfRecordPartition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.ncic.bioinfo.sparkseq.transfer.CollectionConverter;
import scala.collection.JavaConversions;

/**
 * Author: wbc
 */
public class RODContentProvider {

    private final VCFHeader vcfFileHeader;
    private final ArrayList<GATKFeature> features = new ArrayList<>();

    private final String name;

    public RODContentProvider(String name, VcfRecordPartition partition, GenomeLocParser genomeLocParser) {
        this.name = name;
        VCFCodec codec = new VCFCodec();
        VCFHeaderLineIterable headerLineIterable = new VCFHeaderLineIterable(partition.vcfHeader());
        vcfFileHeader = (VCFHeader) codec.readActualHeader(headerLineIterable);

        ArrayList<VariantContext> vcfRecords = new ArrayList<>();
        CollectionConverter.asJavaList(partition.records())
                .forEach(record -> vcfRecords.add(codec.decode(record.toString())));

        //为vcfRecord进行排序
        Collections.sort(vcfRecords,
                (record1, record2) -> record1.getStart() - record2.getStart());

        vcfRecords.forEach(context ->
                features.add(new GATKFeature.TribbleGATKFeature(genomeLocParser, context, name)));
    }

    public RODContentProvider(String name, VCFHeader vcfFileHeader,
                              List<VariantContext> vcfRecords, GenomeLocParser genomeLocParser) {
        this.name = name;

        this.vcfFileHeader = vcfFileHeader;

        Collections.sort(vcfRecords,
                (record1, record2) -> record1.getStart() - record2.getStart());

        vcfRecords.forEach(context ->
                features.add(new GATKFeature.TribbleGATKFeature(genomeLocParser, context, name)));
    }

    public VCFHeader getVcfFileHeader() {
        return vcfFileHeader;
    }

    public ArrayList<GATKFeature> getFeatures() {
        return features;
    }

    public String getName() {
        return name;
    }
}
