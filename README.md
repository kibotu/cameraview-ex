[![Build Status](https://travis-ci.org/pvasa/cameraview-ex.svg?branch=master)](https://travis-ci.org/pvasa/cameraview-ex)
[![Download](https://api.bintray.com/packages/ryan652/android/cameraview-ex/images/download.svg)](https://bintray.com/ryan652/android/cameraview-ex/_latestVersion)
[![License](https://img.shields.io/github/license/pvasa/cameraview-ex.svg)](LICENSE)

# CameraViewEx

_This is an extended version of [Google's cameraview library](https://github.com/google/cameraview) with better stability and many more features._

CameraViewEx highly simplifies integration of camera implementation and various camera features into any Android project. It uses new camera2 api with advanced features on API level 21 and higher and smartly switches to camera1 on older devices (API < 21).

Minimum API 14 is required to use CameraViewEx.
<br><br>

| API Level | Camera API | Preview View |
|:---------:|------------|--------------|
| 14-20     | Camera1    | TextureView  |
| 21+       | Camera2    | TextureView  |

#### Why another camera library?
Every camera library out there has some issues. Some good ones uses only camera1 api which cannot give best performance possible with today's devices, some are not updated anymore, some does not have all the features while some has a lot of features but uses complex api. CameraViewEx tries to solve all these issues while providing simpler api and more features.

> ***Note:** This is a beta version with more advanced features like multiple simultaneous camera modes support. If you need more stable version or find any issues with this version, please switch to [version v2.8.2](https://github.com/pvasa/cameraview-ex/tree/v2.8.2).*

## Features
- High quality image capture
- Multiple camera modes like single capture, continuous frame, and video capture
- Ability to enable all or multiple modes simultaneously
- Preview frame listener
- Support multiple formats for output images like jpeg, yuv_420_888, rgba_8888
- Pinch to zoom
- Touch to focus
- Configurable auto white balance, auto focus, flash, noise reduction, and optical / video stabilization
- Highly configurable video recording with most of the options from MediaRecorder
- Support multiple aspect ratios
- Switch between front and back camera
- Adjustable output image quality
- Zero shutter lag mode
- Shutter animation for single capture

## Usage

#### Import dependency
In app build.gradle,
```gradle
dependencies {
    // ...
    implementation "com.priyankvasa.android:cameraview-ex:3.0.1-beta"
}
```

#### In layout xml
```xml
<com.priyankvasa.android.cameraviewex.CameraView
    android:id="@+id/camera"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:adjustViewBounds="true"
    android:keepScreenOn="true"
    app:aspectRatio="4:3"
    app:autoFocus="continuous_picture"
    app:awb="auto"
    app:cameraMode="single_capture"
    app:facing="back"
    app:flash="auto"
    app:jpegQuality="high"
    app:noiseReduction="high_quality"
    app:opticalStabilization="true"
    app:outputFormat="jpeg"
    app:pinchToZoom="true"
    app:shutter="short_time"
    app:touchToFocus="true"
    app:zsl="true" />
```

#### Setup camera
```kotlin
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    // Callbacks on UI thread
    camera.addCameraOpenedListener { /* Camera opened. */ }
        .addCameraErrorListener { t: Throwable, errorLevel: ErrorLevel -> /* Camera error! */ }
        .addCameraClosedListener { /* Camera closed. */ }
}

override fun onResume() {
    super.onResume()
    camera.start()
}

override fun onPause() {
    camera.stop()
    super.onPause()
}

override fun onDestroyView() {
    camera.destroy()
    super.onDestroyView()
}
```

#### Capture still picture
```kotlin
camera.cameraMode = Modes.CameraMode.SINGLE_CAPTURE
// Output format is whatever set for [app:outputFormat] parameter
// Callback on UI thread
camera.addPictureTakenListener { imageData: ByteArray -> /* Picture taken. */ }
camera.capture()
```

#### Process preview frames
```kotlin
camera.cameraMode = Modes.CameraMode.CONTINUOUS_FRAME
// Output format is always ImageFormat.YUV_420_888
// Callback on background thread
camera.setPreviewFrameListener { image: Image -> /* Preview frame available. */ }
```

#### Record video
```kotlin
camera.cameraMode = Modes.CameraMode.VIDEO_CAPTURE

// Callback on UI thread
camera.addVideoRecordStartedListener { // Video recording started }

// Callback on UI thread
camera.addVideoRecordStoppedListener { isSuccess ->
    // Video recording stopped
    // isSuccess is true if video was recorded and saved successfully
}

camera.startVideoRecording(outputFile) {
    // Configure video (MediaRecorder) parameters
    audioEncoder = AudioEncoder.Aac
    videoFrameRate = 60
    videoStabilization = true
}
// When done recording
camera.stopVideoRecording()

// On APIs 24 and above video recording can be paused and resumed as well
camera.pauseVideoRecording()
camera.resumeVideoRecording()
```

#### Set multiple modes simultaneously
- **In xml**
```xml
<com.priyankvasa.android.cameraviewex.CameraView
    android:id="@+id/camera"
    ...
    app:cameraMode="single_capture|continuous_frame|video_capture"
    ... />
```
- **Or in code**
```kotlin
camera.cameraMode = Modes.CameraMode.SINGLE_CAPTURE or Modes.CameraMode.CONTINUOUS_FRAME or Modes.CameraMode.VIDEO_CAPTURE

// Setup all the listeners including preview frame listener

camera.startVideoRecording(outputFile)
camera.capture()

// The listeners will receive their respective outputs
```

You can see a complete usage in the [sampleApp](https://github.com/pvasa/cameraview-ex/tree/development/sampleApp) app module or [sampleAppJava](https://github.com/pvasa/cameraview-ex/tree/development/sampleAppJava) for usage in Java.

## Configuration

| CameraView property       | XML Attribute            | Possible Values <br/> (bold value is the default one)  |
|---------------------------|--------------------------|--------------------------------------------------------|
| cameraMode                | app:cameraMode           | **single_capture**, continuous_frame, video_capture    |
| facing                    | app:facing               | **back**, front                                        |
| aspectRatio               | app:aspectRatio          | **4:3**, 16:9, 3:2, 16:10, 17:10, 8:5 <br/> _(or any other ratio supported by device)_ |
| autoFocus                 | app:autoFocus            | **off**, auto, macro, continuous_video, <br/> continuous_picture, edof |
| flash                     | app:flash                | **off**, on, torch, auto, redEye                       |
| awb                       | app:awb                  | **off**, auto, incandescent, fluorescent, warm_fluorescent, <br/> daylight, cloudy_daylight, twilight, shade |
| opticalStabilization      | app:opticalStabilization | **false**, true                                        |
| noiseReduction            | app:noiseReduction       | **off**, fast, high_quality, minimal, zero_shutter_lag |
| shutter                   | app:shutter              | **off**, short_time, long_time                         |
| outputFormat              | app:outputFormat         | **jpeg**, yuv_420_888, rgba_8888                       |
| jpegQuality               | app:jpegQuality          | **default**, low, medium, high                         |
| zsl                       | app:zsl                  | **false**, true                                        |
| isActive <br> (get only)  | N/A                      | True if this `CameraView` instance is active and usable, false otherwise. It is set to false after `CameraView.destroy()` call. |
| isCameraOpened <br> (get only) | N/A                 | True if camera is opened, false otherwise.             |
| isSingleCaptureModeEnabled <br> (get only) | N/A     | True if single capture mode is enabled, false otherwise. |
| isContinuousFrameModeEnabled <br> (get only) | N/A   | True if continuous frame mode is enabled, false otherwise. |
| isVideoCaptureModeEnabled <br> (get only) | N/A      | True if video capture mode is enabled, false otherwise. |
| isVideoRecording <br> (get only) | N/A               | True if there is a video recording in progress, false otherwise. |
| supportedAspectRatios <br> (get only) | N/A          | Returns list of `AspectRatio` supported by selected camera. |
| maxDigitalZoom <br> (get only) | N/A                 | Returns a float value which is the maximum possible digital zoom value supported by selected camera. |
| currentDigitalZoom        | N/A                      | Set camera digital zoom value. Must be between 1.0 and `CameraView.maxDigitalZoom` inclusive. |

_**Note:** Devices that run **Camera1** implementation will only support **app:facing**, **app:aspectRatio**, **app:autoFocus** (`off` and `continuous_picture`), and **app:flash** attributes. All others will be ignored. Camera2 implementations (ie. API 21 and above) will support all features._

## Documentation
For detailed documentation, please refer these [docs](https://pvasa.github.io/cameraview-ex/camera-view-ex/com.priyankvasa.android.cameraviewex/-camera-view/index.html).

## Contribution Guidelines
See [CONTRIBUTING.md](https://github.com/pvasa/cameraview-ex/blob/master/CONTRIBUTING.md).
