package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.genotyper;

import htsjdk.variant.variantcontext.VariantContext;

/**
 * Author: wbc
 */
public class VariantCallContext extends VariantContext {

    // Was the site called confidently, either reference or variant?
    public boolean confidentlyCalled = false;

    // Should this site be emitted?
    public boolean shouldEmit = true;

    VariantCallContext(VariantContext vc, boolean confidentlyCalledP) {
        super(vc);
        this.confidentlyCalled = confidentlyCalledP;
    }

    VariantCallContext(VariantContext vc, boolean confidentlyCalledP, boolean shouldEmit) {
        super(vc);
        this.confidentlyCalled = confidentlyCalledP;
        this.shouldEmit = shouldEmit;
    }

    /* these methods are only implemented for GENOTYPE_GIVEN_ALLELES MODE */
    //todo -- expand these methods to all modes

    /**
     *
     * @param callConfidenceThreshold the Unified Argument Collection STANDARD_CONFIDENCE_FOR_CALLING
     * @return true if call was confidently ref
     */
    public boolean isCalledRef(double callConfidenceThreshold) {
        return (confidentlyCalled && (getPhredScaledQual() < callConfidenceThreshold));
    }

    /**
     *
     * @param callConfidenceThreshold the Unified Argument Collection STANDARD_CONFIDENCE_FOR_CALLING
     * @return true if call was confidently alt
     */
    public boolean isCalledAlt(double callConfidenceThreshold) {
        return (confidentlyCalled && (getPhredScaledQual() > callConfidenceThreshold));
    }


}
