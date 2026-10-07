package app.vowed.proof.pose

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
 * The detector is ML Kit's bundled pose model (no download, no network). Landmarks become [PoseFrame]s in the coordinates of the
 * on-screen preview, so "the hand on the right side of the screen" means the same thing to the person and to the code.
 */
class PoseCamera(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val detector = PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
    private var provider: ProcessCameraProvider? = null

    @Volatile private var closed = false

    /** Starts the preview and analysis. [onFrame] is called on a background thread with each frame in which a body was found. */
    fun start(owner: LifecycleOwner, previewView: PreviewView, front: Boolean, onFrame: (PoseFrame) -> Unit, onNoBody: () -> Unit, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image -> analyse(image, front, onFrame, onNoBody) }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, analysis)
            } catch (e: Exception) {
                onError(e.message ?: "The camera could not be started")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyse(image: ImageProxy, front: Boolean, onFrame: (PoseFrame) -> Unit, onNoBody: () -> Unit) {
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
                val frame = toFrame(pose, w, h, front, ts)
                if (frame != null) onFrame(frame) else onNoBody()
            }
            .addOnCompleteListener(executor) { image.close() }
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
        return PoseFrame(ts, map)
    }

    fun stop() {
        closed = true
        runCatching { provider?.unbindAll() }
        runCatching { detector.close() }
        executor.shutdown()
    }
}
