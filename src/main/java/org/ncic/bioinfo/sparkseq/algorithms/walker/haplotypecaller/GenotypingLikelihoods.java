package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.GenotypeLikelihoods;

import java.util.List;

/**
 * Author: wbc
 */
public class GenotypingLikelihoods<A extends Allele> implements SampleList, AlleleList<A> {

    private final GenotypeLikelihoods[] likelihoods;

    private final PloidyModel ploidyModel;

    private final AlleleList<A> alleles;

    /**
     * Creates a new genotyping-likelihoods collection given the genotype alleles, the sample ploidy model and the
     *   likelihoods themselves.
     * <p>
     * Notice that this constructor does not check whether the likelihood array lengths corresponds to the sample plodies and
     * number of alleles.
     * </p>
     *
     * @param alleles the genotyping alleles.
     * @param ploidyModel the ploidy model.
     * @param likelihoods the actual genotype likelihoods, one element per sample.
     *
     * @throws IllegalArgumentException if any argument is {@code null}, or the number of samples in {@code ploidyModel}
     *  does not correspond with the number of likelihoods arrays in {@code likelihoods}
     */
    public GenotypingLikelihoods(final AlleleList<A> alleles, final PloidyModel ploidyModel,
                          final List<GenotypeLikelihoods> likelihoods) {
        if (alleles == null)
            throw new IllegalArgumentException("allele list cannot be null");
        if (ploidyModel == null)
            throw new IllegalArgumentException("the ploidy model cannot be null");
        if (likelihoods == null)
            throw new IllegalArgumentException("the likelihood collection cannot be null");
        if (ploidyModel.sampleCount() !=  likelihoods.size())
            throw new IllegalArgumentException("there must be exactly one likelihood set for each sample");

        this.likelihoods = likelihoods.toArray(new GenotypeLikelihoods[likelihoods.size()]);
        for (final GenotypeLikelihoods likelihood : this.likelihoods)
            if (likelihood == null)
                throw new IllegalArgumentException("no genotype likelihood is allowed to be null");

        this.alleles = alleles;
        this.ploidyModel = ploidyModel;
    }

    @Override
    public int sampleCount() {
        return ploidyModel.sampleCount();
    }

    @Override
    public int sampleIndex(final String sample) {
        return ploidyModel.sampleIndex(sample);
    }

    @Override
    public String sampleAt(final int sampleIndex) {
        return ploidyModel.sampleAt(sampleIndex);
    }

    /**
     * Returns the ploidy of the sample given its index in the collection.
     *
     * @param sampleIndex the query sample index.
     *
     * @throws IllegalArgumentException if {@code sampleIndex} is not a valid index for this collection:
     *   [0,{@link #sampleCount()).
     *
     * @return 0 or greater.
     */
    public int samplePloidy(final int sampleIndex) {
        return ploidyModel.samplePloidy(sampleIndex);
    }

    /**
     * Returns the genotype-likelihoods of the sample given its index in the collection.
     *
     * @param sampleIndex the query sample index.
     *
     * @throws IllegalArgumentException if {@code sampleIndex} is not a valid index for this collection:
     *   [0,{@link #sampleCount()).
     *
     * @return never {@code null}.
     */
    public GenotypeLikelihoods sampleLikelihoods(final int sampleIndex) {
        return likelihoods[sampleIndex];
    }

    @Override
    public int alleleCount() {
        return alleles.alleleCount();
    }

    @Override
    public int alleleIndex(final A allele) {
        return alleles.alleleIndex(allele);
    }

    @Override
    public A alleleAt(final int index) {
        return alleles.alleleAt(index);
    }
}
