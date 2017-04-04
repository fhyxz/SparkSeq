package org.ncic.bioinfo.sparkseq.algorithms.utils.pairhmm;

import org.ncic.bioinfo.sparkseq.algorithms.utils.MathUtils;
import org.ncic.bioinfo.sparkseq.algorithms.utils.QualityUtils;

import java.util.Arrays;

import static java.lang.Math.log10;
import static org.ncic.bioinfo.sparkseq.algorithms.utils.pairhmm.PairHMMModel.*;

/**
 * Author: wbc
 */
public class Log10PairHMM extends N2MemoryPairHMM {
    /**
     * Should we use exact log10 calculation (true), or an approximation (false)?
     */
    private final boolean doExactLog10;


    // we divide e by 3 because the observed base could have come from any of the non-observed alleles
    protected final static double log10_3 = log10(3.0);

    /**
     * Create an uninitialized PairHMM
     *
     * @param doExactLog10 should the log10 calculations be exact (slow) or approximate (faster)
     */
    public Log10PairHMM(final boolean doExactLog10) {
        this.doExactLog10 = doExactLog10;
    }

    /**
     * Is this HMM using exact log10 calculations?
     * @return true if exact, false if approximate
     */
    public boolean isDoingExactLog10Calculations() {
        return doExactLog10;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(final int readMaxLength, final int haplotypeMaxLength ) {
        super.initialize(readMaxLength, haplotypeMaxLength);

        for( int iii=0; iii < paddedMaxReadLength; iii++ ) {
            Arrays.fill(matchMatrix[iii], Double.NEGATIVE_INFINITY);
            Arrays.fill(insertionMatrix[iii], Double.NEGATIVE_INFINITY);
            Arrays.fill(deletionMatrix[iii], Double.NEGATIVE_INFINITY);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double subComputeReadLikelihoodGivenHaplotypeLog10( final byte[] haplotypeBases,
                                                               final byte[] readBases,
                                                               final byte[] readQuals,
                                                               final byte[] insertionGOP,
                                                               final byte[] deletionGOP,
                                                               final byte[] overallGCP,
                                                               final int hapStartIndex,
                                                               final boolean recacheReadValues,
                                                               final int nextHapStartIndex) {


        if ( ! constantsAreInitialized || recacheReadValues )
            initializeProbabilities(insertionGOP, deletionGOP, overallGCP);
        initializePriors(haplotypeBases, readBases, readQuals, hapStartIndex);
        if (previousHaplotypeBases == null || previousHaplotypeBases.length != haplotypeBases.length) {
            // set the initial value (free deletions in the beginning) for the first row in the deletion matrix
            initializeMatrixValues(haplotypeBases);
        }

        for (int i = 1; i < paddedReadLength; i++) {
            // +1 here is because hapStartIndex is 0-based, but our matrices are 1 based
            for (int j = hapStartIndex+1; j < paddedHaplotypeLength; j++) {
                updateCell(i, j, prior[i][j], transition[i]);
            }
        }

        // final probability is the log10 sum of the last element in the Match and Insertion state arrays
        // this way we ignore all paths that ended in deletions! (huge)
        // but we have to sum all the paths ending in the M and I matrices, because they're no longer extended.
        return finalLikelihoodCalculation();
    }

    protected void initializeMatrixValues(final byte[] haplotypeBases) {
        final double initialValue = Math.log10(1.0 / haplotypeBases.length);
        for( int j = 0; j < paddedHaplotypeLength; j++ ) {
            deletionMatrix[0][j] = initialValue;
        }
    }

    protected double finalLikelihoodCalculation() {
        final int endI = paddedReadLength - 1;
        double finalSumProbabilities = myLog10SumLog10(new double[]{matchMatrix[endI][1], insertionMatrix[endI][1]});
        for (int j = 2; j < paddedHaplotypeLength; j++)
            finalSumProbabilities = myLog10SumLog10(new double[]{finalSumProbabilities, matchMatrix[endI][j], insertionMatrix[endI][j]});
        return finalSumProbabilities;
    }


    /**
     * Initializes the matrix that holds all the constants related to the editing
     * distance between the read and the haplotype.
     *
     * @param haplotypeBases the bases of the haplotype
     * @param readBases      the bases of the read
     * @param readQuals      the base quality scores of the read
     * @param startIndex     where to start updating the distanceMatrix (in case this read is similar to the previous read)
     */
    public void initializePriors(final byte[] haplotypeBases, final byte[] readBases, final byte[] readQuals, final int startIndex) {

        // initialize the pBaseReadLog10 matrix for all combinations of read x haplotype bases
        // Abusing the fact that java initializes arrays with 0.0, so no need to fill in rows and columns below 2.

        for (int i = 0; i < readBases.length; i++) {
            final byte x = readBases[i];
            final byte qual = readQuals[i];
            for (int j = startIndex; j < haplotypeBases.length; j++) {
                final byte y = haplotypeBases[j];
                prior[i+1][j+1] = ( x == y || x == (byte) 'N' || y == (byte) 'N' ?
                        QualityUtils.qualToProbLog10(qual) : (QualityUtils.qualToErrorProbLog10(qual) - (doNotUseTristateCorrection ? 0.0 : log10_3)) );
            }
        }
    }

    /**
     * Initializes the matrix that holds all the constants related to quality scores.
     *
     * @param insertionGOP   insertion quality scores of the read
     * @param deletionGOP    deletion quality scores of the read
     * @param overallGCP     overall gap continuation penalty
     */
    protected void initializeProbabilities(final byte[] insertionGOP, final byte[] deletionGOP, final byte[] overallGCP) {
        PairHMMModel.qualToTransProbsLog10(transition,insertionGOP,deletionGOP,overallGCP);
        // note that we initialized the constants
        constantsAreInitialized = true;
    }


    /**
     * Compute the log10SumLog10 of the values
     *
     * NOTE NOTE NOTE
     *
     * Log10PairHMM depends critically on this function tolerating values that are all -Infinity
     * and the sum returning -Infinity.  Note good.  Needs to be fixed.
     *
     * NOTE NOTE NOTE
     *
     * @param values an array of log10 probabilities that need to be summed
     * @return the log10 of the sum of the probabilities
     */
    protected double myLog10SumLog10(final double[] values) {
        return doExactLog10 ? MathUtils.log10sumLog10(values) : MathUtils.approximateLog10SumLog10(values);
    }

    /**
     * Updates a cell in the HMM matrix
     *
     * The read and haplotype indices are offset by one because the state arrays have an extra column to hold the
     * initial conditions

     * @param indI             row index in the matrices to update
     * @param indJ             column index in the matrices to update
     * @param prior            the likelihood editing distance matrix for the read x haplotype
     * @param transition        an array with the six transition relevant to this location
     */
    protected void updateCell( final int indI, final int indJ, final double prior, final double[] transition) {

        matchMatrix[indI][indJ] = prior +
                myLog10SumLog10(new double[]{matchMatrix[indI - 1][indJ - 1] + transition[matchToMatch],
                        insertionMatrix[indI - 1][indJ - 1] + transition[indelToMatch],
                        deletionMatrix[indI - 1][indJ - 1] + transition[indelToMatch]});
        insertionMatrix[indI][indJ] = myLog10SumLog10(new double[] {matchMatrix[indI - 1][indJ] + transition[matchToInsertion], insertionMatrix[indI - 1][indJ] + transition[insertionToInsertion]});
        deletionMatrix[indI][indJ]  = myLog10SumLog10(new double[] {matchMatrix[indI][indJ - 1] + transition[matchToDeletion],  deletionMatrix[indI][indJ - 1] + transition[deletionToDeletion]});
    }
}
