package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.model;

import org.ncic.bioinfo.sparkseq.algorithms.utils.MathUtils;
import org.ncic.bioinfo.sparkseq.exceptions.ReviewedGATKException;

import java.util.Arrays;

/**
 * Author: wbc
 */
public class ProbabilityVector {
    private final double[] probabilityArray;
    private final int minVal;
    private final int maxVal;

    final static double LOG_DYNAMIC_RANGE = 10; // values X below max vector value will be removed

    /**
     * Default constructor: take vector in log-space, with support from range [0,len-1]
     * @param vec                                  Probability (or likelihood) vector in log space
     * @param compressRange                        If true, compress by eliminating edges with little support
     */
    public ProbabilityVector(double[] vec, boolean compressRange) {

        int maxValIdx = MathUtils.maxElementIndex(vec);
        double maxv = vec[maxValIdx];
        if (maxv > 0.0)
            throw new ReviewedGATKException("BUG: Attempting to create a log-probability vector with positive elements");

        if (compressRange) {
            minVal = getMinIdx(vec, maxValIdx);
            maxVal = getMaxIdx(vec, maxValIdx);
            probabilityArray = Arrays.copyOfRange(vec, minVal, maxVal+1);

        }   else {
            probabilityArray = vec;
            minVal = 0;
            maxVal = vec.length-1;

        }
    }

    public ProbabilityVector(double[] vec) {
        this(vec,true);
    }

    public ProbabilityVector(ProbabilityVector other, boolean compressRange) {
        // create new probability vector from other.
        this(other.getUncompressedProbabilityVector(), compressRange);

    }
    public int getMinVal() { return minVal;}
    public int getMaxVal() { return maxVal;}
    public double[] getProbabilityVector() { return probabilityArray;}

    public double[] getProbabilityVector(int minVal, int maxVal) {
        // get vector in specified range. If range is outside of current vector, fill with negative infinities
        double[] x = new double[maxVal - minVal + 1];

        for (int k=minVal; k <= maxVal; k++)
            x[k-minVal] = getLogProbabilityForIndex(k);


        return x;
    }

    public double[] getUncompressedProbabilityVector() {
        double x[] = new double[maxVal+1];

        for (int i=0; i < minVal; i++)
            x[i] = Double.NEGATIVE_INFINITY;
        for (int i=minVal; i <=maxVal; i++)
            x[i] = probabilityArray[i-minVal];

        return x;
    }
    /**
     * Return log Probability for original index i
     * @param idx   Index to probe
     * @return      log10(Pr X = i) )
     */
    public double getLogProbabilityForIndex(int idx) {
        if (idx < minVal || idx > maxVal)
            return Double.NEGATIVE_INFINITY;
        else
            return probabilityArray[idx-minVal];
    }

    //public ProbabilityVector
    public static ProbabilityVector compressVector(double[] vec ) {
        return new ProbabilityVector(vec, true);
    }

    /**
     * Determine left-most index where a vector exceeds (max Value - DELTA)
     * @param vec                    Input vector
     * @param maxValIdx              Index to stop - usually index with max value in vector
     * @return                       Min index where vector > vec[maxValIdx]-LOG_DYNAMIC_RANGE
     */
    private static int getMinIdx(double[] vec, int maxValIdx) {
        int edgeIdx;
        for (edgeIdx=0; edgeIdx<=maxValIdx; edgeIdx++ ) {
            if (vec[edgeIdx] > vec[maxValIdx]-LOG_DYNAMIC_RANGE)
                break;
        }

        return edgeIdx;


    }

    /**
     * Determine right-most index where a vector exceeds (max Value - DELTA)
     * @param vec                    Input vector
     * @param maxValIdx              Index to stop - usually index with max value in vector
     * @return                       Max index where vector > vec[maxValIdx]-LOG_DYNAMIC_RANGE
     */
    private static int getMaxIdx(double[] vec, int maxValIdx) {
        int edgeIdx;
        for (edgeIdx=vec.length-1; edgeIdx>=maxValIdx; edgeIdx-- ) {
            if (vec[edgeIdx] > vec[maxValIdx]-LOG_DYNAMIC_RANGE)
                break;
        }

        return edgeIdx;


    }

    /**
     *
     * @param other
     * @return
     */
    public double logDotProduct(ProbabilityVector other) {
        // find overlap in range
        int minRange = Math.max(this.minVal, other.getMinVal());
        int maxRange = Math.min(this.maxVal, other.getMaxVal());
        if (minRange > maxRange)
            return Double.NEGATIVE_INFINITY;

        // x = 0,1,2,   y = 2,3,4. minRange = 2, maxRange = 2
        double[] result = new double[maxRange - minRange+1];
        for (int k=0; k <= maxRange-minRange; k++) {
            int startI = minRange - this.minVal;
            int startJ = minRange - other.getMinVal();
            result[k] = this.probabilityArray[k+startI] + other.probabilityArray[k+startJ];

        }
        return MathUtils.approximateLog10SumLog10(result);
    }

}
