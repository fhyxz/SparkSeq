package org.ncic.bioinfo.sparkseq.algorithms.walker.haplotypecaller.graphs;

import org.jgrapht.EdgeFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * A Test kmer graph
 *
 * User: rpoplin
 * Date: 2/6/13
 */
public final class TestGraph extends BaseGraph<DeBruijnVertex, BaseEdge> {
    /**
     * Edge factory that creates non-reference multiplicity 1 edges
     */
    private static class MyEdgeFactory implements EdgeFactory<DeBruijnVertex, BaseEdge> {
        @Override
        public BaseEdge createEdge(DeBruijnVertex sourceVertex, DeBruijnVertex targetVertex) {
            return new BaseEdge(false, 1);
        }
    }

    /**
     * Create an empty TestGraph with default kmer size
     */
    public TestGraph() {
        this(11);
    }

    /**
     * Create an empty TestGraph with kmer size
     * @param kmerSize kmer size, must be >= 1
     */
    public TestGraph(int kmerSize) {
        super(kmerSize, new MyEdgeFactory());
    }


    /**
     * Add edge to assembly graph connecting the two kmers
     * @param kmer1 the source kmer for the edge
     * @param kmer2 the target kmer for the edge
     * @param isRef true if the added edge is a reference edge
     */
    public void addKmersToGraph( final byte[] kmer1, final byte[] kmer2, final boolean isRef, final int multiplicity ) {
        if( kmer1 == null ) { throw new IllegalArgumentException("Attempting to add a null kmer to the graph."); }
        if( kmer2 == null ) { throw new IllegalArgumentException("Attempting to add a null kmer to the graph."); }
        if( kmer1.length != kmer2.length ) { throw new IllegalArgumentException("Attempting to add a kmers to the graph with different lengths."); }

        final DeBruijnVertex v1 = new DeBruijnVertex( kmer1 );
        final DeBruijnVertex v2 = new DeBruijnVertex( kmer2 );
        final BaseEdge toAdd = new BaseEdge(isRef, multiplicity);

        addVertices(v1, v2);
        addOrUpdateEdge(v1, v2, toAdd);
    }

    /**
     * Convert this kmer graph to a simple sequence graph.
     *
     * Each kmer suffix shows up as a distinct SeqVertex, attached in the same structure as in the kmer
     * graph.  Nodes that are sources are mapped to SeqVertex nodes that contain all of their sequence
     *
     * @return a newly allocated SequenceGraph
     */
    public SeqGraph convertToSequenceGraph() {
        final SeqGraph seqGraph = new SeqGraph(getKmerSize());
        final Map<DeBruijnVertex, SeqVertex> vertexMap = new HashMap<>();

        // create all of the equivalent seq graph vertices
        for ( final DeBruijnVertex dv : vertexSet() ) {
            final SeqVertex sv = new SeqVertex(dv.getAdditionalSequence(isSource(dv)));
            vertexMap.put(dv, sv);
            seqGraph.addVertex(sv);
        }

        // walk through the nodes and connect them to their equivalent seq vertices
        for( final BaseEdge e : edgeSet() ) {
            final SeqVertex seqOutV = vertexMap.get(getEdgeTarget(e));
            final SeqVertex seqInV = vertexMap.get(getEdgeSource(e));
            seqGraph.addEdge(seqInV, seqOutV, e);
        }

        return seqGraph;
    }
}
