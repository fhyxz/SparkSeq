/*
* Copyright (c) 2012 The Broad Institute
* 
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
* 
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator;

import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.variantcontext.VariantContext;
import org.ncic.bioinfo.sparkseq.algorithms.utils.BaseUtils;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefMetaDataTracker;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.AlignmentContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.PileupElement;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PerReadAlleleLikelihoodMap;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.AnnotatorCompatible;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.InfoFieldAnnotation;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Percentage of N bases
 * <p>
 * <p>N occurs in a sequence when the sequencer does not have enough information to determine which base it should call. The presence of many Ns at the same site lowers our confidence in any calls made there, because it suggests that there was some kind of technical difficulty that interfered with the sequencing process.</p>
 * <p>
 * <p><b>Note that in GATK versions 3.2 and earlier, this annotation only counted N bases from reads generated with SOLiD technology. This functionality was generalized for all sequencing platforms in GATK version 3.3.</b></p>
 * <p>
 * <h3>Related annotations</h3>
 * <ul>
 * <li><b><a href="https://www.broadinstitute.org/gatk/guide/tooldocs/org_broadinstitute_gatk_tools_walkers_annotator_BaseCounts.php">BaseCounts</a></b> counts the number of A, C, G, T bases across all samples.</li>
 * </ul>
 */
public class NBaseCount extends InfoFieldAnnotation {
    public Map<String, Object> annotate(final RefMetaDataTracker tracker,
                                        final AnnotatorCompatible walker,
                                        final ReferenceContext ref,
                                        final Map<String, AlignmentContext> stratifiedContexts,
                                        final VariantContext vc,
                                        final Map<String, PerReadAlleleLikelihoodMap> stratifiedPerReadAlleleLikelihoodMap) {
        if (stratifiedContexts.size() == 0)
            return null;

        int countNBase = 0;
        int countRegularBase = 0;

        for (final AlignmentContext context : stratifiedContexts.values()) {
            for (final PileupElement p : context.getBasePileup()) {
                final String platform = p.getRead().getReadGroup().getPlatform();
                if (BaseUtils.isNBase(p.getBase())) {
                    countNBase++;
                } else if (BaseUtils.isRegularBase(p.getBase())) {
                    countRegularBase++;
                }
            }
        }
        final Map<String, Object> map = new HashMap<String, Object>();
        map.put(getKeyNames().get(0), String.format("%.4f", (double) countNBase / (double) (countNBase + countRegularBase + 1)));
        return map;
    }

    public List<String> getKeyNames() {
        return Arrays.asList("PercentNBase");
    }

    public List<VCFInfoHeaderLine> getDescriptions() {
        return Arrays.asList(new VCFInfoHeaderLine("PercentNBase", 1, VCFHeaderLineType.Float, "Percentage of N bases in the pileup"));
    }
}
