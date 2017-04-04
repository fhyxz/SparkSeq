package org.ncic.bioinfo.sparkseq.algorithms.utils;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * Author: wbc
 */
public class DeprecatedToolChecks {

    // Mapping from walker name to major version number where the walker first disappeared and optional replacement options
    private static Object2ObjectMap deprecatedGATKWalkers = new Object2ObjectOpenHashMap();
    static {
        // Indicate recommended replacement in parentheses if applicable
        deprecatedGATKWalkers.put("ReduceReads", "3.0 (use recommended best practices pipeline with the HaplotypeCaller)");
        deprecatedGATKWalkers.put("CountCovariates", "2.0 (use BaseRecalibrator instead; see documentation for usage)");
        deprecatedGATKWalkers.put("TableRecalibration", "2.0 (use PrintReads with -BQSR instead; see documentation for usage)");
        deprecatedGATKWalkers.put("AlignmentWalker", "2.2 (no replacement)");
        deprecatedGATKWalkers.put("CountBestAlignments", "2.2 (no replacement)");
        deprecatedGATKWalkers.put("SomaticIndelDetector", "2.0 (replaced by the standalone tool Indelocator; see Cancer Tools documentation)");
    }

    // Mapping from walker name to major version number where the walker first disappeared and optional replacement options
    private static Object2ObjectMap deprecatedGATKAnnotations = new Object2ObjectOpenHashMap();
    static {
        // Same comments as for walkers
        deprecatedGATKAnnotations.put("DepthOfCoverage", "2.4 (renamed to Coverage)");
    }

    /**
     * Utility method to check whether a given walker has been deprecated in a previous GATK release
     *
     * @param walkerName   the walker class name (not the full package) to check
     */
    public static boolean isDeprecatedWalker(final String walkerName) {
        return deprecatedGATKWalkers.containsKey(walkerName);
    }

    /**
     * Utility method to check whether a given annotation has been deprecated in a previous GATK release
     *
     * @param annotationName   the annotation class name (not the full package) to check
     */
    public static boolean isDeprecatedAnnotation(final String annotationName) {
        return deprecatedGATKAnnotations.containsKey(annotationName);
    }

    /**
     * Utility method to pull up the version number at which a walker was deprecated and the suggested replacement, if any
     *
     * @param walkerName   the walker class name (not the full package) to check
     */
    public static String getWalkerDeprecationInfo(final String walkerName) {
        return deprecatedGATKWalkers.get(walkerName).toString();
    }

    /**
     * Utility method to pull up the version number at which an annotation was deprecated and the suggested replacement, if any
     *
     * @param annotationName   the annotation class name (not the full package) to check
     */
    public static String getAnnotationDeprecationInfo(final String annotationName) {
        return deprecatedGATKAnnotations.get(annotationName).toString();
    }

}
