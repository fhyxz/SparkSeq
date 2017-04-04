package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator;

import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.GenotypesContext;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFHeaderLineType;
import htsjdk.variant.vcf.VCFInfoHeaderLine;
import htsjdk.variant.vcf.VCFStandardHeaderLines;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLoc;
import org.ncic.bioinfo.sparkseq.algorithms.utils.GenomeLocParser;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.RefMetaDataTracker;
import org.ncic.bioinfo.sparkseq.algorithms.data.reference.ReferenceContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.sam.AlignmentContext;
import org.ncic.bioinfo.sparkseq.algorithms.data.vcf.RodBinding;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.PerReadAlleleLikelihoodMap;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.readlikelihood.ReadLikelihoods;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.ActiveRegionBasedAnnotation;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.AnnotatorCompatible;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.GenotypeAnnotation;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.InfoFieldAnnotation;
import org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.annotator.interfaces.VariantAnnotatorAnnotation;
import org.ncic.bioinfo.sparkseq.exceptions.UserException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Author: wbc
 */
public class VariantAnnotatorEngine {
    private List<InfoFieldAnnotation> requestedInfoAnnotations = Collections.emptyList();
    private List<GenotypeAnnotation> requestedGenotypeAnnotations = Collections.emptyList();
    private List<VAExpression> requestedExpressions = new ArrayList<>();

    private final AnnotatorCompatible walker;
    private final GenomeLocParser parser;

    VariantOverlapAnnotator variantOverlapAnnotator = null;

    protected static class VAExpression {

        public String fullName, fieldName;
        public RodBinding<VariantContext> binding;

        public VAExpression(String fullExpression, List<RodBinding<VariantContext>> bindings) {
            final int indexOfDot = fullExpression.lastIndexOf(".");
            if (indexOfDot == -1)
                throw new UserException.BadArgumentValue(fullExpression, "it should be in rodname.value format");

            fullName = fullExpression;
            fieldName = fullExpression.substring(indexOfDot + 1);

            final String bindingName = fullExpression.substring(0, indexOfDot);
            for (final RodBinding<VariantContext> rod : bindings) {
                if (rod.getName().equals(bindingName)) {
                    binding = rod;
                    break;
                }
            }
        }
    }

    // use this constructor if you want to select specific annotations (and/or interfaces)
    public VariantAnnotatorEngine(List<String> infoFieldAnnotations, List<String> genotypeAnnotations,
                                  List<String> annotationsToExclude, AnnotatorCompatible walker, GenomeLocParser parser) {
        this.walker = walker;
        this.parser = parser;
        initializeAnnotations(infoFieldAnnotations, genotypeAnnotations, annotationsToExclude);
        initializeDBs(parser);
    }

    // select specific expressions to use
    public void initializeExpressions(Set<String> expressionsToUse) {
        // set up the expressions
        for (final String expression : expressionsToUse)
            requestedExpressions.add(new VAExpression(expression, walker.getResourceRodBindings()));
    }

    protected List<VAExpression> getRequestedExpressions() {
        return requestedExpressions;
    }

    private void initializeAnnotations(List<String> infoFieldAnnotations, List<String> genotypeAnnotations, List<String> annotationsToExclude) {
        requestedInfoAnnotations = AnnotationInterfaceManager.createInfoFieldAnnotations(infoFieldAnnotations);
        requestedGenotypeAnnotations = AnnotationInterfaceManager.createGenotypeAnnotations(genotypeAnnotations);
        excludeAnnotations(annotationsToExclude);
    }

    private void excludeAnnotations(List<String> annotationsToExclude) {
        if (annotationsToExclude.size() == 0)
            return;

        final List<InfoFieldAnnotation> tempRequestedInfoAnnotations = new ArrayList<>(requestedInfoAnnotations.size());
        for (final InfoFieldAnnotation annotation : requestedInfoAnnotations) {
            if (!annotationsToExclude.contains(annotation.getClass().getSimpleName()))
                tempRequestedInfoAnnotations.add(annotation);
        }
        requestedInfoAnnotations = tempRequestedInfoAnnotations;

        final List<GenotypeAnnotation> tempRequestedGenotypeAnnotations = new ArrayList<>(requestedGenotypeAnnotations.size());
        for (final GenotypeAnnotation annotation : requestedGenotypeAnnotations) {
            if (!annotationsToExclude.contains(annotation.getClass().getSimpleName()))
                tempRequestedGenotypeAnnotations.add(annotation);
        }
        requestedGenotypeAnnotations = tempRequestedGenotypeAnnotations;
    }

    private void initializeDBs(final GenomeLocParser parser) {
        // check to see whether comp rods were included
        RodBinding<VariantContext> dbSNPBinding = walker.getDbsnpRodBinding();
        if (dbSNPBinding != null && !dbSNPBinding.isBound())
            dbSNPBinding = null;

        final Map<RodBinding<VariantContext>, String> overlapBindings = new LinkedHashMap<>();
        for (final RodBinding<VariantContext> b : walker.getCompRodBindings())
            if (b.isBound()) overlapBindings.put(b, b.getName());
        if (dbSNPBinding != null && !overlapBindings.keySet().contains(VCFConstants.DBSNP_KEY))
            overlapBindings.put(dbSNPBinding, VCFConstants.DBSNP_KEY); // add overlap detection with DBSNP by default

        variantOverlapAnnotator = new VariantOverlapAnnotator(dbSNPBinding, overlapBindings, parser);
    }

    public void invokeAnnotationInitializationMethods(final Set<VCFHeaderLine> headerLines) {
        for (final VariantAnnotatorAnnotation annotation : requestedInfoAnnotations) {
            annotation.initialize(walker, parser, headerLines);
        }

        for (final VariantAnnotatorAnnotation annotation : requestedGenotypeAnnotations) {
            annotation.initialize(walker, parser, headerLines);
        }
    }

    public Set<VCFHeaderLine> getVCFAnnotationDescriptions() {
        final Set<VCFHeaderLine> descriptions = new HashSet<>();

        for (final InfoFieldAnnotation annotation : requestedInfoAnnotations)
            descriptions.addAll(annotation.getDescriptions());
        for (final GenotypeAnnotation annotation : requestedGenotypeAnnotations)
            descriptions.addAll(annotation.getDescriptions());
        for (final String db : variantOverlapAnnotator.getOverlapNames()) {
            if (VCFStandardHeaderLines.getInfoLine(db, false) != null)
                descriptions.add(VCFStandardHeaderLines.getInfoLine(db));
            else
                descriptions.add(new VCFInfoHeaderLine(db, 0, VCFHeaderLineType.Flag, db + " Membership"));
        }

        return descriptions;
    }

    public VariantContext annotateContext(final RefMetaDataTracker tracker,
                                          final ReferenceContext ref,
                                          final Map<String, AlignmentContext> stratifiedContexts,
                                          final VariantContext vc) {
        return annotateContext(tracker, ref, stratifiedContexts, vc, null);
    }

    public VariantContext annotateContext(final RefMetaDataTracker tracker,
                                          final ReferenceContext ref,
                                          final Map<String, AlignmentContext> stratifiedContexts,
                                          final VariantContext vc,
                                          final Map<String, PerReadAlleleLikelihoodMap> perReadAlleleLikelihoodMap) {
        final Map<String, Object> infoAnnotations = new LinkedHashMap<>(vc.getAttributes());

        // annotate expressions where available
        annotateExpressions(tracker, ref.getLocus(), infoAnnotations);

        // go through all the requested info annotationTypes
        for (final InfoFieldAnnotation annotationType : requestedInfoAnnotations) {
            final Map<String, Object> annotationsFromCurrentType = annotationType.annotate(tracker, walker, ref, stratifiedContexts, vc, perReadAlleleLikelihoodMap);
            if (annotationsFromCurrentType != null)
                infoAnnotations.putAll(annotationsFromCurrentType);
        }

        // generate a new annotated VC
        final VariantContextBuilder builder = new VariantContextBuilder(vc).attributes(infoAnnotations);

        // annotate genotypes, creating another new VC in the process
        final VariantContext annotated = builder.genotypes(annotateGenotypes(tracker, ref, stratifiedContexts, vc, perReadAlleleLikelihoodMap)).make();

        // annotate db occurrences
        return annotateDBs(tracker, annotated);
    }

    public VariantContext annotateContextForActiveRegion(final RefMetaDataTracker tracker,
                                                         final ReadLikelihoods<Allele> readLikelihoods,
                                                         final VariantContext vc) {
        //TODO we transform the read-likelihood into the Map^2 previous version for the sake of not changing of not changing annotation interface.
        //TODO should we change those interfaces?
        final Map<String, PerReadAlleleLikelihoodMap> annotationLikelihoods = readLikelihoods.toPerReadAlleleLikelihoodMap();
        return annotateContextForActiveRegion(tracker, annotationLikelihoods, vc);
    }

    public VariantContext annotateContextForActiveRegion(final RefMetaDataTracker tracker,
                                                         final Map<String, PerReadAlleleLikelihoodMap> perReadAlleleLikelihoodMap,
                                                         final VariantContext vc) {
        final Map<String, Object> infoAnnotations = new LinkedHashMap<>(vc.getAttributes());

        // go through all the requested info annotationTypes
        for (final InfoFieldAnnotation annotationType : requestedInfoAnnotations) {
            if (!(annotationType instanceof ActiveRegionBasedAnnotation))
                continue;

            final Map<String, Object> annotationsFromCurrentType = annotationType.annotate(perReadAlleleLikelihoodMap, vc);
            if (annotationsFromCurrentType != null) {
                infoAnnotations.putAll(annotationsFromCurrentType);
            }
        }

        // generate a new annotated VC
        final VariantContextBuilder builder = new VariantContextBuilder(vc).attributes(infoAnnotations);

        // annotate genotypes, creating another new VC in the process
        final VariantContext annotated = builder.genotypes(annotateGenotypes(null, null, null, vc, perReadAlleleLikelihoodMap)).make();

        // annotate db occurrences
        return annotateDBs(tracker, annotated);
    }

    /**
     * Annotate the ID field and other DBs for the given Variant Context
     *
     * @param tracker ref meta data tracker (cannot be null)
     * @param vc      variant context to annotate
     * @return non-null annotated version of vc
     */
    private VariantContext annotateDBs(final RefMetaDataTracker tracker, VariantContext vc) {
        return variantOverlapAnnotator.annotateOverlaps(tracker, variantOverlapAnnotator.annotateRsID(tracker, vc));
    }

    private void annotateExpressions(final RefMetaDataTracker tracker, final GenomeLoc loc, final Map<String, Object> infoAnnotations) {
        for (final VAExpression expression : requestedExpressions) {
            final Collection<VariantContext> VCs = tracker.getValues(expression.binding, loc);
            if (VCs.size() == 0)
                continue;

            final VariantContext vc = VCs.iterator().next();
            // special-case the ID field
            if (expression.fieldName.equals("ID")) {
                if (vc.hasID())
                    infoAnnotations.put(expression.fullName, vc.getID());
            } else if (expression.fieldName.equals("ALT")) {
                infoAnnotations.put(expression.fullName, vc.getAlternateAllele(0).getDisplayString());

            } else if (vc.hasAttribute(expression.fieldName)) {
                infoAnnotations.put(expression.fullName, vc.getAttribute(expression.fieldName));

            }
        }
    }


    private GenotypesContext annotateGenotypes(final RefMetaDataTracker tracker,
                                               final ReferenceContext ref, final Map<String, AlignmentContext> stratifiedContexts,
                                               final VariantContext vc,
                                               final Map<String, PerReadAlleleLikelihoodMap> stratifiedPerReadAlleleLikelihoodMap) {
        if (requestedGenotypeAnnotations.isEmpty())
            return vc.getGenotypes();

        final GenotypesContext genotypes = GenotypesContext.create(vc.getNSamples());
        for (final Genotype genotype : vc.getGenotypes()) {
            AlignmentContext context = null;
            PerReadAlleleLikelihoodMap perReadAlleleLikelihoodMap = null;
            if (stratifiedContexts != null)
                context = stratifiedContexts.get(genotype.getSampleName());
            if (stratifiedPerReadAlleleLikelihoodMap != null)
                perReadAlleleLikelihoodMap = stratifiedPerReadAlleleLikelihoodMap.get(genotype.getSampleName());


            final GenotypeBuilder gb = new GenotypeBuilder(genotype);
            for (final GenotypeAnnotation annotation : requestedGenotypeAnnotations) {
                annotation.annotate(tracker, walker, ref, context, vc, genotype, gb, perReadAlleleLikelihoodMap);
            }
            genotypes.add(gb.make());
        }

        return genotypes;
    }
}
