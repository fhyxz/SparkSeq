package org.ncic.bioinfo.sparkseq.algorithms.utils;

/**
 * Author: wbc
 */
public class HelpConstants {
    public final static String BASE_GATK_URL = "http://www.broadinstitute.org/gatk";
    public final static String GATK_DOCS_URL = BASE_GATK_URL + "/tooldocs/";
    public final static String GATK_FORUM_URL = "http://gatkforums.broadinstitute.org/";
    public final static String GATK_FORUM_API_URL = "https://gatkforums.broadinstitute.org/api/v1/";

    /**
     * Arguments for parallelism options
     */
    public final static String ARG_TREEREDUCIBLE = "-nt";
    public final static String ARG_NANOSCHEDULABLE = "-nct";
    public final static String CMDLINE_GATK_URL = GATK_DOCS_URL + "org_broadinstitute_gatk_engine_CommandLineGATK.php";

    /**
     * Definition of the group names / categories of tools.
     * The names get parsed to make supercategories in the doc index,
     * so be careful when making big changes -- see GATKDoclet.java toMap()
     */
    public final static String DOCS_CAT_DATA = "Sequence Data Processing Tools";
    public final static String DOCS_CAT_QC = "Diagnostics and Quality Control Tools";
    public final static String DOCS_CAT_ENGINE = "Engine Parameters (available to all tools)";
    public final static String DOCS_CAT_RF = "Read Filters";
    public final static String DOCS_CAT_REFUTILS = "Reference Utilities";
    public final static String DOCS_CAT_RODCODECS = "ROD Codecs";
    public final static String DOCS_CAT_USRERR = "User Exceptions (DevZone)";
    public final static String DOCS_CAT_VALIDATION = "Validation Utilities";
    public final static String DOCS_CAT_ANNOT = "Variant Annotations";
    public final static String DOCS_CAT_VARDISC = "Variant Discovery Tools";
    public final static String DOCS_CAT_VARMANIP = "Variant Evaluation and Manipulation Tools";
    public final static String DOCS_CAT_TOY = "Toy Walkers (DevZone)";
    public final static String DOCS_CAT_HELPUTILS = "Help Utilities";

    public static String forumPost(String post) {
        return GATK_FORUM_URL + post;
    }

    /**
     * Go-to developer name codes for tracking and display purposes. Only current team members should be in this list.
     * When someone leaves, their charges should be redistributed. The actual string should be closest to the dev's
     * abbreviated name or two/three-letter nickname as possible. The code can be something else if necessary to
     * disambiguate from other variable.
     */
    public final static String MC = "MC"; // Mauricio Carneiro
    public final static String EB = "EB"; // Eric Banks
    public final static String RP = "RP"; // Ryan Poplin
    public final static String GVDA = "GG"; // Geraldine Van der Auwera
    public final static String VRR = "VRR"; // Valentin Ruano-Rubio
    public final static String ALM = "ALM"; // Ami Levy-Moonshine
    public final static String BH = "BH"; // Bertrand Haas
    public final static String JoT = "JT"; // Joel Thibault
    public final static String DR = "DR"; // David Roazen
    public final static String KS = "KS"; // Khalid Shakir
}
