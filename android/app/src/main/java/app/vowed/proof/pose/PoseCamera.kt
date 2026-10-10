package app.vowed.proof.pose

import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors

/**
 * Connects the camera to the pose model. Frames are analysed in memory and dropped: nothing is stored, nothing is sent.
 * The detector is ML Kit's bundled pose model (no download, no network). Landmarks become [PoseFrame]s in the coordinates of the upright
 * picture the person sees, mirrored for the front camera, so the overlay and "the hand on the right side of the screen" mean the same thing to
 * the person and to the code.
 *
 * Preview and analysis both use a 16:9 picture (close to a phone screen, so little of it is cropped by the full-screen preview). If the phone is
 * slow, only the ANALYSIS picture gets smaller (never below the 640x360 that stays above ML Kit's documented 480x360 minimum); the preview keeps
 * its full frame rate because it is a separate use case.
 */
class PoseCamera(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val detector = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var owner: LifecycleOwner? = null
    private var selector: CameraSelector? = null
    private var handler: ((ImageProxy) -> Unit)? = null

    @Volatile private var closed = false
    @Volatile private var lowRes = false
    private var slowFrames = 0
    private var framesSeen = 0

    private fun analysisUseCase(low: Boolean): ImageAnalysis {
        val size = if (low) Size(640, 360) else Size(960, 540)
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build()
        return ImageAnalysis.Builder().setResolutionSelector(selector).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    }

    /** Starts the preview and analysis. [onFrame] is called on a background thread with each frame in which a body was found. */
    fun start(owner: LifecycleOwner, previewView: PreviewView, front: Boolean, onFrame: (PoseFrame) -> Unit, onNoBody: (Long) -> Unit, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                this.owner = owner
                val preview = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY).build()).build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val use = analysisUseCase(low = false)
                handler = { image -> analyse(image, front, onFrame, onNoBody) }
                use.setAnalyzer(executor) { image -> handler?.invoke(image) }
                analysis = use
                val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                selector = sel
                p.unbindAll()
                p.bindToLifecycle(owner, sel, preview, use)
            } catch (e: Exception) {
                onError(e.message ?: "The camera could not be started")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** One step down in analysis size when the model cannot keep up; the preview is not touched. */
    private fun lowerAnalysisResolution() {
        if (lowRes || closed) return
        lowRes = true
        ContextCompat.getMainExecutor(context).execute {
            val p = provider ?: return@execute
            val o = owner ?: return@execute
            val s = selector ?: return@execute
            runCatching {
                analysis?.let { p.unbind(it) }
                val use = analysisUseCase(low = true)
                use.setAnalyzer(executor) { image -> handler?.invoke(image) }
                analysis = use
                p.bindToLifecycle(o, s, use)
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyse(image: ImageProxy, front: Boolean, onFrame: (PoseFrame) -> Unit, onNoBody: (Long) -> Unit) {
        val media = image.image
        if (media == null || closed) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(media, rotation)
        // the analysed image is upright after rotation, so its width/height are swapped for 90/270
        val w = (if (rotation % 180 == 0) image.width else image.height).toFloat()
        val h = (if (rotation % 180 == 0) image.height else image.width).toFloat()
        val ts = System.currentTimeMillis()
        detector.process(input)
            .addOnSuccessListener(executor) { pose ->
                trackSpeed(System.currentTimeMillis() - ts)
                val frame = toFrame(pose, w, h, front, ts)
                if (frame != null) onFrame(frame) else onNoBody(ts)
            }
            .addOnCompleteListener(executor) { image.close() }
    }

    /** If most of the first frames take more than ~130 ms, switch the analysis picture to the smaller size. */
    private fun trackSpeed(ms: Long) {
        framesSeen++
        if (ms > 130) slowFrames++
        if (framesSeen == 20 && slowFrames >= 14) lowerAnalysisResolution()
    }

    private fun toFrame(pose: Pose, w: Float, h: Float, mirror: Boolean, ts: Long): PoseFrame? {
        if (pose.allPoseLandmarks.isEmpty()) return null
        fun j(type: Int): Joint? = pose.getPoseLandmark(type)?.let { lm ->
            val x = lm.position.x / w
            Joint(if (mirror) 1f - x else x, lm.position.y / h, lm.inFrameLikelihood)
        }
        val map = HashMap<JointId, Joint>()
        fun put(id: JointId, type: Int) = j(type)?.let { map[id] = it }
        put(JointId.L_SHOULDER, PoseLandmark.LEFT_SHOULDER); put(JointId.R_SHOULDER, PoseLandmark.RIGHT_SHOULDER)
        put(JointId.L_ELBOW, PoseLandmark.LEFT_ELBOW); put(JointId.R_ELBOW, PoseLandmark.RIGHT_ELBOW)
        put(JointId.L_WRIST, PoseLandmark.LEFT_WRIST); put(JointId.R_WRIST, PoseLandmark.RIGHT_WRIST)
        put(JointId.L_HIP, PoseLandmark.LEFT_HIP); put(JointId.R_HIP, PoseLandmark.RIGHT_HIP)
        put(JointId.L_KNEE, PoseLandmark.LEFT_KNEE); put(JointId.R_KNEE, PoseLandmark.RIGHT_KNEE)
        put(JointId.L_ANKLE, PoseLandmark.LEFT_ANKLE); put(JointId.R_ANKLE, PoseLandmark.RIGHT_ANKLE)
        // a mirrored front-camera preview keeps the person's left on the screen's left, so the labels already follow the screen
        return PoseFrame(ts, map, aspect = w / h)
    }

    /** True once the analysis picture was made smaller because the phone could not keep up (for the diagnostics). */
    val loweredResolution: Boolean get() = lowRes

    fun stop() {
        closed = true
        runCatching { provider?.unbindAll() }
        runCatching { detector.close() }
        executor.shutdown()
    }

    @Suppress("unused")
    private val aspectRatio16by9 = AspectRatio.RATIO_16_9
}
