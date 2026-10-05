package com.livepicture.ar

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import com.google.ar.core.ArCoreApk
import com.livepicture.ar.cv.CvTrackActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var store: TargetStore
    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView

    /** تصویری که انتخاب شده و منتظر انتخاب ویدیو است. */
    private var pendingImage: Uri? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pendingImage = uri
            Toast.makeText(this, R.string.now_pick_video, Toast.LENGTH_LONG).show()
            pickVideo.launch("video/*")
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val image = pendingImage
        if (uri != null && image != null) askDetails(image, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = TargetStore(this)
        container = findViewById(R.id.targetsContainer)
        emptyView = findViewById(R.id.emptyView)
        pendingImage = savedInstanceState?.let { BundleCompat.getParcelable(it, KEY_PENDING, Uri::class.java) }

        findViewById<View>(R.id.btnAdd).setOnClickListener { pickImage.launch("image/*") }
        findViewById<View>(R.id.btnStart).setOnClickListener {
            if (store.list().isEmpty()) {
                Toast.makeText(this, R.string.no_targets, Toast.LENGTH_SHORT).show()
            } else {
                // اگر گوشی ARCore ندارد، مستقیم به حالت سازگار (OpenCV) برو
                val supported = try {
                    !ArCoreApk.getInstance().checkAvailability(this).isUnsupported
                } catch (e: Exception) {
                    false
                }
                val screen = if (supported) ArActivity::class.java else CvTrackActivity::class.java
                startActivity(Intent(this, screen))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingImage?.let { outState.putParcelable(KEY_PENDING, it) }
    }

    private fun askDetails(image: Uri, video: Uri) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val nameInput = EditText(this).apply {
            hint = getString(R.string.hint_name)
            setText(getString(R.string.app_name) + " " + (store.list().size + 1))
        }
        val widthInput = EditText(this).apply {
            hint = getString(R.string.hint_width)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput)
            addView(widthInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.details_title)
            .setView(form)
            .setNegativeButton(R.string.cancel) { _, _ -> pendingImage = null }
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { getString(R.string.app_name) }
                val widthCm = widthInput.text.toString().replace(',', '.').toFloatOrNull() ?: 0f
                save(image, video, name, widthCm / 100f)
            }
            .show()
    }

    private fun save(image: Uri, video: Uri, name: String, widthMeters: Float) {
        Toast.makeText(this, R.string.saving, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    store.create(image, video, name, widthMeters); null
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }
            pendingImage = null
            if (error == null) {
                Toast.makeText(this@MainActivity, R.string.saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.save_failed, error), Toast.LENGTH_LONG).show()
            }
            refresh()
        }
    }

    private fun refresh() {
        val targets = store.list()
        container.removeAllViews()
        emptyView.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (t in targets) {
            val row = inflater.inflate(R.layout.item_target, container, false)
            row.findViewById<TextView>(R.id.name).text = t.name
            row.findViewById<TextView>(R.id.info).text =
                if (t.widthMeters > 0f) getString(R.string.width_cm, formatCm(t.widthMeters))
                else getString(R.string.width_auto)
            val thumb = row.findViewById<ImageView>(R.id.thumb)
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) { loadThumb(t) }
                thumb.setImageBitmap(bmp)
            }
            row.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener {
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.delete_confirm, t.name))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.delete) { _, _ -> store.delete(t); refresh() }
                    .show()
            }
            container.addView(row)
        }
    }

    private fun formatCm(meters: Float): String {
        val cm = meters * 100f
        return if (cm % 1f == 0f) cm.toInt().toString() else String.format("%.1f", cm)
    }

    private fun loadThumb(t: ArTarget) = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(t.imageFile.absolutePath, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 200) sample *= 2
        BitmapFactory.decodeFile(t.imageFile.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val KEY_PENDING = "pending_image"
    }
}
