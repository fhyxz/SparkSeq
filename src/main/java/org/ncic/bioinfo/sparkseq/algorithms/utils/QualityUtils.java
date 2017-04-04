package org.ncic.bioinfo.sparkseq.algorithms.utils;

import htsjdk.samtools.SAMUtils;

/**
 * Author: wbc
 */
public class QualityUtils {
    /**
     * Maximum quality score that can be encoded in a SAM/BAM file
     */
    public final static byte MAX_SAM_QUAL_SCORE = SAMUtils.MAX_PHRED_SCORE;


    private final static double RAW_MIN_PHRED_SCALED_QUAL = Math.log10(Double.MIN_VALUE);

    /**
     * bams containing quals above this value are extremely suspicious and we should warn the user
     */
    public final static byte MAX_REASONABLE_Q_SCORE = 60;

    /**
     * The lowest quality score for a base that is considered reasonable for statistical analysis.  This is
     * because Q 6 => you stand a 25% of being right, which means all bases are equally likely
     */
    public final static byte MIN_USABLE_Q_SCORE = 6;
    public final static int MAPPING_QUALITY_UNAVAILABLE = 255;

    /**
     * Maximum sense quality value.
     */
    public static final int MAX_QUAL = 254;

    /**
     * Cached values for qual as byte calculations so they are very fast
     */
    private static double qualToErrorProbCache[] = new double[MAX_QUAL + 1];
    private static double qualToProbLog10Cache[] = new double[MAX_QUAL + 1];


    static {
        for (int i = 0; i <= MAX_QUAL; i++) {
            qualToErrorProbCache[i] = qualToErrorProb((double) i);
            qualToProbLog10Cache[i] = Math.log10(1.0 - qualToErrorProbCache[i]);
        }
    }

    /**
     * Private constructor.  No instantiating this class!
     */
    private QualityUtils() {
    }

    // ----------------------------------------------------------------------
    //
    // These are all functions to convert a phred-scaled quality score to a probability
    //
    // ----------------------------------------------------------------------

    /**
     * Convert a phred-scaled quality score to its probability of being true (Q30 => 0.999)
     * <p>
     * This is the Phred-style conversion, *not* the Illumina-style conversion.
     * <p>
     * Because the input is a discretized byte value, this function uses a cache so is very efficient
     * <p>
     * WARNING -- because this function takes a byte for maxQual, you must be careful in converting
     * integers to byte.  The appropriate way to do this is ((byte)(myInt & 0xFF))
     *
     * @param qual a quality score (0-255)
     * @return a probability (0.0-1.0)
     */
    public static double qualToProb(final byte qual) {
        return 1.0 - qualToErrorProb(qual);
    }

    /**
     * Convert a phred-scaled quality score to its probability of being true (Q30 => 0.999)
     * <p>
     * This is the Phred-style conversion, *not* the Illumina-style conversion.
     * <p>
     * Because the input is a double value, this function must call Math.pow so can be quite expensive
     *
     * @param qual a phred-scaled quality score encoded as a double.  Can be non-integer values (30.5)
     * @return a probability (0.0-1.0)
     */
    public static double qualToProb(final double qual) {
        if (qual < 0.0) throw new IllegalArgumentException("qual must be >= 0.0 but got " + qual);
        return 1.0 - qualToErrorProb(qual);
    }

    /**
     * Convert a phred-scaled quality score to its log10 probability of being true (Q30 => log10(0.999))
     * <p>
     * This is the Phred-style conversion, *not* the Illumina-style conversion.
     * <p>
     * Because the input is a double value, this function must call Math.pow so can be quite expensive
     * <p>
     * WARNING -- because this function takes a byte for maxQual, you must be careful in converting
     * integers to byte.  The appropriate way to do this is ((byte)(myInt & 0xFF))
     *
     * @param qual a phred-scaled quality score encoded as a double.  Can be non-integer values (30.5)
     * @return a probability (0.0-1.0)
     */
    public static double qualToProbLog10(final byte qual) {
        return qualToProbLog10Cache[(int) qual & 0xff]; // Map: 127 -> 127; -128 -> 128; -1 -> 255; etc.
    }

    /**
     * Convert a phred-scaled quality score to its probability of being wrong (Q30 => 0.001)
     * <p>
     * This is the Phred-style conversion, *not* the Illumina-style conversion.
     * <p>
     * Because the input is a double value, this function must call Math.pow so can be quite expensive
     *
     * @param qual a phred-scaled quality score encoded as a double.  Can be non-integer values (30.5)
     * @return a probability (0.0-1.0)
     */
    public static double qualToErrorProb(final double qual) {
        if (qual < 0.0) throw new IllegalArgumentException("qual must be >= 0.0 but got " + qual);
        return Math.pow(10.0, qual / -10.0);
    }

    /**
     * Convert a phred-scaled quality score to its probability of being wrong (Q30 => 0.001)
     * <p>
     * This is the Phred-style conversion, *not* the Illumina-style conversion.
     * <p>
     * Because the input is a byte value, this function uses a cache so is very efficient
     * <p>
     * WARNING -- because this function takes a byte for maxQual, you must be careful in converting
     * integers to byte.  The appropriate way to do this is ((byte)(myInt & 0xFF))
     *
     * @param qual a phred-scaled quality score encoded as a byte
     * @return a probability (0.0-1.0)
     */
    public static double qualToErrorProb(final byte qual) {
        return qualToErrorProbCache[(int) qual & 0xff]; // Map: 127 -> 127; -128 -> 128; -1 -> 255; etc.
    }


    /**
     * Convert a phred-scaled quality score to its log10 probability of being wrong (Q30 => log10(0.001))
     * <p>
     * This is the Phred-style conversion, *not* the Illumina-style conversion.
     * <p>
     * The calculation is extremely efficient
     * <p>
     * WARNING -- because this function takes a byte for maxQual, you must be careful in converting
     * integers to byte.  The appropriate way to do this is ((byte)(myInt & 0xFF))
     *
     * @param qual a phred-scaled quality score encoded as a byte
     * @return a probability (0.0-1.0)
     */
    public static double qualToErrorProbLog10(final byte qual) {
        return qualToErrorProbLog10((double) (qual & 0xFF));
    }

    /**
     * Convert a phred-scaled quality score to its log10 probability of being wrong (Q30 => log10(0.001))
     * <p>
     * This is the Phred-style conversion, *not* the Illumina-style conversion.
     * <p>
     * The calculation is extremely efficient
     *
     * @param qual a phred-scaled quality score encoded as a double
     * @return a probability (0.0-1.0)
     */
    public static double qualToErrorProbLog10(final double qual) {
        if (qual < 0.0) throw new IllegalArgumentException("qual must be >= 0.0 but got " + qual);
        return qual / -10.0;
    }

    // ----------------------------------------------------------------------
    //
    // Functions to convert a probability to a phred-scaled quality score
    //
    // ----------------------------------------------------------------------

    /**
     * Convert a probability of being wrong to a phred-scaled quality score (0.01 => 20).
     * <p>
     * Note, this function caps the resulting quality score by the public static value MAX_SAM_QUAL_SCORE
     * and by 1 at the low-end.
     *
     * @param errorRate a probability (0.0-1.0) of being wrong (i.e., 0.01 is 1% change of being wrong)
     * @return a quality score (0-MAX_SAM_QUAL_SCORE)
     */
    public static byte errorProbToQual(final double errorRate) {
        return errorProbToQual(errorRate, MAX_SAM_QUAL_SCORE);
    }

    /**
     * Convert a probability of being wrong to a phred-scaled quality score (0.01 => 20).
     * <p>
     * Note, this function caps the resulting quality score by the public static value MIN_REASONABLE_ERROR
     * and by 1 at the low-end.
     * <p>
     * WARNING -- because this function takes a byte for maxQual, you must be careful in converting
     * integers to byte.  The appropriate way to do this is ((byte)(myInt & 0xFF))
     *
     * @param errorRate a probability (0.0-1.0) of being wrong (i.e., 0.01 is 1% change of being wrong)
     * @return a quality score (0-maxQual)
     */
    public static byte errorProbToQual(final double errorRate, final byte maxQual) {
        if (!MathUtils.goodProbability(errorRate))
            throw new IllegalArgumentException("errorRate must be good probability but got " + errorRate);
        final double d = Math.round(-10.0 * Math.log10(errorRate));
        return boundQual((int) d, maxQual);
    }


    /**
     * Convert a probability of being right to a phred-scaled quality score (0.99 => 20).
     * <p>
     * Note, this function caps the resulting quality score by the min probability allowed (EPS).
     * So for example, if prob is 1e-6, which would imply a Q-score of 60, and EPS is 1e-4,
     * the result of this function is actually Q40.
     * <p>
     * Note that the resulting quality score, regardless of EPS, is capped by MAX_SAM_QUAL_SCORE and
     * bounded on the low-side by 1.
     * <p>
     * WARNING -- because this function takes a byte for maxQual, you must be careful in converting
     * integers to byte.  The appropriate way to do this is ((byte)(myInt & 0xFF))
     *
     * @param trueProb a probability (0.0-1.0) of being right
     * @param maxQual  the maximum quality score we are allowed to emit here, regardless of the error rate
     * @return a phred-scaled quality score (0-maxQualScore) as a byte
     */
    public static byte trueProbToQual(final double trueProb, final byte maxQual) {
        if (!MathUtils.goodProbability(trueProb))
            throw new IllegalArgumentException("trueProb must be good probability but got " + trueProb);
        final double lp = Math.round(-10.0 * MathUtils.log10OneMinusX(trueProb));
        return boundQual((int) lp, maxQual);
    }


    /**
     * Convert a probability of being right to a phred-scaled quality score of being wrong as a double
     * <p>
     * This is a very generic method, that simply computes a phred-scaled double quality
     * score given an error rate.  It has the same precision as a normal double operation
     *
     * @param trueRate the probability of being right (0.0-1.0)
     * @return a phred-scaled version of the error rate implied by trueRate
     */
    public static double phredScaleCorrectRate(final double trueRate) {
        return phredScaleLog10ErrorRate(MathUtils.log10OneMinusX(trueRate));
    }

    /**
     * Convert a log10 probability of being right to a phred-scaled quality score of being wrong as a double
     * <p>
     * This is a very generic method, that simply computes a phred-scaled double quality
     * score given an error rate.  It has the same precision as a normal double operation
     *
     * @param trueRateLog10 the log10 probability of being right (0.0-1.0).  Can be -Infinity to indicate
     *                      that the result is impossible in which MIN_PHRED_SCALED_QUAL is returned
     * @return a phred-scaled version of the error rate implied by trueRate
     */
    public static double phredScaleLog10CorrectRate(final double trueRateLog10) {
        return phredScaleCorrectRate(Math.pow(10.0, trueRateLog10));
    }

    /**
     * Convert a probability of being wrong to a phred-scaled quality score of being wrong as a double
     * <p>
     * This is a very generic method, that simply computes a phred-scaled double quality
     * score given an error rate.  It has the same precision as a normal double operation
     *
     * @param errorRate the probability of being wrong (0.0-1.0)
     * @return a phred-scaled version of the error rate
     */
    public static double phredScaleErrorRate(final double errorRate) {
        return phredScaleLog10ErrorRate(Math.log10(errorRate));
    }

    /**
     * Convert a log10 probability of being wrong to a phred-scaled quality score of being wrong as a double
     * <p>
     * This is a very generic method, that simply computes a phred-scaled double quality
     * score given an error rate.  It has the same precision as a normal double operation
     *
     * @param errorRateLog10 the log10 probability of being wrong (0.0-1.0).  Can be -Infinity, in which case
     *                       the result is MIN_PHRED_SCALED_QUAL
     * @return a phred-scaled version of the error rate
     */
    public static double phredScaleLog10ErrorRate(final double errorRateLog10) {
        if (!MathUtils.goodLog10Probability(errorRateLog10))
            throw new IllegalArgumentException("errorRateLog10 must be good probability but got " + errorRateLog10);
        // abs is necessary for edge base with errorRateLog10 = 0 producing -0.0 doubles
        return Math.abs(-10.0 * Math.max(errorRateLog10, RAW_MIN_PHRED_SCALED_QUAL));
    }

    // ----------------------------------------------------------------------
    //
    // Routines to bound a quality score to a reasonable range
    //
    // ----------------------------------------------------------------------

    /**
     * Return a quality score that bounds qual by maxQual and 1
     * <p>
     * WARNING -- because this function takes a byte for maxQual, you must be careful in converting
     * integers to byte.  The appropriate way to do this is ((byte)(myInt & 0xFF))
     *
     * @param qual    the uncapped quality score as an integer.  Can be < 0 (which may indicate an error in the
     *                client code), which will be brought back to 1, but this isn't an error, as some
     *                routines may use this functionality (BaseRecalibrator, for example)
     * @param maxQual the maximum quality score, must be less < 255
     * @return the bounded quality score
     */
    public static byte boundQual(final int qual, final byte maxQual) {
        return (byte) (Math.max(Math.min(qual, maxQual & 0xFF), 1) & 0xFF);
    }

}