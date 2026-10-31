package com.garmentcolour.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import com.garmentcolour.app.data.AppDatabase
import com.garmentcolour.app.data.CaptureStore
import com.garmentcolour.app.data.ExportJson
import com.garmentcolour.app.models.Segmenters
import com.garmentcolour.app.pipeline.Pipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant

class GarmentColourApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Everything long-lived, built once. */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val db = AppDatabase.open(context)
    val store = CaptureStore(context, db)
    val segmenters = Segmenters(context).also { it.load(scope) }

    private val swept: Deferred<Unit> = scope.async(Dispatchers.IO) {
        try {
            store.sweep()
        } catch (e: Exception) {
            // A failed sweep leaves stale files, never wrong ones; captures still work.
            Log.w("AppGraph", "start-up sweep failed", e)
        }
        Unit
    }

    val pipeline = Pipeline(context, store, segmenters, swept)

    private val prefs = context.getSharedPreferences("garment_colour", Context.MODE_PRIVATE)

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    /** The RAW switch on the capture screen (camera LLD §1); off until RAW's gate is passed. */
    var rawPreferred: Boolean
        get() = prefs.getBoolean("raw", false)
        set(value) = prefs.edit().putBoolean("raw", value).apply()

    /** Storage LLD §3. Returns the number of captures written. */
    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val all = store.everything()
        val text = ExportJson.write(all, BuildConfig.VERSION_NAME, Instant.now())
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("could not open the file you chose")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        all.size
    }
}
