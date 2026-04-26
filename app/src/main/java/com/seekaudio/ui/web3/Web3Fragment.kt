package com.seekaudio.ui.web3

import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.seekaudio.R
import com.seekaudio.data.model.SkrActivityType
import com.seekaudio.databinding.FragmentWeb3Binding
import com.seekaudio.databinding.ItemSkrActivityBinding
import com.seekaudio.ui.player.PlayerViewModel
import com.seekaudio.utils.hide
import com.seekaudio.utils.show
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class Web3Fragment : Fragment() {

    private var _binding: FragmentWeb3Binding? = null
    private val binding get() = _binding!!
    private val vm: PlayerViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWeb3Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Connect button
        binding.btnConnectWallet.setOnClickListener {
            showWalletPickerDialog()
        }

        // Disconnect
        binding.btnDisconnect.setOnClickListener {
            vm.disconnectWallet()
        }

        // Claim SKR
        binding.btnClaimSkr.setOnClickListener {
            vm.claimPendingSkr()
        }

        // Build feature cards for not-connected view
        setupFeatureCards()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.walletState.collect { wallet ->
                        if (wallet.isConnected) {
                            binding.layoutNotConnected.hide()
                            binding.layoutConnected.show()

                            binding.tvWalletName.text    = "${wallet.walletName.uppercase()} CONNECTED"
                            binding.tvWalletAddress.text = shortAddr(wallet.address)
                            binding.tvSolBalance.text    = "◎ ${"%.3f".format(wallet.solBalance)}"
                            binding.tvSkrBalance.text    = wallet.skrBalance.toString()

                            if (wallet.skrPending > 0) {
                                binding.tvSkrPending.show()
                                binding.tvSkrPending.text = "+${wallet.skrPending} ${getString(R.string.pending)}"
                                binding.btnClaimSkr.show()
                                binding.btnClaimSkr.text = getString(R.string.claim_skr, wallet.skrPending.toInt())
                            } else {
                                binding.tvSkrPending.hide()
                                binding.btnClaimSkr.hide()
                            }
                        } else {
                            binding.layoutNotConnected.show()
                            binding.layoutConnected.hide()
                        }
                    }
                }
                launch {
                    vm.skrEarned.collect { earned ->
                        binding.tvSkrEarned.text = "+$earned SKR"
                    }
                }
                launch {
                    vm.skrActivity.collect { activities ->
                        binding.containerActivity.removeAllViews()
                        activities.take(6).forEach { item ->
                            val itemView = ItemSkrActivityBinding.inflate(layoutInflater, binding.containerActivity, false)
                            itemView.tvActivityIcon.text   = item.icon
                            itemView.tvActivityLabel.text  = item.label
                            itemView.tvActivityTime.text   = item.timeLabel
                            itemView.tvActivityAmount.text = item.amount
                            itemView.tvActivityAmount.setTextColor(
                                requireContext().getColor(
                                    if (item.type == SkrActivityType.EARN) R.color.solana_green else R.color.track_chill
                                )
                            )
                            binding.containerActivity.addView(itemView.root)
                        }
                    }
                }
            }
        }
    }

    private fun setupFeatureCards() {
        // Feature list is built dynamically to allow easy additions
        val features = listOf(
            Triple("◎", "Earn SKR Tokens",    "Get rewarded for every track you listen to"),
            Triple("🎵", "Audio NFT Player",  "Play music NFTs directly from your wallet"),
            Triple("💜", "Tip Artists",        "Send SKR directly to artists you love"),
            Triple("🔐", "Seed Vault Support", "Native Seeker hardware wallet integration"),
        )
        binding.containerFeatures.removeAllViews()
        features.forEach { (icon, title, desc) ->
            val row = layoutInflater.inflate(R.layout.item_web3_feature, binding.containerFeatures, false)
            row.findViewById<android.widget.TextView>(R.id.tv_feature_icon).text  = icon
            row.findViewById<android.widget.TextView>(R.id.tv_feature_title).text = title
            row.findViewById<android.widget.TextView>(R.id.tv_feature_desc).text  = desc
            binding.containerFeatures.addView(row)
        }
    }

    private fun showWalletPickerDialog() {
        val wallets = arrayOf("🔐  Seed Vault (Recommended)", "👻  Phantom", "🎒  Backpack", "🌞  Solflare")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Connect Wallet")
            .setItems(wallets) { _, which ->
                val name = when (which) {
                    0 -> "Seed Vault"
                    1 -> "Phantom"
                    2 -> "Backpack"
                    else -> "Solflare"
                }
                // Simulate wallet connection with mock data
                vm.connectWallet(
                    walletName  = name,
                    address     = "7xKp3GhRsTmNq9wBvYcPdLeF2Xa8Jk4nZoU",
                    solBalance  = 4.821,
                    skrBalance  = 1240L,
                    skrPending  = 85L,
                )
            }
            .setNegativeButton("Skip for now", null)
            .show()
    }

    private fun shortAddr(addr: String): String =
        if (addr.length > 10) "${addr.take(6)}...${addr.takeLast(4)}" else addr

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
