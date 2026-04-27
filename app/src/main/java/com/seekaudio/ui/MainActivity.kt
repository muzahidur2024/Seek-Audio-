package com.seekaudio.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.seekaudio.R
import com.seekaudio.databinding.ActivityMainBinding
import com.seekaudio.ui.driving.DrivingActivity
import com.seekaudio.ui.player.PlayerViewModel
import com.seekaudio.utils.formatDuration
import com.seekaudio.utils.hide
import com.seekaudio.utils.show
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    val playerViewModel: PlayerViewModel by viewModels()
    private var isPlayerDestination = false
    private var syncingBottomNavSelection = false
    private val fullPlayerDestinations = setOf(
        R.id.playerFragment,
        R.id.lyricsFragment,
        R.id.queueFragment,
        R.id.sleepTimerFragment,
        R.id.artistFragment,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) onPermissionsGranted()
        else showPermissionRationale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
        setupMiniPlayer()
        checkPermissions()
        playerViewModel.initController()
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            if (syncingBottomNavSelection) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.libraryFragment,
                R.id.playerFragment,
                R.id.equalizerFragment,
                R.id.web3Fragment -> {
                    navigateToTopLevel(item.itemId)
                    true
                }
                else -> false
            }
        }

        navController.addOnDestinationChangedListener { _, dest, _ ->
            syncingBottomNavSelection = true
            when (dest.id) {
                R.id.libraryFragment -> binding.bottomNavigation.selectedItemId = R.id.libraryFragment
                R.id.playerFragment -> binding.bottomNavigation.selectedItemId = R.id.playerFragment
                R.id.lyricsFragment,
                R.id.queueFragment,
                R.id.sleepTimerFragment,
                R.id.artistFragment -> binding.bottomNavigation.selectedItemId = R.id.playerFragment
                R.id.equalizerFragment -> binding.bottomNavigation.selectedItemId = R.id.equalizerFragment
                R.id.web3Fragment -> binding.bottomNavigation.selectedItemId = R.id.web3Fragment
            }
            syncingBottomNavSelection = false

            isPlayerDestination = dest.id in fullPlayerDestinations
            binding.bottomNavigation.show()
            if (isPlayerDestination) {
                binding.miniPlayer.root.hide()
            } else {
                if (playerViewModel.currentSong.value != null) {
                    binding.miniPlayer.root.show()
                } else {
                    binding.miniPlayer.root.hide()
                }
            }
        }
    }

    private fun setupMiniPlayer() {
        lifecycleScope.launch {
            playerViewModel.currentSong.collect { song ->
                if (song != null && !isPlayerDestination) {
                    binding.miniPlayer.root.show()
                    binding.miniPlayer.tvMiniTitle.text  = song.title
                    binding.miniPlayer.tvMiniArtist.text = song.artist
                } else {
                    binding.miniPlayer.root.hide()
                }
            }
        }

        lifecycleScope.launch {
            playerViewModel.playerState.collect { state ->
                binding.miniPlayer.btnMiniPlayPause.setImageResource(
                    if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
                val pct = if (state.durationMs > 0)
                    (state.progressMs * 100 / state.durationMs).toInt() else 0
                binding.miniPlayer.progressMini.progress = pct
            }
        }

        binding.miniPlayer.btnMiniPlayPause.setOnClickListener { playerViewModel.playPause() }
        binding.miniPlayer.btnMiniNext.setOnClickListener      { playerViewModel.next() }
        binding.miniPlayer.root.setOnClickListener {
            navController.navigate(R.id.playerFragment)
        }
    }

    private fun navigateToTopLevel(destinationId: Int) {
        val currentId = navController.currentDestination?.id
        if (currentId == destinationId) return

        val popped = navController.popBackStack(destinationId, false)
        if (popped) return

        val options = navOptions {
            launchSingleTop = true
            restoreState = true
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
        }
        navController.navigate(destinationId, null, options)
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            onPermissionsGranted()
        else
            permissionLauncher.launch(permissions)
    }

    private fun onPermissionsGranted() { playerViewModel.scanDevice() }

    private fun showPermissionRationale() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setPositiveButton(R.string.grant_permission) { _, _ -> checkPermissions() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onStop() {
        playerViewModel.savePlaybackStateNow()
        super.onStop()
    }
}
