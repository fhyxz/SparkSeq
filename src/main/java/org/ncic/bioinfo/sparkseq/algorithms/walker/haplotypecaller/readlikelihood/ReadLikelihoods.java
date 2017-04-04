package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.readlikelihood;

import htsjdk.variant.variantcontext.Allele;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.ncic.bioinfo.sparkseq.algorithms.utils.AlleleListUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GATKVariantContextUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLoc;
import org.ncic.bioinfo.sparkseq.algorithms.utils.SampleListUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.Utils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.downsampling.AlleleBiasedDownsamplingUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.AlleleList;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.IndexedAlleleList;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.IndexedSampleList;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PerReadAlleleLikelihoodMap;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.SampleList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Author: wbc
 */
public class ReadLikelihoods<A extends Allele> implements SampleList, AlleleList<A>, Cloneable {

    /**
     * Reads by sample index. Each sub array contains reference to the reads of the ith sample.
     */
    private GATKSAMRecord[][] readsBySampleIndex;

    /**
     * Indexed per sample, allele and finally read (within sample).
     * <p>
     * valuesBySampleIndex[s][a][r] == lnLk(R_r | A_a) where R_r comes from Sample s.
     * </p>
     */
    private double[][][] valuesBySampleIndex;

    /**
     * Sample list
     */
    private final SampleList samples;

    /**
     * Allele list
     */
    private AlleleList<A> alleles;

    /**
     * Cached allele list.
     */
    private List<A> alleleList;

    /**
     * Cached sample list.
     */
    private List<String> sampleList;

    /**
     * Maps from each read to its index within the sample.
     * <p>
     * <p>In order to save CPU time the indices contained in this array (not the array itself) is
     * lazily initialized by invoking {@link #readIndexBySampleIndex(int)}.</p>
     */
    private final Object2IntMap<GATKSAMRecord>[] readIndexBySampleIndex;

    /**
     * Index of the reference allele if any, otherwise -1
     */
    private int referenceAlleleIndex = -1;

    /**
     * Caches the read-list per sample list returned by {@link #sampleReads}
     */
    private final List<GATKSAMRecord>[] readListBySampleIndex;

    /**
     * Sample matrices lazily initialized (the elements not the array) by invoking {@link #sampleMatrix(int)}.
     */
    private final Matrix<A>[] sampleMatrices;

    /**
     * Constructs a new read-likelihood collection.
     * <p>
     * <p>
     * The initial likelihoods for all allele-read combinations are
     * 0.
     * </p>
     *
     * @param samples all supported samples in the collection.
     * @param alleles all supported alleles in the collection.
     * @param reads   reads stratified per sample.
     * @throws IllegalArgumentException if any of {@code allele}, {@code samples}
     *                                  or {@code reads} is {@code null},
     *                                  or if they contain null values.
     */
    @SuppressWarnings("unchecked")
    public ReadLikelihoods(final SampleList samples, final AlleleList<A> alleles,
                           final Map<String, List<GATKSAMRecord>> reads, boolean useCache) {
        if (alleles == null)
            throw new IllegalArgumentException("allele list cannot be null");
        if (samples == null)
            throw new IllegalArgumentException("sample list cannot be null");
        if (reads == null)
            throw new IllegalArgumentException("read map cannot be null");

        this.samples = samples;
        this.alleles = alleles;

        final int sampleCount = samples.sampleCount();
        final int alleleCount = alleles.alleleCount();

        readsBySampleIndex = new GATKSAMRecord[sampleCount][];
        readListBySampleIndex = new List[sampleCount];
        valuesBySampleIndex = new double[sampleCount][][];
        referenceAlleleIndex = findReferenceAllele(alleles);

        readIndexBySampleIndex = new Object2IntMap[sampleCount];

        setupIndexes(reads, sampleCount, alleleCount, useCache);

        sampleMatrices = (Matrix<A>[]) new Matrix[sampleCount];
    }

    // Add all the indices to alleles, sample and reads in the look-up maps.
    private void setupIndexes(final Map<String, List<GATKSAMRecord>> reads, final int sampleCount, final int alleleCount, boolean useCache) {
        for (int i = 0; i < sampleCount; i++)
            setupSampleData(i, reads, alleleCount, useCache);
    }

    private ReadLikelihoodsArrayCache readLikelihoodsArrayCache = new ReadLikelihoodsArrayCache();
    // Assumes that {@link #samples} has been initialized with the sample names.
    private void setupSampleData(final int sampleIndex, final Map<String, List<GATKSAMRecord>> readsBySample,
                                 final int alleleCount, boolean useCache) {
        final String sample = samples.sampleAt(sampleIndex);

        final List<GATKSAMRecord> reads = readsBySample.get(sample);
        readsBySampleIndex[sampleIndex] = reads == null
                ? new GATKSAMRecord[0]
                : reads.toArray(new GATKSAMRecord[reads.size()]);
        final int sampleReadCount = readsBySampleIndex[sampleIndex].length;

        double[][] sampleValues = null;
        if(useCache){
            sampleValues = readLikelihoodsArrayCache.getDoubleArray(alleleCount, sampleReadCount);
        }else {
            sampleValues = new double[alleleCount][sampleReadCount];
        }
        valuesBySampleIndex[sampleIndex] = sampleValues;
    }

    /**
     * Create an independent copy of this read-likelihoods collection
     */
    public ReadLikelihoods<A> clone() {

        final int sampleCount = samples.sampleCount();
        final int alleleCount = alleles.alleleCount();

        final double[][][] newLikelihoodValues = new double[sampleCount][alleleCount][];

        @SuppressWarnings("unchecked")
        final Object2IntMap<GATKSAMRecord>[] newReadIndexBySampleIndex = new Object2IntMap[sampleCount];
        final GATKSAMRecord[][] newReadsBySampleIndex = new GATKSAMRecord[sampleCount][];

        for (int s = 0; s < sampleCount; s++) {
            newReadsBySampleIndex[s] = readsBySampleIndex[s].clone();
            for (int a = 0; a < alleleCount; a++)
                newLikelihoodValues[s][a] = valuesBySampleIndex[s][a].clone();
        }

        // Finally we create the new read-likelihood
        return new ReadLikelihoods<>(alleles, samples,
                newReadsBySampleIndex,
                newReadIndexBySampleIndex, newLikelihoodValues);
    }

    // Internally used constructor.
    @SuppressWarnings("unchecked")
    private ReadLikelihoods(final AlleleList alleles, final SampleList samples,
                            final GATKSAMRecord[][] readsBySampleIndex, final Object2IntMap<GATKSAMRecord>[] readIndex,
                            final double[][][] values) {
        this.samples = samples;
        this.alleles = alleles;
        this.readsBySampleIndex = readsBySampleIndex;
        this.valuesBySampleIndex = values;
        this.readIndexBySampleIndex = readIndex;
        final int sampleCount = samples.sampleCount();
        this.readListBySampleIndex = new List[sampleCount];

        referenceAlleleIndex = findReferenceAllele(alleles);
        sampleMatrices = (Matrix<A>[]) new Matrix[sampleCount];
    }

    // Search for the reference allele, if not found the index is -1.
    private int findReferenceAllele(final AlleleList<A> alleles) {
        final int alleleCount = alleles.alleleCount();
        for (int i = 0; i < alleleCount; i++)
            if (alleles.alleleAt(i).isReference())
                return i;
        return -1;
    }

    /**
     * Returns the index of a sample within the likelihood collection.
     *
     * @param sample the query sample.
     * @return -1 if the allele is not included, 0 or greater otherwise.
     * @throws IllegalArgumentException if {@code sample} is {@code null}.
     */
    public int sampleIndex(final String sample) {
        return samples.sampleIndex(sample);
    }

    /**
     * Number of samples included in the likelihood collection.
     *
     * @return 0 or greater.
     */
    public int sampleCount() {
        return samples.sampleCount();
    }

    /**
     * Returns sample name given its index.
     *
     * @param sampleIndex query index.
     * @return never {@code null}.
     * @throws IllegalArgumentException if {@code sampleIndex} is negative.
     */
    public String sampleAt(final int sampleIndex) {
        return samples.sampleAt(sampleIndex);
    }

    /**
     * Returns the index of an allele within the likelihood collection.
     *
     * @param allele the query allele.
     * @return -1 if the allele is not included, 0 or greater otherwise.
     * @throws IllegalArgumentException if {@code allele} is {@code null}.
     */
    public int alleleIndex(final A allele) {
        return alleles.alleleIndex(allele);
    }

    /**
     * Returns number of alleles in the collection.
     *
     * @return 0 or greater.
     */
    @SuppressWarnings("unused")
    public int alleleCount() {
        return alleles.alleleCount();
    }

    /**
     * Returns the allele given its index.
     *
     * @param alleleIndex the allele index.
     * @return never {@code null}.
     * @throws IllegalArgumentException the allele index is {@code null}.
     */
    public A alleleAt(final int alleleIndex) {
        return alleles.alleleAt(alleleIndex);
    }

    /**
     * Returns the reads that belong to a sample sorted by their index (within that sample).
     *
     * @param sampleIndex the requested sample.
     * @return never {@code null} but perhaps a zero-length array if there is no reads in sample. No element in
     * the array will be null.
     */
    public List<GATKSAMRecord> sampleReads(final int sampleIndex) {
        checkSampleIndex(sampleIndex);
        final List<GATKSAMRecord> extantList = readListBySampleIndex[sampleIndex];
        if (extantList == null)
            return readListBySampleIndex[sampleIndex] = Collections.unmodifiableList(Arrays.asList(readsBySampleIndex[sampleIndex]));
        else
            return extantList;
    }

    /**
     * Returns a read vs allele likelihood matrix corresponding to a sample.
     *
     * @param sampleIndex target sample.
     * @return never {@code null}
     * @throws IllegalArgumentException if {@code sampleIndex} is not null.
     */
    public Matrix<A> sampleMatrix(final int sampleIndex) {
        checkSampleIndex(sampleIndex);
        final Matrix<A> extantResult = sampleMatrices[sampleIndex];
        if (extantResult != null)
            return extantResult;
        else
            return sampleMatrices[sampleIndex] = new SampleMatrix(sampleIndex);
    }

    /**
     * Adjusts likelihoods so that for each read, the best allele likelihood is 0 and caps the minimum likelihood
     * of any allele for each read based on the maximum alternative allele likelihood.
     *
     * @param bestToZero                     set the best likelihood to 0, others will be subtracted the same amount.
     * @param maximumLikelihoodDifferenceCap maximum difference between the best alternative allele likelihood
     *                                       and any other likelihood.
     * @throws IllegalArgumentException if {@code maximumDifferenceWithBestAlternative} is not 0 or less.
     */
    public void normalizeLikelihoods(final boolean bestToZero, final double maximumLikelihoodDifferenceCap) {
        if (maximumLikelihoodDifferenceCap >= 0.0 || Double.isNaN(maximumLikelihoodDifferenceCap))
            throw new IllegalArgumentException("the minimum reference likelihood fall cannot be positive");

        if (maximumLikelihoodDifferenceCap == Double.NEGATIVE_INFINITY && !bestToZero)
            return;

        final int alleleCount = alleles.alleleCount();
        if (alleleCount == 0) // trivial case there is no alleles.
            return;
        else if (alleleCount == 1 && !bestToZero)
            return;

        for (int s = 0; s < valuesBySampleIndex.length; s++) {
            final double[][] sampleValues = valuesBySampleIndex[s];
            final int readCount = readsBySampleIndex[s].length;
            for (int r = 0; r < readCount; r++)
                normalizeLikelihoodsPerRead(bestToZero, maximumLikelihoodDifferenceCap, sampleValues, s, r);
        }
    }

    // Does the normalizeLikelihoods job for each read.
    private void normalizeLikelihoodsPerRead(final boolean bestToZero, final double maximumBestAltLikelihoodDifference,
                                             final double[][] sampleValues, final int sampleIndex, final int readIndex) {

        final BestAllele bestAlternativeAllele = searchBestAllele(sampleIndex, readIndex, false);

        final double worstLikelihoodCap = bestAlternativeAllele.likelihood + maximumBestAltLikelihoodDifference;

        final double referenceLikelihood = referenceAlleleIndex == -1 ? Double.NEGATIVE_INFINITY :
                sampleValues[referenceAlleleIndex][readIndex];


        final double bestAbsoluteLikelihood = Math.max(bestAlternativeAllele.likelihood, referenceLikelihood);

        final int alleleCount = alleles.alleleCount();
        if (bestToZero) {
            if (bestAbsoluteLikelihood == Double.NEGATIVE_INFINITY)
                for (int a = 0; a < alleleCount; a++)
                    sampleValues[a][readIndex] = 0;
            else if (worstLikelihoodCap != Double.NEGATIVE_INFINITY)
                for (int a = 0; a < alleleCount; a++)
                    sampleValues[a][readIndex] = (sampleValues[a][readIndex] < worstLikelihoodCap ? worstLikelihoodCap : sampleValues[a][readIndex]) - bestAbsoluteLikelihood;
            else
                for (int a = 0; a < alleleCount; a++)
                    sampleValues[a][readIndex] -= bestAbsoluteLikelihood;
        } else  // else if (maximumReferenceLikelihoodFall != Double.NEGATIVE_INFINITY ) { //
            // Guarantee to be the case by enclosing code.
            for (int a = 0; a < alleleCount; a++)
                if (sampleValues[a][readIndex] < worstLikelihoodCap)
                    sampleValues[a][readIndex] = worstLikelihoodCap;
    }

    /**
     * Returns the samples in this read-likelihood collection.
     * <p>
     * Samples are sorted by their index in the collection.
     * </p>
     * <p>
     * <p>
     * The returned list is an unmodifiable view on the read-likelihoods sample list.
     * </p>
     *
     * @return never {@code null}.
     */
    public List<String> samples() {
        return sampleList == null ? sampleList = SampleListUtils.asList(samples) : sampleList;

    }

    /**
     * Returns the samples in this read-likelihood collection.
     * <p>
     * Samples are sorted by their index in the collection.
     * </p>
     * <p>
     * <p>
     * The returned list is an unmodifiable. It will not be updated if the collection
     * allele list changes.
     * </p>
     *
     * @return never {@code null}.
     */
    public List<A> alleles() {
        return alleleList == null ? alleleList = AlleleListUtils.asList(alleles) : alleleList;
    }


    /**
     * Search the best allele for a read.
     *
     * @param sampleIndex including sample index.
     * @param readIndex   target read index.
     * @return never {@code null}, but with {@link BestAllele#allele allele} == {@code null}
     * if non-could be found.
     */
    private BestAllele searchBestAllele(final int sampleIndex, final int readIndex, final boolean canBeReference) {
        final int alleleCount = alleles.alleleCount();
        if (alleleCount == 0 || (alleleCount == 1 && referenceAlleleIndex == 0 && !canBeReference))
            return new BestAllele(sampleIndex, readIndex, -1, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);

        final double[][] sampleValues = valuesBySampleIndex[sampleIndex];
        int bestAlleleIndex = canBeReference || referenceAlleleIndex != 0 ? 0 : 1;

        double bestLikelihood = sampleValues[bestAlleleIndex][readIndex];
        double secondBestLikelihood = Double.NEGATIVE_INFINITY;
        for (int a = bestAlleleIndex + 1; a < alleleCount; a++) {
            if (!canBeReference && referenceAlleleIndex == a)
                continue;
            final double candidateLikelihood = sampleValues[a][readIndex];
            if (candidateLikelihood > bestLikelihood) {
                bestAlleleIndex = a;
                secondBestLikelihood = bestLikelihood;
                bestLikelihood = candidateLikelihood;
            } else if (candidateLikelihood > secondBestLikelihood) {
                secondBestLikelihood = candidateLikelihood;
            }
        }
        return new BestAllele(sampleIndex, readIndex, bestAlleleIndex, bestLikelihood, secondBestLikelihood);
    }

    public void changeReads(final Map<GATKSAMRecord, GATKSAMRecord> readRealignments) {
        final int sampleCount = samples.sampleCount();
        for (int s = 0; s < sampleCount; s++) {
            final GATKSAMRecord[] sampleReads = readsBySampleIndex[s];
            final Object2IntMap<GATKSAMRecord> readIndex = readIndexBySampleIndex[s];
            final int sampleReadCount = sampleReads.length;
            for (int r = 0; r < sampleReadCount; r++) {
                final GATKSAMRecord read = sampleReads[r];
                final GATKSAMRecord replacement = readRealignments.get(read);
                if (replacement == null)
                    continue;
                sampleReads[r] = replacement;
                if (readIndex != null) {
                    readIndex.remove(read);
                    readIndex.put(replacement, r);
                }
            }
        }
    }

    /**
     * Likelihood matrix between a set of alleles and reads.
     *
     * @param <A> the allele-type.
     */
    public interface Matrix<A extends Allele> extends AlleleList<A> {

        /**
         * List of reads in the matrix sorted by their index therein.
         *
         * @return never {@code null}.
         */
        public List<GATKSAMRecord> reads();

        /**
         * List of alleles in the matrix sorted by their index in the collection.
         *
         * @return never {@code null}.
         */
        public List<A> alleles();

        /**
         * Set the likelihood of a read given an allele through their indices.
         *
         * @param alleleIndex the target allele index.
         * @param readIndex   the target read index.
         * @param value       new likelihood value for the target read give the target allele.
         * @throws IllegalArgumentException if {@code alleleIndex} or {@code readIndex}
         *                                  are not valid allele and read indices respectively.
         */
        public void set(final int alleleIndex, final int readIndex, final double value);

        /**
         * Returns the likelihood of a read given a haplotype.
         *
         * @param alleleIndex the index of the given haplotype.
         * @param readIndex   the index of the target read.
         * @return the requested likelihood, whatever value was provided using {@link #set(int, int, double) set}
         * or 0.0 if none was set.
         * @throws IllegalArgumentException if {@code alleleIndex} or {@code readIndex} is not a
         *                                  valid allele or read index respectively.
         */
        public double get(final int alleleIndex, final int readIndex);

        /**
         * Queries the index of an allele in the matrix.
         *
         * @param allele the target allele.
         * @return -1 if such allele does not exist, otherwise its index which 0 or greater.
         * @throws IllegalArgumentException if {@code allele} is {@code null}.
         */
        @SuppressWarnings("unused")
        public int alleleIndex(final A allele);

        /**
         * Queries the index of a read in the matrix.
         *
         * @param read the target read.
         * @return -1 if there is not such a read in the matrix, otherwise its index
         * which is 0 or greater.
         * @throws IllegalArgumentException if {@code read} is {@code null}.
         */
        @SuppressWarnings("unused")
        public int readIndex(final GATKSAMRecord read);

        /**
         * Number of allele in the matrix.
         *
         * @return never negative.
         */
        public int alleleCount();

        /**
         * Number of reads in the matrix.
         *
         * @return never negative.
         */
        public int readCount();

        /**
         * Returns the allele given its index.
         *
         * @param alleleIndex the target allele index.
         * @return never {@code null}.
         * @throws IllegalArgumentException if {@code alleleIndex} is not a valid allele index.
         */
        public A alleleAt(final int alleleIndex);

        /**
         * Returns the allele given its index.
         *
         * @param readIndex the target allele index.
         * @return never {@code null}.
         * @throws IllegalArgumentException if {@code readIndex} is not a valid read index.
         */
        public GATKSAMRecord readAt(final int readIndex);


        /**
         * Copies the likelihood of all the reads for a given allele into an array from a particular offset.
         *
         * @param alleleIndex the targeted allele
         * @param dest        the destination array.
         * @param offset      the copy offset within the destination allele
         */
        public void copyAlleleLikelihoods(final int alleleIndex, final double[] dest, final int offset);
    }

    /**
     * Perform marginalization from an allele set to another (smaller one) taking the maximum value
     * for each read in the original allele subset.
     *
     * @param newToOldAlleleMap map where the keys are the new alleles and the value list the original
     *                          alleles that correspond to the new one.
     * @return never {@code null}. The result will have the requested set of new alleles (keys in {@code newToOldAlleleMap}, and
     * the same set of samples and reads as the original.
     * @throws IllegalArgumentException is {@code newToOldAlleleMap} is {@code null} or contains {@code null} values,
     *                                  or its values contain reference to non-existing alleles in this read-likelihood collection. Also no new allele
     *                                  can have zero old alleles mapping nor two new alleles can make reference to the same old allele.
     */
    public <B extends Allele> ReadLikelihoods<B> marginalize(final Map<B, List<A>> newToOldAlleleMap) {

        if (newToOldAlleleMap == null)
            throw new IllegalArgumentException("the input allele mapping cannot be null");

        @SuppressWarnings("unchecked")
        final B[] newAlleles = newToOldAlleleMap.keySet().toArray((B[]) new Allele[newToOldAlleleMap.size()]);
        final int oldAlleleCount = alleles.alleleCount();
        final int newAlleleCount = newAlleles.length;

        // we get the index correspondence between new old -> new allele, -1 entries mean that the old
        // allele does not map to any new; supported but typically not the case.
        final int[] oldToNewAlleleIndexMap = oldToNewAlleleIndexMap(newToOldAlleleMap, newAlleles, oldAlleleCount, newAlleleCount);

        // We calculate the marginal likelihoods.

        final double[][][] newLikelihoodValues = marginalLikelihoods(oldAlleleCount, newAlleleCount, oldToNewAlleleIndexMap, null);

        final int sampleCount = samples.sampleCount();

        @SuppressWarnings("unchecked")
        final Object2IntMap<GATKSAMRecord>[] newReadIndexBySampleIndex = new Object2IntMap[sampleCount];
        final GATKSAMRecord[][] newReadsBySampleIndex = new GATKSAMRecord[sampleCount][];

        for (int s = 0; s < sampleCount; s++) {
            newReadsBySampleIndex[s] = readsBySampleIndex[s].clone();
        }

        // Finally we create the new read-likelihood
        return new ReadLikelihoods<>(new IndexedAlleleList(newAlleles), samples,
                newReadsBySampleIndex,
                newReadIndexBySampleIndex, newLikelihoodValues);
    }


    /**
     * Perform marginalization from an allele set to another (smaller one) taking the maximum value
     * for each read in the original allele subset.
     *
     * @param newToOldAlleleMap map where the keys are the new alleles and the value list the original
     *                          alleles that correspond to the new one.
     * @param overlap           if not {@code null}, only reads that overlap the location (with unclipping) will be present in
     *                          the output read-collection.
     * @return never {@code null}. The result will have the requested set of new alleles (keys in {@code newToOldAlleleMap}, and
     * the same set of samples and reads as the original.
     * @throws IllegalArgumentException is {@code newToOldAlleleMap} is {@code null} or contains {@code null} values,
     *                                  or its values contain reference to non-existing alleles in this read-likelihood collection. Also no new allele
     *                                  can have zero old alleles mapping nor two new alleles can make reference to the same old allele.
     */
    public <B extends Allele> ReadLikelihoods<B> marginalize(final Map<B, List<A>> newToOldAlleleMap, final GenomeLoc overlap) {

        if (overlap == null)
            return marginalize(newToOldAlleleMap);

        if (newToOldAlleleMap == null)
            throw new IllegalArgumentException("the input allele mapping cannot be null");

        @SuppressWarnings("unchecked")
        final B[] newAlleles = newToOldAlleleMap.keySet().toArray((B[]) new Allele[newToOldAlleleMap.size()]);
        final int oldAlleleCount = alleles.alleleCount();
        final int newAlleleCount = newAlleles.length;

        // we get the index correspondence between new old -> new allele, -1 entries mean that the old
        // allele does not map to any new; supported but typically not the case.
        final int[] oldToNewAlleleIndexMap = oldToNewAlleleIndexMap(newToOldAlleleMap, newAlleles, oldAlleleCount, newAlleleCount);

        final int[][] readsToKeep = overlappingReadIndicesBySampleIndex(overlap);
        // We calculate the marginal likelihoods.

        final double[][][] newLikelihoodValues = marginalLikelihoods(oldAlleleCount, newAlleleCount, oldToNewAlleleIndexMap, readsToKeep);

        final int sampleCount = samples.sampleCount();

        @SuppressWarnings("unchecked")
        final Object2IntMap<GATKSAMRecord>[] newReadIndexBySampleIndex = new Object2IntMap[sampleCount];
        final GATKSAMRecord[][] newReadsBySampleIndex = new GATKSAMRecord[sampleCount][];

        for (int s = 0; s < sampleCount; s++) {
            final int[] sampleReadsToKeep = readsToKeep[s];
            final GATKSAMRecord[] oldSampleReads = readsBySampleIndex[s];
            final int oldSampleReadCount = oldSampleReads.length;
            final int newSampleReadCount = sampleReadsToKeep.length;
            if (newSampleReadCount == oldSampleReadCount) {
                newReadsBySampleIndex[s] = oldSampleReads.clone();
            } else {
                newReadsBySampleIndex[s] = new GATKSAMRecord[newSampleReadCount];
                for (int i = 0; i < newSampleReadCount; i++)
                    newReadsBySampleIndex[s][i] = oldSampleReads[sampleReadsToKeep[i]];
            }
        }

        // Finally we create the new read-likelihood
        return new ReadLikelihoods<>(new IndexedAlleleList(newAlleles), samples,
                newReadsBySampleIndex,
                newReadIndexBySampleIndex, newLikelihoodValues);
    }

    private int[][] overlappingReadIndicesBySampleIndex(final GenomeLoc overlap) {
        if (overlap == null)
            return null;
        final int sampleCount = samples.sampleCount();
        final int[][] result = new int[sampleCount][];
        final IntArrayList buffer = new IntArrayList(200);
        final int referenceIndex = overlap.getContigIndex();
        final int overlapStart = overlap.getStart();
        final int overlapEnd = overlap.getStop();
        for (int s = 0; s < sampleCount; s++) {
            buffer.clear();
            final GATKSAMRecord[] sampleReads = readsBySampleIndex[s];
            final int sampleReadCount = sampleReads.length;
            buffer.ensureCapacity(sampleReadCount);
            for (int r = 0; r < sampleReadCount; r++)
                if (unclippedReadOverlapsRegion(sampleReads[r], referenceIndex, overlapStart, overlapEnd))
                    buffer.add(r);
            result[s] = buffer.toIntArray();
        }
        return result;
    }

    public static boolean unclippedReadOverlapsRegion(final GATKSAMRecord read, final GenomeLoc region) {
        return unclippedReadOverlapsRegion(read, region.getContigIndex(), region.getStart(), region.getStop());
    }

    private static boolean unclippedReadOverlapsRegion(final GATKSAMRecord sampleRead, final int referenceIndex, final int start, final int end) {
        final int readReference = sampleRead.getReferenceIndex();
        if (readReference != referenceIndex)
            return false;

        final int readStart = sampleRead.getUnclippedStart();
        if (readStart > end)
            return false;

        final int readEnd = sampleRead.getReadUnmappedFlag() ? sampleRead.getUnclippedEnd()
                : Math.max(sampleRead.getUnclippedEnd(), sampleRead.getUnclippedStart());
        return readEnd >= start;
    }

    // Calculate the marginal likelihoods considering the old -> new allele index mapping.
    private double[][][] marginalLikelihoods(final int oldAlleleCount, final int newAlleleCount, final int[] oldToNewAlleleIndexMap, final int[][] readsToKeep) {

        final int sampleCount = samples.sampleCount();
        final double[][][] result = new double[sampleCount][][];

        for (int s = 0; s < sampleCount; s++) {
            final int sampleReadCount = readsBySampleIndex[s].length;
            final double[][] oldSampleValues = valuesBySampleIndex[s];
            final int[] sampleReadToKeep = readsToKeep == null || readsToKeep[s].length == sampleReadCount ? null : readsToKeep[s];
            final int newSampleReadCount = sampleReadToKeep == null ? sampleReadCount : sampleReadToKeep.length;
            final double[][] newSampleValues = result[s] = new double[newAlleleCount][newSampleReadCount];
            // We initiate all likelihoods to -Inf.
            for (int a = 0; a < newAlleleCount; a++)
                Arrays.fill(newSampleValues[a], Double.NEGATIVE_INFINITY);
            // For each old allele and read we update the new table keeping the maximum likelihood.
            for (int r = 0; r < newSampleReadCount; r++) {
                for (int a = 0; a < oldAlleleCount; a++) {
                    final int oldReadIndex = newSampleReadCount == sampleReadCount ? r : sampleReadToKeep[r];
                    final int newAlleleIndex = oldToNewAlleleIndexMap[a];
                    if (newAlleleIndex == -1)
                        continue;
                    final double likelihood = oldSampleValues[a][oldReadIndex];
                    if (likelihood > newSampleValues[newAlleleIndex][r])
                        newSampleValues[newAlleleIndex][r] = likelihood;
                }
            }
        }
        return result;
    }

    // calculates an old to new allele index map array.
    private <B extends Allele> int[] oldToNewAlleleIndexMap(final Map<B, List<A>> newToOldAlleleMap, final B[] newAlleles,
                                                            final int oldAlleleCount, final int newAlleleCount) {

        final int[] oldToNewAlleleIndexMap = new int[oldAlleleCount];
        Arrays.fill(oldToNewAlleleIndexMap, -1);  // -1 indicate that there is no new allele that make reference to that old one.

        for (int i = 0; i < newAlleleCount; i++) {
            final B newAllele = newAlleles[i];
            if (newAllele == null)
                throw new IllegalArgumentException("input alleles cannot be null");
            final List<A> oldAlleles = newToOldAlleleMap.get(newAllele);
            if (oldAlleles == null)
                throw new IllegalArgumentException("no new allele list can be null");
            for (final A oldAllele : oldAlleles) {
                if (oldAllele == null)
                    throw new IllegalArgumentException("old alleles cannot be null");
                final int oldAlleleIndex = alleleIndex(oldAllele);
                if (oldAlleleIndex == -1)
                    throw new IllegalArgumentException("missing old allele " + oldAllele + " in likelihood collection ");
                if (oldToNewAlleleIndexMap[oldAlleleIndex] != -1)
                    throw new IllegalArgumentException("collision: two new alleles make reference to the same old allele");
                oldToNewAlleleIndexMap[oldAlleleIndex] = i;
            }
        }
        return oldToNewAlleleIndexMap;
    }

    /**
     * Remove those reads that do not overlap certain genomic location.
     * <p>
     * <p>
     * This method modifies the current read-likelihoods collection.
     * </p>
     *
     * @param location the target location.
     * @throws IllegalArgumentException the location cannot be {@code null} nor unmapped.
     */
    @SuppressWarnings("unused")
    public void filterToOnlyOverlappingUnclippedReads(final GenomeLoc location) {
        if (location == null)
            throw new IllegalArgumentException("the location cannot be null");
        if (location.isUnmapped())
            throw new IllegalArgumentException("the location cannot be unmapped");

        final int sampleCount = samples.sampleCount();

        final int locContig = location.getContigIndex();
        final int locStart = location.getStart();
        final int locEnd = location.getStop();

        final int alleleCount = alleles.alleleCount();
        final IntArrayList removeIndices = new IntArrayList(10);
        for (int s = 0; s < sampleCount; s++) {
            int readRemoveCount = 0;
            final GATKSAMRecord[] sampleReads = readsBySampleIndex[s];
            final int sampleReadCount = sampleReads.length;
            for (int r = 0; r < sampleReadCount; r++)
                if (!unclippedReadOverlapsRegion(sampleReads[r], locContig, locStart, locEnd))
                    removeIndices.add(r);
            removeSampleReads(s, removeIndices, alleleCount);
            removeIndices.clear();
        }
    }

    /**
     * Removes those read that the best possible likelihood given any allele is just too low.
     * <p>
     * <p>
     * This is determined by a maximum error per read-base against the best likelihood possible.
     * </p>
     *
     * @param maximumErrorPerBase the minimum acceptable error rate per read base, must be
     *                            a positive number.
     * @throws IllegalStateException    is not supported for read-likelihood that do not contain alleles.
     * @throws IllegalArgumentException if {@code maximumErrorPerBase} is negative.
     */
    public void filterPoorlyModeledReads(final double maximumErrorPerBase) {
        if (alleles.alleleCount() == 0)
            throw new IllegalStateException("unsupported for read-likelihood collections with no alleles");
        if (Double.isNaN(maximumErrorPerBase) || maximumErrorPerBase <= 0.0)
            throw new IllegalArgumentException("the maximum error per base must be a positive number");
        final int sampleCount = samples.sampleCount();

        final int alleleCount = alleles.alleleCount();
        final IntArrayList removeIndices = new IntArrayList(10);
        for (int s = 0; s < sampleCount; s++) {
            final GATKSAMRecord[] sampleReads = readsBySampleIndex[s];
            final int sampleReadCount = sampleReads.length;
            for (int r = 0; r < sampleReadCount; r++) {
                final GATKSAMRecord read = sampleReads[r];
                if (readIsPoorlyModelled(s, r, read, maximumErrorPerBase))
                    removeIndices.add(r);
            }
            removeSampleReads(s, removeIndices, alleleCount);
            removeIndices.clear();
        }
    }

    // Check whether the read is poorly modelled.
    protected boolean readIsPoorlyModelled(final int sampleIndex, final int readIndex, final GATKSAMRecord read, final double maxErrorRatePerBase) {
        final double maxErrorsForRead = Math.min(2.0, Math.ceil(read.getReadLength() * maxErrorRatePerBase));
        final double log10QualPerBase = -4.0;
        final double log10MaxLikelihoodForTrueAllele = maxErrorsForRead * log10QualPerBase;

        final int alleleCount = alleles.alleleCount();
        final double[][] sampleValues = valuesBySampleIndex[sampleIndex];
        for (int a = 0; a < alleleCount; a++)
            if (sampleValues[a][readIndex] >= log10MaxLikelihoodForTrueAllele)
                return false;
        return true;
    }

    /**
     * Add more reads to the collection.
     *
     * @param readsBySample     reads to add.
     * @param initialLikelihood the likelihood for the new entries.
     * @throws IllegalArgumentException if {@code readsBySample} is {@code null} or {@code readsBySample} contains
     *                                  {@code null} reads, or {@code readsBySample} contains read that are already present in the read-likelihood
     *                                  collection.
     */
    public void addReads(final Map<String, List<GATKSAMRecord>> readsBySample, final double initialLikelihood) {

        for (final Map.Entry<String, List<GATKSAMRecord>> entry : readsBySample.entrySet()) {

            final String sample = entry.getKey();
            final List<GATKSAMRecord> newSampleReads = entry.getValue();
            final int sampleIndex = samples.sampleIndex(sample);

            if (sampleIndex == -1)
                throw new IllegalArgumentException("input sample " + sample +
                        " is not part of the read-likelihoods collection");

            if (newSampleReads == null || newSampleReads.size() == 0)
                continue;

            final int sampleReadCount = readsBySampleIndex[sampleIndex].length;
            final int newSampleReadCount = sampleReadCount + newSampleReads.size();

            appendReads(newSampleReads, sampleIndex, sampleReadCount, newSampleReadCount);
            extendsLikelihoodArrays(initialLikelihood, sampleIndex, sampleReadCount, newSampleReadCount);
        }
    }

    // Extends the likelihood arrays-matrices.
    private void extendsLikelihoodArrays(double initialLikelihood, int sampleIndex, int sampleReadCount, int newSampleReadCount) {
        final double[][] sampleValues = valuesBySampleIndex[sampleIndex];
        final int alleleCount = alleles.alleleCount();
        for (int a = 0; a < alleleCount; a++)
            sampleValues[a] = Arrays.copyOf(sampleValues[a], newSampleReadCount);
        if (initialLikelihood != 0.0) // the default array new value.
            for (int a = 0; a < alleleCount; a++)
                Arrays.fill(sampleValues[a], sampleReadCount, newSampleReadCount, initialLikelihood);
    }

    // Append the new read reference into the structure per-sample.
    private void appendReads(final List<GATKSAMRecord> newSampleReads, final int sampleIndex,
                             final int sampleReadCount, final int newSampleReadCount) {
        final GATKSAMRecord[] sampleReads = readsBySampleIndex[sampleIndex] =
                Arrays.copyOf(readsBySampleIndex[sampleIndex], newSampleReadCount);

        int nextReadIndex = sampleReadCount;
        final Object2IntMap<GATKSAMRecord> sampleReadIndex = readIndexBySampleIndex[sampleIndex];
        for (final GATKSAMRecord newRead : newSampleReads) {
            //    if (sampleReadIndex.containsKey(newRead)) // might be worth handle this without exception (ignore the read?) but in practice should never be the case.
            //        throw new IllegalArgumentException("you cannot add reads that are already in read-likelihood collection");
            if (sampleReadIndex != null) sampleReadIndex.put(newRead, nextReadIndex);
            sampleReads[nextReadIndex++] = newRead;
        }
    }

    /**
     * Adds the non-reference allele to the read-likelihood collection setting each read likelihood to the second
     * best found (or best one if only one allele has likelihood).
     * <p>
     * <p>Nothing will happen if the read-likelihoods collection already includes the non-ref allele</p>
     * <p>
     * <p>
     * <i>Implementation note: even when strictly speaking we do not need to demand the calling code to pass
     * the reference the non-ref allele, we still demand it in order to lead the
     * the calling code to use the right generic type for this likelihoods
     * collection {@link Allele}.</i>
     * </p>
     *
     * @param nonRefAllele the non-ref allele.
     * @throws IllegalArgumentException if {@code nonRefAllele} is anything but the designated &lt;NON_REF&gt;
     *                                  symbolic allele {@link GATKVariantContextUtils#NON_REF_SYMBOLIC_ALLELE}.
     */
    public void addNonReferenceAllele(final A nonRefAllele) {

        if (nonRefAllele == null)
            throw new IllegalArgumentException("non-ref allele cannot be null");
        if (!nonRefAllele.equals(GATKVariantContextUtils.NON_REF_SYMBOLIC_ALLELE))
            throw new IllegalArgumentException("the non-ref allele is not valid");
        // Already present?
        if (alleles.alleleIndex(nonRefAllele) != -1)
            return;

        final int oldAlleleCount = alleles.alleleCount();
        final int newAlleleCount = oldAlleleCount + 1;
        @SuppressWarnings("unchecked")
        final A[] newAlleles = (A[]) new Allele[newAlleleCount];
        for (int a = 0; a < oldAlleleCount; a++)
            newAlleles[a] = alleles.alleleAt(a);
        newAlleles[oldAlleleCount] = nonRefAllele;
        alleles = new IndexedAlleleList<>(newAlleles);
        alleleList = null; // remove the cached alleleList.

        final int sampleCount = samples.sampleCount();
        for (int s = 0; s < sampleCount; s++)
            addNonReferenceAlleleLikelihoodsPerSample(oldAlleleCount, newAlleleCount, s);
    }

    // Updates per-sample structures according to the addition of the NON_REF allele.
    private void addNonReferenceAlleleLikelihoodsPerSample(final int alleleCount, final int newAlleleCount, final int sampleIndex) {
        final double[][] sampleValues = valuesBySampleIndex[sampleIndex] = Arrays.copyOf(valuesBySampleIndex[sampleIndex], newAlleleCount);
        final int sampleReadCount = readsBySampleIndex[sampleIndex].length;

        final double[] nonRefAlleleLikelihoods = sampleValues[alleleCount] = new double[sampleReadCount];
        Arrays.fill(nonRefAlleleLikelihoods, Double.NEGATIVE_INFINITY);
        for (int r = 0; r < sampleReadCount; r++) {
            final BestAllele bestAllele = searchBestAllele(sampleIndex, r, true);
            final double secondBestLikelihood = Double.isInfinite(bestAllele.confidence) ? bestAllele.likelihood
                    : bestAllele.likelihood - bestAllele.confidence;
            nonRefAlleleLikelihoods[r] = secondBestLikelihood;
        }
    }

    public void contaminationDownsampling(final Map<String, Double> perSampleDownsamplingFraction) {

        final int sampleCount = samples.sampleCount();
        final IntArrayList readsToRemove = new IntArrayList(10); // blind estimate, can be improved?
        final int alleleCount = alleles.alleleCount();
        for (int s = 0; s < sampleCount; s++) {
            final String sample = samples.sampleAt(s);
            final Double fractionDouble = perSampleDownsamplingFraction.get(sample);
            if (fractionDouble == null)
                continue;
            final double fraction = fractionDouble;
            if (Double.isNaN(fraction) || fraction <= 0.0)
                continue;
            if (fraction >= 1.0) {
                final int sampleReadCount = readsBySampleIndex[s].length;
                readsToRemove.ensureCapacity(sampleReadCount);
                for (int r = 0; r < sampleReadCount; r++)
                    readsToRemove.add(r);
                removeSampleReads(s, readsToRemove, alleleCount);
                readsToRemove.clear();
            } else {
                final Map<A, List<GATKSAMRecord>> readsByBestAllelesMap = readsByBestAlleleMap(s);
                removeSampleReads(s, AlleleBiasedDownsamplingUtils.selectAlleleBiasedReads(readsByBestAllelesMap, fraction), alleleCount);
            }
        }
    }

    /**
     * Returns the collection of best allele estimates for the reads based on the read-likelihoods.
     *
     * @return never {@code null}, one element per read in the read-likelihoods collection.
     * @throws IllegalStateException if there is no alleles.
     */
    public Collection<BestAllele> bestAlleles() {
        final List<BestAllele> result = new ArrayList<>(100); // blind estimate.
        final int sampleCount = samples.sampleCount();
        for (int s = 0; s < sampleCount; s++) {
            final GATKSAMRecord[] sampleReads = readsBySampleIndex[s];
            final int readCount = sampleReads.length;
            for (int r = 0; r < readCount; r++)
                result.add(searchBestAllele(s, r, true));
        }
        return result;
    }

    /**
     * Returns reads stratified by their best allele.
     *
     * @param sampleIndex the target sample.
     * @return never {@code null}, perhaps empty.
     */
    public Map<A, List<GATKSAMRecord>> readsByBestAlleleMap(final int sampleIndex) {
        checkSampleIndex(sampleIndex);
        final int alleleCount = alleles.alleleCount();
        final int sampleReadCount = readsBySampleIndex[sampleIndex].length;
        final Map<A, List<GATKSAMRecord>> result = new HashMap<>(alleleCount);
        for (int a = 0; a < alleleCount; a++)
            result.put(alleles.alleleAt(a), new ArrayList<GATKSAMRecord>(sampleReadCount));
        readsByBestAlleleMap(sampleIndex, result);
        return result;
    }

    /**
     * Returns reads stratified by their best allele.
     *
     * @return never {@code null}, perhaps empty.
     */
    @SuppressWarnings("unused")
    public Map<A, List<GATKSAMRecord>> readsByBestAlleleMap() {
        final int alleleCount = alleles.alleleCount();
        final Map<A, List<GATKSAMRecord>> result = new HashMap<>(alleleCount);
        final int totalReadCount = readCount();
        for (int a = 0; a < alleleCount; a++)
            result.put(alleles.alleleAt(a), new ArrayList<GATKSAMRecord>(totalReadCount));
        final int sampleCount = samples.sampleCount();
        for (int s = 0; s < sampleCount; s++)
            readsByBestAlleleMap(s, result);
        return result;
    }

    private void readsByBestAlleleMap(final int sampleIndex, final Map<A, List<GATKSAMRecord>> result) {
        final GATKSAMRecord[] reads = readsBySampleIndex[sampleIndex];
        final int readCount = reads.length;

        for (int r = 0; r < readCount; r++) {
            final BestAllele bestAllele = searchBestAllele(sampleIndex, r, true);
            if (!bestAllele.isInformative())
                continue;
            result.get(bestAllele.allele).add(bestAllele.read);
        }
    }

    /**
     * Returns the index of a read within a sample read-likelihood sub collection.
     *
     * @param sampleIndex the sample index.
     * @param read        the query read.
     * @return -1 if there is no such read in that sample, 0 or greater otherwise.
     */
    @SuppressWarnings("unused")
    public int readIndex(final int sampleIndex, final GATKSAMRecord read) {
        final Object2IntMap<GATKSAMRecord> readIndex = readIndexBySampleIndex(sampleIndex);
        if (readIndex.containsKey(read))
            return readIndexBySampleIndex(sampleIndex).getInt(read);
        else
            return -1;
    }

    /**
     * Returns the total number of reads in the read-likelihood collection.
     *
     * @return never {@code null}
     */
    public int readCount() {
        int sum = 0;
        final int sampleCount = samples.sampleCount();
        for (int i = 0; i < sampleCount; i++)
            sum += readsBySampleIndex[i].length;
        return sum;
    }

    /**
     * Returns the number of reads that belong to a sample in the read-likelihood collection.
     *
     * @param sampleIndex the query sample index.
     * @return 0 or greater.
     * @throws IllegalArgumentException if {@code sampleIndex} is not a valid sample index.
     */
    public int sampleReadCount(int sampleIndex) {
        checkSampleIndex(sampleIndex);
        return readsBySampleIndex[sampleIndex].length;
    }

    /**
     * Contains information about the best allele for a read search result.
     */
    public class BestAllele {
        public static final double INFORMATIVE_THRESHOLD = 0.2;

        /**
         * Null if there is no possible match (no allele?).
         */
        public final A allele;

        /**
         * The containing sample.
         */
        public final String sample;

        /**
         * The query read.
         */
        public final GATKSAMRecord read;

        /**
         * If allele != null, the indicates the likelihood of the read.
         */
        public final double likelihood;

        /**
         * Confidence that the read actually was generated under that likelihood.
         * This is equal to the difference between this and the second best allele match.
         */
        public final double confidence;

        private BestAllele(final int sampleIndex, final int readIndex, final int bestAlleleIndex,
                           final double likelihood, final double secondBestLikelihood) {
            allele = bestAlleleIndex == -1 ? null : alleles.alleleAt(bestAlleleIndex);
            this.likelihood = likelihood;
            sample = samples.sampleAt(sampleIndex);
            read = readsBySampleIndex[sampleIndex][readIndex];
            confidence = likelihood == secondBestLikelihood ? 0 : likelihood - secondBestLikelihood;
        }

        public boolean isInformative() {
            return confidence > INFORMATIVE_THRESHOLD;
        }
    }

    private void removeSampleReads(final int sampleIndex, final IntArrayList indexToRemove, final int alleleCount) {
        final int removeCount = indexToRemove.size();
        if (removeCount == 0)
            return;

        final GATKSAMRecord[] sampleReads = readsBySampleIndex[sampleIndex];
        final int sampleReadCount = sampleReads.length;

        final Object2IntMap<GATKSAMRecord> indexByRead = readIndexBySampleIndex[sampleIndex];
        if (indexByRead != null)
            for (int i = 0; i < removeCount; i++)
                indexByRead.remove(sampleReads[indexToRemove.getInt(i)]);
        final boolean[] removeIndex = new boolean[sampleReadCount];
        int firstDeleted = indexToRemove.get(0);
        for (int i = 0; i < removeCount; i++)
            removeIndex[indexToRemove.get(i)] = true;

        final int newSampleReadCount = sampleReadCount - removeCount;

        // Now we skim out the removed reads from the read array.
        final GATKSAMRecord[] oldSampleReads = readsBySampleIndex[sampleIndex];
        final GATKSAMRecord[] newSampleReads = new GATKSAMRecord[newSampleReadCount];

        System.arraycopy(oldSampleReads, 0, newSampleReads, 0, firstDeleted);
        Utils.skimArray(oldSampleReads, firstDeleted, newSampleReads, firstDeleted, removeIndex, firstDeleted);

        // Then we skim out the likelihoods of the removed reads.
        final double[][] oldSampleValues = valuesBySampleIndex[sampleIndex];
        final double[][] newSampleValues = new double[alleleCount][newSampleReadCount];
        for (int a = 0; a < alleleCount; a++) {
            System.arraycopy(oldSampleValues[a], 0, newSampleValues[a], 0, firstDeleted);
            Utils.skimArray(oldSampleValues[a], firstDeleted, newSampleValues[a], firstDeleted, removeIndex, firstDeleted);
        }
        valuesBySampleIndex[sampleIndex] = newSampleValues;
        readsBySampleIndex[sampleIndex] = newSampleReads;
        readListBySampleIndex[sampleIndex] = null; // reset the unmodifiable list.
    }


    // Requires that the collection passed iterator can remove elements, and it can be modified.
    private void removeSampleReads(final int sampleIndex, final Collection<GATKSAMRecord> readsToRemove, final int alleleCount) {
        final GATKSAMRecord[] sampleReads = readsBySampleIndex[sampleIndex];
        final int sampleReadCount = sampleReads.length;

        final Object2IntMap<GATKSAMRecord> indexByRead = readIndexBySampleIndex(sampleIndex);
        // Count how many we are going to remove, which ones (indexes) and remove entry from the read-index map.
        final boolean[] removeIndex = new boolean[sampleReadCount];
        int removeCount = 0; // captures the number of deletions.
        int firstDeleted = sampleReadCount;    // captures the first position that was deleted.

        final Iterator<GATKSAMRecord> readsToRemoveIterator = readsToRemove.iterator();
        while (readsToRemoveIterator.hasNext()) {
            final GATKSAMRecord read = readsToRemoveIterator.next();
            if (indexByRead.containsKey(read)) {
                final int index = indexByRead.getInt(read);
                if (firstDeleted > index)
                    firstDeleted = index;
                removeCount++;
                removeIndex[index] = true;
                readsToRemoveIterator.remove();
                indexByRead.remove(read);
            }
        }

        // Nothing to remove we just finish here.
        if (removeCount == 0)
            return;

        final int newSampleReadCount = sampleReadCount - removeCount;

        // Now we skim out the removed reads from the read array.
        final GATKSAMRecord[] oldSampleReads = readsBySampleIndex[sampleIndex];
        final GATKSAMRecord[] newSampleReads = new GATKSAMRecord[newSampleReadCount];

        System.arraycopy(oldSampleReads, 0, newSampleReads, 0, firstDeleted);
        Utils.skimArray(oldSampleReads, firstDeleted, newSampleReads, firstDeleted, removeIndex, firstDeleted);

        // Update the indices for the extant reads from the first deletion onwards.
        for (int r = firstDeleted; r < newSampleReadCount; r++) {
            indexByRead.put(newSampleReads[r], r);
        }

        // Then we skim out the likelihoods of the removed reads.
        final double[][] oldSampleValues = valuesBySampleIndex[sampleIndex];
        final double[][] newSampleValues = new double[alleleCount][newSampleReadCount];
        for (int a = 0; a < alleleCount; a++) {
            System.arraycopy(oldSampleValues[a], 0, newSampleValues[a], 0, firstDeleted);
            Utils.skimArray(oldSampleValues[a], firstDeleted, newSampleValues[a], firstDeleted, removeIndex, firstDeleted);
        }
        valuesBySampleIndex[sampleIndex] = newSampleValues;
        readsBySampleIndex[sampleIndex] = newSampleReads;
        readListBySampleIndex[sampleIndex] = null; // reset the unmodifiable list.
    }

    private Object2IntMap<GATKSAMRecord> readIndexBySampleIndex(final int sampleIndex) {
        if (readIndexBySampleIndex[sampleIndex] == null) {
            final GATKSAMRecord[] sampleReads = readsBySampleIndex[sampleIndex];
            final int sampleReadCount = sampleReads.length;
            readIndexBySampleIndex[sampleIndex] = new Object2IntOpenHashMap<>(sampleReadCount);
            for (int r = 0; r < sampleReadCount; r++)
                readIndexBySampleIndex[sampleIndex].put(sampleReads[r], r);
        }
        return readIndexBySampleIndex[sampleIndex];
    }

    /**
     * Transform into a multi-sample HashMap backed {@link PerReadAlleleLikelihoodMap} type.
     *
     * @return never {@code null}.
     * @deprecated This method should eventually disappear once we have removed PerReadAlleleLikelihoodMap class completelly.
     */
    @Deprecated
    @SuppressWarnings("all")
    public Map<String, PerReadAlleleLikelihoodMap> toPerReadAlleleLikelihoodMap() {
        final int sampleCount = samples.sampleCount();
        final Map<String, PerReadAlleleLikelihoodMap> result = new HashMap<>(sampleCount);
        for (int s = 0; s < sampleCount; s++)
            result.put(samples.sampleAt(s), toPerReadAlleleLikelihoodMap(s));
        return result;
    }

    /**
     * @return never {@code null}.
     */
    @Deprecated
    public PerReadAlleleLikelihoodMap toPerReadAlleleLikelihoodMap(final int sampleIndex) {
        checkSampleIndex(sampleIndex);
        final PerReadAlleleLikelihoodMap result = new PerReadAlleleLikelihoodMap();
        final int alleleCount = alleles.alleleCount();
        final GATKSAMRecord[] sampleReads = readsBySampleIndex[sampleIndex];
        final int sampleReadCount = sampleReads.length;
        for (int a = 0; a < alleleCount; a++) {
            final A allele = alleles.alleleAt(a);
            final double[] readLikelihoods = valuesBySampleIndex[sampleIndex][a];
            for (int r = 0; r < sampleReadCount; r++)
                result.add(sampleReads[r], allele, readLikelihoods[r]);
        }
        return result;
    }

    /**
     * Implements a likelihood matrix per sample given its index.
     */
    private class SampleMatrix implements Matrix<A> {

        private final int sampleIndex;

        private SampleMatrix(final int sampleIndex) {
            this.sampleIndex = sampleIndex;
        }

        @Override
        public List<GATKSAMRecord> reads() {
            return sampleReads(sampleIndex);
        }

        @Override
        public List<A> alleles() {
            return ReadLikelihoods.this.alleles();
        }

        @Override
        public void set(final int alleleIndex, final int readIndex, final double value) {
            valuesBySampleIndex[sampleIndex][alleleIndex][readIndex] = value;
        }

        @Override
        public double get(final int alleleIndex, final int readIndex) {
            return valuesBySampleIndex[sampleIndex][alleleIndex][readIndex];
        }

        @Override
        public int alleleIndex(final A allele) {
            return ReadLikelihoods.this.alleleIndex(allele);
        }

        @Override
        public int readIndex(final GATKSAMRecord read) {
            return ReadLikelihoods.this.readIndex(sampleIndex, read);
        }

        @Override
        public int alleleCount() {
            return alleles.alleleCount();
        }

        @Override
        public int readCount() {
            return readsBySampleIndex[sampleIndex].length;
        }

        @Override
        public A alleleAt(int alleleIndex) {
            return ReadLikelihoods.this.alleleAt(alleleIndex);
        }

        @Override
        public GATKSAMRecord readAt(final int readIndex) {
            if (readIndex < 0)
                throw new IllegalArgumentException("the read-index cannot be negative");
            final GATKSAMRecord[] sampleReads = readsBySampleIndex[sampleIndex];
            if (readIndex >= sampleReads.length)
                throw new IllegalArgumentException("the read-index is beyond the read count of the sample");
            return sampleReads[readIndex];
        }

        @Override
        public void copyAlleleLikelihoods(final int alleleIndex, final double[] dest, final int offset) {
            System.arraycopy(valuesBySampleIndex[sampleIndex][alleleIndex], 0, dest, offset, readCount());
        }
    }

    /**
     * Checks whether the provide sample index is valid.
     * <p>
     * If not, it throws an exception.
     * </p>
     *
     * @param sampleIndex the target sample index.
     * @throws IllegalArgumentException if {@code sampleIndex} is invalid, i.e. outside the range [0,{@link #sampleCount}).
     */
    private void checkSampleIndex(final int sampleIndex) {
        if (sampleIndex < 0 || sampleIndex >= samples.sampleCount())
            throw new IllegalArgumentException("invalid sample index: " + sampleIndex);
    }
}