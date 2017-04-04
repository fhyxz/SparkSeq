package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator;

import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefMetaDataTracker;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.AlignmentContext;
import org.ncic.bioinfo.sparkseq.algorithms.utils.MathUtils;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PerReadAlleleLikelihoodMap;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.ActiveRegionBasedAnnotation;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.AnnotatorCompatible;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.InfoFieldAnnotation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Author: wbc
 */
public class GenotypeSummaries extends InfoFieldAnnotation implements ActiveRegionBasedAnnotation {

    public final static String CCC = "CCC";
    public final static String NCC = "NCC";
    public final static String HWP = "HWP";
    public final static String GQ_MEAN = "GQ_MEAN";
    public final static String GQ_STDDEV = "GQ_STDDEV";

    @Override
    public Map<String, Object> annotate(final RefMetaDataTracker tracker,
                                        final AnnotatorCompatible walker,
                                        final ReferenceContext ref,
                                        final Map<String, AlignmentContext> stratifiedContexts,
                                        final VariantContext vc,
                                        final Map<String, PerReadAlleleLikelihoodMap> perReadAlleleLikelihoodMap ) {
        if ( ! vc.hasGenotypes() )
            return null;

        final Map<String,Object> returnMap = new HashMap<>();
        returnMap.put(NCC, vc.getNoCallCount());

        final MathUtils.RunningAverage average = new MathUtils.RunningAverage();
        for( final Genotype g : vc.getGenotypes() ) {
            if( g.hasGQ() ) {
                average.add(g.getGQ());
            }
        }
        if( average.observationCount() > 0L ) {
            returnMap.put(GQ_MEAN, String.format("%.2f", average.mean()));
            if( average.observationCount() > 1L ) {
                returnMap.put(GQ_STDDEV, String.format("%.2f", average.stddev()));
            }
        }

        return returnMap;
    }

    @Override
    public List<String> getKeyNames() {
        return Arrays.asList(CCC, NCC, HWP, GQ_MEAN, GQ_STDDEV);
    }

    @Override
    public List<VCFInfoHeaderLine> getDescriptions() {
        return Arrays.asList(
                new VCFInfoHeaderLine(CCC, 1, VCFHeaderLineType.Integer, "Number of called chromosomes"),
                new VCFInfoHeaderLine(NCC, 1, VCFHeaderLineType.Integer, "Number of no-called samples"),
                new VCFInfoHeaderLine(HWP, 1, VCFHeaderLineType.Float, "P value from test of Hardy Weinberg Equilibrium"),
                new VCFInfoHeaderLine(GQ_MEAN, 1, VCFHeaderLineType.Float, "Mean of all GQ values"),
                new VCFInfoHeaderLine(GQ_STDDEV, 1, VCFHeaderLineType.Float, "Standard deviation of all GQ values")
        );
    }
}
