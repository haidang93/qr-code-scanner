package net.touchcapture.qr.flutterqr

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Camera.CameraInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.platform.PlatformView
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.util.*
import kotlin.collections.ArrayList


class QRView(private val context: Context, messenger: BinaryMessenger, private val id: Int, private val params: HashMap<String, Any>) :
        PlatformView, MethodChannel.MethodCallHandler, PluginRegistry.RequestPermissionsResultListener {

    private var isTorchOn: Boolean = false
    private var isPaused: Boolean = false
    private var barcodeView: CustomFramingRectBarcodeView? = null
    private val channel: MethodChannel = MethodChannel(messenger, "net.touchcapture.qr.flutterqr/qrview_$id")
    private var permissionGranted: Boolean = false

    init {
        if (Shared.binding != null) {
            Shared.binding!!.addRequestPermissionsResultListener(this)
        }

        channel.setMethodCallHandler(this)
        Shared.activity?.application?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(p0: Activity) {
                if (p0 == Shared.activity && !isPaused && hasCameraPermission()) {
                    barcodeView?.pause()
                }
            }

            override fun onActivityResumed(p0: Activity) {
                if (p0 == Shared.activity && !isPaused && hasCameraPermission()) {
                    barcodeView?.resume()
                }
            }

            override fun onActivityStarted(p0: Activity) {
            }

            override fun onActivityDestroyed(p0: Activity) {
            }

            override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {
            }

            override fun onActivityStopped(p0: Activity) {
            }

            override fun onActivityCreated(p0: Activity, p1: Bundle?) {
            }
        })
    }

    override fun dispose() {
        barcodeView?.pause()
        barcodeView = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when(call.method) {
            "imageScan" -> imageScan(call, result)
            "startScan" -> startScan(call.arguments as? List<Int>, result)
            "stopScan" -> stopScan()
            "flipCamera" -> flipCamera(result)
            "toggleFlash" -> toggleFlash(result)
            "pauseCamera" -> pauseCamera(result)
            // Stopping camera is the same as pausing camera
            "stopCamera" -> pauseCamera(result)
            "resumeCamera" -> resumeCamera(result)
            "requestPermissions" -> checkAndRequestPermission(result)
            "getCameraInfo" -> getCameraInfo(result)
            "getFlashInfo" -> getFlashInfo(result)
            "getSystemFeatures" -> getSystemFeatures(result)
            "changeScanArea" -> changeScanArea(
                call.argument<Double>("scanAreaWidth")!!,
                call.argument<Double>("scanAreaHeight")!!,
                call.argument<Double>("cutOutBottomOffset")!!,
                result,
            )
            "invertScan" -> setInvertScan(call.argument<Boolean>("isInvertScan")!!, result)
            else -> result.notImplemented()
        }
    }

    private fun imageScan(call: MethodCall, result: MethodChannel.Result) {
        val filePath = call.argument<String>("file")
        val file = File(filePath)
        if (!file.exists()) {
            result.error("File not found. filePath: $filePath", null, null)
            return
        }

        val fis = FileInputStream(file)
        val bitmap = BitmapFactory.decodeStream(fis)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        val hints = Hashtable<DecodeHintType, Any>()
        val decodeFormats = ArrayList<BarcodeFormat>()
        decodeFormats.add(BarcodeFormat.QR_CODE)
        hints[DecodeHintType.POSSIBLE_FORMATS] = decodeFormats
        hints[DecodeHintType.CHARACTER_SET] = "utf-8"
        hints[DecodeHintType.TRY_HARDER] = true

        try {
            val decodeResult = MultiFormatReader().decode(binaryBitmap, hints)
            result.success(decodeResult.text)
        } catch (e: NotFoundException) {
            result.error("Not found data", null, null)
        }
    }

    private fun getCameraInfo(result: MethodChannel.Result) {
        if (barcodeView == null) {
            return barCodeViewNotSet(result)
        }
        result.success(barcodeView!!.cameraSettings.requestedCameraId)
    }

    private fun flipCamera(result: MethodChannel.Result) {
        if (barcodeView == null) {
            return barCodeViewNotSet(result)
        } else {
            barcodeView!!.pause()
            val settings = barcodeView!!.cameraSettings

            if(settings.requestedCameraId == CameraInfo.CAMERA_FACING_FRONT)
                settings.requestedCameraId = CameraInfo.CAMERA_FACING_BACK
            else
                settings.requestedCameraId = CameraInfo.CAMERA_FACING_FRONT

            barcodeView!!.cameraSettings = settings
            barcodeView!!.resume()
            result.success(settings.requestedCameraId)
        }
    }

    private fun getFlashInfo(result: MethodChannel.Result) {
        if (barcodeView == null) {
            return barCodeViewNotSet(result)
        }
        result.success(isTorchOn)
    }

    private fun toggleFlash(result: MethodChannel.Result) {
        if (barcodeView == null) {
            return barCodeViewNotSet(result)
        }

        if (hasFlash()) {
            barcodeView!!.setTorch(!isTorchOn)
            isTorchOn = !isTorchOn
            result.success(isTorchOn)
        } else {
            result.error("404", "This device doesn't support flash", null)
        }

    }

    private fun pauseCamera(result: MethodChannel.Result) {
        if (barcodeView == null) {
            return barCodeViewNotSet(result)
        } else {
            if (barcodeView!!.isPreviewActive) {
                isPaused = true
                barcodeView!!.pause()
            }
            result.success(true)
        }
    }

    private fun resumeCamera(result: MethodChannel.Result) {
        if (barcodeView == null) {
            return barCodeViewNotSet(result)
        } else {
            if (!barcodeView!!.isPreviewActive) {
                isPaused = false
                barcodeView!!.resume()
            }
            result.success(true)
        }
    }

    private fun hasFlash(): Boolean {
        return hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
    }

    private fun hasBackCamera(): Boolean {
        return hasSystemFeature(PackageManager.FEATURE_CAMERA)
    }

    private fun hasFrontCamera(): Boolean {
        return hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
    }

    private fun hasSystemFeature(feature: String): Boolean {
        return Shared.activity!!.packageManager
                .hasSystemFeature(feature)
    }

    private fun barCodeViewNotSet(result: MethodChannel.Result) {
        result.error("404", "No barcode view found", null)
    }

    override fun getView(): View {
        return initBarCodeView().apply {}!!
    }

    private fun initBarCodeView(): CustomFramingRectBarcodeView? {
        if (barcodeView == null) {
            barcodeView =
                CustomFramingRectBarcodeView(Shared.activity)
            if (params["cameraFacing"] as Int == 1) {
                barcodeView?.cameraSettings?.requestedCameraId = CameraInfo.CAMERA_FACING_FRONT
            }
        } else {
            if (!isPaused) barcodeView!!.resume()
        }
        return barcodeView
    }

    private fun startScan(arguments: List<Int>?, result: MethodChannel.Result) {
        val allowedBarcodeTypes = mutableListOf<BarcodeFormat>()
        try {
            checkAndRequestPermission(result)

            arguments?.forEach {
                allowedBarcodeTypes.add(BarcodeFormat.values()[it])
            }
        } catch (e: java.lang.Exception) {
            result.error(null, null, null)
        }

        barcodeView?.decodeContinuous(
                object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) {
                        if (allowedBarcodeTypes.size == 0 || allowedBarcodeTypes.contains(result.barcodeFormat)) {
                            val code = mapOf(
                                    "code" to result.text,
                                    "type" to result.barcodeFormat.name,
                                    "rawBytes" to result.rawBytes)
                            channel.invokeMethod("onRecognizeQR", code)
                        }

                    }

                    override fun possibleResultPoints(resultPoints: List<ResultPoint>) {}
                }
        )
    }

    private fun stopScan() {
        barcodeView?.stopDecoding()
    }

    private fun getSystemFeatures(result: MethodChannel.Result) {
        try {
            result.success(mapOf("hasFrontCamera" to hasFrontCamera(),
                    "hasBackCamera" to hasBackCamera(), "hasFlash" to hasFlash(),
                    "activeCamera" to barcodeView?.cameraSettings?.requestedCameraId))
        } catch (e: Exception) {
            result.error(null, null, null)
        }
    }

    private fun changeScanArea(
        dpScanAreaWidth: Double,
        dpScanAreaHeight: Double,
        cutOutBottomOffset: Double,
        result: MethodChannel.Result
    ) {
        setScanAreaSize(dpScanAreaWidth, dpScanAreaHeight, cutOutBottomOffset)
        result.success(true)
    }

    private fun setInvertScan(isInvert: Boolean, result: MethodChannel.Result) {
        barcodeView!!.pause()
        val settings = barcodeView!!.cameraSettings
        settings.isScanInverted = isInvert
        barcodeView!!.cameraSettings = settings
        barcodeView!!.resume();
    }

    private fun setScanAreaSize(
        dpScanAreaWidth: Double,
        dpScanAreaHeight: Double,
        dpCutOutBottomOffset: Double
    ) {
        barcodeView?.setFramingRect(
            convertDpToPixels(dpScanAreaWidth),
            convertDpToPixels(dpScanAreaHeight),
            convertDpToPixels(dpCutOutBottomOffset),
        )
    }

    private fun convertDpToPixels(dp: Double) =
        (dp * context.resources.displayMetrics.density).toInt()

    private fun hasCameraPermission(): Boolean {
        return permissionGranted ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Shared.activity?.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermission(result: MethodChannel.Result?) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (Shared.activity?.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    permissionGranted = true
                    channel.invokeMethod("onPermissionSet", true)
                } else {
                    Shared.activity?.requestPermissions(
                            arrayOf(Manifest.permission.CAMERA),
                            Shared.CAMERA_REQUEST_ID + this.id)
                }
            }
            else -> {
                // We should have permissions on older OS versions
                permissionGranted = true
                channel.invokeMethod("onPermissionSet", true)
            }
        }
    }

    override fun onRequestPermissionsResult( requestCode: Int,
                                             permissions: Array<out String>?,
                                             grantResults: IntArray): Boolean {
        if(requestCode == Shared.CAMERA_REQUEST_ID + this.id) {
            return if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permissionGranted = true
                channel.invokeMethod("onPermissionSet", true)
                true
            } else {
                permissionGranted = false
                channel.invokeMethod("onPermissionSet", false)
                false
            }
        }
        return false
    }

}

