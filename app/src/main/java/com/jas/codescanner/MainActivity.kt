package com.jas.codescanner

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var overlay: CropOverlayView
    private lateinit var statusView: TextView
    private lateinit var capturedView: TextView
    private lateinit var capturedScroll: ScrollView
    private lateinit var scanButton: Button

    private var controller: LifecycleCameraController? = null
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val stitcher = CodeStitcher()
    @Volatile private var scanning = false

    private val prefs by lazy { getSharedPreferences("scanner", MODE_PRIVATE) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else setStatus("Camera permission is needed to scan.", COLOR_WARN)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        statusView = findViewById(R.id.status)
        capturedView = findViewById(R.id.captured)
        capturedScroll = findViewById(R.id.capturedScroll)
        scanButton = findViewById(R.id.btnScan)

        stitcher.load(prefs.getString(KEY_TEXT, "") ?: "")
        overlay.setCropFractions(
            prefs.getFloat("cropL", 0.04f), prefs.getFloat("cropT", 0.08f),
            prefs.getFloat("cropR", 0.96f), prefs.getFloat("cropB", 0.56f),
        )
        overlay.onCropChanged = { l, t, r, b ->
            prefs.edit().putFloat("cropL", l).putFloat("cropT", t)
                .putFloat("cropR", r).putFloat("cropB", b).apply()
        }

        scanButton.setOnClickListener { setScanning(!scanning) }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { undo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.btnEdit).setOnClickListener { showEditor() }
        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyAll() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareAll() }

        setScanning(false)
        refreshCaptured()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onPause() {
        super.onPause()
        saveText()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller?.clearImageAnalysisAnalyzer()
        analysisExecutor.shutdown()
        recognizer.close()
    }

    // --- camera + OCR ------------------------------------------------------------

    private fun startCamera() {
        val aspect = AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val c = LifecycleCameraController(this)
        c.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        c.setPreviewResolutionSelector(
            ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build()
        )
        // Higher resolution = sharper small code text.
        c.setImageAnalysisResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
        )
        c.setImageAnalysisAnalyzer(
            analysisExecutor,
            MlKitAnalyzer(
                listOf(recognizer),
                ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, // boxes come back in screen coordinates
                analysisExecutor,
            ) { result -> onOcrResult(result.getValue(recognizer)) }
        )
        c.bindToLifecycle(this)
        previewView.controller = c
        controller = c
    }

    /** Runs on the analysis thread for every processed camera frame. */
    private fun onOcrResult(text: Text?) {
        if (text == null) return
        val crop = overlay.cropRectSnapshot()
        val tol = 6f * resources.displayMetrics.density
        val used = ArrayList<RectF>()
        val ignored = ArrayList<RectF>()
        val segments = ArrayList<OcrSegment>()

        for (block in text.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val r = RectF(box)
                val insideBox = r.centerX() >= crop.left && r.centerX() <= crop.right &&
                    r.top >= crop.top - tol && r.bottom <= crop.bottom + tol
                val upright = abs(line.angle) < 12f
                if (insideBox && upright && line.text.isNotBlank()) {
                    used.add(r)
                    segments.add(OcrSegment(line.text, r.left, r.top, r.right, r.bottom))
                } else {
                    ignored.add(r)
                }
            }
        }
        overlay.setBoxes(used, ignored)
        if (!scanning) return

        val result = stitcher.process(FrameBuilder.build(segments))
        runOnUiThread { onStitchResult(result) }
    }

    private fun onStitchResult(res: CodeStitcher.Result) {
        if (!scanning) return
        when (res.state) {
            CodeStitcher.State.NO_TEXT -> setStatus("No text in the box", COLOR_IDLE)
            CodeStitcher.State.READING -> setStatus("Reading… · ${res.total} lines", COLOR_IDLE)
            CodeStitcher.State.TRACKING -> setStatus("Scanning · ${res.total} lines — keep scrolling", COLOR_OK)
            CodeStitcher.State.ADDED -> setStatus("+${res.added} · ${res.total} lines", COLOR_OK)
            CodeStitcher.State.LOST -> setStatus("Lost track — scroll back to the last captured line", COLOR_WARN)
        }
        if (res.added > 0) refreshCaptured()
    }

    // --- UI actions ---------------------------------------------------------------

    private fun setScanning(on: Boolean) {
        scanning = on
        stitcher.resetPending()
        scanButton.text = if (on) "Pause" else "Start scanning"
        if (on) {
            setStatus("Reading…", COLOR_IDLE)
        } else {
            setStatus("Paused · ${stitcher.lineCount()} lines — fit the code in the box", COLOR_IDLE)
            saveText()
        }
    }

    private fun undo() {
        val n = stitcher.undo()
        toast(if (n > 0) "Removed last $n lines" else "Nothing to undo")
        refreshCaptured()
        saveText()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Clear captured code?")
            .setMessage("This removes all ${stitcher.lineCount()} lines.")
            .setPositiveButton("Clear") { _, _ ->
                stitcher.clear(); refreshCaptured(); saveText()
                if (!scanning) setScanning(false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditor() {
        val wasScanning = scanning
        if (wasScanning) setScanning(false)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val editor = EditText(this).apply {
            setText(stitcher.text())
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(true)
            minLines = 12
            maxLines = 22
            isVerticalScrollBarEnabled = true
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit captured code")
            .setView(editor)
            .setPositiveButton("Save") { _, _ ->
                stitcher.load(editor.text.toString()); refreshCaptured(); saveText()
                setScanning(false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyAll() {
        val text = stitcher.text()
        if (text.isEmpty()) { toast("Nothing captured yet"); return }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("code", text))
        toast("Copied ${stitcher.lineCount()} lines")
    }

    private fun shareAll() {
        val text = stitcher.text()
        if (text.isEmpty()) { toast("Nothing captured yet"); return }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Captured code")
        }
        startActivity(Intent.createChooser(send, "Share code"))
    }

    private fun refreshCaptured() {
        val text = stitcher.text()
        if (text.isEmpty()) {
            capturedView.text = "Captured code appears here.\n\n1. Drag the box so it covers only the code.\n2. Tap Start scanning.\n3. Scroll slowly on the computer."
            capturedView.setTextColor(Color.rgb(120, 126, 138))
        } else {
            capturedView.text = text
            capturedView.setTextColor(Color.rgb(215, 218, 224))
            capturedScroll.post { capturedScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setStatus(msg: String, color: Int) {
        statusView.text = msg
        statusView.setTextColor(color)
    }

    private fun saveText() {
        prefs.edit().putString(KEY_TEXT, stitcher.text()).apply()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_TEXT = "captured"
        private val COLOR_OK = Color.rgb(61, 220, 132)
        private val COLOR_IDLE = Color.WHITE
        private val COLOR_WARN = Color.rgb(255, 176, 32)
    }
}
