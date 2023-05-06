package nl.tudelft.trustchain.musicdao.core.recommender.ranking

import mu.KotlinLogging
import nl.tudelft.trustchain.musicdao.core.recommender.model.*
import nl.tudelft.trustchain.musicdao.core.recommender.ranking.iterator.CustomHybridRandomWalkWithExplorationIterator
import org.jgrapht.graph.DefaultUndirectedWeightedGraph
import java.util.*

class IncrementalHybridPersonalizedPageRankSalsaMeritRank(
    maxWalkLength: Int,
    repetitions: Int,
    rootNode: Node,
    resetProbability: Float,
    val betaDecayThreshold: Float,
    val betaDecay: Float,
    explorationProbability: Float,
    graph: DefaultUndirectedWeightedGraph<NodeOrSong, NodeSongEdge>,
    val heapEfficientImplementation: Boolean = true
) : IncrementalRandomWalkedBasedRankingAlgo<DefaultUndirectedWeightedGraph<NodeOrSong, NodeSongEdge>, NodeOrSong, NodeSongEdge>(
    maxWalkLength,
    repetitions,
    rootNode
) {
    private val logger = KotlinLogging.logger {}
    private val iter =
        CustomHybridRandomWalkWithExplorationIterator(
            graph,
            rootNode,
            maxWalkLength.toLong(),
            resetProbability,
            explorationProbability,
            Random(),
            graph.vertexSet().filterIsInstance<Node>()
                .toList()
        )
    private lateinit var randomWalks: MutableList<MutableList<NodeOrSong>>

    init {
        initiateRandomWalks()
    }

    private fun performRandomWalk(existingWalk: MutableList<NodeOrSong>) {
        if (existingWalk.size >= maxWalkLength) {
            logger.info { "Random walk requested for already complete or overfull random walk" }
            return
        }
        iter.nextVertex = rootNode
        existingWalk.add(iter.next())
        while (iter.hasNext()) {
            existingWalk.add(iter.next())
        }
    }

    override fun calculateRankings() {
        val songCounts = randomWalks.flatten().groupingBy { it }.eachCount().filterKeys { it is SongRecommendation }
        val betaDecays = if(heapEfficientImplementation) calculateBetaDecays(songCounts) else calculateBetaDecaysSpaceIntensive()
        val totalOccs = songCounts.values.sum()
        for ((songRec, occ) in songCounts) {
            val betaDecay = if(betaDecays[songRec]!!) (1 - betaDecay).toDouble() else 1.0
            songRec.rankingScore = (occ.toDouble() / totalOccs) * betaDecay
        }
    }

    fun modifyNodesOrSongs(changedNodes: Set<Node>, newNodes: List<Node>) {
        iter.modifyEdges(changedNodes)
        iter.modifyPersonalizedPageRanks(newNodes)
        initiateRandomWalks()
    }

    private fun performNewRandomWalk(): MutableList<NodeOrSong> {
        val randomWalk: MutableList<NodeOrSong> = mutableListOf()
        performRandomWalk(randomWalk)
        return randomWalk
    }

    override fun initiateRandomWalks() {
        randomWalks = mutableListOf()
        for (walk in 0 until repetitions) {
            randomWalks.add(performNewRandomWalk())
        }
    }

    private fun calculateBetaDecays(recCounts: Map<NodeOrSong, Int>): Map<SongRecommendation, Boolean> {
        val shouldBetaDecay = mutableMapOf<SongRecommendation, Boolean>()
        for (rec in recCounts.keys) {
            val visitThroughAnotherNode = mutableMapOf<NodeOrSong, Int>()
            var totalVisits = 0
            for (walk in randomWalks) {
                if (walk.contains(rec)) {
                    val uniqueNodes = mutableSetOf<NodeOrSong>()
                    totalVisits++
                    for (visitNode in walk) {
                        if (visitNode == rec) {
                            break
                        }
                        if (!uniqueNodes.contains(visitNode)) {
                            visitThroughAnotherNode[visitNode] =
                                (visitThroughAnotherNode[visitNode] ?: 0) + 1
                            uniqueNodes.add(visitNode)
                        }
                    }
                }
            }
            visitThroughAnotherNode[rootNode] = 0
            if(rec.identifier.contains("sybil")) {
                print("bla")
            }
            val maxVisitsFromAnotherNode = visitThroughAnotherNode.values.max()
            val score = maxVisitsFromAnotherNode.toFloat() / totalVisits
            shouldBetaDecay[rec as SongRecommendation] = score > betaDecayThreshold
        }
        return shouldBetaDecay
    }

    private fun calculateBetaDecaysSpaceIntensive(): Map<SongRecommendation, Boolean> {
        val shouldBetaDecay = mutableMapOf<SongRecommendation, Boolean>()
        val totalVisitsToRec = mutableMapOf<SongRecommendation, Int>()
        val visitToNodeThroughOtherNode = mutableMapOf<SongRecommendation, MutableMap<NodeOrSong, Int>>()
        for (walk in randomWalks) {
            val uniqueNodesOrSong = mutableSetOf<NodeOrSong>()
            for (nodeOrRec in walk) {
                if (nodeOrRec is SongRecommendation) {
                    if (!uniqueNodesOrSong.contains(nodeOrRec)) {
                        totalVisitsToRec[nodeOrRec] = (totalVisitsToRec[nodeOrRec] ?: 0) + 1
                        for (visitedNode in uniqueNodesOrSong) {
                            val existingMap = visitToNodeThroughOtherNode[nodeOrRec] ?: mutableMapOf()
                            existingMap[visitedNode] = (existingMap[visitedNode] ?: 0) + 1
                            visitToNodeThroughOtherNode[nodeOrRec] = existingMap
                        }
                    }
                }
                uniqueNodesOrSong.add(nodeOrRec)
            }
        }
        for (node in totalVisitsToRec.keys) {
                val maxVisitsFromAnotherNode =
                    visitToNodeThroughOtherNode[node]?.filter { it.key != rootNode }?.values?.max() ?: 0
                val score = maxVisitsFromAnotherNode.toFloat() / totalVisitsToRec[node]!!
                shouldBetaDecay[node] = score > betaDecayThreshold
        }
        return shouldBetaDecay
    }
}
