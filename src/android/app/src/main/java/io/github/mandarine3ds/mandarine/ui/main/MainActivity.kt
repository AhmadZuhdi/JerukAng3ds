// Copyright 2025 Citra Project / Mandarine Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package io.github.mandarine3ds.mandarine.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationBarView
import kotlinx.coroutines.launch
import io.github.mandarine3ds.mandarine.R
import io.github.mandarine3ds.mandarine.activities.EmulationActivity
import io.github.mandarine3ds.mandarine.contracts.OpenFileResultContract
import io.github.mandarine3ds.mandarine.databinding.ActivityMainBinding
import io.github.mandarine3ds.mandarine.features.settings.model.Settings
import io.github.mandarine3ds.mandarine.features.settings.model.SettingsViewModel
import io.github.mandarine3ds.mandarine.features.settings.ui.SettingsActivity
import io.github.mandarine3ds.mandarine.features.settings.utils.SettingsFile
import io.github.mandarine3ds.mandarine.fragments.SelectUserDirectoryDialogFragment
import io.github.mandarine3ds.mandarine.utils.CiaInstallWorker
import io.github.mandarine3ds.mandarine.utils.MandarineDirectoryHelper
import io.github.mandarine3ds.mandarine.utils.DirectoryInitialization
import io.github.mandarine3ds.mandarine.utils.FileBrowserHelper
import io.github.mandarine3ds.mandarine.utils.InsetsHelper
import io.github.mandarine3ds.mandarine.utils.PermissionsHandler
import io.github.mandarine3ds.mandarine.utils.ThemeUtil
import io.github.mandarine3ds.mandarine.viewmodel.GamesViewModel
import io.github.mandarine3ds.mandarine.viewmodel.HomeViewModel
import io.github.mandarine3ds.mandarine.dialogs.NetPlayDialog

class MainActivity : AppCompatActivity(), ThemeProvider {
    private lateinit var binding: ActivityMainBinding

    private val homeViewModel: HomeViewModel by viewModels()
    private val gamesViewModel: GamesViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override var themeId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition {
            !DirectoryInitialization.areMandarineDirectoriesReady() &&
                    PermissionsHandler.hasWriteAccess(this)
        }

        if (PermissionsHandler.hasWriteAccess(applicationContext) &&
            DirectoryInitialization.areMandarineDirectoriesReady()) {
            settingsViewModel.settings.loadSettings()
        }

        ThemeUtil.themeChangeListener(this)
        ThemeUtil.setTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        window.statusBarColor =
            ContextCompat.getColor(applicationContext, android.R.color.transparent)
        window.navigationBarColor =
            ContextCompat.getColor(applicationContext, android.R.color.transparent)

        binding.statusBarShade.setBackgroundColor(
            ThemeUtil.getColorWithOpacity(
                MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorSurface
                ),
                ThemeUtil.SYSTEM_BAR_ALPHA
            )
        )
        if (InsetsHelper.getSystemGestureType(applicationContext) !=
            InsetsHelper.GESTURE_NAVIGATION
        ) {
            binding.navigationBarShade.setBackgroundColor(
                ThemeUtil.getColorWithOpacity(
                    MaterialColors.getColor(
                        binding.root,
                        com.google.android.material.R.attr.colorSurface
                    ),
                    ThemeUtil.SYSTEM_BAR_ALPHA
                )
            )
        }

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        setUpNavigation(navHostFragment.navController)

        lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    homeViewModel.statusBarShadeVisible.collect {
                        showStatusBarShade(it)
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    homeViewModel.isPickingUserDir.collect { checkUserPermissions() }
                }
            }
        }

        // Dismiss previous notifications (should not happen unless a crash occurred)
        EmulationActivity.stopForegroundService(this)

        setInsets()
    }

    override fun onResume() {
        checkUserPermissions()

        ThemeUtil.setCorrectTheme(this)
        super.onResume()
    }

    override fun onDestroy() {
        EmulationActivity.stopForegroundService(this)
        super.onDestroy()
    }

    fun displayMultiplayerDialog() {
        val dialog = NetPlayDialog(this)
        dialog.show()
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)
        themeId = resId
    }

    private fun checkUserPermissions() {
        val firstTimeSetup = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)

        if (!firstTimeSetup && !PermissionsHandler.hasWriteAccess(this) &&
            !homeViewModel.isPickingUserDir.value
        ) {
            SelectUserDirectoryDialogFragment.newInstance(this)
                .show(supportFragmentManager, SelectUserDirectoryDialogFragment.TAG)
        }
    }

    fun finishSetup(navController: NavController) {
        navController.navigate(R.id.action_firstTimeSetupFragment_to_gamesFragment)
    }

    private fun setUpNavigation(navController: NavController) {
        val firstTimeSetup = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)

        if (firstTimeSetup && !homeViewModel.navigatedToSetup) {
            navController.navigate(R.id.firstTimeSetupFragment)
            homeViewModel.navigatedToSetup = true
        }
    }

    private fun showStatusBarShade(visible: Boolean) {
        binding.statusBarShade.animate().apply {
            if (visible) {
                binding.statusBarShade.visibility = View.VISIBLE
                binding.statusBarShade.translationY = binding.statusBarShade.height.toFloat() * -2
                duration = 300
                translationY(0f)
                interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            } else {
                duration = 300
                translationY(binding.statusBarShade.height.toFloat() * -2)
                interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            }
        }.withEndAction {
            if (!visible) {
                binding.statusBarShade.visibility = View.INVISIBLE
            }
        }.start()
    }

    private fun setInsets() =
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.root
        ) { _: View, windowInsets: WindowInsetsCompat ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val mlpStatusShade = binding.statusBarShade.layoutParams as MarginLayoutParams
            mlpStatusShade.height = insets.top
            binding.statusBarShade.layoutParams = mlpStatusShade

            // The only situation where we care to have a nav bar shade is when it's at the bottom
            // of the screen where scrolling list elements can go behind it.
            val mlpNavShade = binding.navigationBarShade.layoutParams as MarginLayoutParams
            mlpNavShade.height = insets.bottom
            binding.navigationBarShade.layoutParams = mlpNavShade

            windowInsets
        }

    val openMandarineDirectory = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { result: Uri? ->
        if (result == null) {
            return@registerForActivityResult
        }

        MandarineDirectoryHelper(this@MainActivity).showMandarineDirectoryDialog(result, buttonState = {})
    }

    val ciaFileInstaller = registerForActivityResult(
        OpenFileResultContract()
    ) { result: Intent? ->
        if (result == null) {
            return@registerForActivityResult
        }

        val selectedFiles =
            FileBrowserHelper.getSelectedFiles(result, applicationContext, listOf("cia"))
        if (selectedFiles == null) {
            Toast.makeText(applicationContext, R.string.cia_file_not_found, Toast.LENGTH_LONG)
                .show()
            return@registerForActivityResult
        }

        val workManager = WorkManager.getInstance(applicationContext)
        workManager.enqueueUniqueWork(
            "installCiaWork", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequest.Builder(CiaInstallWorker::class.java)
                .setInputData(
                    Data.Builder().putStringArray("CIA_FILES", selectedFiles)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        )
    }
}
