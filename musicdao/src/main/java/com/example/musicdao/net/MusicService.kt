package com.example.musicdao.net

import android.content.Intent
import android.content.res.Resources.NotFoundException
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.musicdao.R
import com.example.musicdao.databinding.FragmentBlankBinding
import com.squareup.sqldelight.android.AndroidSqliteDriver
import com.turn.ttorrent.client.Client
import com.turn.ttorrent.client.SharedTorrent
import kotlinx.android.synthetic.main.fragment_blank.*
import nl.tudelft.ipv8.IPv8Configuration
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.OverlayConfiguration
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.android.keyvault.AndroidCryptoProvider
import nl.tudelft.ipv8.attestation.trustchain.*
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainSQLiteStore
import nl.tudelft.ipv8.keyvault.PrivateKey
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.peerdiscovery.strategy.RandomWalk
import nl.tudelft.ipv8.sqldelight.Database
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.BaseActivity
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*


class MusicService : BaseActivity() {
    private lateinit var binding: FragmentBlankBinding
    private lateinit var recyclerView: RecyclerView
    private lateinit var viewAdapter: RecyclerView.Adapter<*>
    private lateinit var viewManager: RecyclerView.LayoutManager
    private var items: MutableList<String> = mutableListOf()

    private var peers: List<Peer> = listOf()
    override val navigationGraph = R.navigation.musicdao_navgraph
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_blank)
        supportActionBar?.title = "Music app"

        binding = FragmentBlankBinding.inflate(layoutInflater)

        viewManager = LinearLayoutManager(this)
        items.add("Listening for peers...")

        viewAdapter = MusicListAdapter(items)

        viewAdapter.notifyDataSetChanged()

        recyclerView = findViewById<RecyclerView>(R.id.recycler_view_items).apply {
            // use this setting to improve performance if you know that changes
            // in content do not change the layout size of the RecyclerView
            setHasFixedSize(false)

            // use a linear layout manager
            layoutManager = viewManager

            // specify an viewAdapter (see also next example)
            adapter = viewAdapter
        }

        val community = OverlayConfiguration(
            Overlay.Factory(MusicDemoCommunity::class.java),
            listOf(RandomWalk.Factory())
        )

        val settings = TrustChainSettings()
        val driver = AndroidSqliteDriver(Database.Schema, this, "trustchain.db")
        val store = TrustChainSQLiteStore(Database(driver))
        val randomWalk = RandomWalk.Factory()
        val trustChainCommunity = OverlayConfiguration(
            TrustChainCommunity.Factory(settings, store),
            listOf(randomWalk)
        )

        val config = IPv8Configuration(overlays = listOf(community, trustChainCommunity))

        IPv8Android.Factory(application)
            .setConfiguration(config)
            .setPrivateKey(getPrivateKey())
            .init()

        items.add("I am " + IPv8Android.getInstance().myPeer.mid)
        viewAdapter.notifyDataSetChanged()

        val overlay = IPv8Android.getInstance().getOverlay<TrustChainCommunity>()!!
        registerBlockListener()
        registerBlockSigner()

        sendProposalButton.setOnClickListener {
            peers = overlay.getPeers()
            if (peers.isNotEmpty()) {
                for (peer in peers) {
                    items.add("Sending to peer ${peer.mid}")
                    publishRandomTrack(peer)
                }
            } else {
                items.add("No peers found")
            }
            viewAdapter.notifyDataSetChanged()
        }

        shareTrackButton.setOnClickListener {
            selectLocalTrackFile()
        }
    }

    private fun selectLocalTrackFile() {
        val chooseFile = Intent(Intent.ACTION_GET_CONTENT)
        chooseFile.type = "audio/*"
        val chooseFileActivity = Intent.createChooser(chooseFile, "Choose a file")
        startActivityForResult(chooseFileActivity, 1)
        val uri = chooseFileActivity.data
        if (uri != null) {
            println(uri.path)
        }
    }

    /**
     * This is called when the chooseFile is completed
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (uri != null) {
            //This should be reached when the chooseFile intent is completed and the user selected
            //an audio file
            shareTorrent(uri)
        }
    }

    @Throws(NotFoundException::class)
    private fun shareTorrent(uri: Uri) {
        println("Trying to share torrent $uri")
        val input = applicationContext.contentResolver.openInputStream(uri);

        //TODO generate a suitable signature for this torrent
        val hash = Random().nextInt().toString() + ".mp3"
        if (input != null) {
            val tempFileLocation = "$cacheDir/$hash"

            //TODO currently creates temp copies before seeding, but should not be necessary
            Files.copy(input, Paths.get(tempFileLocation))
            val file = File(tempFileLocation)

            //Create a torrent file for the audio file
            //TODO createdBy
            val torrent = SharedTorrent.create(file, URI(file.absolutePath), "createdBy")
            val torrentFile = "$tempFileLocation.torrent"
            torrent.save(FileOutputStream(torrentFile))
            val sharedTorrent = SharedTorrent.fromFile(File(torrentFile), cacheDir)

            //Start seeding the torrent off the main thread
            val thread = Thread(Runnable {
                try {
                    val torrentClient = Client(InetAddress.getLocalHost(), sharedTorrent)
                    torrentClient.share()
                    Timer().scheduleAtFixedRate(
                        object : TimerTask() {
                            override fun run() {
                                if (torrentClient.torrent.isInitialized) {
                                    torrentClient.info()
                                } else {
                                    println("Initializing torrent")
                                }
                            }
                        }, 0, 5000
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            })
            thread.start()
        }
    }

    //TODO this function should be removed completely and decoupled into separate functions
    private fun publishRandomTrack(peer: Peer) {
        val trackId = Random().nextInt()
        val myPeer = IPv8Android.getInstance().myPeer

        val transaction = mapOf(
            "trackId" to trackId,
            "author" to myPeer.mid,
            "magnet" to "magnet:?xt=urn:btih:e825e94efe8a5a88c98bd7e0c6b9f1ae6b8d522b"
        )
        val publicKey = peer.publicKey.keyToBin()
        val trustchain = IPv8Android.getInstance().getOverlay<TrustChainCommunity>()!!
        items.add("Creating proposal block")
        viewAdapter.notifyDataSetChanged()
        trustchain.createProposalBlock("publish_track", transaction, publicKey)
    }

    private fun registerBlockSigner() {
        val trustchain = IPv8Android.getInstance().getOverlay<TrustChainCommunity>()!!
        trustchain.registerBlockSigner("publish_track", object : BlockSigner {
            override fun onSignatureRequest(block: TrustChainBlock) {
                items.add("Signing block ${block.blockId}")
                viewAdapter.notifyDataSetChanged()
                trustchain.createAgreementBlock(block, mapOf<Any?, Any?>())
            }
        })
    }

    private fun registerBlockListener() {
        val trustchain = IPv8Android.getInstance().getOverlay<TrustChainCommunity>()!!
        trustchain.addListener("publish_track", object : BlockListener {
            override fun onBlockReceived(block: TrustChainBlock) {
                items.add("BLOCK: ${block.transaction}")
                viewAdapter.notifyDataSetChanged()
                Log.d("TrustChainDemo", "onBlockReceived: ${block.blockId} ${block.transaction}")
            }
        })
    }

    private fun onMessage(packet: Packet) {
        val (peer, payload) = packet.getAuthPayload(SongMessage.Deserializer)
        items.add("Song from " + peer.mid + " : " + payload.message)
    }

    companion object {
        private const val PREF_PRIVATE_KEY = "private_key"
        private const val MESSAGE_ID = 1
    }

    private fun getPrivateKey(): PrivateKey {
        // Load a key from the shared preferences
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val privateKey = prefs.getString(PREF_PRIVATE_KEY, null)
        return if (privateKey == null) {
            // Generate a new key on the first launch
            val newKey = AndroidCryptoProvider.generateKey()
            prefs.edit()
                .putString(PREF_PRIVATE_KEY, newKey.keyToBin().toHex())
                .apply()
            newKey
        } else {
            AndroidCryptoProvider.keyFromPrivateBin(privateKey.hexToBytes())
        }
    }
}
