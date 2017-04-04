package org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator;

import org.ncic.bioinfo.sparkseq.algorithms.utils.EventType;
import org.ncic.bioinfo.sparkseq.algorithms.utils.RecalUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.basic.NestedIntegerArray;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.algorithms.walker.baserecalibrator.covariate.Covariate;

import java.util.LinkedList;
import java.util.List;

/**
 * Author: wbc
 */
public class RecalibrationEngine {
    final protected Covariate[] covariates;
    final private int numReadGroups;
    final private boolean lowMemoryMode;

    /**
     * Has finalizeData() been called?
     */
    private boolean finalized = false;

    /**
     * The final (merged, etc) recalibration tables, suitable for downstream analysis.
     */
    private RecalibrationTables finalRecalibrationTables = null;

    private final List<RecalibrationTables> recalibrationTablesList = new LinkedList<RecalibrationTables>();

    private final ThreadLocal<RecalibrationTables> threadLocalTables = new ThreadLocal<RecalibrationTables>() {
        private synchronized RecalibrationTables makeAndCaptureTable() {
            final RecalibrationTables newTable = new RecalibrationTables(covariates, numReadGroups);
            recalibrationTablesList.add(newTable);
            return newTable;
        }

        @Override
        protected synchronized RecalibrationTables initialValue() {
            if ( lowMemoryMode ) {
                return recalibrationTablesList.isEmpty() ? makeAndCaptureTable() : recalibrationTablesList.get(0);
            } else {
                return makeAndCaptureTable();
            }
        }
    };

    /**
     * Get a recalibration table suitable for updating the underlying RecalDatums
     *
     * May return a thread-local version, or a single version, depending on the initialization
     * arguments of this instance.
     *
     * @return updated tables
     */
    protected RecalibrationTables getUpdatableRecalibrationTables() {
        return threadLocalTables.get();
    }

    /**
     * Initialize the recalibration engine
     *
     * Called once before any calls to updateDataForRead are made.  The engine should prepare itself
     * to handle any number of updateDataForRead calls containing ReadRecalibrationInfo containing
     * keys for each of the covariates provided.
     *
     * The engine should collect match and mismatch data into the recalibrationTables data.
     *
     * @param covariates an array of the covariates we'll be using in this engine, order matters
     * @param numReadGroups the number of read groups we should use for the recalibration tables
     */
    public RecalibrationEngine(final Covariate[] covariates, final int numReadGroups, final boolean enableLowMemoryMode) {
        if ( covariates == null ) throw new IllegalArgumentException("Covariates cannot be null");
        if ( numReadGroups < 1 ) throw new IllegalArgumentException("numReadGroups must be >= 1 but got " + numReadGroups);

        this.covariates = covariates.clone();
        this.numReadGroups = numReadGroups;
        this.lowMemoryMode = enableLowMemoryMode;
    }

    /**
     * Update the recalibration statistics using the information in recalInfo
     * @param recalInfo data structure holding information about the recalibration values for a single read
     */
    public void updateDataForRead( final ReadRecalibrationInfo recalInfo ) {
        final GATKSAMRecord read = recalInfo.getRead();
        final ReadCovariates readCovariates = recalInfo.getCovariatesValues();
        final RecalibrationTables tables = getUpdatableRecalibrationTables();
        final NestedIntegerArray<RecalDatum> qualityScoreTable = tables.getQualityScoreTable();

        for( int offset = 0; offset < read.getReadBases().length; offset++ ) {
            if( ! recalInfo.skip(offset) ) {

                for (final EventType eventType : EventType.values()) {
                    final int[] keys = readCovariates.getKeySet(offset, eventType);
                    final int eventIndex = eventType.ordinal();
                    final byte qual = recalInfo.getQual(eventType, offset);
                    final double isError = recalInfo.getErrorFraction(eventType, offset);

                    RecalUtils.incrementDatumOrPutIfNecessary(qualityScoreTable, qual, isError, keys[0], keys[1], eventIndex);

                    for (int i = 2; i < covariates.length; i++) {
                        if (keys[i] < 0)
                            continue;

                        RecalUtils.incrementDatumOrPutIfNecessary(tables.getTable(i), qual, isError, keys[0], keys[1], keys[i], eventIndex);
                    }
                }
            }
        }
    }


    /**
     * Finalize, if appropriate, all derived data in recalibrationTables.
     *
     * Called once after all calls to updateDataForRead have been issued.
     *
     * Assumes that all of the principal tables (by quality score) have been completely updated,
     * and walks over this data to create summary data tables like by read group table.
     */
    public void finalizeData() {
        if ( finalized ) throw new IllegalStateException("FinalizeData() has already been called");

        // merge all of the thread-local tables
        finalRecalibrationTables = mergeThreadLocalRecalibrationTables();

        final NestedIntegerArray<RecalDatum> byReadGroupTable = finalRecalibrationTables.getReadGroupTable();
        final NestedIntegerArray<RecalDatum> byQualTable = finalRecalibrationTables.getQualityScoreTable();

        // iterate over all values in the qual table
        for ( final NestedIntegerArray.Leaf<RecalDatum> leaf : byQualTable.getAllLeaves() ) {
            final int rgKey = leaf.keys[0];
            final int eventIndex = leaf.keys[2];
            final RecalDatum rgDatum = byReadGroupTable.get(rgKey, eventIndex);
            final RecalDatum qualDatum = leaf.value;

            if ( rgDatum == null ) {
                // create a copy of qualDatum, and initialize byReadGroup table with it
                byReadGroupTable.put(new RecalDatum(qualDatum), rgKey, eventIndex);
            } else {
                // combine the qual datum with the existing datum in the byReadGroup table
                rgDatum.combine(qualDatum);
            }
        }

        finalized = true;
    }

    /**
     * Merge all of the thread local recalibration tables into a single one.
     *
     * Reuses one of the recalibration tables to hold the merged table, so this function can only be
     * called once in the engine.
     *
     * @return the merged recalibration table
     */
    private RecalibrationTables mergeThreadLocalRecalibrationTables() {
        if ( recalibrationTablesList.isEmpty() ) {
            recalibrationTablesList.add( new RecalibrationTables(covariates, numReadGroups) );
        }

        RecalibrationTables merged = null;
        for ( final RecalibrationTables table : recalibrationTablesList ) {
            if ( merged == null )
                // fast path -- if there's only only one table, so just make it the merged one
                merged = table;
            else {
                merged.combine(table);
            }
        }

        return merged;
    }

    /**
     * Get the final recalibration tables, after finalizeData() has been called
     *
     * This returns the finalized recalibration table collected by this engine.
     *
     * It is an error to call this function before finalizeData has been called
     *
     * @return the finalized recalibration table collected by this engine
     */
    public RecalibrationTables getFinalRecalibrationTables() {
        if ( ! finalized ) throw new IllegalStateException("Cannot get final recalibration tables until finalizeData() has been called");
        return finalRecalibrationTables;
    }
}
