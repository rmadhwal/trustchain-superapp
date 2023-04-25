package nl.tudelft.trustchain.musicdao.recommender

import nl.tudelft.trustchain.musicdao.core.recommender.gossip.EdgeGossiper
import nl.tudelft.trustchain.musicdao.core.recommender.graph.NodeToNodeNetwork
import nl.tudelft.trustchain.musicdao.core.recommender.graph.NodeToSongNetwork
import nl.tudelft.trustchain.musicdao.core.recommender.graph.SubNetworks
import nl.tudelft.trustchain.musicdao.core.recommender.graph.TrustNetwork
import nl.tudelft.trustchain.musicdao.core.recommender.model.*
import nl.tudelft.trustchain.musicdao.core.recommender.ranking.IncrementalHybridPersonalizedPageRankSalsa
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import java.sql.Timestamp
import kotlin.random.Random

class EdgeGossiperTest {
    private var nodeToSongNetwork: NodeToSongNetwork = NodeToSongNetwork()
    private var nodeToNodeNetwork: NodeToNodeNetwork = NodeToNodeNetwork()
    private lateinit var trustNetwork: TrustNetwork
    private lateinit var incrementalHybrid: IncrementalHybridPersonalizedPageRankSalsa
    private val seed = 42L
    private lateinit var rootNode: Node
    private val rng = Random(seed)
    private val nNodes = 5000
    private val nSongs = nNodes / 10
    private val nEdges = 10
    private val maxTimestamp = System.currentTimeMillis() + 10000
    private val minTimestamp = System.currentTimeMillis()
    private lateinit var edgeGossiper: EdgeGossiper

    @Before
    fun setUp() {
        for(node in 0 until nNodes) {
            val nodeToAdd = Node(node.toString())
            nodeToNodeNetwork.addNode(nodeToAdd)
            nodeToSongNetwork.addNodeOrSong(nodeToAdd)
        }
        for(song in 0 until nSongs) {
            nodeToSongNetwork.addNodeOrSong(SongRecommendation(song.toString()))
        }
        // Create 10 edges from each node to 10 random songs
        val allNodes = nodeToSongNetwork.getAllNodes().toList()
        rootNode = allNodes[0]
        val allSongs = nodeToSongNetwork.getAllSongs().toList()
        for(node in allNodes) {
            for(i in 0 until nEdges) {
                var randomNum = (0 until nSongs - 1).random(rng)
                nodeToSongNetwork.addEdge(node, allSongs[randomNum], NodeSongEdge(rng.nextDouble(), Timestamp(rng.nextLong(minTimestamp, maxTimestamp))))
                randomNum = (0 until nNodes - 1).random(rng)
                val randomNode = if(randomNum < node.getIpv8().toInt()) randomNum else randomNum + 1
                nodeToNodeNetwork.addEdge(node, allNodes[randomNode], NodeTrustEdge(rng.nextDouble(), Timestamp(rng.nextLong(minTimestamp, maxTimestamp))))
            }
        }
    }

    @Test
    fun canInitializeDeltasForEdgeGossiping() {
        trustNetwork = TrustNetwork(SubNetworks(nodeToNodeNetwork, nodeToSongNetwork), rootNode.getIpv8())
        edgeGossiper = EdgeGossiper(RecommenderCommunityMock("someServiceId"), false, trustNetwork)
        val nodeToNodeDeltas = edgeGossiper.nodeToNodeEdgeDeltas
        val nodeToSongDeltas = edgeGossiper.nodeToSongEdgeDeltas

        val oldestNodeToNodeEdge = nodeToNodeNetwork.getAllEdges().minBy { it.timestamp }
        val newestNodeToNodeEdge = nodeToNodeNetwork.getAllEdges().maxBy { it.timestamp }
        val deltaOldestAndNewestNtNEdge = (newestNodeToNodeEdge.timestamp.time - oldestNodeToNodeEdge.timestamp.time).toInt()
        Assert.assertEquals(deltaOldestAndNewestNtNEdge, nodeToNodeDeltas.max())

        val oldestNodeToSongEdge = nodeToSongNetwork.getAllEdges().minBy { it.timestamp }
        val newestNodeToSongEdge = nodeToSongNetwork.getAllEdges().maxBy { it.timestamp }
        val deltaOldestAndNewestNtSEdge = (newestNodeToSongEdge.timestamp.time - oldestNodeToSongEdge.timestamp.time).toInt()
        Assert.assertEquals(deltaOldestAndNewestNtSEdge, nodeToSongDeltas.max())
    }

    @Test
    fun canInitializeWeightsBasedOnDeltaValues() {
        trustNetwork = TrustNetwork(SubNetworks(nodeToNodeNetwork, nodeToSongNetwork), rootNode.getIpv8())
        edgeGossiper = EdgeGossiper(RecommenderCommunityMock("someServiceId"), false, trustNetwork)
        val nodeToNodeDeltas = edgeGossiper.nodeToNodeEdgeDeltas
        val nodeToSongDeltas = edgeGossiper.nodeToSongEdgeDeltas
        val nodeToNodeWeights = edgeGossiper.nodeToNodeEdgeWeights
        val nodeToSongWeights = edgeGossiper.nodeToSongEdgeWeights

        val oldestNodeToNodeEdge = nodeToNodeNetwork.getAllEdges().minBy { it.timestamp }
        val newestNodeToNodeEdge = nodeToNodeNetwork.getAllEdges().maxBy { it.timestamp }
        val sumNtNDeltas = nodeToNodeDeltas.sum()
        val expectedWeightOldestNtNEdge = 0.0f
        val expectedWeightNewestNtNEdge = ((newestNodeToNodeEdge.timestamp.time - oldestNodeToNodeEdge.timestamp.time) / sumNtNDeltas).toFloat()
        Assert.assertEquals(expectedWeightOldestNtNEdge, nodeToNodeWeights.first(), 0.001f)
        Assert.assertEquals(expectedWeightNewestNtNEdge, nodeToNodeWeights.last(), 0.001f)

        val oldestNodeToSongEdge = nodeToSongNetwork.getAllEdges().minBy { it.timestamp }
        val newestNodeToSongEdge = nodeToSongNetwork.getAllEdges().maxBy { it.timestamp }
        val sumNtSDeltas = nodeToSongDeltas.sum()
        val expectedWeightOldestNtSEdge = 0.0f
        val expectedWeightNewestNtSEdge = ((newestNodeToSongEdge.timestamp.time - oldestNodeToSongEdge.timestamp.time) / sumNtSDeltas).toFloat()
        Assert.assertEquals(expectedWeightOldestNtSEdge, nodeToSongWeights.first(), 0.001f)
        Assert.assertEquals(expectedWeightNewestNtSEdge, nodeToSongWeights.last(), 0.001f)

        Assert.assertEquals(1.0f, nodeToNodeWeights.sum(), 0.001f)
        Assert.assertEquals(1.0f, nodeToSongWeights.sum(), 0.001f)
    }
}
