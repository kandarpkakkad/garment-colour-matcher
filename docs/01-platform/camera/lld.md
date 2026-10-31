# Camera — LLD

Code: `app/.../ui/capture/{CaptureScreen,CameraBinding}.kt`.

## 1. Binding

CameraX 1.5, `ProcessCameraProvider` (`awaitInstance`), the back camera, two use cases:

- `Preview`, shown by a `PreviewView` (`FIT_CENTER`) inside an `AndroidView`.
- `ImageCapture`, `CAPTURE_MODE_MAXIMIZE_QUALITY`, flash off or on, and the output format:
  `OUTPUT_FORMAT_JPEG`, or `OUTPUT_FORMAT_RAW` when the RAW switch is on (D23). For RAW,
  Camera2 interop sets `STATISTICS_LENS_SHADING_MAP_MODE_ON` on the capture request, so
  `DngCreator` writes the lens-shading gain map into the DNG.

`LifecycleCameraController` is not used: it cannot select an output format. Changing the RAW
switch rebinds (`unbindAll`, then `bindToLifecycle` with the new `ImageCapture`). Binding
happens only once the permission is granted, against the screen's lifecycle owner, so leaving
the screen closes the camera.

**RAW support** is read after the first bind:
`ImageCapture.getImageCaptureCapabilities(camera.cameraInfo).supportedOutputFormats` contains
`OUTPUT_FORMAT_RAW`. Unsupported: the switch is disabled and says why. The switch's state is
remembered (`SharedPreferences`, key `raw`); it starts off.

## 2. Shutter

`takePicture(OutputFileOptions(file), mainExecutor, callback)` with
`file = cache/pending/<uuid>.jpg`, or `.dng` for RAW (CameraX writes the DNG through
`DngCreator`, orientation in its `Orientation` tag). On success the file goes to
`Pipeline.process(Input.Camera(file))` or `Input.CameraRaw(file)`. The shutter is disabled
while a capture is processing.

## 3. Picker

`ActivityResultContracts.PickVisualMedia` with `ImageOnly`. The URI is handed to
`Pipeline.process(Input.Import(uri))`, which copies the bytes into the capture's directory as
`original.jpg` — whatever the source format, the name stays `original.jpg`; the decoder reads
by content, not extension. A DNG picked here goes through Android's own decoder on the JPEG
path, not the RAW path.

## 4. Permission

`Manifest.permission.CAMERA`, checked with `checkSelfPermission` when the screen resumes and
asked for with `ActivityResultContracts.RequestPermission`. After a second refusal Android
stops showing the dialog; the button then opens the app's settings page.

## 5. Manifest

```xml
<uses-feature android:name="android.hardware.camera.any" android:required="false" />
<uses-permission android:name="android.permission.CAMERA" />
```

`required="false"` so a camera-less device can still import.
