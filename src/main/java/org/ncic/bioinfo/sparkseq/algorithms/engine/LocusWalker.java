package org.ncic.bioinfo.sparkseq.algorithms.engine;

import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLoc;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLocParser;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefMetaDataTracker;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.AlignmentContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.LocusSamTraverser;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.SamContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.filter.DuplicateReadFilter;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.filter.FailsVendorQualityCheckFilter;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.filter.Filter;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.filter.FilterUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.filter.NotPrimaryAlignmentFilter;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.filter.UnmappedReadFilter;
import org.ncic.bioinfo.sparkseq.algorithms.data.vcf.RODContentProvider;
import org.ncic.bioinfo.sparkseq.algorithms.data.vcf.RefMetaTrackerTraverser;

import java.util.List;

/**
 * Author: wbc
 */
public abstract class LocusWalker extends Walker {

    private LocusSamTraverser locusSamTraverser;
    private RefMetaTrackerTraverser refMetaTrackerTraverser;

    public LocusWalker(GenomeLocParser genomeLocParser,
                       RefContentProvider refContentProvider,
                       SamContentProvider samContentProvider,
                       List<RODContentProvider> rodContentProviderList) {
        super(genomeLocParser, refContentProvider, samContentProvider, rodContentProviderList);
    }

    public void run() {
        initialize();

        locusSamTraverser = getLocusSamTraverser();
        refMetaTrackerTraverser = new RefMetaTrackerTraverser(rodContentProviderList);
        locusSamTraverser.rewind();

        while (locusSamTraverser.hasNext()) {
            AlignmentContext alignmentContext = locusSamTraverser.next();
            GenomeLoc locus = alignmentContext.getLocation();
            ReferenceContext referenceContext =
                    refContentProvider.getReferenceContext(locus);
            RefMetaDataTracker tracker = refMetaTrackerTraverser.getOverlappedTracker(locus);
            map(tracker, referenceContext, alignmentContext);
        }

        onTraversalDone();
    }

    protected LocusSamTraverser getLocusSamTraverser() {
        FilterUtils filterUtils = getLocusWalkerFilterUtils();
        List<Filter> filters = getFilter();
        if (filters != null) {
            for (Filter filter : filters) {
                filterUtils.addFilter(filter);
            }
        }

        GenomeLoc allLocus = refContentProvider.getLocus();
        LocusSamTraverser traverser = new LocusSamTraverser(samContentProvider, allLocus, filterUtils);
        traverser.rewind();
        return traverser;
    }

    protected FilterUtils getLocusWalkerFilterUtils() {
        FilterUtils filterUtils = new FilterUtils();
        filterUtils.addFilter(new UnmappedReadFilter());
        filterUtils.addFilter(new NotPrimaryAlignmentFilter());
        filterUtils.addFilter(new DuplicateReadFilter());
        filterUtils.addFilter(new FailsVendorQualityCheckFilter());
        return filterUtils;
    }

    protected abstract List<Filter> getFilter();

    protected abstract void map(RefMetaDataTracker tracker,
                                ReferenceContext ref,
                                AlignmentContext context);

    protected abstract void onTraversalDone();
}
