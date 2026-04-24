package com.pocketnode.app

import android.app.Application
import com.pocketnode.app.inference.LlamaInference
import com.pocketnode.app.inference.DocumentReader
import com.pocketnode.app.licensing.LicenseManager

// Shared inference state accessible by both ChatViewModel and the Edge API service.
data class InferenceSession(
    val contextPtr: Long,
    val modelName: String
)

class MainApplication : Application() {

    lateinit var inference: LlamaInference
        private set

    lateinit var licenseManager: LicenseManager
        private set

    // Updated by ChatViewModel after a successful model load.
    // Read by ApiServer to serve Edge API requests.
    @Volatile
    var activeSession: InferenceSession? = null

    override fun onCreate() {
        super.onCreate()
        inference = LlamaInference()
        licenseManager = LicenseManager(this)
        DocumentReader.init(this)
    }
}
