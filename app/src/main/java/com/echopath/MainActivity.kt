package com.echopath

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.echopath.engine.AcousticRadarEngine
import com.echopath.engine.RadarTelemetry
import com.echopath.engine.RadarUpdateListener

class MainActivity : AppCompatActivity() {

    private lateinit var radarEngine: AcousticRadarEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // The UI team will build the visual layout here. 
        // For now, we just instantiate the headless engine.
        
        radarEngine = AcousticRadarEngine(this)
        
        radarEngine.setUpdateListener(object : RadarUpdateListener {
            override fun onRadarUpdate(telemetry: RadarTelemetry) {
                // UI updates will go here, currently headless.
                // Telemetry contains delta, tier, carrierPower, etc.
            }
        })

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        } else {
            radarEngine.start()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            radarEngine.start()
        } else {
            Toast.makeText(this, "Microphone permission required for Acoustic Radar", Toast.LENGTH_LONG).show()
            // Can fallback to simulation if desired
            // radarEngine.start(forceSimulation = true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        radarEngine.stop()
    }
}
