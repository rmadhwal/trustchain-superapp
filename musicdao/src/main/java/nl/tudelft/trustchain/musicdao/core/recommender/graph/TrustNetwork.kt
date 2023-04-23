package nl.tudelft.trustchain.musicdao.core.recommender.graph

import mu.KotlinLogging
import nl.tudelft.trustchain.musicdao.core.recommender.model.*
import nl.tudelft.trustchain.musicdao.core.recommender.ranking.IncrementalHybridPersonalizedPageRankSalsa
import nl.tudelft.trustchain.musicdao.core.recommender.ranking.IncrementalPersonalizedPageRank

class TrustNetwork(subNetworks: SubNetworks,
                   sourceNodeAddress: String) {
    private val nodeToNodeNetwork = subNetworks.nodeToNodeNetwork
    private val nodeToSongNetwork = subNetworks.nodeToSongNetwork
    private val incrementalPersonalizedPageRank: IncrementalPersonalizedPageRank
    private val incrementalHybridPersonalizedPageRankSalsa: IncrementalHybridPersonalizedPageRankSalsa
    private val allNodes: MutableList<Node>
    private val logger = KotlinLogging.logger {}
    val rootNode: Node

    companion object {
        const val MAX_WALK_LENGTH = 1000
        const val REPETITIONS = 10000
        const val RESET_PROBABILITY = 0.01f
        const val EXPLORATION_PROBABILITY = 0.05f
    }

    init {
        val allNodesList = nodeToNodeNetwork.getAllNodes()
        allNodes = allNodesList.toMutableList()
        rootNode = allNodes.first { it.getIpv8() == sourceNodeAddress}
        incrementalPersonalizedPageRank = IncrementalPersonalizedPageRank(MAX_WALK_LENGTH, REPETITIONS, rootNode, RESET_PROBABILITY, nodeToNodeNetwork.graph)
        incrementalHybridPersonalizedPageRankSalsa = IncrementalHybridPersonalizedPageRankSalsa(MAX_WALK_LENGTH, REPETITIONS, rootNode, RESET_PROBABILITY, EXPLORATION_PROBABILITY, nodeToSongNetwork.graph)
    }

    fun addNodeToSongEdge(edge: NodeSongEdgeWithNodeAndSongRec): Boolean {
        var existingAffinity = 0.0
        if(containsEdge(edge.node, edge.songRec)) {
            val existingEdgeTimestamp = nodeToSongNetwork.graph.getEdge(edge.node, edge.songRec).timestamp
            existingAffinity = nodeToSongNetwork.graph.getEdge(edge.node, edge.songRec).affinity
            if(existingEdgeTimestamp >= edge.nodeSongEdge.timestamp) {
                return false
            }
        }
        if (!containsNode(edge.node)) {
            if (!addNode(edge.node)) {
                return false
            }
        }
        if (!containsSongRec(edge.songRec)) {
            if (!addSongRec(edge.songRec)) {
                return false
            }
        }
        return nodeToSongNetwork.addEdge(edge.node, edge.songRec, edge.nodeSongEdge).also {
            if(!it) {
                logger.error { "Couldn't add edge from ${edge.node} to ${edge.songRec}" }
            } else {
                if(edge.node == rootNode) {
                    updateNodeTrust(edge.songRec, edge.nodeSongEdge.affinity - existingAffinity)
                }
                incrementalPersonalizedPageRank.modifyEdges(setOf(rootNode))
                incrementalHybridPersonalizedPageRankSalsa.modifyNodesOrSongs(setOf(rootNode), nodeToNodeNetwork.getAllNodes().toList())
            }
        }
    }

    private fun updateNodeTrust(songRec: SongRecommendation, affinityDelta: Double) {
        val recommenderNodeEdges = nodeToSongNetwork.graph.outgoingEdgesOf(songRec)
        val rootNeighborEdges = nodeToNodeNetwork.graph.outgoingEdgesOf(rootNode)
        for(recommenderNodeEdge in recommenderNodeEdges) {
            val recommenderNode = nodeToSongNetwork.graph.getEdgeSource(recommenderNodeEdge) as Node
            if(recommenderNode != rootNode) {
                val trustDelta = recommenderNodeEdge.affinity * affinityDelta
                val existingTrustEdgeToNode =
                    rootNeighborEdges.find { nodeToNodeNetwork.graph.getEdgeTarget(it) == recommenderNode }
                val existingTrust = existingTrustEdgeToNode?.trust ?: 0.0
                val newTrust = existingTrust + trustDelta
                addNodeToNodeEdge(NodeTrustEdgeWithSourceAndTarget(NodeTrustEdge(newTrust), rootNode, recommenderNode))
            }
        }
    }

    fun addNodeToNodeEdge(edge: NodeTrustEdgeWithSourceAndTarget): Boolean {
        if(containsEdge(edge.sourceNode, edge.targetNode)) {
            val existingEdgeTimestamp = nodeToNodeNetwork.graph.getEdge(edge.sourceNode, edge.targetNode).timestamp
            if(existingEdgeTimestamp >= edge.nodeTrustEdge.timestamp) {
                return false
            }
        }
        val newSourceNode = !containsNode(edge.sourceNode)
        if (newSourceNode) {
            if (!addNode(edge.sourceNode)) {
                return false
            }
        }
        if (!containsNode(edge.targetNode)) {
            if (!addNode(edge.targetNode)) {
                return false
            }
        }
        return nodeToNodeNetwork.addEdge(edge.sourceNode, edge.targetNode, edge.nodeTrustEdge).also {
            if(!it) {
                logger.error { "Couldn't add edge from ${edge.sourceNode} to ${edge.targetNode}" }
            } else {
                if(!newSourceNode) {
                    incrementalPersonalizedPageRank.modifyEdges(setOf(edge.sourceNode))
                    incrementalHybridPersonalizedPageRankSalsa.modifyNodesOrSongs(setOf(edge.sourceNode), nodeToNodeNetwork.getAllNodes().toList())
                }
            }
        }
    }

    fun getAllNodeToNodeEdges(): List<NodeTrustEdge> {
        return nodeToNodeNetwork.getAllEdges().toList()
    }

    fun getAllNodeToSongEdges(): List<NodeSongEdge> {
        return nodeToSongNetwork.getAllEdges().toList()
    }

    private fun containsEdge(source: Node, target: NodeOrSong): Boolean {
        return if(target is SongRecommendation) nodeToSongNetwork.graph.containsEdge(source, target) else nodeToNodeNetwork.graph.containsEdge(source, target as Node)
    }

    private fun containsNode(node: Node): Boolean {
        return nodeToSongNetwork.graph.containsVertex(node) && nodeToSongNetwork.graph.containsVertex(node)
    }

    private fun containsSongRec(songRec: SongRecommendation): Boolean {
        return nodeToSongNetwork.graph.containsVertex(songRec)
    }

    fun addNode(node: Node): Boolean {
        return nodeToSongNetwork.addNodeOrSong(node) && nodeToNodeNetwork.addNode(node)
    }

    fun addSongRec(songRec: SongRecommendation): Boolean {
        return nodeToSongNetwork.addNodeOrSong(songRec)
    }


}
