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
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.StandardAnnotation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Fraction of reads containing spanning deletions
 * <p>
 * <p>The presence of many reads with deletions spanning a given site is often an indication that a variant call made at that site is in fact a false positive. This annotation counts the number of reads that contain deletions spanning the site divided by the total number of reads that cover the site.</p>
 * <p>
 * <h3>Caveats</h3>
 * <ul>
 * <li>This annotation is not compatible with HaplotypeCaller; its purpose is to compensate for the UnifiedGenotyper's inability to integrate SNPs and indels in the same model (unlike HaplotypeCaller)</li>
 * <li>By default, the UnifiedGenotyper will not call variants where the fraction of spanning deletions is above a certain threshold. This threshold can be adjusted using the `--max_deletion_fraction` argument.</li>
 * </ul>
 */
public class SpanningDeletions extends InfoFieldAnnotation implements StandardAnnotation {

    public Map<String, Object> annotate(final RefMetaDataTracker tracker,
                                        final AnnotatorCompatible walker,
                                        final ReferenceContext ref,
                                        final Map<String, AlignmentContext> stratifiedContexts,
                                        final VariantContext vc,
                                        final Map<String, PerReadAlleleLikelihoodMap> stratifiedPerReadAlleleLikelihoodMap) {
        if (stratifiedContexts.size() == 0)
            return null;

        // not meaningful when we're at an indel location: deletions that start at location N are by definition called at the position  N-1, and at position N-1
        // there are no informative deletions in the pileup
        if (!vc.isSNP())
            return null;

        int deletions = 0;
        int depth = 0;
        for (Map.Entry<String, AlignmentContext> sample : stratifiedContexts.entrySet()) {
            for (final PileupElement p : sample.getValue().getBasePileup()) {
                depth++;
                if (p.isDeletion())
                    deletions++;
            }
        }
        Map<String, Object> map = new HashMap<String, Object>();
        map.put(getKeyNames().get(0), String.format("%.2f", depth == 0 ? 0.0 : (double) deletions / (double) depth));
        return map;
    }

    public List<String> getKeyNames() {
        return Arrays.asList("Dels");
    }

    public List<VCFInfoHeaderLine> getDescriptions() {
        return Arrays.asList(new VCFInfoHeaderLine("Dels", 1, VCFHeaderLineType.Float, "Fraction of Reads Containing Spanning Deletions"));
    }
}