package org.ncic.bioinfo.sparkseq.algorithms.utils;

import org.ncic.bioinfo.sparkseq.algorithms.data.basic.NestedIntegerArray;
import org.ncic.bioinfo.sparkseq.algorithms.data.basic.Pair;
import org.ncic.bioinfo.sparkseq.algorithms.utils.reports.GATKReport;
import org.ncic.bioinfo.sparkseq.algorithms.utils.reports.GATKReportTable;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMReadGroupRecord;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.QuantizationInfo;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.ReadCovariates;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.RecalDatum;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.RecalibrationTables;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.covariate.ContextCovariate;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.covariate.Covariate;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.RecalibrationArgumentCollection;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.covariate.CycleCovariate;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.covariate.QualityScoreCovariate;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.covariate.ReadGroupCovariate;
import org.ncic.bioinfo.sparkseq.exceptions.UserException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Author: wbc
 */
public class RecalUtils {
    public final static String ARGUMENT_REPORT_TABLE_TITLE = "Arguments";
    public final static String QUANTIZED_REPORT_TABLE_TITLE = "Quantized";
    public final static String READGROUP_REPORT_TABLE_TITLE = "RecalTable0";
    public final static String QUALITY_SCORE_REPORT_TABLE_TITLE = "RecalTable1";
    public final static String ALL_COVARIATES_REPORT_TABLE_TITLE = "RecalTable2";

    public final static String ARGUMENT_COLUMN_NAME = "Argument";
    public final static String ARGUMENT_VALUE_COLUMN_NAME = "Value";
    public final static String QUANTIZED_VALUE_COLUMN_NAME = "QuantizedScore";
    public static final String QUANTIZED_COUNT_COLUMN_NAME = "Count";
    public final static String READGROUP_COLUMN_NAME = "ReadGroup";
    public final static String EVENT_TYPE_COLUMN_NAME = "EventType";
    public final static String EMPIRICAL_QUALITY_COLUMN_NAME = "EmpiricalQuality";
    public final static String ESTIMATED_Q_REPORTED_COLUMN_NAME = "EstimatedQReported";
    public final static String QUALITY_SCORE_COLUMN_NAME = "QualityScore";
    public final static String COVARIATE_VALUE_COLUMN_NAME = "CovariateValue";
    public final static String COVARIATE_NAME_COLUMN_NAME = "CovariateName";
    public final static String NUMBER_OBSERVATIONS_COLUMN_NAME = "Observations";
    public final static String NUMBER_ERRORS_COLUMN_NAME = "Errors";

    private final static String COLOR_SPACE_ATTRIBUTE_TAG = "CS"; // The tag that holds the color space for SOLID bams
    private final static String COLOR_SPACE_INCONSISTENCY_TAG = "ZC"; // A new tag made up for the recalibrator which will hold an array of ints which say if this base is inconsistent with its color
    private static boolean warnUserNullPlatform = false;

    private static final String SCRIPT_FILE = "BQSR.R";

    private static final Pair<String, String> covariateValue = new Pair<String, String>(RecalUtils.COVARIATE_VALUE_COLUMN_NAME, "%s");
    private static final Pair<String, String> covariateName = new Pair<String, String>(RecalUtils.COVARIATE_NAME_COLUMN_NAME, "%s");
    private static final Pair<String, String> eventType = new Pair<String, String>(RecalUtils.EVENT_TYPE_COLUMN_NAME, "%s");
    private static final Pair<String, String> empiricalQuality = new Pair<String, String>(RecalUtils.EMPIRICAL_QUALITY_COLUMN_NAME, "%.4f");
    private static final Pair<String, String> estimatedQReported = new Pair<String, String>(RecalUtils.ESTIMATED_Q_REPORTED_COLUMN_NAME, "%.4f");
    private static final Pair<String, String> nObservations = new Pair<String, String>(RecalUtils.NUMBER_OBSERVATIONS_COLUMN_NAME, "%d");
    private static final Pair<String, String> nErrors = new Pair<String, String>(RecalUtils.NUMBER_ERRORS_COLUMN_NAME, "%.2f");

    /**
     * Generates two lists : required covariates and optional covariates based on the user's requests.
     * <p>
     * Performs the following tasks in order:
     * 1. Adds all requierd covariates in order
     * 2. Check if the user asked to use the standard covariates and adds them all if that's the case
     * 3. Adds all covariates requested by the user that were not already added by the two previous steps
     *
     * @param argumentCollection the argument collection object for the recalibration walker
     * @return a pair of ordered lists : required covariates (first) and optional covariates (second)
     */
    public static Pair<ArrayList<Covariate>, ArrayList<Covariate>> initializeCovariates(RecalibrationArgumentCollection argumentCollection) {
        //TODO 直接按照默认生成了四个coveriates，两个required两个optional的
        ArrayList<Covariate> requiredCovariates = addRequiredCovariatesToList();

        ArrayList<Covariate> optionalCovariates = new ArrayList<>();
        optionalCovariates.add(new ContextCovariate());
        optionalCovariates.add(new CycleCovariate());

        return new Pair<ArrayList<Covariate>, ArrayList<Covariate>>(requiredCovariates, optionalCovariates);
    }

    /**
     * Adds the required covariates to a covariate list
     * <p>
     * Note: this method really only checks if the classes object has the expected number of required covariates, then add them by hand.
     *
     * @return the covariate list
     */
    private static ArrayList<Covariate> addRequiredCovariatesToList() {
        ArrayList<Covariate> dest = new ArrayList<Covariate>(2);

        dest.add(new ReadGroupCovariate()); // enforce the order with RG first and QS next.
        dest.add(new QualityScoreCovariate());
        return dest;
    }

    public enum SOLID_RECAL_MODE {
        /**
         * Treat reference inserted bases as reference matching bases. Very unsafe!
         */
        DO_NOTHING,
        /**
         * Set reference inserted bases and the previous base (because of color space alignment details) to Q0. This is the default option.
         */
        SET_Q_ZERO,
        /**
         * In addition to setting the quality scores to zero, also set the base itself to 'N'. This is useful to visualize in IGV.
         */
        SET_Q_ZERO_BASE_N,
        /**
         * Look at the color quality scores and probabilistically decide to change the reference inserted base to be the base which is implied by the original color space instead of the reference.
         */
        REMOVE_REF_BIAS;

        public static SOLID_RECAL_MODE recalModeFromString(String recalMode) {
            if (recalMode.equals("DO_NOTHING"))
                return SOLID_RECAL_MODE.DO_NOTHING;
            if (recalMode.equals("SET_Q_ZERO"))
                return SOLID_RECAL_MODE.SET_Q_ZERO;
            if (recalMode.equals("SET_Q_ZERO_BASE_N"))
                return SOLID_RECAL_MODE.SET_Q_ZERO_BASE_N;
            if (recalMode.equals("REMOVE_REF_BIAS"))
                return SOLID_RECAL_MODE.REMOVE_REF_BIAS;

            throw new UserException.BadArgumentValue(recalMode, "is not a valid SOLID_RECAL_MODE value");
        }
    }

    public enum SOLID_NOCALL_STRATEGY {
        /**
         * When a no call is detected throw an exception to alert the user that recalibrating this SOLiD data is unsafe. This is the default option.
         */
        THROW_EXCEPTION,
        /**
         * Leave the read in the output bam completely untouched. This mode is only okay if the no calls are very rare.
         */
        LEAVE_READ_UNRECALIBRATED,
        /**
         * Mark these reads as failing vendor quality checks so they can be filtered out by downstream analyses.
         */
        PURGE_READ;

        public static SOLID_NOCALL_STRATEGY nocallStrategyFromString(String nocallStrategy) {
            if (nocallStrategy.equals("THROW_EXCEPTION"))
                return SOLID_NOCALL_STRATEGY.THROW_EXCEPTION;
            if (nocallStrategy.equals("LEAVE_READ_UNRECALIBRATED"))
                return SOLID_NOCALL_STRATEGY.LEAVE_READ_UNRECALIBRATED;
            if (nocallStrategy.equals("PURGE_READ"))
                return SOLID_NOCALL_STRATEGY.PURGE_READ;

            throw new UserException.BadArgumentValue(nocallStrategy, "is not a valid SOLID_NOCALL_STRATEGY value");
        }
    }

    /**
     * Section of code shared between the two recalibration walkers which uses the command line arguments to adjust attributes of the read such as quals or platform string
     *
     * @param read The read to adjust
     * @param RAC  The list of shared command line arguments
     */
    public static void parsePlatformForRead(final GATKSAMRecord read, final RecalibrationArgumentCollection RAC) {
        GATKSAMReadGroupRecord readGroup = read.getReadGroup();

        if (RAC.FORCE_PLATFORM != null && (readGroup.getPlatform() == null || !readGroup.getPlatform().equals(RAC.FORCE_PLATFORM))) {
            readGroup.setPlatform(RAC.FORCE_PLATFORM);
        }

        if (readGroup.getPlatform() == null) {
            if (RAC.DEFAULT_PLATFORM != null) {
                if (!warnUserNullPlatform) {
                    Utils.warnUser("The input .bam file contains reads with no platform information. " +
                            "Defaulting to platform = " + RAC.DEFAULT_PLATFORM + ". " +
                            "First observed at read with name = " + read.getReadName());
                    warnUserNullPlatform = true;
                }
                readGroup.setPlatform(RAC.DEFAULT_PLATFORM);
            } else {
                throw new UserException.MalformedBAM(read, "The input .bam file contains reads with no platform information. First observed at read with name = " + read.getReadName());
            }
        }
    }


    /**
     * Parse through the color space of the read and add a new tag to the SAMRecord that says which bases are
     * inconsistent with the color space. If there is a no call in the color space, this method returns false meaning
     * this read should be skipped
     *
     * @param strategy the strategy used for SOLID no calls
     * @param read     The SAMRecord to parse
     * @return true if this read is consistent or false if this read should be skipped
     */
    public static boolean isColorSpaceConsistent(final SOLID_NOCALL_STRATEGY strategy, final GATKSAMRecord read) {
        if (!ReadUtils.isSOLiDRead(read)) // If this is a SOLID read then we have to check if the color space is inconsistent. This is our only sign that SOLID has inserted the reference base
            return true;

        // Haven't calculated the inconsistency array yet for this read
        if (read.getAttribute(RecalUtils.COLOR_SPACE_INCONSISTENCY_TAG) == null) {
            final Object attr = read.getAttribute(RecalUtils.COLOR_SPACE_ATTRIBUTE_TAG);
            if (attr != null) {
                byte[] colorSpace;
                if (attr instanceof String)
                    colorSpace = ((String) attr).getBytes();
                else
                    throw new UserException.MalformedBAM(read, String.format("Value encoded by %s in %s isn't a string!", RecalUtils.COLOR_SPACE_ATTRIBUTE_TAG, read.getReadName()));

                final boolean badColor = hasNoCallInColorSpace(colorSpace);
                if (badColor) {
                    if (strategy == SOLID_NOCALL_STRATEGY.LEAVE_READ_UNRECALIBRATED) {
                        return false; // can't recalibrate a SOLiD read with no calls in the color space, and the user wants to skip over them
                    } else if (strategy == SOLID_NOCALL_STRATEGY.PURGE_READ) {
                        read.setReadFailsVendorQualityCheckFlag(true);
                        return false;
                    }
                }

                byte[] readBases = read.getReadBases(); // Loop over the read and calculate first the inferred bases from the color and then check if it is consistent with the read
                if (read.getReadNegativeStrandFlag())
                    readBases = BaseUtils.simpleReverseComplement(read.getReadBases());

                final byte[] inconsistency = new byte[readBases.length];
                int i;
                byte prevBase = colorSpace[0]; // The sentinel
                for (i = 0; i < readBases.length; i++) {
                    final byte thisBase = getNextBaseFromColor(read, prevBase, colorSpace[i + 1]);
                    inconsistency[i] = (byte) (thisBase == readBases[i] ? 0 : 1);
                    prevBase = readBases[i];
                }
                read.setAttribute(RecalUtils.COLOR_SPACE_INCONSISTENCY_TAG, inconsistency);
            } else if (strategy == SOLID_NOCALL_STRATEGY.THROW_EXCEPTION) // if the strategy calls for an exception, throw it
                throw new UserException.MalformedBAM(read, "Unable to find color space information in SOLiD read. First observed at read with name = " + read.getReadName() + " Unfortunately this .bam file can not be recalibrated without color space information because of potential reference bias.");

            else
                return false; // otherwise, just skip the read
        }

        return true;
    }

    private static boolean hasNoCallInColorSpace(final byte[] colorSpace) {
        final int length = colorSpace.length;
        for (int i = 1; i < length; i++) {  // skip the sentinal
            final byte color = colorSpace[i];
            if (color != (byte) '0' && color != (byte) '1' && color != (byte) '2' && color != (byte) '3') {
                return true; // There is a bad color in this SOLiD read
            }
        }

        return false; // There aren't any color no calls in this SOLiD read
    }

    /**
     * Given the base and the color calculate the next base in the sequence
     *
     * @param read     the read
     * @param prevBase The base
     * @param color    The color
     * @return The next base in the sequence
     */
    private static byte getNextBaseFromColor(GATKSAMRecord read, final byte prevBase, final byte color) {
        switch (color) {
            case '0':
                return prevBase;
            case '1':
                return performColorOne(prevBase);
            case '2':
                return performColorTwo(prevBase);
            case '3':
                return performColorThree(prevBase);
            default:
                throw new UserException.MalformedBAM(read, "Unrecognized color space in SOLID read, color = " + (char) color +
                        " Unfortunately this bam file can not be recalibrated without full color space information because of potential reference bias.");
        }
    }

    /**
     * Check if this base is inconsistent with its color space. If it is then SOLID inserted the reference here and we should reduce the quality
     *
     * @param read   The read which contains the color space to check against
     * @param offset The offset in the read at which to check
     * @return Returns true if the base was inconsistent with the color space
     */
    public static boolean isColorSpaceConsistent(final GATKSAMRecord read, final int offset) {
        final Object attr = read.getAttribute(RecalUtils.COLOR_SPACE_INCONSISTENCY_TAG);
        if (attr != null) {
            final byte[] inconsistency = (byte[]) attr;
            // NOTE: The inconsistency array is in the direction of the read, not aligned to the reference!
            if (read.getReadNegativeStrandFlag()) { // Negative direction
                return inconsistency[inconsistency.length - offset - 1] == (byte) 0;
            } else { // Forward direction
                return inconsistency[offset] == (byte) 0;
            }
        } else { // No inconsistency array, so nothing is inconsistent
            return true;
        }
    }

    /**
     * Computes all requested covariates for every offset in the given read
     * by calling covariate.getValues(..).
     * <p>
     * It populates an array of covariate values where result[i][j] is the covariate
     * value for the ith position in the read and the jth covariate in
     * reqeustedCovariates list.
     *
     * @param read                The read for which to compute covariate values.
     * @param requestedCovariates The list of requested covariates.
     * @return a matrix with all the covariates calculated for every base in the read
     */
    public static ReadCovariates computeCovariates(final GATKSAMRecord read, final Covariate[] requestedCovariates) {
        final ReadCovariates readCovariates = new ReadCovariates(read.getReadLength(), requestedCovariates.length);
        computeCovariates(read, requestedCovariates, readCovariates);
        return readCovariates;
    }

    /**
     * Computes all requested covariates for every offset in the given read
     * by calling covariate.getValues(..).
     * <p>
     * It populates an array of covariate values where result[i][j] is the covariate
     * value for the ith position in the read and the jth covariate in
     * reqeustedCovariates list.
     *
     * @param read                The read for which to compute covariate values.
     * @param requestedCovariates The list of requested covariates.
     * @param resultsStorage      The object to store the covariate values
     */
    public static void computeCovariates(final GATKSAMRecord read, final Covariate[] requestedCovariates, final ReadCovariates resultsStorage) {
        // Loop through the list of requested covariates and compute the values of each covariate for all positions in this read
        for (int i = 0; i < requestedCovariates.length; i++) {
            resultsStorage.setCovariateIndex(i);
            requestedCovariates[i].recordValues(read, resultsStorage);
        }
    }

    /**
     * Perform a certain transversion (A <-> C or G <-> T) on the base.
     *
     * @param base the base [AaCcGgTt]
     * @return the transversion of the base, or the input base if it's not one of the understood ones
     */
    private static byte performColorOne(byte base) {
        switch (base) {
            case 'A':
            case 'a':
                return 'C';
            case 'C':
            case 'c':
                return 'A';
            case 'G':
            case 'g':
                return 'T';
            case 'T':
            case 't':
                return 'G';
            default:
                return base;
        }
    }

    /**
     * Perform a transition (A <-> G or C <-> T) on the base.
     *
     * @param base the base [AaCcGgTt]
     * @return the transition of the base, or the input base if it's not one of the understood ones
     */
    private static byte performColorTwo(byte base) {
        switch (base) {
            case 'A':
            case 'a':
                return 'G';
            case 'C':
            case 'c':
                return 'T';
            case 'G':
            case 'g':
                return 'A';
            case 'T':
            case 't':
                return 'C';
            default:
                return base;
        }
    }

    /**
     * Return the complement (A <-> T or C <-> G) of a base.
     *
     * @param base the base [AaCcGgTt]
     * @return the complementary base, or the input base if it's not one of the understood ones
     */
    private static byte performColorThree(byte base) {
        switch (base) {
            case 'A':
            case 'a':
                return 'T';
            case 'C':
            case 'c':
                return 'G';
            case 'G':
            case 'g':
                return 'C';
            case 'T':
            case 't':
                return 'A';
            default:
                return base;
        }
    }

    /**
     * Combines the recalibration data for table1 and table2 into table1
     * <p>
     * Note that table1 is the destination, so it is modified
     *
     * @param table1 the destination table to merge table2 into
     * @param table2 the source table to merge into table1
     */
    public static void combineTables(final NestedIntegerArray<RecalDatum> table1, final NestedIntegerArray<RecalDatum> table2) {
        if (table1 == null) throw new IllegalArgumentException("table1 cannot be null");
        if (table2 == null) throw new IllegalArgumentException("table2 cannot be null");
        if (!Arrays.equals(table1.getDimensions(), table2.getDimensions()))
            throw new IllegalArgumentException("Table1 " + Utils.join(",", table1.getDimensions()) + " not equal to " + Utils.join(",", table2.getDimensions()));

        for (final NestedIntegerArray.Leaf<RecalDatum> row : table2.getAllLeaves()) {
            final RecalDatum myDatum = table1.get(row.keys);

            if (myDatum == null)
                table1.put(row.value, row.keys);
            else
                myDatum.combine(row.value);
        }
    }

    /**
     * Increments the RecalDatum at the specified position in the specified table, or put a new item there
     * if there isn't already one.
     * <p>
     * Does this in a thread-safe way WITHOUT being synchronized: relies on the behavior of NestedIntegerArray.put()
     * to return false if another thread inserts a new item at our position in the middle of our put operation.
     *
     * @param table   the table that holds/will hold our item
     * @param qual    qual for this event
     * @param isError error value for this event
     * @param keys    location in table of our item
     */
    public static void incrementDatumOrPutIfNecessary(final NestedIntegerArray<RecalDatum> table,
                                                      final byte qual,
                                                      final double isError,
                                                      final int... keys) {
        final RecalDatum existingDatum = table.get(keys);

        if (existingDatum == null) {
            // No existing item, try to put a new one
            if (!table.put(createDatumObject(qual, isError), keys)) {
                // Failed to put a new item because another thread came along and put an item here first.
                // Get the newly-put item and increment it (item is guaranteed to exist at this point)
                table.get(keys).increment(1L, isError);
            }
        } else {
            // Easy case: already an item here, so increment it
            existingDatum.increment(1L, isError);
        }
    }

    /**
     * creates a datum object with one observation and one or zero error
     *
     * @param reportedQual the quality score reported by the instrument for this base
     * @param isError      whether or not the observation is an error
     * @return a new RecalDatum object with the observation and the error
     */
    private static RecalDatum createDatumObject(final byte reportedQual, final double isError) {
        return new RecalDatum(1, isError, reportedQual);
    }

    // report

    private static List<GATKReportTable> generateReportTables(final RecalibrationTables recalibrationTables, final Covariate[] requestedCovariates, boolean sortByCols) {
        List<GATKReportTable> result = new LinkedList<GATKReportTable>();
        int reportTableIndex = 0;
        int rowIndex = 0;
        final Map<Covariate, String> covariateNameMap = new HashMap<Covariate, String>(requestedCovariates.length);
        for (final Covariate covariate : requestedCovariates)
            covariateNameMap.put(covariate, parseCovariateName(covariate));

        for (int tableIndex = 0; tableIndex < recalibrationTables.numTables(); tableIndex++) {

            final ArrayList<Pair<String, String>> columnNames = new ArrayList<Pair<String, String>>(); // initialize the array to hold the column names
            columnNames.add(new Pair<String, String>(covariateNameMap.get(requestedCovariates[0]), "%s")); // save the required covariate name so we can reference it in the future
            if (tableIndex != RecalibrationTables.TableType.READ_GROUP_TABLE.ordinal()) {
                columnNames.add(new Pair<String, String>(covariateNameMap.get(requestedCovariates[1]), "%s")); // save the required covariate name so we can reference it in the future
                if (tableIndex >= RecalibrationTables.TableType.OPTIONAL_COVARIATE_TABLES_START.ordinal()) {
                    columnNames.add(covariateValue);
                    columnNames.add(covariateName);
                }
            }

            columnNames.add(eventType); // the order of these column names is important here
            columnNames.add(empiricalQuality);
            if (tableIndex == RecalibrationTables.TableType.READ_GROUP_TABLE.ordinal())
                columnNames.add(estimatedQReported); // only the read group table needs the estimated Q reported
            columnNames.add(nObservations);
            columnNames.add(nErrors);

            final GATKReportTable reportTable;
            if (tableIndex <= RecalibrationTables.TableType.OPTIONAL_COVARIATE_TABLES_START.ordinal()) {
                if (sortByCols) {
                    reportTable = new GATKReportTable("RecalTable" + reportTableIndex++, "", columnNames.size(), GATKReportTable.TableSortingWay.SORT_BY_COLUMN);
                } else {
                    reportTable = new GATKReportTable("RecalTable" + reportTableIndex++, "", columnNames.size(), GATKReportTable.TableSortingWay.DO_NOT_SORT);
                }
                for (final Pair<String, String> columnName : columnNames)
                    reportTable.addColumn(columnName.getFirst(), columnName.getSecond());
                rowIndex = 0; // reset the row index since we're starting with a new table
            } else {
                reportTable = result.get(RecalibrationTables.TableType.OPTIONAL_COVARIATE_TABLES_START.ordinal());
            }

            final NestedIntegerArray<RecalDatum> table = recalibrationTables.getTable(tableIndex);
            for (final NestedIntegerArray.Leaf row : table.getAllLeaves()) {
                final RecalDatum datum = (RecalDatum) row.value;
                final int[] keys = row.keys;

                int columnIndex = 0;
                int keyIndex = 0;
                reportTable.set(rowIndex, columnNames.get(columnIndex++).getFirst(), requestedCovariates[0].formatKey(keys[keyIndex++]));
                if (tableIndex != RecalibrationTables.TableType.READ_GROUP_TABLE.ordinal()) {
                    reportTable.set(rowIndex, columnNames.get(columnIndex++).getFirst(), requestedCovariates[1].formatKey(keys[keyIndex++]));
                    if (tableIndex >= RecalibrationTables.TableType.OPTIONAL_COVARIATE_TABLES_START.ordinal()) {
                        final Covariate covariate = requestedCovariates[tableIndex];

                        reportTable.set(rowIndex, columnNames.get(columnIndex++).getFirst(), covariate.formatKey(keys[keyIndex++]));
                        reportTable.set(rowIndex, columnNames.get(columnIndex++).getFirst(), covariateNameMap.get(covariate));
                    }
                }

                final EventType event = EventType.eventFrom(keys[keyIndex]);
                reportTable.set(rowIndex, columnNames.get(columnIndex++).getFirst(), event.toString());

                reportTable.set(rowIndex, columnNames.get(columnIndex++).getFirst(), datum.getEmpiricalQuality());
                if (tableIndex == RecalibrationTables.TableType.READ_GROUP_TABLE.ordinal())
                    reportTable.set(rowIndex, columnNames.get(columnIndex++).getFirst(), datum.getEstimatedQReported()); // we only add the estimated Q reported in the RG table
                reportTable.set(rowIndex, columnNames.get(columnIndex++).getFirst(), datum.getNumObservations());
                reportTable.set(rowIndex, columnNames.get(columnIndex).getFirst(), datum.getNumMismatches());

                rowIndex++;
            }
            result.add(reportTable);
        }

        return result;
    }

    private static String parseCovariateName(final Covariate covariate) {
        return covariate.getClass().getSimpleName().split("Covariate")[0];
    }

    /**
     * Return a human-readable string representing the used covariates
     *
     * @param requestedCovariates a vector of covariates
     * @return a non-null comma-separated string
     */
    public static String covariateNames(final Covariate[] requestedCovariates) {
        final List<String> names = new ArrayList<String>(requestedCovariates.length);
        for (final Covariate cov : requestedCovariates)
            names.add(cov.getClass().getSimpleName());
        return Utils.join(",", names);
    }

    public static GATKReport getRecalibrationReport(final RecalibrationArgumentCollection RAC,
                                                    final QuantizationInfo quantizationInfo,
                                                    final RecalibrationTables recalibrationTables,
                                                    final Covariate[] requestedCovariates,
                                                    boolean sortByCols) {
        return createRecalibrationGATKReport(
                RAC.generateReportTable(covariateNames(requestedCovariates)),
                quantizationInfo.generateReportTable(sortByCols),
                generateReportTables(recalibrationTables, requestedCovariates, sortByCols));
    }

    /**
     * Creates a consolidated GATK report from the tables. Report can then be written to a stream via GATKReport.print(PrintStream).
     *
     * @param argumentTable     Argument table
     * @param quantizationTable Quantization Table
     * @param recalTables       Other recal tables
     * @return GATK report
     */
    private static GATKReport createRecalibrationGATKReport(final GATKReportTable argumentTable, final GATKReportTable quantizationTable, final List<GATKReportTable> recalTables) {
        final GATKReport report = new GATKReport();
        report.addTable(argumentTable);
        report.addTable(quantizationTable);
        report.addTables(recalTables);
        return report;
    }
}