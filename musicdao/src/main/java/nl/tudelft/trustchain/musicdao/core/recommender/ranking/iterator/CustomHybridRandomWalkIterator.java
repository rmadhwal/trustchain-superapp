/*
 * Based largely on RandomWalkVertexIterator found in JGraphT
 *
 * In addition to their functionality, this iterator resets the random walk with a given probability
 */
package nl.tudelft.trustchain.musicdao.core.recommender.ranking.iterator;

import android.os.Build;
import nl.tudelft.trustchain.musicdao.core.recommender.model.Node;
import nl.tudelft.trustchain.musicdao.core.recommender.model.NodeOrSong;
import nl.tudelft.trustchain.musicdao.core.recommender.model.SongRecommendation;
import org.jetbrains.annotations.NotNull;
import org.jgrapht.Graph;
import org.jgrapht.Graphs;

import java.util.*;

/**
 * A hybrid personalized PageRank/SALSA random walk iterator for undirected bipartite graphs.
 *
 * Given a bipartite graph with vertexes NodeOrSong and S and a starting point in V, we select a neighbour
 * in S weighted by edges from the starting point to S. After this, we take an edge back to
 * NodeOrSong from S weighted by the PageRank of the node set of edges back.
 *
 * @param <E> the graph edge type
 */
public class CustomHybridRandomWalkIterator<E>
    implements
    Iterator<NodeOrSong>
{
    private final Random rng;
    private final Graph<NodeOrSong, E> graph;
    private final Map<Node, Double> outEdgesTotalWeight;
    private final Map<SongRecommendation, Double> personalizedPageRankTotalWeight;
    private final long maxHops;

    public NodeOrSong getNextVertex() {
        return nextVertex;
    }

    public void setNextVertex(NodeOrSong nextVertex) {
        this.nextVertex = nextVertex;
    }

    private long hops;

    public long getHops() {
        return hops;
    }

    public void setHops(long hops) {
        this.hops = hops;
    }

    private NodeOrSong nextVertex;
    private Node lastNode;
    private final float resetProbability;

    /**
     * Create a new iterator
     *
     * @param graph the graph
     * @param vertex the starting vertex
     * @param maxHops maximum hops to perform during the walk
     * @param resetProbability probability between 0 and 1 with which to reset the random walk
     * @param rng the random number generator
     */
    public CustomHybridRandomWalkIterator(
        Graph<NodeOrSong, E> graph, Node vertex, long maxHops, float resetProbability, Random rng)
    {
        this.graph = Objects.requireNonNull(graph);
        this.outEdgesTotalWeight = new HashMap<>();
        this.personalizedPageRankTotalWeight = new HashMap<>();
        this.hops = 0;
        this.nextVertex = Objects.requireNonNull(vertex);
        if (!graph.containsVertex(vertex)) {
            throw new IllegalArgumentException("Random walk must start at a graph node");
        }
        this.maxHops = maxHops;
        this.resetProbability = resetProbability;
        this.rng = rng;
    }

    @Override
    public boolean hasNext()
    {
        return nextVertex != null;
    }

    @Override
    public NodeOrSong next()
    {
        if (nextVertex == null) {
            throw new NoSuchElementException();
        }
        NodeOrSong value = nextVertex;
        if(value instanceof Node) {
            lastNode = (Node) value;
            computeNextSong();
        }
        else
            computeNextNode();
        return value;
    }

//    public void modifyEdges(Set<V> sourceNodes)
//    {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            for(NodeOrSong sourceNode : sourceNodes)
//                outEdgesTotalWeight.put(sourceNode, graph.outgoingEdgesOf(sourceNode).stream().mapToDouble(graph::getEdgeWeight).sum());
//        } else {
//            for(NodeOrSong sourceNode : sourceNodes) {
//                double outEdgesTotalWeightSum = 0.0;
//                for (E edge : graph.outgoingEdgesOf(sourceNode)) {
//                    outEdgesTotalWeightSum += graph.getEdgeWeight(edge);
//                }
//                outEdgesTotalWeight.put(sourceNode, outEdgesTotalWeightSum);
//            }
//        }
//    }


    private void computeNextSong()
    {
        if (hops >= maxHops || (rng.nextFloat() < resetProbability)) {
            nextVertex = null;
            return;
        }

        hops++;
        if (graph.outDegreeOf(nextVertex) == 0) {
            nextVertex = null;
            return;
        }

        E e = null;
        double outEdgesWeight = getOutEdgesWeight((Node) nextVertex);
        double p = outEdgesWeight * rng.nextDouble();
        double cumulativeP = 0d;
        for (E curEdge : graph.outgoingEdgesOf(nextVertex)) {
            cumulativeP += graph.getEdgeWeight(curEdge);
            if (p <= cumulativeP) {
                e = curEdge;
                break;
            }
        }
        nextVertex = Graphs.getOppositeVertex(graph, e, nextVertex);
        if(nextVertex instanceof Node)
            throw new RuntimeException("Found Node to Node edge in Node To Song graph: " + e);
    }

    private void computeNextNode()
    {
        if (hops >= maxHops || (rng.nextFloat() < resetProbability)) {
            nextVertex = null;
            return;
        }

        hops++;
        if (graph.outDegreeOf(nextVertex) == 0 || graph.outDegreeOf(nextVertex) == 1 && graph.getEdgeSource(graph.outgoingEdgesOf(nextVertex).iterator().next()) == lastNode) {
            nextVertex = null;
            return;
        }

        E e = null;
        double outEdgesWeight = getOutEdgesWeight((SongRecommendation) nextVertex) - lastNode.getPersonalizedPageRankScore();
        double p = outEdgesWeight * rng.nextDouble();
        double cumulativeP = 0d;
        Node oppositeNode = null;
        for (E curEdge : graph.outgoingEdgesOf(nextVertex)) {
            oppositeNode = (Node) Graphs.getOppositeVertex(graph, curEdge, nextVertex);
            if(oppositeNode != lastNode) {
                cumulativeP += oppositeNode.getPersonalizedPageRankScore();
                if (p <= cumulativeP) {
                    e = curEdge;
                    break;
                }
            }
        }
        nextVertex = oppositeNode;
    }

    @NotNull
    private Double getOutEdgesWeight(Node vertex) {
        double outEdgesWeight = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            outEdgesWeight = outEdgesTotalWeight.computeIfAbsent(vertex, v -> graph
                    .outgoingEdgesOf(v).stream().mapToDouble(graph::getEdgeWeight).sum());
        } else {
            if(!outEdgesTotalWeight.containsKey(vertex)) {
                for(E edge: graph.outgoingEdgesOf(vertex)) {
                    outEdgesWeight += graph.getEdgeWeight(edge);
                }
                outEdgesTotalWeight.put(vertex, outEdgesWeight);
            } else {
                Double weight = outEdgesTotalWeight.get(vertex);
                if(weight != null) {
                    outEdgesWeight = weight;
                }
            }
        }
        return outEdgesWeight;
    }

    private Double returnPageRankOfNeighbour(E edge) {
        Node coerced = (Node) Graphs.getOppositeVertex(graph, edge, nextVertex);
        return coerced.getPersonalizedPageRankScore();
    }

    @NotNull
    private Double getOutEdgesWeight(SongRecommendation song) {
        double outEdgesWeight = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            outEdgesWeight = personalizedPageRankTotalWeight.computeIfAbsent(song, s -> graph
                    .outgoingEdgesOf(s).stream().mapToDouble(this::returnPageRankOfNeighbour).sum());
        } else {
            if(!personalizedPageRankTotalWeight.containsKey(song)) {
                for(E edge: graph.outgoingEdgesOf(song)) {
                    outEdgesWeight += returnPageRankOfNeighbour(edge);
                }
                personalizedPageRankTotalWeight.put(song, outEdgesWeight);
            } else {
                Double weight = personalizedPageRankTotalWeight.get(song);
                if(weight != null) {
                    outEdgesWeight = weight;
                }
            }
        }
        return outEdgesWeight;
    }

}