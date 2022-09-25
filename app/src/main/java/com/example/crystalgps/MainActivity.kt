package com.example.crystalgps

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import org.nield.kotlinstatistics.median
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt


var cLatitude : Double = 0.0
var cLongitude: Double = 0.0
var wLatitude : Double = 0.0
var wLongitude: Double = 0.0
var wSaved: Boolean = false

@Suppress("DEPRECATION")
class MainActivity : Activity(), SensorEventListener {
    private var mFusedLocationClient: FusedLocationProviderClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_main)
        val waypointButton = findViewById<Button>(R.id.button_waypoint)
        waypointButton.keepScreenOn = true

        waypointButton.setOnClickListener {
            wLatitude = cLatitude;
            wLongitude = cLongitude;
            wSaved = true
        }

        Log.d("XXX", "Setting up location services.")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d("XXX", "Insufficient permissions for location service.")
        } else {
            val mLocationRequest = LocationRequest()
            mLocationRequest.interval = 0
            mLocationRequest.fastestInterval = 0
            mLocationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            mFusedLocationClient?.requestLocationUpdates(mLocationRequest, mLocationCallback, Looper.myLooper())
        }
        mSensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = mSensorManager!!.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = mSensorManager!!.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    private var mSensorManager: SensorManager? = null
    var accelerometer: Sensor? = null
    var magnetometer: Sensor? = null

    override fun onResume() {
        super.onResume()
        mSensorManager!!.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI * 1115)
        mSensorManager!!.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI * 1115)
    }

    override fun onPause() {
        super.onPause()
        mSensorManager!!.unregisterListener(this)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private val mQueue: MutableList<Double> = ArrayList()
    private var mBearing: Double = 0.0
    private var mGravity = FloatArray(3)
    private var mGeomagnetic = FloatArray(3)

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type === Sensor.TYPE_ACCELEROMETER) {
            mGravity = event.values
        }
        if (event.sensor.type === Sensor.TYPE_MAGNETIC_FIELD) {
            mGeomagnetic = event.values
        }
        if (mGravity === null || mGeomagnetic === null) {
            return
        }
        val rotation = FloatArray(9)
        val inclination = FloatArray(9)
        val success = SensorManager.getRotationMatrix(rotation, inclination, mGravity, mGeomagnetic)
        if (success) {
            if (mQueue.size > 200) {
                mQueue.removeAt(0)
            }
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotation, orientation)
            var azimut = Math.toDegrees(orientation[0].toDouble())
            mQueue.add(mQueue.size, azimut)
            mBearing = (mQueue.median() + 360.0) % 360.0
        }
    }

    fun gpsToComponents(f:Double): String {
        var g = f - (f % 1.0)
        var m = (f % 1e+0 - f % 1e-2) * 1e+2
        var s = "%.1f".format((f % 1e-2 - f % 1e-5) * 1e+4)
        return "${g.roundToInt()}°" +
               "${m.roundToInt()}'" +
               "${s}\""
    }

    private fun vibratePhone(d:Double) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        vibrator.cancel()
        var pattern:LongArray
        if (d < 30.0) {
            pattern = longArrayOf(0, 50)
            if (d < 10.0) {
                pattern = longArrayOf(0, 50, 20, 50)
            }
            vibrator.vibrate(pattern, -1)
        }
    }

    private var mLocationCallback: LocationCallback = object : LocationCallback() {
        @SuppressLint("MissingPermission")
        override fun onLocationResult(locationResult: LocationResult) {
            val locationList: List<Location> = locationResult.locations
            if (locationList.isNotEmpty()) {
                val location: Location = locationList[locationList.size - 1]
                cLatitude = location.latitude
                cLongitude = location.longitude
                val textView: TextView = findViewById(R.id.main_text)
                var t = ""
                if (wSaved) {
                    val dy = wLatitude - location.latitude
                    val dx = wLongitude - location.longitude
                    val dd = (sqrt(dy * dy + dx * dx) * 111.0 * 1000.0).roundToInt()
                    val wBearing = ((1.0 - atan2(dy, dx) / PI) * 180.0 + 270.0) % 360.0
                    val right = (wBearing + 360.0 - mBearing) % 360.0
                    val left = (mBearing + 360.0 - wBearing) % 360.0
                    var turn = right
                    var pre = ""
                    var post = "→"
                    if (left < right) {
                        turn = left
                        pre = "←"
                        post = ""
                    }
                    t += "${gpsToComponents(wLatitude)}\n" +
                         "${gpsToComponents(wLongitude)}\n" +
                         "${pre}${turn.toInt()}°${post}\n" +
                         "${dd}m\n\n"
                    vibratePhone(turn)
                } else {
                    wLatitude = location.latitude
                    wLongitude = location.longitude
                }
                t += "${gpsToComponents(location.latitude)}\n" +
                     "${gpsToComponents(location.longitude)}\n" +
                     "${mBearing.toInt()}°\n" +
                     "±${location.accuracy.roundToInt()}m\n" +
                     "${(location.speed * 3.6).roundToInt()}km/h\n"
                textView.text = t
                //Log.d("XXX", t)
            }
        }
    }
}