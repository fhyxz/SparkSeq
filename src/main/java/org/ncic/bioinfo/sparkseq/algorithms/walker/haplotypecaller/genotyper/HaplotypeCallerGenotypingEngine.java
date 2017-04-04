package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.genotyper;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GATKVariantContextUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLoc;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLocParser;
import org.ncic.bioinfo.sparkseq.algorithms.utils.Utils;
import org.ncic.bioinfo.sparkseq.algorithms.data.basic.Pair;
import org.ncic.bioinfo.sparkseq.algorithms.utils.haplotype.EventMap;
import org.ncic.bioinfo.sparkseq.algorithms.utils.haplotype.Haplotype;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefMetaDataTracker;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.ActiveRegionFinder;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.AlleleList;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.GenotypingData;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.GenotypingLikelihoods;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.GenotypingOutputMode;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.IndexedAlleleList;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.OutputMode;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PloidyModel;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.readlikelihood.ReadLikelihoods;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.SampleList;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.afcalculate.AFCalculatorProvider;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.argcollection.HaplotypeCallerArgumentCollection;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.model.GenotypeLikelihoodsCalculationModel;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.model.GenotypingModel;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.model.HomogeneousPloidyModel;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.model.ReferenceConfidenceMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Author: wbc
 */
public class HaplotypeCallerGenotypingEngine extends GenotypingEngine<HaplotypeCallerArgumentCollection> {

    private static final int ALLELE_EXTENSION = 2;
    private static final String phase01 = "0|1";
    private static final String phase10 = "1|0";

    private MergeVariantsAcrossHaplotypes crossHaplotypeEventMerger;

    private final boolean doPhysicalPhasing;

    private final GenotypingModel genotypingModel;

    private final PloidyModel ploidyModel;

    /**
     * {@inheritDoc}
     * @param configuration {@inheritDoc}
     * @param samples {@inheritDoc}
     * @param genomeLocParser {@inheritDoc}
     * @param doPhysicalPhasing whether to try physical phasing.
     */
    public HaplotypeCallerGenotypingEngine(final HaplotypeCallerArgumentCollection configuration, final SampleList samples, final GenomeLocParser genomeLocParser, final AFCalculatorProvider afCalculatorProvider, final boolean doPhysicalPhasing) {
        super(configuration,samples,genomeLocParser,afCalculatorProvider);
        if (genomeLocParser == null)
            throw new IllegalArgumentException("the genome location parser provided cannot be null");
        this.doPhysicalPhasing= doPhysicalPhasing;
        ploidyModel = new HomogeneousPloidyModel(samples,configuration.genotypeArgs.samplePloidy);
        genotypingModel = new InfiniteRandomMatingPopulationModel();
    }

    /**
     * Change the merge variant across haplotypes for this engine.
     *
     * @param crossHaplotypeEventMerger new merger, can be {@code null}.
     */
    public void setCrossHaplotypeEventMerger(final MergeVariantsAcrossHaplotypes crossHaplotypeEventMerger) {
        this.crossHaplotypeEventMerger = crossHaplotypeEventMerger;
    }

    @Override
    protected String callSourceString() {
        return "HC_call";
    }

    @Override
    protected boolean forceKeepAllele(final Allele allele) {
        return allele == GATKVariantContextUtils.NON_REF_SYMBOLIC_ALLELE ||
                configuration.genotypingOutputMode == GenotypingOutputMode.GENOTYPE_GIVEN_ALLELES ||
                configuration.emitReferenceConfidence != ReferenceConfidenceMode.NONE;
    }

    @Override
    protected boolean forceSiteEmission() {
        return configuration.outputMode == OutputMode.EMIT_ALL_SITES || configuration.genotypingOutputMode == GenotypingOutputMode.GENOTYPE_GIVEN_ALLELES;
    }

    /**
     * Carries the result of a call to #assignGenotypeLikelihoods
     */
    public static class CalledHaplotypes {
        private final List<VariantContext> calls;
        private final Set<Haplotype> calledHaplotypes;

        protected CalledHaplotypes(final List<VariantContext> calls, final Set<Haplotype> calledHaplotypes) {
            if ( calls == null ) throw new IllegalArgumentException("calls cannot be null");
            if ( calledHaplotypes == null ) throw new IllegalArgumentException("calledHaplotypes cannot be null");
            if ( Utils.xor(calls.isEmpty(), calledHaplotypes.isEmpty()) )
                throw new IllegalArgumentException("Calls and calledHaplotypes should both be empty or both not but got calls=" + calls + " calledHaplotypes=" + calledHaplotypes);
            this.calls = calls;
            this.calledHaplotypes = calledHaplotypes;
        }

        /**
         * Get the list of calls made at this location
         * @return a non-null (but potentially empty) list of calls
         */
        public List<VariantContext> getCalls() {
            return calls;
        }

        /**
         * Get the set of haplotypes that we actually called (i.e., underlying one of the VCs in getCalls().
         * @return a non-null set of haplotypes
         */
        public Set<Haplotype> getCalledHaplotypes() {
            return calledHaplotypes;
        }
    }

    /**
     * Main entry point of class - given a particular set of haplotypes, samples and reference context, compute
     * genotype likelihoods and assemble into a list of variant contexts and genomic events ready for calling
     *
     * The list of samples we're working with is obtained from the readLikelihoods
     *
     * @param haplotypes                             Haplotypes to assign likelihoods to
     * @param readLikelihoods                       Map from reads->(haplotypes,likelihoods)
     * @param perSampleFilteredReadList              Map from sample to reads that were filtered after assembly and before calculating per-read likelihoods.
     * @param ref                                    Reference bytes at active region
     * @param refLoc                                 Corresponding active region genome location
     * @param activeRegionWindow                     Active window
     * @param genomeLocParser                        GenomeLocParser
     * @param activeAllelesToGenotype                Alleles to genotype
     * @param emitReferenceConfidence whether we should add a &lt;NON_REF&gt; alternative allele to the result variation contexts.
     *
     * @return                                       A CalledHaplotypes object containing a list of VC's with genotyped events and called haplotypes
     *
     */
    // TODO - can this be refactored? this is hard to follow!
    public CalledHaplotypes assignGenotypeLikelihoods( final List<Haplotype> haplotypes,
                                                       final ReadLikelihoods<Haplotype> readLikelihoods,
                                                       final Map<String, List<GATKSAMRecord>> perSampleFilteredReadList,
                                                       final byte[] ref,
                                                       final GenomeLoc refLoc,
                                                       final GenomeLoc activeRegionWindow,
                                                       final GenomeLocParser genomeLocParser,
                                                       final RefMetaDataTracker tracker,
                                                       final List<VariantContext> activeAllelesToGenotype,
                                                       final boolean emitReferenceConfidence) {
        // sanity check input arguments
        if (haplotypes == null || haplotypes.isEmpty()) throw new IllegalArgumentException("haplotypes input should be non-empty and non-null, got "+haplotypes);
        if (readLikelihoods == null || readLikelihoods.sampleCount() == 0) throw new IllegalArgumentException("readLikelihoods input should be non-empty and non-null, got "+readLikelihoods);
        if (ref == null || ref.length == 0 ) throw new IllegalArgumentException("ref bytes input should be non-empty and non-null, got " + Arrays.toString(ref));
        if (refLoc == null || refLoc.size() != ref.length) throw new IllegalArgumentException(" refLoc must be non-null and length must match ref bytes, got "+refLoc);
        if (activeRegionWindow == null ) throw new IllegalArgumentException("activeRegionWindow must be non-null");
        if (activeAllelesToGenotype == null ) throw new IllegalArgumentException("activeAllelesToGenotype must be non-null");
        if (genomeLocParser == null ) throw new IllegalArgumentException("genomeLocParser must be non-null");

        // update the haplotypes so we're ready to call, getting the ordered list of positions on the reference
        // that carry events among the haplotypes
        final TreeSet<Integer> startPosKeySet = decomposeHaplotypesIntoVariantContexts(haplotypes, readLikelihoods, ref, refLoc, activeAllelesToGenotype);

        // Walk along each position in the key set and create each event to be outputted
        final Set<Haplotype> calledHaplotypes = new HashSet<>();
        final List<VariantContext> returnCalls = new ArrayList<>();
        final int ploidy = configuration.genotypeArgs.samplePloidy;
        final List<Allele> noCallAlleles = GATKVariantContextUtils.noCallAlleles(ploidy);

        for( final int loc : startPosKeySet ) {
            if( loc >= activeRegionWindow.getStart() && loc <= activeRegionWindow.getStop() ) { // genotyping an event inside this active region
                final List<VariantContext> eventsAtThisLoc = getVCsAtThisLocation(haplotypes, loc, activeAllelesToGenotype);

                if( eventsAtThisLoc.isEmpty() ) { continue; }

                // Create the event mapping object which maps the original haplotype events to the events present at just this locus
                final Map<Event, List<Haplotype>> eventMapper = createEventMapper(loc, eventsAtThisLoc, haplotypes);

                // Sanity check the priority list for mistakes
                final List<String> priorityList = makePriorityList(eventsAtThisLoc);

                // Merge the event to find a common reference representation

                VariantContext mergedVC = GATKVariantContextUtils.simpleMerge(eventsAtThisLoc, priorityList,
                        GATKVariantContextUtils.FilteredRecordMergeType.KEEP_IF_ANY_UNFILTERED,
                        GATKVariantContextUtils.GenotypeMergeType.PRIORITIZE, false, false, null, false, false);

                if( mergedVC == null )
                    continue;



                final GenotypeLikelihoodsCalculationModel.Model calculationModel = mergedVC.isSNP()
                        ? GenotypeLikelihoodsCalculationModel.Model.SNP : GenotypeLikelihoodsCalculationModel.Model.INDEL;

                if (emitReferenceConfidence)
                    mergedVC = addNonRefSymbolicAllele(mergedVC);

                final Map<VariantContext, Allele> mergeMap = new LinkedHashMap<>();
                mergeMap.put(null, mergedVC.getReference()); // the reference event (null) --> the reference allele
                for(int iii = 0; iii < eventsAtThisLoc.size(); iii++) {
                    mergeMap.put(eventsAtThisLoc.get(iii), mergedVC.getAlternateAllele(iii)); // BUGBUG: This is assuming that the order of alleles is the same as the priority list given to simpleMerge function
                }

                final Map<Allele, List<Haplotype>> alleleMapper = createAlleleMapper(mergeMap, eventMapper);

                if( configuration.DEBUG && logger != null ) {
                    if (logger != null) logger.info("Genotyping event at " + loc + " with alleles = " + mergedVC.getAlleles());
                }

                ReadLikelihoods<Allele> readAlleleLikelihoods = readLikelihoods.marginalize(alleleMapper, genomeLocParser.createPaddedGenomeLoc(genomeLocParser.createGenomeLoc(mergedVC), ALLELE_EXTENSION));
                if (configuration.isSampleContaminationPresent())
                    readAlleleLikelihoods.contaminationDownsampling(configuration.getSampleContamination());

                if (emitReferenceConfidence)
                    readAlleleLikelihoods.addNonReferenceAllele(GATKVariantContextUtils.NON_REF_SYMBOLIC_ALLELE);

                final GenotypesContext genotypes = calculateGLsForThisEvent( readAlleleLikelihoods, mergedVC, noCallAlleles );
                final VariantContext call = calculateGenotypes(new VariantContextBuilder(mergedVC).genotypes(genotypes).make(), calculationModel);
                if( call != null ) {

                    readAlleleLikelihoods = prepareReadAlleleLikelihoodsForAnnotation(readLikelihoods, perSampleFilteredReadList,
                            genomeLocParser, emitReferenceConfidence, alleleMapper, readAlleleLikelihoods, call);

                    VariantContext annotatedCall = annotationEngine.annotateContextForActiveRegion(tracker,readAlleleLikelihoods, call);

                    if( call.getAlleles().size() != mergedVC.getAlleles().size() )
                        annotatedCall = GATKVariantContextUtils.reverseTrimAlleles(annotatedCall);

                    // maintain the set of all called haplotypes
                    for ( final Allele calledAllele : call.getAlleles() ) {
                        final List<Haplotype> haplotypeList = alleleMapper.get(calledAllele);
                        if (haplotypeList == null) continue;
                        calledHaplotypes.addAll(haplotypeList);
                    }

                    returnCalls.add( annotatedCall );
                }
            }
        }

        final List<VariantContext> phasedCalls = doPhysicalPhasing ? phaseCalls(returnCalls, calledHaplotypes) : returnCalls;
        return new CalledHaplotypes(phasedCalls, calledHaplotypes);
    }

    /**
     * Tries to phase the individual alleles based on pairwise comparisons to the other alleles based on all called haplotypes
     *
     * @param calls             the list of called alleles
     * @param calledHaplotypes  the set of haplotypes used for calling
     * @return a non-null list which represents the possibly phased version of the calls
     */
    protected List<VariantContext> phaseCalls(final List<VariantContext> calls, final Set<Haplotype> calledHaplotypes) {

        // construct a mapping from alternate allele to the set of haplotypes that contain that allele
        final Map<VariantContext, Set<Haplotype>> haplotypeMap = constructHaplotypeMapping(calls, calledHaplotypes);

        // construct a mapping from call to phase set ID
        final Map<VariantContext, Pair<Integer, String>> phaseSetMapping = new HashMap<>();
        final int uniqueCounterEndValue = constructPhaseSetMapping(calls, haplotypeMap, calledHaplotypes.size() - 1, phaseSetMapping);

        // we want to establish (potential) *groups* of phased variants, so we need to be smart when looking at pairwise phasing partners
        return constructPhaseGroups(calls, phaseSetMapping, uniqueCounterEndValue);
    }

    /**
     * Construct the mapping from alternate allele to the set of haplotypes that contain that allele
     *
     * @param originalCalls    the original unphased calls
     * @param calledHaplotypes  the set of haplotypes used for calling
     * @return non-null Map
     */
    protected static Map<VariantContext, Set<Haplotype>> constructHaplotypeMapping(final List<VariantContext> originalCalls,
                                                                                   final Set<Haplotype> calledHaplotypes) {
        final Map<VariantContext, Set<Haplotype>> haplotypeMap = new HashMap<>(originalCalls.size());
        for ( final VariantContext call : originalCalls ) {
            // don't try to phase if there is not exactly 1 alternate allele
            if ( ! isBiallelic(call) ) {
                haplotypeMap.put(call, Collections.<Haplotype>emptySet());
                continue;
            }

            // keep track of the haplotypes that contain this particular alternate allele
            final Set<Haplotype> hapsWithAllele = new HashSet<>();
            final Allele alt = call.getAlternateAllele(0);

            for ( final Haplotype h : calledHaplotypes ) {
                for ( final VariantContext event : h.getEventMap().getVariantContexts() ) {
                    if ( event.getStart() == call.getStart() && event.getAlternateAlleles().contains(alt) )
                        hapsWithAllele.add(h);
                }
            }
            haplotypeMap.put(call, hapsWithAllele);
        }

        return haplotypeMap;
    }


    /**
     * Construct the mapping from call (variant context) to phase set ID
     *
     * @param originalCalls    the original unphased calls
     * @param haplotypeMap     mapping from alternate allele to the set of haplotypes that contain that allele
     * @param totalAvailableHaplotypes the total number of possible haplotypes used in calling
     * @param phaseSetMapping  the map to populate in this method;
     *                         note that it is okay for this method NOT to populate the phaseSetMapping at all (e.g. in an impossible-to-phase situation)
     * @return the next incremental unique index
     */
    protected static int constructPhaseSetMapping(final List<VariantContext> originalCalls,
                                                  final Map<VariantContext, Set<Haplotype>> haplotypeMap,
                                                  final int totalAvailableHaplotypes,
                                                  final Map<VariantContext, Pair<Integer, String>> phaseSetMapping) {

        final int numCalls = originalCalls.size();
        int uniqueCounter = 0;

        // use the haplotype mapping to connect variants that are always/never present on the same haplotypes
        for ( int i = 0; i < numCalls - 1; i++ ) {
            final VariantContext call = originalCalls.get(i);
            final Set<Haplotype> haplotypesWithCall = haplotypeMap.get(call);
            if ( haplotypesWithCall.isEmpty() )
                continue;

            final boolean callIsOnAllHaps = haplotypesWithCall.size() == totalAvailableHaplotypes;

            for ( int j = i+1; j < numCalls; j++ ) {
                final VariantContext comp = originalCalls.get(j);
                final Set<Haplotype> haplotypesWithComp = haplotypeMap.get(comp);
                if ( haplotypesWithComp.isEmpty() )
                    continue;

                // if the variants are together on all haplotypes, record that fact.
                // another possibility is that one of the variants is on all possible haplotypes (i.e. it is homozygous).
                final boolean compIsOnAllHaps = haplotypesWithComp.size() == totalAvailableHaplotypes;
                if ( (haplotypesWithCall.size() == haplotypesWithComp.size() && haplotypesWithCall.containsAll(haplotypesWithComp)) || callIsOnAllHaps || compIsOnAllHaps ) {

                    // create a new group if these are the first entries
                    if ( ! phaseSetMapping.containsKey(call) ) {
                        // note that if the comp is already in the map then that is very bad because it means that there is
                        // another variant that is in phase with the comp but not with the call.  Since that's an un-phasable
                        // situation, we should abort if we encounter it.
                        if ( phaseSetMapping.containsKey(comp) ) {
                            phaseSetMapping.clear();
                            return 0;
                        }

                        // An important note: even for homozygous variants we are setting the phase as "0|1" here.
                        // We do this because we cannot possibly know for sure at this time that the genotype for this
                        // sample will actually be homozygous downstream: there are steps in the pipeline that are liable
                        // to change the genotypes.  Because we can't make those assumptions here, we have decided to output
                        // the phase as if the call is heterozygous and then "fix" it downstream as needed.
                        phaseSetMapping.put(call, new Pair<>(uniqueCounter, phase01));
                        phaseSetMapping.put(comp, new Pair<>(uniqueCounter, phase01));
                        uniqueCounter++;
                    }
                    // otherwise it's part of an existing group so use that group's unique ID
                    else if ( ! phaseSetMapping.containsKey(comp) ) {
                        final Pair<Integer, String> callPhase = phaseSetMapping.get(call);
                        phaseSetMapping.put(comp, new Pair<>(callPhase.first, callPhase.second));
                    }
                }
                // if the variants are apart on *all* haplotypes, record that fact
                else if ( haplotypesWithCall.size() + haplotypesWithComp.size() == totalAvailableHaplotypes ) {

                    final Set<Haplotype> intersection = new HashSet<>();
                    intersection.addAll(haplotypesWithCall);
                    intersection.retainAll(haplotypesWithComp);
                    if ( intersection.isEmpty() ) {
                        // create a new group if these are the first entries
                        if ( ! phaseSetMapping.containsKey(call) ) {
                            // note that if the comp is already in the map then that is very bad because it means that there is
                            // another variant that is in phase with the comp but not with the call.  Since that's an un-phasable
                            // situation, we should abort if we encounter it.
                            if ( phaseSetMapping.containsKey(comp) ) {
                                phaseSetMapping.clear();
                                return 0;
                            }

                            phaseSetMapping.put(call, new Pair<>(uniqueCounter, phase01));
                            phaseSetMapping.put(comp, new Pair<>(uniqueCounter, phase10));
                            uniqueCounter++;
                        }
                        // otherwise it's part of an existing group so use that group's unique ID
                        else if ( ! phaseSetMapping.containsKey(comp) ){
                            final Pair<Integer, String> callPhase = phaseSetMapping.get(call);
                            phaseSetMapping.put(comp, new Pair<>(callPhase.first, callPhase.second.equals(phase01) ? phase10 : phase01));
                        }
                    }
                }
            }
        }

        return uniqueCounter;
    }

    /**
     * Assemble the phase groups together and update the original calls accordingly
     *
     * @param originalCalls    the original unphased calls
     * @param phaseSetMapping  mapping from call (variant context) to phase group ID
     * @param indexTo          last index (exclusive) of phase group IDs
     * @return a non-null list which represents the possibly phased version of the calls
     */
    protected static List<VariantContext> constructPhaseGroups(final List<VariantContext> originalCalls,
                                                               final Map<VariantContext, Pair<Integer, String>> phaseSetMapping,
                                                               final int indexTo) {
        final List<VariantContext> phasedCalls = new ArrayList<>(originalCalls);

        // if we managed to find any phased groups, update the VariantContexts
        for ( int count = 0; count < indexTo; count++ ) {
            // get all of the (indexes of the) calls that belong in this group (keeping them in the original order)
            final List<Integer> indexes = new ArrayList<>();
            for ( int index = 0; index < originalCalls.size(); index++ ) {
                final VariantContext call = originalCalls.get(index);
                if ( phaseSetMapping.containsKey(call) && phaseSetMapping.get(call).first == count )
                    indexes.add(index);
            }
            if ( indexes.size() < 2 )
                throw new IllegalStateException("Somehow we have a group of phased variants that has fewer than 2 members");

            // create a unique ID based on the leftmost one
            final String uniqueID = createUniqueID(originalCalls.get(indexes.get(0)));

            // update the VCs
            for ( final int index : indexes ) {
                final VariantContext originalCall = originalCalls.get(index);
                final VariantContext phasedCall = phaseVC(originalCall, uniqueID, phaseSetMapping.get(originalCall).second);
                phasedCalls.set(index, phasedCall);
            }
        }

        return phasedCalls;
    }

    /**
     * Is this variant bi-allelic?  This implementation is very much specific to this class so shouldn't be pulled out into a generalized place.
     *
     * @param vc the variant context
     * @return true if this variant context is bi-allelic, ignoring the NON-REF symbolic allele, false otherwise
     */
    private static boolean isBiallelic(final VariantContext vc) {
        return vc.isBiallelic() || (vc.getNAlleles() == 3 && vc.getAlternateAlleles().contains(GATKVariantContextUtils.NON_REF_SYMBOLIC_ALLELE));
    }

    /**
     * Create a unique identifier given the variant context
     *
     * @param vc   the variant context
     * @return non-null String
     */
    private static String createUniqueID(final VariantContext vc) {
        return String.format("%d_%s_%s", vc.getStart(), vc.getReference().getDisplayString(), vc.getAlternateAllele(0).getDisplayString());
        // return base + "_0," + base + "_1";
    }

    /**
     * Add physical phase information to the provided variant context
     *
     * @param vc   the variant context
     * @param ID   the ID to use
     * @param phaseGT the phase GT string to use
     * @return phased non-null variant context
     */
    private static VariantContext phaseVC(final VariantContext vc, final String ID, final String phaseGT) {
        final List<Genotype> phasedGenotypes = new ArrayList<>();
        for ( final Genotype g : vc.getGenotypes() )
            phasedGenotypes.add(new GenotypeBuilder(g).attribute(ActiveRegionFinder.HAPLOTYPE_CALLER_PHASING_ID_KEY, ID).attribute(ActiveRegionFinder.HAPLOTYPE_CALLER_PHASING_GT_KEY, phaseGT).make());
        return new VariantContextBuilder(vc).genotypes(phasedGenotypes).make();
    }

    private VariantContext addNonRefSymbolicAllele(final VariantContext mergedVC) {
        final VariantContextBuilder vcb = new VariantContextBuilder(mergedVC);
        final List<Allele> originalList = mergedVC.getAlleles();
        final List<Allele> alleleList = new ArrayList<>(originalList.size() + 1);
        alleleList.addAll(mergedVC.getAlleles());
        alleleList.add(GATKVariantContextUtils.NON_REF_SYMBOLIC_ALLELE);
        vcb.alleles(alleleList);
        return vcb.make();
    }

    // Builds the read-likelihoods collection to use for annotation considering user arguments and the collection
    // used for genotyping.
    private ReadLikelihoods<Allele> prepareReadAlleleLikelihoodsForAnnotation(
            final ReadLikelihoods<Haplotype> readHaplotypeLikelihoods,
            final Map<String, List<GATKSAMRecord>> perSampleFilteredReadList,
            final GenomeLocParser genomeLocParser,
            final boolean emitReferenceConfidence,
            final Map<Allele, List<Haplotype>> alleleMapper,
            final ReadLikelihoods<Allele> readAlleleLikelihoodsForGenotyping,
            final VariantContext call) {

        final ReadLikelihoods<Allele> readAlleleLikelihoodsForAnnotations;
        final GenomeLoc loc = genomeLocParser.createGenomeLoc(call);

        // We can reuse for annotation the likelihood for genotyping as long as there is no contamination filtering
        // or the user want to use the contamination filtered set for annotations.
        // Otherwise (else part) we need to do it again.
        if (configuration.USE_FILTERED_READ_MAP_FOR_ANNOTATIONS || !configuration.isSampleContaminationPresent()) {
            readAlleleLikelihoodsForAnnotations = readAlleleLikelihoodsForGenotyping;
            readAlleleLikelihoodsForAnnotations.filterToOnlyOverlappingUnclippedReads(loc);
        } else {
            readAlleleLikelihoodsForAnnotations = readHaplotypeLikelihoods.marginalize(alleleMapper, loc);
            if (emitReferenceConfidence)
                readAlleleLikelihoodsForAnnotations.addNonReferenceAllele(
                        GATKVariantContextUtils.NON_REF_SYMBOLIC_ALLELE);
        }

        // Skim the filtered map based on the location so that we do not add filtered read that are going to be removed
        // right after a few lines of code bellow.
        final Map<String, List<GATKSAMRecord>> overlappingFilteredReads = overlappingFilteredReads(perSampleFilteredReadList, loc);

        readAlleleLikelihoodsForAnnotations.addReads(overlappingFilteredReads,0);

        return readAlleleLikelihoodsForAnnotations;
    }


    private Map<String, List<GATKSAMRecord>> overlappingFilteredReads(final Map<String, List<GATKSAMRecord>> perSampleFilteredReadList, final GenomeLoc loc) {
        final Map<String,List<GATKSAMRecord>> overlappingFilteredReads = new HashMap<>(perSampleFilteredReadList.size());

        for (final Map.Entry<String,List<GATKSAMRecord>> sampleEntry : perSampleFilteredReadList.entrySet()) {
            final List<GATKSAMRecord> originalList = sampleEntry.getValue();
            final String sample = sampleEntry.getKey();
            if (originalList == null || originalList.size() == 0)
                continue;
            final List<GATKSAMRecord> newList = new ArrayList<>(originalList.size());
            for (final GATKSAMRecord read : originalList) {
                if (ReadLikelihoods.unclippedReadOverlapsRegion(read, loc))
                    newList.add(read);
            }
            if (newList.size() == 0)
                continue;
            overlappingFilteredReads.put(sample,newList);
        }
        return overlappingFilteredReads;
    }

    /**
     * Go through the haplotypes we assembled, and decompose them into their constituent variant contexts
     *
     * @param haplotypes the list of haplotypes we're working with
     * @param readLikelihoods map from samples -> the per read allele likelihoods
     * @param ref the reference bases (over the same interval as the haplotypes)
     * @param refLoc the span of the reference bases
     * @param activeAllelesToGenotype alleles we want to ensure are scheduled for genotyping (GGA mode)
     * @return never {@code null} but perhaps an empty list if there is no variants to report.
     */
    private TreeSet<Integer> decomposeHaplotypesIntoVariantContexts(final List<Haplotype> haplotypes,
                                                                    final ReadLikelihoods readLikelihoods,
                                                                    final byte[] ref,
                                                                    final GenomeLoc refLoc,
                                                                    final List<VariantContext> activeAllelesToGenotype) {
        final boolean in_GGA_mode = !activeAllelesToGenotype.isEmpty();

        // Using the cigar from each called haplotype figure out what events need to be written out in a VCF file
        final TreeSet<Integer> startPosKeySet = EventMap.buildEventMapsForHaplotypes(haplotypes, ref, refLoc, configuration.DEBUG);

        if ( !in_GGA_mode ) {
            // run the event merger if we're not in GGA mode
            if (crossHaplotypeEventMerger == null)
                throw new IllegalStateException(" no variant merger was provided at set-up when needed in GGA mode");
            final boolean mergedAnything = crossHaplotypeEventMerger.merge(haplotypes, readLikelihoods, startPosKeySet, ref, refLoc);
            if ( mergedAnything )
                cleanUpSymbolicUnassembledEvents( haplotypes ); // the newly created merged events could be overlapping the unassembled events
        } else {
            startPosKeySet.clear();
            for( final VariantContext compVC : activeAllelesToGenotype ) {
                startPosKeySet.add( compVC.getStart() );
            }
        }

        return startPosKeySet;
    }

    /**
     * Get the priority list (just the list of sources for these variant context) used to merge overlapping events into common reference view
     * @param vcs a list of variant contexts
     * @return the list of the sources of vcs in the same order
     */
    private List<String> makePriorityList(final List<VariantContext> vcs) {
        final List<String> priorityList = new LinkedList<>();
        for ( final VariantContext vc : vcs ) priorityList.add(vc.getSource());
        return priorityList;
    }

    private List<VariantContext> getVCsAtThisLocation(final List<Haplotype> haplotypes,
                                                      final int loc,
                                                      final List<VariantContext> activeAllelesToGenotype) {
        // the overlapping events to merge into a common reference view
        final List<VariantContext> eventsAtThisLoc = new ArrayList<>();

        if( activeAllelesToGenotype.isEmpty() ) {
            for( final Haplotype h : haplotypes ) {
                final EventMap eventMap = h.getEventMap();
                final VariantContext vc = eventMap.get(loc);
                if( vc != null && !containsVCWithMatchingAlleles(eventsAtThisLoc, vc) ) {
                    eventsAtThisLoc.add(vc);
                }
            }
        } else { // we are in GGA mode!
            int compCount = 0;
            for( final VariantContext compVC : activeAllelesToGenotype ) {
                if( compVC.getStart() == loc ) {
                    int alleleCount = 0;
                    for( final Allele compAltAllele : compVC.getAlternateAlleles() ) {
                        List<Allele> alleleSet = new ArrayList<>(2);
                        alleleSet.add(compVC.getReference());
                        alleleSet.add(compAltAllele);
                        final String vcSourceName = "Comp" + compCount + "Allele" + alleleCount;
                        // check if this event is already in the list of events due to a repeat in the input alleles track
                        final VariantContext candidateEventToAdd = new VariantContextBuilder(compVC).alleles(alleleSet).source(vcSourceName).make();
                        boolean alreadyExists = false;
                        for( final VariantContext eventToTest : eventsAtThisLoc ) {
                            if( eventToTest.hasSameAllelesAs(candidateEventToAdd) ) {
                                alreadyExists = true;
                            }
                        }
                        if( !alreadyExists ) {
                            eventsAtThisLoc.add(candidateEventToAdd);
                        }
                        alleleCount++;
                    }
                }
                compCount++;
            }
        }

        return eventsAtThisLoc;
    }

    /**
     * For a particular event described in inputVC, form PL vector for each sample by looking into allele read map and filling likelihood matrix for each allele
     * @param readLikelihoods          Allele map describing mapping from reads to alleles and corresponding likelihoods
     * @param mergedVC               Input VC with event to genotype
     * @return                       GenotypesContext object wrapping genotype objects with PLs
     */
    private GenotypesContext calculateGLsForThisEvent( final ReadLikelihoods<Allele> readLikelihoods, final VariantContext mergedVC, final List<Allele> noCallAlleles ) {
        final List<Allele> vcAlleles = mergedVC.getAlleles();
        final AlleleList<Allele> alleleList = readLikelihoods.alleleCount() == vcAlleles.size() ? readLikelihoods : new IndexedAlleleList<>(vcAlleles);
        final GenotypingLikelihoods<Allele> likelihoods = genotypingModel.calculateLikelihoods(alleleList,new GenotypingData<>(ploidyModel,readLikelihoods));
        final int sampleCount = samples.sampleCount();
        final GenotypesContext result = GenotypesContext.create(sampleCount);
        for (int s = 0; s < sampleCount; s++)
            result.add(new GenotypeBuilder(samples.sampleAt(s)).alleles(noCallAlleles).PL(likelihoods.sampleLikelihoods(s).getAsPLs()).make());
        return result;
    }

    /**
     * Removes symbolic events from list of haplotypes
     * @param haplotypes       Input/output list of haplotypes, before/after removal
     */
    // TODO - split into input haplotypes and output haplotypes as not to share I/O arguments
    protected static void cleanUpSymbolicUnassembledEvents( final List<Haplotype> haplotypes ) {
        final List<Haplotype> haplotypesToRemove = new ArrayList<>();
        for( final Haplotype h : haplotypes ) {
            for( final VariantContext vc : h.getEventMap().getVariantContexts() ) {
                if( vc.isSymbolic() ) {
                    for( final Haplotype h2 : haplotypes ) {
                        for( final VariantContext vc2 : h2.getEventMap().getVariantContexts() ) {
                            if( vc.getStart() == vc2.getStart() && (vc2.isIndel() || vc2.isMNP()) ) { // unfortunately symbolic alleles can't currently be combined with non-point events
                                haplotypesToRemove.add(h);
                                break;
                            }
                        }
                    }
                }
            }
        }
        haplotypes.removeAll(haplotypesToRemove);
    }

    protected static Map<Allele, List<Haplotype>> createAlleleMapper( final Map<VariantContext, Allele> mergeMap, final Map<Event, List<Haplotype>> eventMap ) {
        final Map<Allele, List<Haplotype>> alleleMapper = new LinkedHashMap<>();
        for( final Map.Entry<VariantContext, Allele> entry : mergeMap.entrySet() ) {
            alleleMapper.put(entry.getValue(), eventMap.get(new Event(entry.getKey())));
        }
        return alleleMapper;
    }

    protected static Map<Event, List<Haplotype>> createEventMapper( final int loc, final List<VariantContext> eventsAtThisLoc, final List<Haplotype> haplotypes) {

        final Map<Event, List<Haplotype>> eventMapper = new LinkedHashMap<>(eventsAtThisLoc.size()+1);
        final Event refEvent = new Event(null);
        eventMapper.put(refEvent, new ArrayList<Haplotype>());
        for( final VariantContext vc : eventsAtThisLoc ) {
            eventMapper.put(new Event(vc), new ArrayList<Haplotype>());
        }

        for( final Haplotype h : haplotypes ) {
            if( h.getEventMap().get(loc) == null ) {
                eventMapper.get(refEvent).add(h);
            } else {
                for( final VariantContext vcAtThisLoc : eventsAtThisLoc ) {
                    if( h.getEventMap().get(loc).hasSameAllelesAs(vcAtThisLoc) ) {
                        eventMapper.get(new Event(vcAtThisLoc)).add(h);
                        break;
                    }
                }
            }
        }

        return eventMapper;
    }

    @Deprecated
    protected static Map<Integer,VariantContext> generateVCsFromAlignment(final Haplotype haplotype, final byte[] ref, final GenomeLoc refLoc, final String sourceNameToAdd ) {
        return new EventMap(haplotype, ref, refLoc, sourceNameToAdd);
    }

    protected static boolean containsVCWithMatchingAlleles( final List<VariantContext> list, final VariantContext vcToTest ) {
        for( final VariantContext vc : list ) {
            if( vc.hasSameAllelesAs(vcToTest) ) {
                return true;
            }
        }
        return false;
    }

    protected static class Event {
        public VariantContext vc;

        public Event( final VariantContext vc ) {
            this.vc = vc;
        }

        @Override
        public boolean equals( final Object obj ) {
            return obj instanceof Event && ((((Event) obj).vc == null && vc == null) || (((Event) obj).vc != null && vc != null && ((Event) obj).vc.hasSameAllelesAs(vc))) ;
        }

        @Override
        public int hashCode() {
            return (vc == null ? -1 : vc.getAlleles().hashCode());
        }
    }

    /**
     * Returns the ploidy-model used by this genotyping engine.
     *
     * @return never {@code null}.
     */
    public PloidyModel getPloidyModel() {
        return ploidyModel;
    }

    /**
     * Returns the genotyping-model used by this genotyping engine.
     *
     * @return never {@code null}.
     */
    public GenotypingModel getGenotypingModel() {
        return genotypingModel;
    }
}