package nl.tudelft.trustchain.musicdao.recommender

import nl.tudelft.trustchain.musicdao.core.recommender.gossip.NodeToNodeEdgeGossip
import nl.tudelft.trustchain.musicdao.core.recommender.gossip.NodeToSongEdgeGossip
import nl.tudelft.trustchain.musicdao.core.recommender.model.*
import org.junit.Assert
import org.junit.Test
import java.sql.Timestamp

class GraphSerialization {
    val nodeToNodeTrust = 5.2
    val someNodeTrustEdge = NodeTrustEdge(nodeToNodeTrust, Timestamp(343434))
    val sourceNode = Node("someSourceIp")
    val targetNode = Node("someTargetIp")
    val someNodeTrustEdgeWithSourceAndTarget = NodeTrustEdgeWithSourceAndTarget(someNodeTrustEdge, sourceNode, targetNode)
    val nodeToNodeEdgeGossip = NodeToNodeEdgeGossip(someNodeTrustEdgeWithSourceAndTarget)

    val nodeToSongTrust = 4.2
    val someNodeSongEdge = NodeSongEdge(nodeToSongTrust, Timestamp(341234))
    val someSongRec = SongRecommendation("someTorrentHash")
    val someNodeSongEdgeWithSourceAndTarget = NodeSongEdgeWithNodeAndSongRec(someNodeSongEdge, sourceNode, someSongRec)
    val nodeToSongEdgeGossip = NodeToSongEdgeGossip(someNodeSongEdgeWithSourceAndTarget)

    @Test
    fun canSerializeAndDeserializeNodeToNodeEdgeGossip() {
        val serializedNodeTrustEdge = nodeToNodeEdgeGossip.serialize()
        val deserializedEdge = NodeToNodeEdgeGossip.deserialize(serializedNodeTrustEdge, 0).first
        Assert.assertEquals(nodeToNodeTrust, deserializedEdge.edge.nodeTrustEdge.trust, 0.001)
        Assert.assertEquals(sourceNode, deserializedEdge.edge.sourceNode)
        Assert.assertEquals(targetNode, deserializedEdge.edge.targetNode)
    }

    @Test
    fun canSerializeAndDeserializeNodeToSongEdgeGossip() {
        val serializedNodeSongEdge = nodeToSongEdgeGossip.serialize()
        val deserializedEdge = NodeToSongEdgeGossip.deserialize(serializedNodeSongEdge, 0).first
        Assert.assertEquals(nodeToSongTrust, deserializedEdge.edge.nodeSongEdge.affinity, 0.001)
        Assert.assertEquals(sourceNode, deserializedEdge.edge.node)
        Assert.assertEquals(someSongRec, deserializedEdge.edge.songRec)
    }



}
