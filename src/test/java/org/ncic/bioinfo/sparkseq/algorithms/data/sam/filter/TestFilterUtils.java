package org.ncic.bioinfo.sparkseq.algorithms.data.sam.filter;

import junit.framework.TestCase;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.GATKSAMRecord;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.ReadSamTraverser;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.SamContentProvider;
import org.ncic.bioinfo.sparkseq.data.basic.BasicSamRecord;
import org.ncic.bioinfo.sparkseq.data.common.ReadGroupInfo;
import org.ncic.bioinfo.sparkseq.data.common.RefContigInfo;
import org.ncic.bioinfo.sparkseq.data.common.SamHeaderInfo;
import org.ncic.bioinfo.sparkseq.data.partition.SamRecordPartition;
import org.ncic.bioinfo.sparkseq.fileio.NormalFileLoader;
import scala.collection.immutable.List;

/**
 * Author: wbc
 */
public class TestFilterUtils extends TestCase {

    public void testCreate() {
        FilterUtils filterUtils = new FilterUtils();
        Filter unmappedFilter = new UnmappedReadFilter();
        filterUtils.addFilter(unmappedFilter);
        assertEquals(1, filterUtils.filters.size());
        filterUtils.addFilter(FilterUtils.DUPLICATE_READ_FILTER);
        assertEquals(2, filterUtils.filters.size());
        String[] filters = {FilterUtils.FAILS_VENDOR_QUALITY_CHECK_FILTER, FilterUtils.HC_MAPPING_QUALITY_FILTER};
        filterUtils.addFilter(filters);
        assertEquals(4, filterUtils.filters.size());
    }

    public void testFilterWhenReadTraverse() {
        String filePath = getClass().getResource("/test.sam").getFile();
        RefContigInfo refContigInfo = RefContigInfo.apply(getClass().getResource("/human_g1k_v37.dict").getFile());
        SamHeaderInfo headerInfo = SamHeaderInfo.sortedHeader(refContigInfo, null);
        headerInfo.addReadGroupInfo(ReadGroupInfo.apply("SRR504516", "sample1"));
        List<BasicSamRecord> samRecords = NormalFileLoader.loadSam(filePath, refContigInfo);

        SamRecordPartition samRecordPartition = new SamRecordPartition(1, 0, samRecords, headerInfo);
        SamContentProvider samContentProvider = new SamContentProvider(samRecordPartition);

        String[] filterNames = {FilterUtils.UNMAPPED_READ_FILTER,
                FilterUtils.MAPPING_QUALITY_UNAVAILABLE_FILTER,
                FilterUtils.HC_MAPPING_QUALITY_FILTER};
        FilterUtils filterUtils = new FilterUtils(filterNames);
        ReadSamTraverser traverser = new ReadSamTraverser(samContentProvider, filterUtils);
        GATKSAMRecord record = traverser.next();
        assertEquals("SRR504516.519_HWI-ST423_0087:3:1:7608:2165", record.getReadName());
    }

    public void testFilterWhenLocusTraverse() {

    }
}
