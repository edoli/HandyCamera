package kr.edoli.handycamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlinx.android.synthetic.main.activity_main.*
import java.io.*
import java.nio.ByteBuffer
import java.text.DateFormat
import java.util.*
import kotlin.collections.ArrayList


class MainActivity : AppCompatActivity() {

    private val TAG = "HandyCameraTag"

    private val cameraIndex = 0
    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSessions: CameraCaptureSession? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var imageDimension: Size? = null
    private var imageReader: ImageReader? = null
    private val file: File? = null
    private val REQUEST_CAMERA_PERMISSION = 200
    private val mFlashSupported: Boolean = false
    private var mBackgroundHandler: Handler? = null
    private var mBackgroundThread: HandlerThread? = null

    private var param_sensitivity = 100
    private var param_exposure_time = 0L
    private var param_focal_length = 0f
    private var param_focus_distance = 0f
    private var param_aperture = 0f
    private var param_flash = 0

    enum class ImageFormatEnum(val code: Int) {
        DNG(ImageFormat.RAW_SENSOR),
        RAW_SENSOR(ImageFormat.RAW_SENSOR),
        RAW_PRIVATE(ImageFormat.RAW_PRIVATE),
        RAW12(ImageFormat.RAW12),
        RAW10(ImageFormat.RAW10),
        JPEG(ImageFormat.JPEG),
        HEIC(ImageFormat.HEIC),
        Depth16(ImageFormat.DEPTH16),
        DepthJPEG(ImageFormat.DEPTH_JPEG),
    }

    enum class FlashModeEnum(val code: Int) {
        OFF(CameraMetadata.FLASH_MODE_OFF),
        SINGLE(CameraMetadata.FLASH_MODE_SINGLE),
        TORCH(CameraMetadata.FLASH_MODE_TORCH)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val decorView = window.decorView
        val uiOption =
            window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        decorView.systemUiVisibility = uiOption

        texture_view.surfaceTextureListener = textureListener
        setupCameraUI()
    }

    fun setupCameraUI() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.e(TAG, "is camera open")
        cameraId = manager.cameraIdList[cameraIndex]
        val characteristics = manager.getCameraCharacteristics(cameraId!!)
        cameraCharacteristics = characteristics

        // ImageFormat
        spinner_imageformat.adapter =
            ArrayAdapter<ImageFormatEnum>(this, R.layout.spinner_item, ImageFormatEnum.values())


        // flash
        spinner_flash.fromList(FlashModeEnum.values().toList()) { value ->
            param_flash = value.code
            updatePreview()
        }
//        spinner_imageformat.adapter =
//            ArrayAdapter<FlashModeEnum>(this, R.layout.spinner_item, FlashModeEnum.values())

        // Aperture
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)!!
        spinner_aperture.fromList(apertures.toList()) { value ->
            param_aperture = value
            updatePreview()
        }

        // Focal length
        val focalLengths =
            characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!
        spinner_focal_length.fromList(focalLengths.toList()) { value ->
            param_focal_length = value
            updatePreview()
        }

        // Sensitivity
        val sensitivityRange =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
        seekbar_sensitivity.bind(sensitivityRange.lower, sensitivityRange.upper) { value ->
            param_sensitivity = value
            textview_sensitivity.text = value.toString()
            updatePreview()
        }

        // Exposure time
        val exposureTimeRange =
            characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
        seekbar_exposure_time.bind(
            exposureTimeRange.lower,
            exposureTimeRange.lower * 20000,
            true
        ) { value ->
            param_exposure_time = value
            textview_exposure_time.text = (value / 1000 / 1000).toString()
            updatePreview()
        }

        // Focus distance
        seekbar_focus_distance.bind(0.0f, 10f, true) { value ->
            val v = 10f - value
            param_focus_distance = v
            textview_focus_distance.text = v.toString()
            updatePreview()
        }

        // Capture button
        btn_takepicture.setOnClickListener {
            val imageFormatEnum = spinner_imageformat.selectedItem as ImageFormatEnum
            val asDng = imageFormatEnum == ImageFormatEnum.DNG
            takePicture(imageFormatEnum.code, asDng)
        }
    }

    var textureListener: TextureView.SurfaceTextureListener =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.e(TAG, "onOpened")

            cameraDevice = camera
            createCameraPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice!!.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice!!.close()
            cameraDevice = null
        }
    }

    private fun startBackgroundThread() {
        mBackgroundThread = HandlerThread("Camera Background")
        mBackgroundThread!!.start()
        mBackgroundHandler = Handler(mBackgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundThread!!.quitSafely()
        try {
            mBackgroundThread!!.join()
            mBackgroundThread = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun takePicture(imageFormat: Int, asDng: Boolean = false) {
        if (cameraDevice == null) {
            Log.e(TAG, "cameraDevice is null")
            return
        }

        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(cameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val largestRaw = map.getOutputSizes(imageFormat).maxBy { size ->
                size.width * size.height
            }!!
            val reader =
                ImageReader.newInstance(largestRaw.width, largestRaw.height, imageFormat, 1)
            val outputSurfaces = ArrayList<Surface>(2)
            outputSurfaces.add(reader.surface)
            val captureBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
//            setupCaptureRequestParameters(captureBuilder)
            captureBuilder.addTarget(reader.surface)
            val dateString = DateFormat.getDateTimeInstance().format(Date())
            var name = if (asDng) {
                "${dateString}.dng"
            } else if (imageFormat == ImageFormat.JPEG) {
                "${dateString}.jpg"
            } else if (imageFormat == ImageFormat.HEIC) {
                "${dateString}.heic"
            } else {
                "${dateString}.raw"
            }
            val file = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DCIM
                ), name
            )
            var captureResult: CaptureResult? = null

            Log.d(TAG, file.toString())
            val readerListener = object : OnImageAvailableListener {
                override fun onImageAvailable(reader: ImageReader) {
                    Log.d(TAG, "image available")
                    var image: Image? = null
                    try {
                        image = reader.acquireLatestImage()
                        val size = Size(image.width, image.height)
                        val buffer = image!!.planes[0].buffer
                        if (asDng) {
                            saveDng(buffer, size)
                        } else {
                            val bytes = ByteArray(buffer.capacity())
                            buffer.get(bytes)
                            save(bytes)
                        }
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } finally {
                        image?.close()
                    }
                }

                @Throws(IOException::class)
                private fun save(bytes: ByteArray) {
                    var output: OutputStream? = null
                    try {
                        output = FileOutputStream(file)
                        output.write(bytes)
                    } finally {
                        output?.close()
                    }
                }

                private fun saveDng(buffer: ByteBuffer, size: Size) {
                    val cloneBuffer = ByteBuffer.allocate(buffer.capacity())
                    buffer.rewind()
                    cloneBuffer.put(buffer)
                    buffer.rewind()
                    cloneBuffer.flip()

                    Thread {
                        while (captureResult == null) {
                            Thread.sleep(100)
                        }

                        val dngCreator = DngCreator(characteristics, captureResult!!)

                        var dngStream: OutputStream? = null
                        try {
                            dngStream = FileOutputStream(file)
                            dngCreator.writeByteBuffer(dngStream, size, cloneBuffer, 0)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            dngStream?.close()
                        }
                    }.start()
                }
            }
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler)
            val captureListener = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    super.onCaptureCompleted(session, request, result)
                    captureResult = result
                    Toast.makeText(this@MainActivity, "Saved:$file", Toast.LENGTH_SHORT).show()
                    createCameraPreview()
                }
            }
            cameraDevice!!.createCaptureSession(
                outputSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            session.capture(
                                captureBuilder.build(),
                                captureListener,
                                mBackgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }

                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {

                    }
                },
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun createCameraPreview() {
        try {
            val texture = texture_view.surfaceTexture!!
            texture.setDefaultBufferSize(imageDimension!!.width, imageDimension!!.height)
            val surface = Surface(texture)
            captureRequestBuilder =
                cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            setupCaptureRequestParameters(captureRequestBuilder!!)
            captureRequestBuilder!!.addTarget(surface)
            cameraDevice!!.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        //The camera is already closed
                        if (null == cameraDevice) {
                            return
                        }
                        // When the session is ready, we start displaying the preview.
                        cameraCaptureSessions = cameraCaptureSession
                        updatePreview()

                        seekbar_sensitivity.progress = 100
                        seekbar_exposure_time.progress = 1000
                        seekbar_focus_distance.progress = 5000
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        Toast.makeText(
                            this@MainActivity,
                            "Configuration change",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun setupCaptureRequestParameters(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, param_sensitivity)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, param_exposure_time)
        builder.set(CaptureRequest.LENS_FOCAL_LENGTH, param_focal_length)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, param_focus_distance)
        builder.set(CaptureRequest.LENS_APERTURE, param_aperture)
        builder.set(CaptureRequest.FLASH_MODE, param_flash)
    }

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.e(TAG, "is camera open")
        try {
            cameraId = manager.cameraIdList[cameraIndex]
            val characteristics = manager.getCameraCharacteristics(cameraId!!)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            imageDimension = map.getOutputSizes(SurfaceTexture::class.java)[0]
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_CAMERA_PERMISSION
                )
                return
            }
            manager.openCamera(cameraId!!, stateCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

        Log.e(TAG, "openCamera X")
    }

    private fun updatePreview() {
        if (null == cameraDevice || captureRequestBuilder == null) {
            Log.e(TAG, "updatePreview error, return")
            return
        }
        try {
            setupCaptureRequestParameters(captureRequestBuilder!!)
            cameraCaptureSessions!!.setRepeatingRequest(
                captureRequestBuilder!!.build(),
                null,
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closeCamera() {
        if (null != cameraDevice) {
            cameraDevice!!.close()
            cameraDevice = null
        }
        if (null != imageReader) {
            imageReader!!.close()
            imageReader = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(
                    this,
                    "Sorry!!!, you can't use this app without granting permission",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume")
        startBackgroundThread()
        if (texture_view.isAvailable) {
            openCamera()
        } else {
            texture_view.surfaceTextureListener = textureListener
        }
    }

    override fun onPause() {
        Log.e(TAG, "onPause")
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }
}
