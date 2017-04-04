package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.model;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import org.apache.log4j.Logger;
import org.ncic.bioinfo.sparkseq.algorithms.utils.AlignmentContextUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GATKVariantContextUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLoc;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLocParser;
import org.ncic.bioinfo.sparkseq.algorithms.utils.MathUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.basic.Pair;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefMetaDataTracker;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.AlignmentContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.ReadBackedPileup;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.GeneralPloidyGenotypeLikelihoods;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PerReadAlleleLikelihoodMap;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.argcollection.UnifiedArgumentCollection;
import org.ncic.bioinfo.sparkseq.exceptions.ReviewedGATKException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Author: wbc
 */
public abstract class GeneralPloidyGenotypeLikelihoodsCalculationModel extends GenotypeLikelihoodsCalculationModel {

    final protected UnifiedArgumentCollection UAC;

    protected GeneralPloidyGenotypeLikelihoodsCalculationModel(UnifiedArgumentCollection UAC, Logger logger) {
        super(UAC,logger);
        this.UAC = UAC;

    }


    /*
       Get vc with alleles from reference sample. Can be null if there's no ref sample call or no ref sample coverage at this site.
    */
    protected VariantContext getTrueAlleles(final RefMetaDataTracker tracker,
                                            final ReferenceContext ref,
                                            Map<String,AlignmentContext> contexts) {
        // Get reference base from VCF or Reference
        if (UAC.referenceSampleName == null)
            return null;

        AlignmentContext context = contexts.get(UAC.referenceSampleName);
        ArrayList<Allele> trueReferenceAlleles = new ArrayList<Allele>();

        VariantContext referenceSampleVC;

        if (tracker != null && context != null)
            referenceSampleVC = tracker.getFirstValue(UAC.referenceSampleRod, context.getLocation());
        else
            return null;

        if (referenceSampleVC == null) {
            trueReferenceAlleles.add(Allele.create(ref.getBase(),true));
            return new VariantContextBuilder("pc",ref.getLocus().getContig(), ref.getLocus().getStart(), ref.getLocus().getStop(),trueReferenceAlleles).make();

        }
        else {
            Genotype referenceGenotype = referenceSampleVC.getGenotype(UAC.referenceSampleName);
            List<Allele> referenceAlleles = referenceGenotype.getAlleles();

            return new VariantContextBuilder("pc",referenceSampleVC.getChr(), referenceSampleVC.getStart(), referenceSampleVC.getEnd(),
                    referenceSampleVC.getAlleles())
                    .genotypes(new GenotypeBuilder(UAC.referenceSampleName, referenceAlleles).GQ(referenceGenotype.getGQ()).make())
                    .make();
        }
    }


    /**
     * GATK Engine creates readgroups of the form XXX.Y.Z
     * XXX.Y is the unique lane identifier.
     *     Z is the id of the sample to make the read group id unique
     * This function returns the list of lane identifiers.
     *
     * @param readGroups readGroups A collection of read group strings (obtained from the alignment context pileup)
     * @return a collection of lane ids.
     */
    public static Set<String> parseLaneIDs(Collection<String> readGroups) {
        HashSet<String> result = new HashSet<String>();
        for (String readGroup : readGroups) {
            result.add(getLaneIDFromReadGroupString(readGroup));
        }
        return result;
    }

    /**
     * GATK Engine creates readgroups of the form XXX.Y.Z
     * XXX.Y is the unique lane identifier.
     *     Z is the id of the sample to make the read group id unique
     *
     * @param readGroupID the read group id string
     * @return just the lane id (the XXX.Y string)
     */
    public static String getLaneIDFromReadGroupString(String readGroupID) {
        // System.out.println(readGroupID);
        String [] parsedID = readGroupID.split("\\.");
        if (parsedID.length > 1)
            return parsedID[0] + "." + parsedID[1];
        else
            return parsedID[0] + ".0";
    }


    /** Wrapper class that encapsulates likelihood object and sample name
     *
     */
    protected static class PoolGenotypeData {

        public final String name;
        public final GeneralPloidyGenotypeLikelihoods GL;
        public final int depth;
        public final List<Allele> alleles;

        public PoolGenotypeData(final String name, final GeneralPloidyGenotypeLikelihoods GL, final int depth, final List<Allele> alleles) {
            this.name = name;
            this.GL = GL;
            this.depth = depth;
            this.alleles = alleles;
        }
    }

    // determines the alleles to use
    protected List<Allele> determineAlternateAlleles(final List<PoolGenotypeData> sampleDataList) {

        if (sampleDataList.isEmpty())
            return Collections.emptyList();

        final int REFERENCE_IDX = 0;
        final List<Allele> allAlleles = sampleDataList.get(0).GL.getAlleles();
        double[] likelihoodSums = new double[allAlleles.size()];

        // based on the GLs, find the alternate alleles with enough probability
        for ( PoolGenotypeData sampleData : sampleDataList ) {
            final Pair<int[],Double> mlACPair = sampleData.GL.getMostLikelyACCount();
            final double topLogGL = mlACPair.second;

            if (sampleData.GL.getAlleles().size() != allAlleles.size())
                throw new ReviewedGATKException("BUG: inconsistent size of alleles!");

            // ref allele is always first in array list
            if (sampleData.GL.alleles.get(0).isNonReference())
                throw new ReviewedGATKException("BUG: first allele in list is not reference!");

            double refGL = sampleData.GL.getLikelihoods()[REFERENCE_IDX];

            // check if maximum likelihood AC is all-ref for current pool. If so, skip
            if (mlACPair.first[REFERENCE_IDX] == sampleData.GL.numChromosomes)
                continue;

            // most likely AC is not all-ref: for all non-ref alleles, add difference of max likelihood and all-ref likelihood
            for (int i=0; i < mlACPair.first.length; i++) {
                if (i==REFERENCE_IDX) continue;

                if (mlACPair.first[i] > 0)
                    likelihoodSums[i] += topLogGL - refGL;

            }
        }

        final List<Allele> allelesToUse = new ArrayList<Allele>();
        for ( int i = 0; i < likelihoodSums.length; i++ ) {
            if ( likelihoodSums[i] > 0.0 )
                allelesToUse.add(allAlleles.get(i));
        }

        return allelesToUse;
    }

    public static final String REFSAMPLE_DEPTH_KEY = "REFDEPTH";

    public VariantContext getLikelihoods(final RefMetaDataTracker tracker,
                                         final ReferenceContext ref,
                                         Map<String, AlignmentContext> contexts,
                                         final AlignmentContextUtils.ReadOrientation contextType,
                                         final List<Allele> allAllelesToUse,
                                         final boolean useBAQedPileup,
                                         final GenomeLocParser locParser,
                                         final Map<String, PerReadAlleleLikelihoodMap> perReadAlleleLikelihoodMap) {

        HashMap<String, ErrorModel> perLaneErrorModels = getPerLaneErrorModels(tracker, ref, contexts);
        if (perLaneErrorModels == null && UAC.referenceSampleName != null)
            return null;

        if (UAC.TREAT_ALL_READS_AS_SINGLE_POOL) {
            AlignmentContext mergedContext = AlignmentContextUtils.joinContexts(contexts.values());
            Map<String,AlignmentContext> newContext = new HashMap<String,AlignmentContext>();
            newContext.put(DUMMY_SAMPLE_NAME,mergedContext);
            contexts = newContext;
        }
        if (contextType == AlignmentContextUtils.ReadOrientation.COMPLETE) {
            // starting a new site: clear allele list
            perReadAlleleLikelihoodMap.clear(); // clean mapping sample-> per read, per allele likelihoods
        }
        // get initial alleles to genotype
        final List<Allele> allAlleles = new ArrayList<Allele>();
        if (allAllelesToUse == null || allAllelesToUse.isEmpty())
            allAlleles.addAll(getInitialAllelesToUse(tracker, ref,contexts,contextType,locParser, allAllelesToUse));
        else
            allAlleles.addAll(allAllelesToUse);

        if (allAlleles.isEmpty())
            return null;

        final ArrayList<PoolGenotypeData> GLs = new ArrayList<PoolGenotypeData>(contexts.size());

        for ( Map.Entry<String, AlignmentContext> sample : contexts.entrySet() ) {
            // skip reference sample
            if (UAC.referenceSampleName != null && sample.getKey().equals(UAC.referenceSampleName))
                continue;

            ReadBackedPileup pileup = AlignmentContextUtils.stratify(sample.getValue(), contextType).getBasePileup();
            if (!perReadAlleleLikelihoodMap.containsKey(sample.getKey())){
                // no likelihoods have been computed for this sample at this site
                perReadAlleleLikelihoodMap.put(sample.getKey(), new PerReadAlleleLikelihoodMap());
            }

            // create the GenotypeLikelihoods object
            final GeneralPloidyGenotypeLikelihoods GL = getPoolGenotypeLikelihoodObject(allAlleles, null, UAC.genotypeArgs.samplePloidy, perLaneErrorModels, useBAQedPileup, ref, UAC.IGNORE_LANE_INFO, perReadAlleleLikelihoodMap.get(sample.getKey()));
            // actually compute likelihoods
            final int nGoodBases = GL.add(pileup, UAC);
            if ( nGoodBases > 0 )
                // create wrapper object for likelihoods and add to list
                GLs.add(new PoolGenotypeData(sample.getKey(), GL, getFilteredDepth(pileup), allAlleles));
        }

        // find the alternate allele(s) that we should be using
        final List<Allele> alleles = getFinalAllelesToUse(tracker, ref, allAllelesToUse, GLs);
        if (alleles == null || alleles.isEmpty() || (alleles.size() == 1 && alleles.get(0).isReference()))
            return null;
        // start making the VariantContext
        final GenomeLoc loc = ref.getLocus();
        final int endLoc = getEndLocation(tracker, ref, alleles);

        final VariantContextBuilder builder = new VariantContextBuilder("UG_call", loc.getContig(), loc.getStart(), endLoc, alleles);
        builder.alleles(alleles);

        final HashMap<String, Object> attributes = new HashMap<String, Object>();

        if (UAC.referenceSampleName != null && perLaneErrorModels != null)
            attributes.put(REFSAMPLE_DEPTH_KEY, ErrorModel.getTotalReferenceDepth(perLaneErrorModels));

        builder.attributes(attributes);
        // create the genotypes; no-call everyone for now
        final GenotypesContext genotypes = GenotypesContext.create();
        final int ploidy = UAC.genotypeArgs.samplePloidy;
        final List<Allele> noCall = GATKVariantContextUtils.noCallAlleles(ploidy);

        for ( PoolGenotypeData sampleData : GLs ) {
            // extract from multidimensional array
            final double[] myLikelihoods = GeneralPloidyGenotypeLikelihoods.subsetToAlleles(sampleData.GL.getLikelihoods(), sampleData.GL.numChromosomes,
                    allAlleles, alleles);

            // normalize in log space so that max element is zero.
            final GenotypeBuilder gb = new GenotypeBuilder(sampleData.name, noCall);
            gb.DP(sampleData.depth);
            gb.PL(MathUtils.normalizeFromLog10(myLikelihoods, false, true));
            genotypes.add(gb.make());
        }

        return builder.genotypes(genotypes).make();

    }


    protected HashMap<String, ErrorModel> getPerLaneErrorModels(final RefMetaDataTracker tracker,
                                                                final ReferenceContext ref,
                                                                Map<String, AlignmentContext> contexts) {
        VariantContext refVC =  getTrueAlleles(tracker, ref, contexts);


        // Build error model for site based on reference sample, and keep stratified for each lane.
        AlignmentContext refContext = null;
        if (UAC.referenceSampleName != null)
            refContext = contexts.get(UAC.referenceSampleName);

        ReadBackedPileup refPileup = null;
        if (refContext != null) {
            HashMap<String, ErrorModel> perLaneErrorModels = new HashMap<String, ErrorModel>();
            refPileup = refContext.getBasePileup();

            Set<String> laneIDs = new TreeSet<String>();
            if (UAC.TREAT_ALL_READS_AS_SINGLE_POOL || UAC.IGNORE_LANE_INFO)
                laneIDs.add(DUMMY_LANE);
            else
                laneIDs = parseLaneIDs(refPileup.getReadGroups());
            // build per-lane error model for all lanes present in ref sample
            for (String laneID : laneIDs) {
                // get reference pileup for this lane
                ReadBackedPileup refLanePileup = refPileup;
                // subset for this lane
                if (refPileup != null && !(UAC.TREAT_ALL_READS_AS_SINGLE_POOL || UAC.IGNORE_LANE_INFO))
                    refLanePileup = refPileup.getPileupForLane(laneID);

                //ReferenceSample referenceSample = new ReferenceSample(UAC.referenceSampleName, refLanePileup, trueReferenceAlleles);
                perLaneErrorModels.put(laneID, new ErrorModel(UAC,  refLanePileup, refVC, ref));
            }
            return perLaneErrorModels;

        }
        else
            return null;

    }

    /*
       Abstract methods - must be implemented in derived classes
    */

    protected abstract GeneralPloidyGenotypeLikelihoods getPoolGenotypeLikelihoodObject(final List<Allele> alleles,
                                                                                        final double[] logLikelihoods,
                                                                                        final int ploidy,
                                                                                        final HashMap<String, ErrorModel> perLaneErrorModels,
                                                                                        final boolean useBQAedPileup,
                                                                                        final ReferenceContext ref,
                                                                                        final boolean ignoreLaneInformation,
                                                                                        final PerReadAlleleLikelihoodMap perReadAlleleLikelihoodMap);

    protected abstract List<Allele> getInitialAllelesToUse(final RefMetaDataTracker tracker,
                                                           final ReferenceContext ref,
                                                           Map<String, AlignmentContext> contexts,
                                                           final AlignmentContextUtils.ReadOrientation contextType,
                                                           final GenomeLocParser locParser,
                                                           final List<Allele> allAllelesToUse);

    protected abstract List<Allele> getFinalAllelesToUse(final RefMetaDataTracker tracker,
                                                         final ReferenceContext ref,
                                                         final List<Allele> allAllelesToUse,
                                                         final ArrayList<PoolGenotypeData> GLs);

    protected abstract int getEndLocation(final RefMetaDataTracker tracker,
                                          final ReferenceContext ref,
                                          final List<Allele> alternateAllelesToUse);
}

