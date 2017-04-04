package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator;

import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.variantcontext.VariantContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefMetaDataTracker;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.AlignmentContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.PileupElement;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PerReadAlleleLikelihoodMap;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.AnnotatorCompatible;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.InfoFieldAnnotation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Proportion of low quality reads
 *
 * <p>This annotation tells you what fraction of reads have a mapping quality of less than the given threshold of 10 (including 0). Note that certain tools may impose a different minimum mapping quality threshold. For example, HaplotypeCaller excludes reads with MAPQ<20.</p>
 *
 * <h3>Calculation</h3>
 * <p> $$ LowMQ = \frac{# reads with MAPQ=0 + # reads with MAPQ<10}{total # reads} $$
 * </p>
 *
 * <h3>Related annotations</h3>
 * <ul>
 *     <li><b><a href="https://www.broadinstitute.org/gatk/guide/tooldocs/org_broadinstitute_gatk_tools_walkers_annotator_MappingQualityZero.php">MappingQualityZero</a></b> gives the count of reads with MAPQ=0 across all samples.</li>
 *     <li><b><a href="https://www.broadinstitute.org/gatk/guide/tooldocs/org_broadinstitute_gatk_tools_walkers_annotator_MappingQualityZeroBySample.php">MappingQualityZeroBySample</a></b> gives the count of reads with MAPQ=0 for each individual sample.</li>
 * </ul>
 */
public class LowMQ extends InfoFieldAnnotation {

    public Map<String, Object> annotate(final RefMetaDataTracker tracker,
                                        final AnnotatorCompatible walker,
                                        final ReferenceContext ref,
                                        final Map<String, AlignmentContext> stratifiedContexts,
                                        final VariantContext vc,
                                        final Map<String, PerReadAlleleLikelihoodMap> stratifiedPerReadAlleleLikelihoodMap) {
        if ( stratifiedContexts.size() == 0 )
            return null;

        double mq0 = 0;
		double mq10 = 0;
		double total = 0;
        for ( Map.Entry<String, AlignmentContext> sample : stratifiedContexts.entrySet() )
		{
            for ( PileupElement p : sample.getValue().getBasePileup() )
			{
                if ( p.getMappingQual() == 0 )  { mq0 += 1; }
                if ( p.getMappingQual() <= 10 ) { mq10 += 1; }
				total += 1; 
            }
        }
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(getKeyNames().get(0), String.format("%.04f,%.04f,%.00f", mq0/total, mq10/total, total));
        return map;
    }

    public List<String> getKeyNames() { return Arrays.asList("LowMQ"); }

    public List<VCFInfoHeaderLine> getDescriptions() { return Arrays.asList(new VCFInfoHeaderLine(getKeyNames().get(0), 3, VCFHeaderLineType.Float, "3-tuple: <fraction of reads with MQ=0>,<fraction of reads with MQ<=10>,<total number of reads>")); }
}
