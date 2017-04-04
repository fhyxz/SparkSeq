package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator;

import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextUtils;
import org.ncic.bioinfo.sparkseq.algorithms.engine.Walker;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLocParser;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefMetaDataTracker;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.AlignmentContext;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PerReadAlleleLikelihoodMap;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.ActiveRegionBasedAnnotation;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.AnnotatorCompatible;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.InfoFieldAnnotation;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.StandardAnnotation;

import java.util.*;


/**
 * Counts and frequency of alleles in called genotypes
 * <p>
 * <p>This annotation outputs the following:</p>
 * <p>
 * <ul>
 * <li>Number of times each ALT allele is represented, in the same order as listed (AC)</li>
 * <li>Frequency of each ALT allele, in the same order as listed (AF)</li>
 * <li>Total number of alleles in called genotypes (AN)</li>
 * </ul>
 * <h3>Example</h3>
 * <pre>AC=1;AF=0.500;AN=2</pre>
 * <p>This set of annotations, relating to a heterozygous call(0/1) means there is 1 alternate allele in the genotype. The corresponding allele frequency is 0.5 because there is 1 alternate allele and 1 reference allele in the genotype.
 * The total number of alleles in the genotype should be equivalent to the ploidy of the sample.</p>
 */
public class ChromosomeCounts extends InfoFieldAnnotation implements StandardAnnotation, ActiveRegionBasedAnnotation {

    private Set<String> founderIds = new HashSet<String>();

    public Map<String, Object> annotate(final RefMetaDataTracker tracker,
                                        final AnnotatorCompatible walker,
                                        final ReferenceContext ref,
                                        final Map<String, AlignmentContext> stratifiedContexts,
                                        final VariantContext vc,
                                        final Map<String, PerReadAlleleLikelihoodMap> perReadAlleleLikelihoodMap) {
        if (!vc.hasGenotypes())
            return null;

        return VariantContextUtils.calculateChromosomeCounts(vc, new HashMap<String, Object>(), true, founderIds);
    }

    public void initialize(AnnotatorCompatible walker, GenomeLocParser toolkit, Set<VCFHeaderLine> headerLines) {
        //If families were given, get the founders ids
        founderIds = new HashSet<String>();
        founderIds.add(((Walker) walker).getSampleName());
    }

    public List<String> getKeyNames() {
        return Arrays.asList(ChromosomeCountConstants.keyNames);
    }

    public List<VCFInfoHeaderLine> getDescriptions() {
        return Arrays.asList(ChromosomeCountConstants.descriptions);
    }
}