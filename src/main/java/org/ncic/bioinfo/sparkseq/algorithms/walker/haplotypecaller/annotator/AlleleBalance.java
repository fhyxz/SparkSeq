package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import org.ncic.bioinfo.sparkseq.algorithms.utils.MathUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefMetaDataTracker;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.AlignmentContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.ReadBackedPileup;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PerReadAlleleLikelihoodMap;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.AnnotatorCompatible;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.InfoFieldAnnotation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Allele balance across all samples
 *
 * <p> This is an experimental annotation that attempts to estimate whether the data supporting a variant call fits allelic ratio expectations, or whether there might be some bias in the data. Each sample will contribute its allelic read depth (from the AD annotation) to either ABHom or ABHet depending on its genotype call: ABHom if the call is homozygous (REF/REF or ALT/ALT), and ABHet if the call is heterozygous (REF/ALT). Additionally, reads that support something other than the genotyped alleles (called "non-alleles") will be counted in the OND tag, which represents the overall fraction of data that diverges from the diploid hypothesis.</p>
 * <h3>Calculations</h3>
 * <p> $$ ABHom = \frac{# ALT alleles}{total # alleles} $$ <br />
 *     $$ ABHet = \frac{# REF alleles}{# total alleles} $$ <br />
 *     $$ OND = \frac{# genotyped alleles}{# alleles + # non-alleles} $$
 * </p>
 * <p> For ABHom, the value should be close to 1.00 because ideally, all the reads should support a single allele. For ABHet, the value should be close to 0.5, so half of the alleles support the ref allele and half of the alleles support the alt allele. Divergence from these expected ratios may indicate that there is some bias in favor of one allele. Note the caveats below regarding cancer and RNAseq analysis. </p>
 * <h3>Caveats</h3>
 * <ul>
 *     <li>This annotation will only work properly for biallelic variants where all samples are called heterozygous or homozygous.</li>
 *     <li>This annotation cannot currently be calculated for indels.</li>
 *     <li>tThe reasoning underlying this annotation only applies to germline variants in DNA sequencing data. In somatic/cancer analysis, divergent ratios are expected due to tumor heterogeneity. In RNAseq analysis, divergent ratios may indicate differential allele expression.</li>
 *     <li>As stated above, this annotation is experimental and should be interpreted with caution as we cannot guarantee that it is appropriate. Basically, use it at your own risk.</li>
 * </ul>
 * <h3>Related annotations</h3>
 * <ul>
 *     <li><b><a href="https://www.broadinstitute.org/gatk/guide/tooldocs/org_broadinstitute_gatk_tools_walkers_annotator_AlleleBalanceBySample.php">AlleleBallanceBySample</a></b> calculates allele balance for each individual sample.</li>
 *     <li><b><a href="https://www.broadinstitute.org/gatk/guide/tooldocs/org_broadinstitute_gatk_tools_walkers_annotator_DepthPerAlleleBySample.php">DepthPerAlleleBySample</a></b> calculates depth of coverage for each allele per sample.</li>
 * </ul>
 */

public class AlleleBalance extends InfoFieldAnnotation {

    public Map<String, Object> annotate(final RefMetaDataTracker tracker,
                                        final AnnotatorCompatible walker,
                                        final ReferenceContext ref,
                                        final Map<String, AlignmentContext> stratifiedContexts,
                                        final VariantContext vc,
                                        final Map<String, PerReadAlleleLikelihoodMap> stratifiedPerReadAlleleLikelihoodMap) {
        //if ( stratifiedContexts.size() == 0 )
        //    return null;

        if ( !vc.isBiallelic() )
            return null;
        final GenotypesContext genotypes = vc.getGenotypes();
        if ( !vc.hasGenotypes() )
            return null;

        double ratioHom = 0.0;
        double ratioHet = 0.0;
        double weightHom = 0.0;
        double weightHet = 0.0;
        double overallNonDiploid = 0.0;
        for ( Genotype genotype : genotypes ) {

            if ( vc.isSNP() ) {

                final int[] counts = getCounts(genotype, stratifiedContexts, vc);
                // If AD was not calculated, we can't continue
                if(counts == null)
                    continue;

                final int n_allele = counts.length;
                int count_sum = 0;
                for(int i=0; i<n_allele; i++){
                    count_sum += counts[i];
                }
                double pTrue = 1.0 - Math.pow(10.0,-genotype.getGQ() / (double) 10 );
                if ( genotype.isHet() ) {

                    final int otherCount = count_sum - (counts[0] + counts[1]);
                    // sanity check
                    if ( counts[0] + counts[1] == 0 )
                        continue;

                    // weight the allele balance by genotype quality so that e.g. mis-called homs don't affect the ratio too much
                    ratioHet += pTrue * ((double)counts[0] / (double)(counts[0] + counts[1]));
                    weightHet += pTrue;
                    overallNonDiploid += ( (double) otherCount )/((double) count_sum*genotypes.size());
                } else if ( genotype.isHom() ) {
                    final int alleleIdx = genotype.isHomRef() ?  0 : 1 ;
                    final int alleleCount = counts[alleleIdx];
                    int bestOtherCount = 0;
                    for(int i=0; i<n_allele; i++){
                        if( i == alleleIdx )
                            continue;
                        if( counts[i] > bestOtherCount )
                            bestOtherCount = counts[i];
                    }
                    final int otherCount = count_sum - alleleCount;
                    ratioHom += pTrue*( (double) alleleCount)/((double) (alleleCount+bestOtherCount));
                    weightHom += pTrue;
                    overallNonDiploid += ((double ) otherCount)/((double) count_sum*genotypes.size());
                }
                // Allele Balance for indels was not being computed correctly (since there was no allele matching).  Instead of
                // prolonging the life of imperfect code, I've decided to delete it.  If someone else wants to try again from
                // scratch, be my guest - but make sure it's done correctly!  [EB]
            }
        }

        // make sure we had a het genotype

        Map<String, Object> map = new HashMap<>();
        if ( weightHet > 0.0 ) {
            map.put("ABHet",ratioHet/weightHet);
        }

        if ( weightHom > 0.0 ) {
            map.put("ABHom",ratioHom/weightHom);
        }

        if ( overallNonDiploid > 0.0 ) {
            map.put("OND",overallNonDiploid);
        }
        return map;
    }

    /**
     * Provide a centralized method of getting the number of reads per allele, 
     * depending on the input given.  Will use the following (in order of preference):
     * - genotype.getAD()
     * - reads from an AlignmentContext
     * - reads from a PerReadAlleleLikelihoodMap (Not yet implemented) 
     *
     *
     * @param genotype The genotype of interest
     * @param stratifiedContexts A mapping 
     * @param vc
     * @return
     */
    private int[] getCounts(final Genotype genotype,
                            final Map<String, AlignmentContext> stratifiedContexts,
                            final VariantContext vc){

        // Can't do anything without a genotype here
        if(genotype == null)
            return null;

        int[] retVal = genotype.getAD();
        AlignmentContext context;

        if ( retVal == null && stratifiedContexts != null &&
                (context = stratifiedContexts.get(genotype.getSampleName())) != null){
            // If we get to this point, the getAD() function returned no information
            // about AlleleDepth by Sample - perhaps it wasn't annotated?
            // In that case, let's try to build it up using the algorithm that
            // was here in v 3.1-1 and earlier
            // Also, b/c of the assignment check in the if statement above,
            // we know we have a valid AlignmentContext for this sample!

            final ReadBackedPileup pileup = context.getBasePileup();
            final String bases = new String(pileup.getBases());
            List<Allele> alleles = vc.getAlleles();
            final int n_allele = alleles.size();
            retVal = new int[n_allele];

            // Calculate the depth for each allele, under the assumption that
            // the allele is a single base
            int i=0;
            for(Allele a : alleles){
                retVal[i] = MathUtils.countOccurrences(a.toString().charAt(0), bases);
                i++;
            }

        }

        return retVal;

    }

    public List<String> getKeyNames() { return Arrays.asList("ABHet","ABHom","OND"); }

    public List<VCFInfoHeaderLine> getDescriptions() { return Arrays.asList(new VCFInfoHeaderLine("ABHet", 1, VCFHeaderLineType.Float, "Allele Balance for heterozygous calls (ref/(ref+alt))"),
            new VCFInfoHeaderLine("ABHom", 1, VCFHeaderLineType.Float, "Allele Balance for homozygous calls (A/(A+O)) where A is the allele (ref or alt) and O is anything other"),
            new VCFInfoHeaderLine("OND", 1, VCFHeaderLineType.Float, "Overall non-diploid ratio (alleles/(alleles+non-alleles))")); }
}