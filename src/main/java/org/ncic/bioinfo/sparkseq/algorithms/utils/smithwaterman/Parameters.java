package org.ncic.bioinfo.sparkseq.algorithms.utils.smithwaterman;

/**
 * Author: wbc
 */
public final class Parameters {
    public final int w_match;
    public final int w_mismatch;
    public final int w_open;
    public final int w_extend;

    /**
     * Create a new set of SW parameters
     * @param w_match the match score
     * @param w_mismatch the mismatch penalty
     * @param w_open the gap open penalty
     * @param w_extend the gap extension penalty

     */
    public Parameters(final int w_match, final int w_mismatch, final int w_open, final int w_extend) {
        if ( w_mismatch > 0 ) throw new IllegalArgumentException("w_mismatch must be <= 0 but got " + w_mismatch);
        if ( w_open> 0 ) throw new IllegalArgumentException("w_open must be <= 0 but got " + w_open);
        if ( w_extend> 0 ) throw new IllegalArgumentException("w_extend must be <= 0 but got " + w_extend);

        this.w_match = w_match;
        this.w_mismatch = w_mismatch;
        this.w_open = w_open;
        this.w_extend = w_extend;
    }
}
