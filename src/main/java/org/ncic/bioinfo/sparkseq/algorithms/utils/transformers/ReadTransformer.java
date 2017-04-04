package org.ncic.bioinfo.sparkseq.algorithms.utils.transformers;

/**
 * Author: wbc
 */

import org.ncic.bioinfo.sparkseq.algorithms.engine.Walker;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.algorithms.utils.reports.GATKReport;

import java.util.Comparator;
import java.util.Map;

/**
 * Baseclass used to describe a read transformer like BAQ and BQSR
 * <p>
 * Read transformers are plugable infrastructure that modify read state
 * either on input, on output, or within walkers themselves.
 * <p>
 * The function apply() is called on each read seen by the GATK (after passing
 * all ReadFilters) and it can do as it sees fit (without modifying the alignment)
 * to the read to change qualities, add tags, etc.
 * <p>
 * Initialize is called once right before the GATK traversal begins providing
 * the ReadTransformer with the ability to collect and initialize data from the
 * engine.
 * <p>
 * Note that all ReadTransformers within the classpath are created and initialized.  If one
 * shouldn't be run it should look at the command line options of the engine and override
 * the enabled.
 *
 * @author depristo
 * @since 8/31/12
 */
abstract public class ReadTransformer {
    /**
     * When should this read transform be applied?
     */
    private ApplicationTime applicationTime;

    /**
     * Keep track of whether we've been initialized already, and ensure it's not called more than once.
     */
    private boolean initialized = false;

    protected ReadTransformer() {
    }

    /*
     * @return the ordering constraint for the given read transformer
     */
    public OrderingConstraint getOrderingConstraint() {
        return OrderingConstraint.DO_NOT_CARE;
    }

    /**
     * Master initialization routine.  Called to setup a ReadTransform, using it's overloaded initializeSub routine.
     *
     * @param overrideTime if not null, we will run this ReadTransform at the time provided, regardless of the timing of this read transformer itself
     * @param args         tfor initializing values
     * @param walker       the walker we intend to run
     */
    public final void initialize(final ApplicationTime overrideTime,
                                 final Map<String, Object> args, final Walker walker) {
        if (args == null) throw new IllegalArgumentException("engine cannot be null");
        if (walker == null) throw new IllegalArgumentException("walker cannot be null");

        initializeSub(args, walker);
        if (overrideTime != null) this.applicationTime = overrideTime;
        initialized = true;
    }

    /**
     * Subclasses must override this to initialize themselves
     *
     * @param args   for initializing values
     * @param walker the walker we intend to run
     * @return the point of time we'd like this read transform to be run
     */
    protected abstract void initializeSub(Map<String, Object> args, final Walker walker);

    /**
     * Should this ReadTransformer be activated?  Called after initialize, which allows this
     * read transformer to look at its arguments and decide if it should be active.  All
     * ReadTransformers must override this, as by default they are not enabled.
     *
     * @return true if this ReadTransformer should be used on the read stream
     */
    public boolean enabled() {
        return false;
    }

    /**
     * Has this transformer been initialized?
     *
     * @return true if it has
     */
    public final boolean isInitialized() {
        return initialized;
    }

    /**
     * When should we apply this read transformer?
     *
     * @return true if yes
     */
    public final ApplicationTime getApplicationTime() {
        return applicationTime;
    }

    /**
     * Primary interface function for a read transform to actually do some work
     * <p>
     * The function apply() is called on each read seen by the GATK (after passing
     * all ReadFilters) and it can do as it sees fit (without modifying the alignment)
     * to the read to change qualities, add tags, etc.
     *
     * @param read the read to transform
     * @return the transformed read
     */
    abstract public GATKSAMRecord apply(final GATKSAMRecord read);

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

    /**
     * When should a read transformer be applied?
     */
    public static enum ApplicationTime {
        /**
         * Walker does not tolerate this read transformer
         */
        FORBIDDEN,

        /**
         * apply the transformation to the incoming reads, the default
         */
        ON_INPUT,

        /**
         * apply the transformation to the outgoing read stream
         */
        ON_OUTPUT,

        /**
         * the walker will deal with the calculation itself
         */
        HANDLED_IN_WALKER
    }

    /*
     * This enum specifies the constraints that the given read transformer has relative to any other read transformers being used
     */
    public enum OrderingConstraint {
        /*
         * If 2 read transformers are both active and MUST_BE_FIRST, then an error will be generated
         */
        MUST_BE_FIRST,

        /*
         * No constraints on the ordering for this read transformer
         */
        DO_NOT_CARE,

        /*
         * If 2 read transformers are both active and MUST_BE_LAST, then an error will be generated
         */
        MUST_BE_LAST
    }

    public static class ReadTransformerComparator implements Comparator<ReadTransformer> {

        public int compare(final ReadTransformer r1, final ReadTransformer r2) {
            if (r1.getOrderingConstraint() == r2.getOrderingConstraint())
                return 0;
            return (r1.getOrderingConstraint() == OrderingConstraint.MUST_BE_FIRST || r2.getOrderingConstraint() == OrderingConstraint.MUST_BE_LAST) ? -1 : 1;
        }
    }
}
