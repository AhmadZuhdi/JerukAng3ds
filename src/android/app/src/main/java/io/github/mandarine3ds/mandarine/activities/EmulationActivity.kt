// Copyright 2025 Citra Project / Mandarine Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

package io.github.mandarine3ds.mandarine.activities

import android.Manifest.permission
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import io.github.mandarine3ds.mandarine.MandarineApplication
import io.github.mandarine3ds.mandarine.NativeLibrary
import io.github.mandarine3ds.mandarine.R
import io.github.mandarine3ds.mandarine.camera.StillImageCameraHelper.OnFilePickerResult
import io.github.mandarine3ds.mandarine.contracts.OpenFileResultContract
import io.github.mandarine3ds.mandarine.databinding.ActivityEmulationBinding
import io.github.mandarine3ds.mandarine.display.ScreenAdjustmentUtil
import io.github.mandarine3ds.mandarine.features.hotkeys.HotkeyUtility
import io.github.mandarine3ds.mandarine.features.settings.model.BooleanSetting
import io.github.mandarine3ds.mandarine.features.settings.model.SettingsViewModel
import io.github.mandarine3ds.mandarine.features.settings.model.view.InputBindingSetting
import io.github.mandarine3ds.mandarine.fragments.EmulationFragment
import io.github.mandarine3ds.mandarine.fragments.MessageDialogFragment
import io.github.mandarine3ds.mandarine.utils.ControllerMappingHelper
import io.github.mandarine3ds.mandarine.utils.FileBrowserHelper
import io.github.mandarine3ds.mandarine.utils.ForegroundService
import io.github.mandarine3ds.mandarine.utils.EmulationLifecycleUtil
import io.github.mandarine3ds.mandarine.utils.EmulationMenuSettings
import io.github.mandarine3ds.mandarine.utils.ThemeUtil
import io.github.mandarine3ds.mandarine.viewmodel.EmulationViewModel
import io.github.mandarine3ds.mandarine.utils.NetPlayManager
import io.github.mandarine3ds.mandarine.dialogs.NetPlayDialog
import io.github.mandarine3ds.mandarine.features.settings.model.IntSetting
import androidx.core.os.BundleCompat
import io.github.mandarine3ds.mandarine.utils.PlayTimeTracker
import io.github.mandarine3ds.mandarine.model.Game

class EmulationActivity : AppCompatActivity() {
    private val pref: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(MandarineApplication.appContext)
    private var foregroundService: Intent? = null
    var isActivityRecreated = false
    private val emulationViewModel: EmulationViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private lateinit var binding: ActivityEmulationBinding
    private lateinit var screenAdjustmentUtil: ScreenAdjustmentUtil
    private lateinit var hotkeyUtility: HotkeyUtility

    private var isEmulationRunning: Boolean = false
    private var emulationStartTime: Long = 0

    private var enableAutoMap: Boolean = false

    private val touchButtons: MutableMap<String, Map<String, Any>> = mutableMapOf()
    private val pressedButtons: MutableSet<Int> = mutableSetOf();

    private val emulationFragment: EmulationFragment
        get() {
            val navHostFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
            return navHostFragment.getChildFragmentManager().fragments.last() as EmulationFragment
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.setTheme(this)

        settingsViewModel.settings.loadSettings()

        super.onCreate(savedInstanceState)

        NativeLibrary.enableAdrenoTurboMode(BooleanSetting.ADRENO_GPU_BOOST.boolean)

        binding = ActivityEmulationBinding.inflate(layoutInflater)
        screenAdjustmentUtil = ScreenAdjustmentUtil(this, windowManager, settingsViewModel.settings)
        hotkeyUtility = HotkeyUtility(screenAdjustmentUtil, this)
        setContentView(binding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController
        navController.setGraph(R.navigation.emulation_navigation, intent.extras)

        isActivityRecreated = savedInstanceState != null

        // Set these options now so that the SurfaceView the game renders into is the right size.
        enableFullscreenImmersive()

        // Override Mandarine core INI with the one set by our in game menu
        NativeLibrary.swapScreens(
            EmulationMenuSettings.swapScreens,
            windowManager.defaultDisplay.rotation
        )

        // Start a foreground service to prevent the app from getting killed in the background
        foregroundService = Intent(this, ForegroundService::class.java)
        startForegroundService(foregroundService)

        EmulationLifecycleUtil.addShutdownHook(hook = {
            if (intent.getBooleanExtra("launchedFromShortcut", false)) {
                finishAffinity()
            } else {
                this.finish()
            }
        })

        isEmulationRunning = true
        instance = this

        emulationStartTime = System.currentTimeMillis()

        applyOrientationSettings() // Check for orientation settings at startup
        setupConfig()
    }

    // TODO: find to get realtime(?) data
    fun getBatteryInfo(): FloatArray {

        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = applicationContext.registerReceiver(null, ifilter)

        if (batteryStatus == null) {
            return floatArrayOf(0f, 0f)
        }

        val temperature = batteryStatus!!.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val temperatureCelsius = temperature / 10f

        val level: Int = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val scale: Int = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryPct = level * 100 / scale.toFloat()

        return floatArrayOf(
            temperatureCelsius,
            batteryPct
        )
    }

    // On some devices, the system bars will not disappear on first boot or after some
    // rotations. Here we set full screen immersive repeatedly in onResume and in
    // onWindowFocusChanged to prevent the unwanted status bar state.
    override fun onResume() {
        super.onResume()
        enableFullscreenImmersive()
        applyOrientationSettings() // Check for orientation settings changes on runtime
        setupConfig()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        enableFullscreenImmersive()
    }

    public override fun onRestart() {
        super.onRestart()
        NativeLibrary.reloadCameraDevices()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("isEmulationRunning", isEmulationRunning)
        outState.putInt("force_orientation", requestedOrientation)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        isEmulationRunning = savedInstanceState.getBoolean("isEmulationRunning", false)
    }

    override fun onDestroy() {
        NativeLibrary.enableAdrenoTurboMode(false)
        EmulationLifecycleUtil.clear()
        val sessionTime = System.currentTimeMillis() - emulationStartTime

        val game = try {
            intent.extras?.let { extras ->
                BundleCompat.getParcelable(extras, "game", Game::class.java)
            } ?: throw IllegalStateException("Missing game data in intent extras")
        } catch (e: Exception) {
            throw IllegalStateException("Failed to retrieve game data: ${e.message}", e)
        }

        PlayTimeTracker.addPlayTime(game, sessionTime)
        stopForegroundService(this)
        isEmulationRunning = false
        instance = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            NativeLibrary.REQUEST_CODE_NATIVE_CAMERA -> {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED &&
                    shouldShowRequestPermissionRationale(permission.CAMERA)
                ) {
                    MessageDialogFragment.newInstance(
                        R.string.camera,
                        R.string.camera_permission_needed
                    ).show(supportFragmentManager, MessageDialogFragment.TAG)
                }
                NativeLibrary.cameraPermissionResult(
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                )
            }

            NativeLibrary.REQUEST_CODE_NATIVE_MIC -> {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED &&
                    shouldShowRequestPermissionRationale(permission.RECORD_AUDIO)
                ) {
                    MessageDialogFragment.newInstance(
                        R.string.microphone,
                        R.string.microphone_permission_needed
                    ).show(supportFragmentManager, MessageDialogFragment.TAG)
                }
                NativeLibrary.micPermissionResult(
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                )
            }

            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    fun onEmulationStarted() {
        emulationViewModel.setEmulationStarted(true)
        Toast.makeText(
            applicationContext,
            getString(R.string.emulation_menu_help),
            Toast.LENGTH_LONG
        ).show()
    }

    fun displayMultiplayerDialog() {
        val dialog = NetPlayDialog(this)
        dialog.show()
    }

    fun addNetPlayMessages(type: Int, msg: String) {
        NetPlayManager.addNetPlayMessage(type, msg)
    }

    private fun enableFullscreenImmersive() {
        val attributes = window.attributes

        attributes.layoutInDisplayCutoutMode =
            if (BooleanSetting.EXPAND_TO_CUTOUT_AREA.boolean && !NativeLibrary.isPortraitMode) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            } else {
                // TODO: Remove this once we properly account for display insets in the input overlay
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
            }

        window.attributes = attributes

        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyOrientationSettings() {
        val orientationOption = IntSetting.ORIENTATION_OPTION.int
        screenAdjustmentUtil.changeActivityOrientation(orientationOption)
    }

    private fun setupConfig() {

        enableAutoMap = pref.getBoolean(BooleanSetting.CONTROL_AUTOMAP.name, false)

        touchButtons.clear()
        touchButtons[NativeLibrary.ButtonType.BUTTON_TOUCH_1.toString()] = mapOf(
            "portrait_x" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_1}-Portrait-X", 0f),
            "portrait_y" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_1}-Portrait-Y", 0f),
            "landscape_x" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_1}-X", 0f),
            "landscape_y" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_1}-Y", 0f),
            "trigger" to setOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_X)
        )

        touchButtons[NativeLibrary.ButtonType.BUTTON_TOUCH_2.toString()] = mapOf(
            "portrait_x" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_2}-Portrait-X", 0f),
            "portrait_y" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_2}-Portrait-Y", 0f),
            "landscape_x" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_2}-X", 0f),
            "landscape_y" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_2}-Y", 0f),
            "trigger" to setOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_Y)
        )

        touchButtons[NativeLibrary.ButtonType.BUTTON_TOUCH_3.toString()] = mapOf(
            "portrait_x" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_3}-Portrait-X", 0f),
            "portrait_y" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_3}-Portrait-Y", 0f),
            "landscape_x" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_3}-X", 0f),
            "landscape_y" to pref.getFloat("${NativeLibrary.ButtonType.BUTTON_TOUCH_3}-Y", 0f),
            "trigger" to setOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_B)
        )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {

        // TODO: change to detect is event come from gamepad or not
        val keyToHandle = setOf(
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BACK
        )

        if (keyToHandle.contains(event.keyCode)) {
            try {
                this.handleDispatchKeyEvent(event)
            } catch (e: Exception) {
                return super.dispatchKeyEvent(event)
            }
            return true;
        }

        return super.dispatchKeyEvent(event)
    }

    // Gets button presses
    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation")
    fun handleDispatchKeyEvent(event: KeyEvent): Boolean {
        // TODO: Move this check into native code - prevents crash if input pressed before starting emulation
        if (!NativeLibrary.isRunning()) {
            return false
        }

        if (emulationFragment.isDrawerOpen()) {
            throw Exception()
        }

        val button = pref.getInt(InputBindingSetting.getInputButtonKey(event), event.scanCode)
        val action: Int = when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                hotkeyUtility.handleHotkey(button)

                // On some devices, the back gesture / button press is not intercepted by androidx
                // and fails to open the emulation menu. So we're stuck running deprecated code to
                // cover for either a fault on androidx's side or in OEM skins (MIUI at least)
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    // If the hotkey is pressed, we don't want to open the drawer
                    if (hotkeyUtility.HotkeyIsPressed) {
                        return true
                    } else {
                        onBackPressed()
                    }
                }

                // Normal key events.
                pressedButtons.add(event.keyCode)
                NativeLibrary.ButtonState.PRESSED
            }

            KeyEvent.ACTION_UP -> {
                pressedButtons.remove(event.keyCode)
                hotkeyUtility.HotkeyIsPressed = false
                NativeLibrary.ButtonState.RELEASED
            }
            else -> return false
        }
        val input = event.device
            ?: // Controller was disconnected
            return false

        // TODO: make this configurable
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.BUTTON_X, action)
            NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.BUTTON_A, action)
            return true;
        }

        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            Toast.makeText(
                applicationContext,
                "Saving state",
                Toast.LENGTH_LONG
            ).show()

            NativeLibrary.saveState(NativeLibrary.QUICKSAVE_SLOT)

            return true;
        }

        if (pressedButtons.size > 1) {
            for ((k, v) in touchButtons) {
                val triggerSet = (v.get("trigger") as Iterable<*>).toSet()
                val intersect = pressedButtons.intersect(triggerSet)
                if (intersect.size == triggerSet.size && intersect == triggerSet) {
                    val isPortrait = NativeLibrary.isPortraitMode

                    val x = if (isPortrait) v["portrait_x"] else v["landscape_x"]
                    val y = if (isPortrait) v["portrait_y"] else v["landscape_y"]

                    NativeLibrary.onTouchEvent(x as Float, y as Float, true)
                    Handler().postDelayed({
                        NativeLibrary.onTouchEvent(0f, 0f, false)
                    }, 50)
                    return true
                }
            }
        }

        if (enableAutoMap && button == event.scanCode) {
            return when(event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.BUTTON_A, action)
                KeyEvent.KEYCODE_BUTTON_B -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.BUTTON_B, action)
                KeyEvent.KEYCODE_BUTTON_X -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.BUTTON_X, action)
                KeyEvent.KEYCODE_BUTTON_Y -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.BUTTON_Y, action)
                KeyEvent.KEYCODE_BUTTON_R1 -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.TRIGGER_R, action)
                KeyEvent.KEYCODE_BUTTON_L1 -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.TRIGGER_L, action)
                KeyEvent.KEYCODE_DPAD_UP -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.DPAD_UP, action)
                KeyEvent.KEYCODE_DPAD_DOWN -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.DPAD_DOWN, action)
                KeyEvent.KEYCODE_DPAD_LEFT -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.DPAD_LEFT, action)
                KeyEvent.KEYCODE_DPAD_RIGHT -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.DPAD_RIGHT, action)
                KeyEvent.KEYCODE_BUTTON_SELECT -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.BUTTON_SELECT, action)
                KeyEvent.KEYCODE_BUTTON_START -> NativeLibrary.onGamePadEvent(input.descriptor, NativeLibrary.ButtonType.BUTTON_START, action)
                else -> {
                    NativeLibrary.onGamePadEvent(input.descriptor, button, action)
                }
            }
        }

        return NativeLibrary.onGamePadEvent(input.descriptor, button, action)
    }

    private fun onAmiiboSelected(selectedFile: String) {
        val success = NativeLibrary.loadAmiibo(selectedFile)
        if (!success) {
            MessageDialogFragment.newInstance(
                R.string.amiibo_load_error,
                R.string.amiibo_load_error_message
            ).show(supportFragmentManager, MessageDialogFragment.TAG)
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // TODO: Move this check into native code - prevents crash if input pressed before starting emulation
        if (!NativeLibrary.isRunning() ||
            (event.source and InputDevice.SOURCE_CLASS_JOYSTICK == 0) ||
            emulationFragment.isDrawerOpen()) {
            return super.dispatchGenericMotionEvent(event)
        }

        // Don't attempt to do anything if we are disconnecting a device.
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            return true
        }
        val input = event.device
        val motions = input.motionRanges
        val axisValuesCirclePad = floatArrayOf(0.0f, 0.0f)
        val axisValuesCStick = floatArrayOf(0.0f, 0.0f)
        val axisValuesDPad = floatArrayOf(0.0f, 0.0f)
        var isTriggerPressedLMapped = false
        var isTriggerPressedRMapped = false
        var isTriggerPressedZLMapped = false
        var isTriggerPressedZRMapped = false
        var isTriggerPressedL = false
        var isTriggerPressedR = false
        var isTriggerPressedZL = false
        var isTriggerPressedZR = false
        for (range in motions) {
            val axis = range.axis
            val origValue = event.getAxisValue(axis)
            var value = ControllerMappingHelper.scaleAxis(input, axis, origValue)
            val nextMapping =
                pref.getInt(InputBindingSetting.getInputAxisButtonKey(axis), -1)
            val guestOrientation =
                pref.getInt(InputBindingSetting.getInputAxisOrientationKey(axis), -1)
            if (nextMapping == -1 || guestOrientation == -1) {
                // Axis is unmapped
                continue
            }
            if (value > 0f && value < 0.1f || value < 0f && value > -0.1f) {
                // Skip joystick wobble
                value = 0f
            }
            when (nextMapping) {
                NativeLibrary.ButtonType.STICK_LEFT -> {
                    axisValuesCirclePad[guestOrientation] = value
                }

                NativeLibrary.ButtonType.STICK_C -> {
                    axisValuesCStick[guestOrientation] = value
                }

                NativeLibrary.ButtonType.DPAD -> {
                    axisValuesDPad[guestOrientation] = value
                }

                NativeLibrary.ButtonType.TRIGGER_L -> {
                    isTriggerPressedLMapped = true
                    isTriggerPressedL = value != 0f
                }

                NativeLibrary.ButtonType.TRIGGER_R -> {
                    isTriggerPressedRMapped = true
                    isTriggerPressedR = value != 0f
                }

                NativeLibrary.ButtonType.BUTTON_ZL -> {
                    isTriggerPressedZLMapped = true
                    isTriggerPressedZL = value != 0f
                }

                NativeLibrary.ButtonType.BUTTON_ZR -> {
                    isTriggerPressedZRMapped = true
                    isTriggerPressedZR = value != 0f
                }
            }
        }

        // Circle-Pad and C-Stick status
        NativeLibrary.onGamePadMoveEvent(
            input.descriptor,
            NativeLibrary.ButtonType.STICK_LEFT,
            axisValuesCirclePad[0],
            axisValuesCirclePad[1]
        )
        NativeLibrary.onGamePadMoveEvent(
            input.descriptor,
            NativeLibrary.ButtonType.STICK_C,
            axisValuesCStick[0],
            axisValuesCStick[1]
        )

        // Triggers L/R and ZL/ZR
        if (isTriggerPressedLMapped) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.TRIGGER_L,
                if (isTriggerPressedL) {
                    NativeLibrary.ButtonState.PRESSED
                } else {
                    NativeLibrary.ButtonState.RELEASED
                }
            )
        }
        if (isTriggerPressedRMapped) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.TRIGGER_R,
                if (isTriggerPressedR) {
                    NativeLibrary.ButtonState.PRESSED
                } else {
                    NativeLibrary.ButtonState.RELEASED
                }
            )
        }
        if (isTriggerPressedZLMapped) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.BUTTON_ZL,
                if (isTriggerPressedZL) {
                    NativeLibrary.ButtonState.PRESSED
                } else {
                    NativeLibrary.ButtonState.RELEASED
                }
            )
        }
        if (isTriggerPressedZRMapped) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.BUTTON_ZR,
                if (isTriggerPressedZR) {
                    NativeLibrary.ButtonState.PRESSED
                } else {
                    NativeLibrary.ButtonState.RELEASED
                }
            )
        }

        // Work-around to allow D-pad axis to be bound to emulated buttons
        if (axisValuesDPad[0] == 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_LEFT,
                NativeLibrary.ButtonState.RELEASED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_RIGHT,
                NativeLibrary.ButtonState.RELEASED
            )
        }
        if (axisValuesDPad[0] < 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_LEFT,
                NativeLibrary.ButtonState.PRESSED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_RIGHT,
                NativeLibrary.ButtonState.RELEASED
            )
        }
        if (axisValuesDPad[0] > 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_LEFT,
                NativeLibrary.ButtonState.RELEASED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_RIGHT,
                NativeLibrary.ButtonState.PRESSED
            )
        }
        if (axisValuesDPad[1] == 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_UP,
                NativeLibrary.ButtonState.RELEASED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_DOWN,
                NativeLibrary.ButtonState.RELEASED
            )
        }
        if (axisValuesDPad[1] < 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_UP,
                NativeLibrary.ButtonState.PRESSED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_DOWN,
                NativeLibrary.ButtonState.RELEASED
            )
        }
        if (axisValuesDPad[1] > 0f) {
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_UP,
                NativeLibrary.ButtonState.RELEASED
            )
            NativeLibrary.onGamePadEvent(
                NativeLibrary.TouchScreenDevice,
                NativeLibrary.ButtonType.DPAD_DOWN,
                NativeLibrary.ButtonState.PRESSED
            )
        }
        return true
    }

    val openFileLauncher =
        registerForActivityResult(OpenFileResultContract()) { result: Intent? ->
            if (result == null) return@registerForActivityResult
            val selectedFiles = FileBrowserHelper.getSelectedFiles(
                result, applicationContext, listOf<String>("bin")
            ) ?: return@registerForActivityResult
            onAmiiboSelected(selectedFiles[0])
        }

    val openImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { result: Uri? ->
            if (result == null) {
                return@registerForActivityResult
            }

            OnFilePickerResult(result.toString())
        }

    companion object {
        private var instance: EmulationActivity? = null

        fun stopForegroundService(activity: Activity) {
            val startIntent = Intent(activity, ForegroundService::class.java)
            startIntent.action = ForegroundService.ACTION_STOP
            activity.startForegroundService(startIntent)
        }

        fun isRunning(): Boolean {
            return instance?.isEmulationRunning ?: false
        }
    }
}
