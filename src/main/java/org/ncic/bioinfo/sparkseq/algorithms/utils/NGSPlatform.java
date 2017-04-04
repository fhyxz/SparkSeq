package org.ncic.bioinfo.sparkseq.algorithms.utils;

import org.apache.commons.lang3.StringUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMReadGroupRecord;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;

import java.util.LinkedList;
import java.util.List;

/**
 * Author: wbc
 */
public enum NGSPlatform {
    // note the order of elements here determines the order of matching operations, and therefore the
    // efficiency of getting a NGSPlatform from a string.
    ILLUMINA("ILLUMINA", "SLX", "SOLEXA"),
    SOLID("SOLID"),
    LS454("454"),
    COMPLETE_GENOMICS("COMPLETE"),
    PACBIO("PACBIO"),
    ION_TORRENT("IONTORRENT"),
    CAPILLARY("CAPILLARY"),
    HELICOS("HELICOS"),
    UNKNOWN("UNKNOWN");

    /**
     * Array of the prefix names in a BAM file for each of the platforms.
     */
    protected final String[] BAM_PL_NAMES;

    NGSPlatform(final String... BAM_PL_NAMES) {
        if (BAM_PL_NAMES.length == 0)
            throw new IllegalStateException("Platforms must have at least one name");

        for (int i = 0; i < BAM_PL_NAMES.length; i++)
            BAM_PL_NAMES[i] = BAM_PL_NAMES[i].toUpperCase();

        this.BAM_PL_NAMES = BAM_PL_NAMES;
    }

    /**
     * Returns a representative PL string for this platform
     *
     * @return
     */
    public final String getDefaultPlatform() {
        return BAM_PL_NAMES[0];
    }

    /**
     * Convenience get -- get the NGSPlatform from a GATKSAMRecord.
     * <p>
     * Just gets the platform from the GATKReadGroupRecord associated with this read.
     *
     * @param read a non-null GATKSAMRecord
     * @return an NGSPlatform object matching the PL field of the header, of UNKNOWN if there was no match,
     * if there is no read group for read, or there's no PL field for the read group
     */
    public static NGSPlatform fromRead(final GATKSAMRecord read) {
        if (read == null) throw new IllegalArgumentException("read cannot be null");
        final GATKSAMReadGroupRecord rg = read.getReadGroup();
        return rg == null ? UNKNOWN : rg.getNGSPlatform();
    }

    /**
     * Returns the NGSPlatform corresponding to the PL tag in the read group
     *
     * @param plFromRG -- the PL field (or equivalent) in a ReadGroup object.  Can be null => UNKNOWN
     * @return an NGSPlatform object matching the PL field of the header, or UNKNOWN if there was no match or plFromRG is null
     */
    public static NGSPlatform fromReadGroupPL(final String plFromRG) {
        if (plFromRG == null) return UNKNOWN;

        // todo -- algorithm could be implemented more efficiently, as the list of all
        // todo -- names is known upfront, so a decision tree could be used to identify
        // todo -- a prefix common to PL
        final String pl = plFromRG.toUpperCase();
        for (final NGSPlatform ngsPlatform : NGSPlatform.values()) {
            for (final String bamPLName : ngsPlatform.BAM_PL_NAMES) {
                if (pl.contains(bamPLName))
                    return ngsPlatform;
            }
        }

        return UNKNOWN;
    }

    /**
     * checks whether or not the requested platform is listed in the set (and is not unknown)
     *
     * @param platform the read group string that describes the platform used.  can be null
     * @return true if the platform is known (i.e. it's in the list and is not UNKNOWN)
     */
    public static boolean isKnown(final String platform) {
        return fromReadGroupPL(platform) != UNKNOWN;
    }

    /**
     * Get a human-readable list of platform names
     *
     * @return the list of platform names
     */
    public static String knownPlatformsString() {
        final List<String> names = new LinkedList<String>();
        for (final NGSPlatform pl : values()) {
            for (final String name : pl.BAM_PL_NAMES)
                names.add(name);
        }
        return StringUtils.join(",", names);
    }
}
