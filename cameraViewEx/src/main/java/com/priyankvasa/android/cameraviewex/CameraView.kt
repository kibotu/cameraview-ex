/*
 * Copyright 2019 Priyank Vasa
 *
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.priyankvasa.android.cameraviewex

import android.Manifest
import android.app.Activity
import android.content.Context
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.support.annotation.RequiresApi
import android.support.annotation.RequiresPermission
import android.support.v4.view.ViewCompat
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.priyankvasa.android.cameraviewex.R.attr.outputFormat
import com.priyankvasa.android.cameraviewex.extension.getValue
import com.priyankvasa.android.cameraviewex.extension.isUiThread
import com.priyankvasa.android.cameraviewex.extension.setValue
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.io.File
import kotlin.coroutines.CoroutineContext

class CameraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            System.setProperty("kotlinx.coroutines.debug", "on")
        }
    }

    private val parentJob: Job = SupervisorJob()

    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + parentJob)

    private val coroutineContext: CoroutineContext get() = coroutineScope.coroutineContext

    private val preview: PreviewImpl = createPreview(context)

    private val cameraOpenedListeners: HashSet<() -> Unit> by lazy { HashSet<() -> Unit>() }

    private val pictureTakenListeners: HashSet<(imageData: ByteArray) -> Unit>
        by lazy { HashSet<(imageData: ByteArray) -> Unit>() }

    private var legacyPreviewFrameListener: ((image: LegacyImage) -> Unit)? = null

    private var previewFrameListener: ((image: Image) -> Unit)? = null

    private val cameraErrorListeners: HashSet<(t: Throwable, errorLevel: ErrorLevel) -> Unit>
        by lazy { HashSet<(t: Throwable, errorLevel: ErrorLevel) -> Unit>() }

    private val cameraClosedListeners: HashSet<() -> Unit>
        by lazy { HashSet<() -> Unit>() }

    private val videoRecordStartedListeners: HashSet<() -> Unit>
        by lazy { HashSet<() -> Unit>() }

    private val videoRecordStoppedListeners: HashSet<(isSuccess: Boolean) -> Unit>
        by lazy { HashSet<(isSuccess: Boolean) -> Unit>() }

    private val listener = object : CameraInterface.Listener {

        private var requestLayoutOnOpen: Boolean = false

        var isEnabled: Boolean = true
            private set

        fun reserveRequestLayoutOnOpen() {
            requestLayoutOnOpen = true
        }

        fun disable() {
            isEnabled = false
            clear()
        }

        fun clear() {
            cameraOpenedListeners.clear()
            legacyPreviewFrameListener = null
            previewFrameListener = null
            pictureTakenListeners.clear()
            cameraErrorListeners.clear()
            cameraClosedListeners.clear()
            videoRecordStartedListeners.clear()
            videoRecordStoppedListeners.clear()
        }

        override fun onCameraOpened() {
            launchOnUi {
                if (requestLayoutOnOpen) {
                    requestLayoutOnOpen = false
                    requestLayout()
                }
                cameraOpenedListeners.forEach { it() }
            }
        }

        override fun onLegacyPreviewFrame(image: LegacyImage) {
            legacyPreviewFrameListener?.invoke(image)
        }

        @RequiresApi(Build.VERSION_CODES.KITKAT)
        override fun onPreviewFrame(reader: ImageReader) {
            reader.acquireLatestImage()?.use { previewFrameListener?.invoke(it) }
        }

        override fun onPictureTaken(imageData: ByteArray) {
            launchOnUi { pictureTakenListeners.forEach { it(imageData) } }
        }

        override fun onCameraError(e: Exception, errorLevel: ErrorLevel, isCritical: Boolean) {
            if (isCritical && cameraErrorListeners.isEmpty()) throw e
            if (errorLevel == ErrorLevel.Debug) Timber.d(e)
            else launchOnUi { cameraErrorListeners.forEach { it(e, errorLevel) } }
        }

        override fun onCameraClosed() {
            launchOnUi { cameraClosedListeners.forEach { it.invoke() } }
        }

        override fun onVideoRecordStarted() {
            launchOnUi { videoRecordStartedListeners.forEach { it.invoke() } }
        }

        override fun onVideoRecordStopped(isSuccess: Boolean) {
            launchOnUi { videoRecordStoppedListeners.forEach { it.invoke(isSuccess) } }
        }
    }

    /** Display orientation detector */
    private val orientationDetector: OrientationDetector = object : OrientationDetector(context) {

        override fun onDisplayOrientationChanged(displayOrientation: Int) {
            preview.setDisplayOrientation(displayOrientation)
            camera.deviceRotation = displayOrientation
        }

        override fun onSensorOrientationChanged(sensorOrientation: Int) {
            val orientation: Orientation = Orientation.parse(sensorOrientation)
            val rotation: Int = when (orientation) {
                Orientation.Portrait, Orientation.PortraitInverted -> orientation.value
                Orientation.Landscape -> Orientation.LandscapeInverted.value
                Orientation.LandscapeInverted -> Orientation.Landscape.value
                Orientation.Unknown -> return
            }
            if (camera.deviceRotation != rotation) camera.deviceRotation = rotation
        }
    }

    private val config: CameraConfiguration = if (isInEditMode) {
        listener.disable()
        orientationDetector.disable()
        CameraConfiguration()
    } else {

        // Add shutter view
        addView(preview.shutterView)
        preview.shutterView.layoutParams = preview.view.layoutParams

        // Attributes
        context.obtainStyledAttributes(
            attrs,
            R.styleable.CameraView,
            defStyleAttr,
            R.style.Widget_CameraView
        ).run {

            adjustViewBounds = getBoolean(R.styleable.CameraView_android_adjustViewBounds, Modes.DEFAULT_ADJUST_VIEW_BOUNDS)

            CameraConfiguration().apply {

                facing.value = getInt(R.styleable.CameraView_facing, Modes.DEFAULT_FACING)
                aspectRatio = getString(R.styleable.CameraView_aspectRatio)
                    .runCatching ar@{
                        if (this@ar.isNullOrBlank()) Modes.DEFAULT_ASPECT_RATIO
                        else AspectRatio.parse(this@ar)
                    }
                    .getOrElse {
                        listener.onCameraError(CameraViewException(
                            "Invalid aspect ratio." +
                                " Reverting to default ${Modes.DEFAULT_ASPECT_RATIO}",
                            it
                        ))
                        Modes.DEFAULT_ASPECT_RATIO
                    }
                autoFocus.value = getInt(R.styleable.CameraView_autoFocus, Modes.DEFAULT_AUTO_FOCUS)
                flash.value = getInt(R.styleable.CameraView_flash, Modes.DEFAULT_FLASH)
                cameraMode.value = getInt(R.styleable.CameraView_cameraMode, Modes.DEFAULT_CAMERA_MODE)
                outputFormat.value = getInt(R.styleable.CameraView_outputFormat, Modes.DEFAULT_OUTPUT_FORMAT)
                shutter.value = getInt(R.styleable.CameraView_shutter, Modes.DEFAULT_SHUTTER)

                // API 21+
                jpegQuality.value = getInt(R.styleable.CameraView_jpegQuality, Modes.DEFAULT_JPEG_QUALITY)
                touchToFocus.value = getBoolean(R.styleable.CameraView_touchToFocus, Modes.DEFAULT_TOUCH_TO_FOCUS)
                pinchToZoom.value = getBoolean(R.styleable.CameraView_pinchToZoom, Modes.DEFAULT_PINCH_TO_ZOOM)
                awb.value = getInt(R.styleable.CameraView_awb, Modes.DEFAULT_AWB)
                opticalStabilization.value = getBoolean(R.styleable.CameraView_opticalStabilization, Modes.DEFAULT_OPTICAL_STABILIZATION)
                noiseReduction.value = getInt(R.styleable.CameraView_noiseReduction, Modes.DEFAULT_NOISE_REDUCTION)
                zsl.value = getBoolean(R.styleable.CameraView_zsl, Modes.DEFAULT_ZSL)
                this@run.recycle()
            }
        }
    }

    private var camera: CameraInterface = run {

        val cameraJob: Job = SupervisorJob(parentJob)

        return@run when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP -> Camera1(listener, preview, config, cameraJob)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M -> Camera2(listener, preview, config, cameraJob, context)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> Camera2Api23(listener, preview, config, cameraJob, context)
            else -> Camera2Api24(listener, preview, config, cameraJob, context)
        }
    }

    init {
        config.observeAspectRatio(camera) {
            if (runBlocking(coroutineContext) { camera.setAspectRatio(it) }) requestLayout()
        }
        config.shutter.observe(camera) { preview.shutterView.shutterTime = it }
    }

    internal val isUiTestCompatible: Boolean get() = camera is Camera2

    /**
     * Returns `true` if this [CameraView] instance is active and usable.
     * It will `return` false after [destroy] is called.
     * A new instance should be created if [isActive] is false.
     */
    val isActive: Boolean get() = camera.isActive && parentJob.isActive

    /** `true` if the camera is opened `false` otherwise. */
    val isCameraOpened: Boolean get() = camera.isCameraOpened

    /** `true` if there is a video recording in progress, `false` otherwise. */
    val isVideoRecording: Boolean get() = camera.isVideoRecording

    /** Check if [Modes.CameraMode.SINGLE_CAPTURE] is enabled */
    val isSingleCaptureModeEnabled: Boolean get() = config.isSingleCaptureModeEnabled

    /** Check if [Modes.CameraMode.CONTINUOUS_FRAME] is enabled */
    val isContinuousFrameModeEnabled: Boolean get() = config.isContinuousFrameModeEnabled

    /** Check if [Modes.CameraMode.VIDEO_CAPTURE] is enabled */
    val isVideoCaptureModeEnabled: Boolean get() = config.isVideoCaptureModeEnabled

    /** Set camera mode of operation. Supported values are [Modes.CameraMode]. */
    fun setCameraMode(@Modes.CameraMode mode: Int) {
        if (!requireInUiThread()) return
        config.cameraMode.value = mode
    }

    /**
     * True when this CameraView is adjusting its bounds to preserve the aspect ratio of
     * camera.
     */
    var adjustViewBounds: Boolean = false
        set(value) {
            if (value == field || !requireInUiThread()) return
            field = value
            requestLayout()
        }

    /** Current aspect ratio of camera. Valid format is "height:width" eg. "4:3". */
    var aspectRatio: AspectRatio by config::aspectRatio

    /**
     * Set format of the output of image data produced from the camera for [Modes.CameraMode.SINGLE_CAPTURE] mode.
     * Supported values are [Modes.OutputFormat].
     */
    @get:Modes.OutputFormat
    @setparam:Modes.OutputFormat
    var outputFormat: Int by config.outputFormat::value

    /**
     * Set image quality of the output image.
     * This property is only applicable for [outputFormat] [Modes.OutputFormat.JPEG]
     * Supported values are [Modes.JpegQuality].
     */
    @get:Modes.JpegQuality
    @setparam:Modes.JpegQuality
    var jpegQuality: Int by config.jpegQuality::value

    /** Set which camera to use (like front or back). Supported values are [Modes.Facing]. */
    @get:Modes.Facing
    @setparam:Modes.Facing
    var facing: Int
        get() = config.facing.value
        set(value) {
            if (!requireInUiThread()) return
            config.facing.value = value
        }

    /** Gets all the aspect ratios supported by the current camera. */
    val supportedAspectRatios: Set<AspectRatio> get() = camera.supportedAspectRatios

    /**
     * Set auto focus mode for selected camera. Supported modes are [Modes.AutoFocus].
     * See [android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE]
     */
    @get:Modes.AutoFocus
    @setparam:Modes.AutoFocus
    var autoFocus: Int
        get() = config.autoFocus.value
        set(value) {
            if (!requireInUiThread()) return
            config.autoFocus.value = value
        }

    /** Allow manual focus on an area by tapping on camera view. True is on and false is off. */
    var touchToFocus: Boolean by config.touchToFocus::value

    /** Allow pinch gesture on camera view for digital zooming. True is on and false is off. */
    var pinchToZoom: Boolean by config.pinchToZoom::value

    /** Maximum digital zoom supported by selected camera device. */
    val maxDigitalZoom: Float get() = camera.maxDigitalZoom

    /** Set digital zoom value. Must be between 1.0f and [maxDigitalZoom] inclusive. */
    var currentDigitalZoom: Float
        get() = config.currentDigitalZoom.value
        set(value) {
            if (!requireInUiThread()) return
            config.currentDigitalZoom.value = value
        }

    /**
     * Set auto white balance mode for preview and still captures. Supported values are [Modes.AutoWhiteBalance].
     * See [android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE]
     */
    @get:Modes.AutoWhiteBalance
    @setparam:Modes.AutoWhiteBalance
    var awb: Int
        get() = config.awb.value
        set(value) {
            if (!requireInUiThread()) return
            config.awb.value = value
        }

    /**
     * Set flash mode. Supported values are [Modes.Flash].
     * See [android.hardware.camera2.CaptureRequest.FLASH_MODE]
     */
    @get:Modes.Flash
    @setparam:Modes.Flash
    var flash: Int
        get() = config.flash.value
        set(value) {
            if (!requireInUiThread()) return
            config.flash.value = value
        }

    /**
     * Turn on or off optical stabilization for preview and still captures.
     * See [android.hardware.camera2.CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE]
     */
    var opticalStabilization: Boolean
        get() = config.opticalStabilization.value
        set(value) {
            if (!requireInUiThread()) return
            config.opticalStabilization.value = value
        }

    /**
     * Set noise reduction mode. Supported values are [Modes.NoiseReduction].
     * See [android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE]
     */
    @get:Modes.NoiseReduction
    @setparam:Modes.NoiseReduction
    var noiseReduction: Int
        get() = config.noiseReduction.value
        set(value) {
            if (!requireInUiThread()) return
            config.noiseReduction.value = value
        }

    /** Current shutter time in milliseconds. Supported values are [Modes.Shutter]. */
    @get:Modes.Shutter
    @setparam:Modes.Shutter
    var shutter: Int by config.shutter::value

    /**
     * Set zero shutter lag mode capture.
     * See [android.hardware.camera2.CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG]
     */
    var zsl: Boolean
        get() = config.zsl.value
        set(value) {
            if (!requireInUiThread()) return
            config.zsl.value = value
        }

    private fun createPreview(context: Context): PreviewImpl = TextureViewPreview(context, this)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isInEditMode) ViewCompat.getDisplay(this)?.let { orientationDetector.enable(it) }
    }

    override fun onDetachedFromWindow() {
        if (!isInEditMode) orientationDetector.disable()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (isInEditMode) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        // Handle android:adjustViewBounds
        if (adjustViewBounds) {

            if (!isCameraOpened) {
                listener.reserveRequestLayoutOnOpen()
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }

            val widthMode: Int = View.MeasureSpec.getMode(widthMeasureSpec)
            val heightMode: Int = View.MeasureSpec.getMode(heightMeasureSpec)

            if (widthMode == View.MeasureSpec.EXACTLY && heightMode != View.MeasureSpec.EXACTLY) {
                val ratio: AspectRatio = config.aspectRatio
                var height: Int = (View.MeasureSpec.getSize(widthMeasureSpec) * ratio.toFloat()).toInt()
                if (heightMode == View.MeasureSpec.AT_MOST) {
                    height = Math.min(height, View.MeasureSpec.getSize(heightMeasureSpec))
                }
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
            } else if (widthMode != View.MeasureSpec.EXACTLY && heightMode == View.MeasureSpec.EXACTLY) {
                val ratio: AspectRatio = config.aspectRatio
                var width: Int = (View.MeasureSpec.getSize(heightMeasureSpec) * ratio.toFloat()).toInt()
                if (widthMode == View.MeasureSpec.AT_MOST) {
                    width = Math.min(width, View.MeasureSpec.getSize(widthMeasureSpec))
                }
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    heightMeasureSpec)
            } else {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        // Measure the TextureView
        val width: Int = measuredWidth
        val height: Int = measuredHeight
        var ratio: AspectRatio = config.aspectRatio

        if (orientationDetector.lastKnownDisplayOrientation % 180 == 0) ratio = ratio.inverse()

        if (height < width * ratio.y / ratio.x) preview.view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(
                width * ratio.y / ratio.x,
                View.MeasureSpec.EXACTLY
            )
        ) else preview.view.measure(
            View.MeasureSpec.makeMeasureSpec(
                height * ratio.x / ratio.y,
                View.MeasureSpec.EXACTLY
            ),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        )

        preview.shutterView.layoutParams = preview.view.layoutParams
    }

    override fun onSaveInstanceState(): Parcelable? =
        SavedState(
            super.onSaveInstanceState() ?: Bundle(),
            adjustViewBounds,
            config.cameraMode.value,
            outputFormat,
            jpegQuality,
            facing,
            config.aspectRatio,
            autoFocus,
            touchToFocus,
            pinchToZoom,
            currentDigitalZoom,
            awb,
            flash,
            opticalStabilization,
            noiseReduction,
            config.shutter.value,
            zsl
        )

    override fun onRestoreInstanceState(state: Parcelable?): Unit = when (state) {
        is SavedState -> {
            super.onRestoreInstanceState(state.superState)
            adjustViewBounds = state.adjustViewBounds
            config.apply {
                facing.value = state.facing
                cameraMode.value = state.cameraMode
                outputFormat.value = state.outputFormat
                jpegQuality.value = state.jpegQuality
                aspectRatio = state.ratio
                autoFocus.value = state.autoFocus
                touchToFocus.value = state.touchToFocus
                pinchToZoom.value = state.pinchToZoom
                currentDigitalZoom.value = state.currentDigitalZoom
                awb.value = state.awb
                flash.value = state.flash
                opticalStabilization.value = state.opticalStabilization
                noiseReduction.value = state.noiseReduction
                shutter.value = state.shutter
                zsl.value = state.zsl
            }
            Unit
        }
        else -> super.onRestoreInstanceState(state)
    }

    private fun requireActive(): Boolean = isActive.also {
        if (!it) listener.onCameraError(
            CameraViewException("CameraView instance is destroyed and cannot be used further. Please create a new instance."),
            isCritical = true
        )
    }

    private fun requireCameraOpened(): Boolean = isCameraOpened.also {
        if (!it) listener.onCameraError(
            CameraViewException("Camera is not open. Call start() first."),
            errorLevel = ErrorLevel.Warning
        )
    }

    /**
     * TODO: Add docs
     */
    fun config(configBlock: CameraConfiguration.() -> Unit = {}): CameraConfiguration = config.apply(configBlock)

    /**
     * Open a camera device and start showing camera preview. This is typically called from
     * [Activity.onResume].
     * @throws [CameraViewException] if [destroy] is already called and this [CameraView] instance is no longer active.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun start(): Unit = runBlocking(coroutineContext) {

        if (!requireActive()) return@runBlocking

        if (isCameraOpened) {
            listener.onCameraError(
                CameraViewException("Camera is already open. Call stop() first."),
                errorLevel = ErrorLevel.Warning
            )
            return@runBlocking
        }

        // Save original state and restore later if camera falls back to using Camera1
        val state: Parcelable? = onSaveInstanceState()

        if (camera.start()) return@runBlocking // Camera started successfully, return.

        // This camera instance is no longer useful, destroy it.
        camera.destroy()

        // Already tried using Camera1 api, return.
        // Errors leading to this situation are already posted from Camera1 api
        if (camera is Camera1) return@runBlocking

        // Device uses legacy hardware layer; fall back to Camera1
        camera = Camera1(listener, preview, config, SupervisorJob(parentJob))

        // Restore original state
        onRestoreInstanceState(state)

        // Try to start camera again using Camera1 api
        // Return if successful
        if (camera.start()) return@runBlocking

        // Unable to start camera using any api. Post a critical error.
        listener.onCameraError(
            CameraViewException("Unable to use camera or camera2 api." +
                " Please check if the camera hardware is usable and CameraView is correctly configured."),
            isCritical = true
        )
    }

    /** Take a picture. The result will be returned to listeners added by [addPictureTakenListener]. */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun capture(): Unit = when {

        !requireActive() || !requireCameraOpened() -> Unit

        !config.isSingleCaptureModeEnabled -> listener.onCameraError(
            CameraViewException("Single capture mode is disabled." +
                " Update camera mode by" +
                " `CameraView.cameraMode = Modes.CameraMode.SINGLE_CAPTURE`" +
                " to enable and capture images."),
            isCritical = true
        )

        else -> runBlocking(coroutineContext) { camera.takePicture() }
    }

    /**
     * Start capturing video.
     * @param outputFile where video will be saved
     * @param videoConfig lambda on [VideoConfiguration] (optional) (if not provided, it uses default configuration)
     */
    @RequiresPermission(allOf = [
        Manifest.permission.CAMERA,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.RECORD_AUDIO
    ])
    @JvmOverloads
    fun startVideoRecording(outputFile: File, videoConfig: VideoConfiguration.() -> Unit = {}): Unit = when {

        !requireActive() || !requireCameraOpened() -> Unit

        !config.isVideoCaptureModeEnabled -> listener.onCameraError(
            CameraViewException("Video capture mode is disabled." +
                " Update camera mode by" +
                " `CameraView.cameraMode = Modes.CameraMode.VIDEO_CAPTURE`" +
                " to enable and capture videos."),
            isCritical = true
        )

        isVideoRecording -> listener.onCameraError(
            CameraViewException("Video recording already in progress." +
                " Call CameraView.stopVideoRecording() before calling start."),
            ErrorLevel.Warning
        )

        else -> runBlocking(coroutineContext) {
            camera.startVideoRecording(outputFile, VideoConfiguration().apply(videoConfig))
        }
    }

    /**
     * Pause video recording
     * @return true if the video was paused false otherwise
     * Note: Always returns false on API < 24
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun pauseVideoRecording(): Boolean = camera.pauseVideoRecording()

    /**
     * Resume video recording
     * @return true if the video was resumed false otherwise
     * Note: Always returns false on API < 24
     */
    @RequiresApi(Build.VERSION_CODES.N)
    fun resumeVideoRecording(): Boolean = camera.resumeVideoRecording()

    /**
     * Stop video recording
     * @return true if video was stopped and saved to given outputFile, false otherwise
     */
    fun stopVideoRecording(): Boolean = runBlocking(coroutineContext) { camera.stopVideoRecording() }

    /**
     * Stop camera preview and close the device.
     * This is typically called from fragment's onPause callback.
     */
    fun stop(): Unit = runBlocking(coroutineContext) { camera.stop() }

    /**
     * Clear all listeners, [stop] camera, and kill background threads.
     * Once [destroy] is called, camera cannot be started.
     * A new [CameraView] instance must be created to use camera again.
     * This is typically called from fragment's onDestroyView callback.
     */
    fun destroy() {
        runBlocking(coroutineContext) {
            removeAllListeners()
            camera.destroy()
        }
        parentJob.cancel()
    }

    /**
     * Add a new camera opened [listener].
     * @param listener lambda
     * @return instance of [CameraView] it is called on
     */
    fun addCameraOpenedListener(listener: () -> Unit): CameraView {
        if (this.listener.isEnabled) cameraOpenedListeners.add(listener)
        return this
    }

    /**
     * Remove camera opened [listener].
     * @param listener that was previously added.
     * @return instance of [CameraView] it is called on
     */
    fun removeCameraOpenedListener(listener: () -> Unit): CameraView {
        cameraOpenedListeners.remove(listener)
        return this
    }

    /**
     * Set legacy (camera1) preview frame [listener].
     *
     * @param listener lambda with image of type [LegacyImage] as its argument
     * which contains the preview frame from camera1 and its metadata.
     * The image data format stated by [LegacyImage.format] is [android.graphics.ImageFormat]
     * @return instance of [CameraView] it is called on
     */
    fun setLegacyPreviewFrameListener(listener: (image: LegacyImage) -> Unit): CameraView {
        if (this.listener.isEnabled) legacyPreviewFrameListener = listener
        return this
    }

    /**
     * Remove legacy (camera1) preview frame [listener].
     * @return instance of [CameraView] it is called on
     */
    fun removeLegacyPreviewFrameListener(): CameraView {
        legacyPreviewFrameListener = null
        return this
    }

    /**
     * Set preview frame [listener]. Be careful while using this listener as it is invoked on each frame,
     * which could be 60 times per second if frame rate is 60 fps.
     * Ideally, next frame should only be processed once current frame is done processing.
     * Continuously launching background tasks for each frame is is not memory efficient,
     * the device will run out of memory very quickly and force close the app.
     *
     * @param listener lambda with image of type [Image] as its argument which is the preview frame.
     * It is always of type [android.graphics.ImageFormat.YUV_420_888]
     * @return instance of [CameraView] it is called on
     * @sample setupCameraSample
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun setPreviewFrameListener(listener: (image: Image) -> Unit): CameraView {
        if (this.listener.isEnabled) previewFrameListener = listener
        return this
    }

    /**
     * Remove preview frame [listener].
     * @return instance of [CameraView] it is called on
     */
    fun removePreviewFrameListener(): CameraView {
        previewFrameListener = null
        return this
    }

    /**
     * Add a new picture taken [listener].
     * @param listener lambda with imageData of type [ByteArray] as argument
     * which is image data of the captured image, of format set with [CameraView.outputFormat]
     * @return instance of [CameraView] it is called on
     */
    fun addPictureTakenListener(listener: (imageData: ByteArray) -> Unit): CameraView {
        if (this.listener.isEnabled) pictureTakenListeners.add(listener)
        return this
    }

    /**
     * Remove picture taken [listener].
     * @return instance of [CameraView] it is called on
     */
    fun removePictureTakenListener(listener: (ByteArray) -> Unit): CameraView {
        pictureTakenListeners.remove(listener)
        return this
    }

    /**
     * Add a new camera error [listener].
     * If no error listeners are added, then "critical" errors will be thrown to system exception handler (ie. hard crash)
     * The only critical error thrown for now is for invalid aspect ratio.
     *
     * @param listener lambda with t of type [Throwable] and errorLevel of type [ErrorLevel] as arguments
     * @return instance of [CameraView] it is called on
     */
    fun addCameraErrorListener(listener: (t: Throwable, errorLevel: ErrorLevel) -> Unit): CameraView {
        cameraErrorListeners.add(listener)
        return this
    }

    /**
     * Remove camera error [listener].
     * @param listener that was previously added.
     * @return instance of [CameraView] it is called on
     */
    fun removeCameraErrorListener(listener: (Throwable, ErrorLevel) -> Unit): CameraView {
        cameraErrorListeners.remove(listener)
        return this
    }

    /**
     * Add a new camera closed [listener].
     * @param listener lambda
     * @return instance of [CameraView] it is called on
     */
    fun addCameraClosedListener(listener: () -> Unit): CameraView {
        if (this.listener.isEnabled) cameraClosedListeners.add(listener)
        return this
    }

    /**
     * Remove camera closed [listener].
     * @param listener that was previously added.
     * @return instance of [CameraView] it is called on
     */
    fun removeCameraClosedListener(listener: () -> Unit): CameraView {
        cameraClosedListeners.remove(listener)
        return this
    }

    /**
     * Add a new video record started [listener].
     * @param listener lambda
     * @return instance of [CameraView] it was called on
     */
    fun addVideoRecordStartedListener(listener: () -> Unit): CameraView {
        videoRecordStartedListeners.add(listener)
        return this
    }

    /**
     * Remove video record started [listener].
     * @param listener that was previously added.
     * @return instance of [CameraView] it is called on
     */
    fun removeVideoRecordStartedListener(listener: () -> Unit): CameraView {
        videoRecordStartedListeners.remove(listener)
        return this
    }

    /**
     * Add a new video record stopped [listener].
     * @param listener lambda
     * @return instance of [CameraView] it was called on
     */
    fun addVideoRecordStoppedListener(listener: (isSuccess: Boolean) -> Unit): CameraView {
        videoRecordStoppedListeners.add(listener)
        return this
    }

    /**
     * Remove video record stopped [listener].
     * @param listener that was previously added.
     * @return instance of [CameraView] it is called on
     */
    fun removeVideoRecordStoppedListener(listener: (isSuccess: Boolean) -> Unit): CameraView {
        videoRecordStoppedListeners.remove(listener)
        return this
    }

    /** Remove all listeners previously set. */
    fun removeAllListeners() {
        listener.clear()
    }

    private inline fun launchOnUi(crossinline block: suspend () -> Unit): Job =
        coroutineScope.launch(Dispatchers.Main) { block() }

    @Parcelize
    internal data class SavedState(
        val parcelable: Parcelable,
        val adjustViewBounds: Boolean,
        val cameraMode: Int,
        val outputFormat: Int,
        val jpegQuality: Int,
        val facing: Int,
        val ratio: AspectRatio,
        val autoFocus: Int,
        val touchToFocus: Boolean,
        val pinchToZoom: Boolean,
        val currentDigitalZoom: Float,
        val awb: Int,
        val flash: Int,
        val opticalStabilization: Boolean,
        val noiseReduction: Int,
        val shutter: Int,
        val zsl: Boolean
    ) : View.BaseSavedState(parcelable), Parcelable
}

@JvmSynthetic
internal fun requireInUiThread(): Boolean = Thread.currentThread().isUiThread
    .also { if (!it) throw CameraViewException("This task needs to be executed in UI thread.") }