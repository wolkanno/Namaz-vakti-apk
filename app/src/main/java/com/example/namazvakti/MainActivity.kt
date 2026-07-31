package com.example.namazvakti

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationServices
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var currentStep = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("NamazVaktiPrefs", Context.MODE_PRIVATE)

        // İzinleri İste
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            ), 101
        )

        val isConfigured = prefs.getBoolean("is_configured", false)
        if (!isConfigured) {
            showLocationStep()
        } else {
            showMainDashboard()
        }
    }

    // --- 1. AŞAMA: KONUM BUL EKRANI ---
    private fun showLocationStep() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 100, 50, 50)
        }

        val btnLocation = Button(this).apply {
            text = "📍 Konumu Bul ve Vakitleri Çek"
            setOnClickListener {
                getDeviceLocation { lat, lon ->
                    fetchPrayerTimes(lat, lon) { success ->
                        runOnUiThread {
                            if (success) {
                                Toast.makeText(this@MainActivity, "Konum ve vakitler alındı!", Toast.LENGTH_SHORT).show()
                                showSoundSettingsStep()
                            } else {
                                Toast.makeText(this@MainActivity, "Vakitler çekilemedi!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
        layout.addView(btnLocation)
        setContentView(layout)
    }

    // --- 2. AŞAMA: BİLDİRİM SESİ AYARLARI ---
    private fun showSoundSettingsStep() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val title = TextView(this).apply { text = "Bildirim Ses Seçimleri"; textSize = 20f }
        val btnVakitSes = Button(this).apply { text = "Vaktinde Bildirim Sesi Seç" }
        val btnOncesiSes = Button(this).apply { text = "Vakit Öncesi Bildirim Sesi Seç" }
        val btnNext = Button(this).apply {
            text = "İleri ➔"
            setOnClickListener {
                prefs.edit().putBoolean("is_configured", true).apply()
                showMainDashboard()
            }
        }

        layout.addView(title)
        layout.addView(btnVakitSes)
        layout.addView(btnOncesiSes)
        layout.addView(btnNext)
        setContentView(layout)
    }

    // --- 3. AŞAMA: ANA EKRAN (5 VAKİT TABLOSU VE AYARLAR) ---
    private fun showMainDashboard() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply { 
            text = "🕌 Bugünkü Namaz Vakitleri"
            textSize = 22f 
        }
        layout.addView(title)

        val vakitler = arrayOf("İmsak", "Öğle", "İkindi", "Akşam", "Yatsı")
        for (vakit in vakitler) {
            val time = prefs.getString("vakit_$vakit", "--:--")
            val tv = TextView(this).apply {
                text = "$vakit : $time"
                textSize = 18f
                setPadding(0, 15, 0, 15)
            }
            layout.addView(tv)
        }

        val btnSettings = Button(this).apply {
            text = "⚙️ Ayarlar (Vakit Öncesi / Ses / Sessiz Mod)"
            setOnClickListener { showDetailSettings() }
        }
        layout.addView(btnSettings)

        setContentView(layout)
        startForegroundServiceNotification()
    }

    // --- 4. AŞAMA: DETAYLI AYARLAR ---
    private fun showDetailSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply { text = "⚙️ Gelişmiş Ayarlar"; textSize = 20f }
        layout.addView(title)

        val chkOverrideSilent = CheckBox(this).apply {
            text = "Telefon Sessiz/Titreşimdeyken de Ses Çalsın"
            isChecked = prefs.getBoolean("override_silent", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("override_silent", isChecked).apply()
            }
        }
        layout.addView(chkOverrideSilent)

        val vakitler = arrayOf("İmsak", "Öğle", "İkindi", "Akşam", "Yatsı")
        for (vakit in vakitler) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val chk = CheckBox(this).apply {
                text = "$vakit Öncesi Uyarı"
                isChecked = prefs.getBoolean("pre_alarm_active_$vakit", true)
                setOnCheckedChangeListener { _, isChecked ->
                    prefs.edit().putBoolean("pre_alarm_active_$vakit", isChecked).apply()
                }
            }
            val edtMinutes = EditText(this).apply {
                hint = "dk"
                setText(prefs.getInt("pre_alarm_min_$vakit", 15).toString())
            }
            row.addView(chk)
            row.addView(edtMinutes)
            layout.addView(row)
        }

        val btnBack = Button(this).apply {
            text = "Kaydet ve Geri Dön"
            setOnClickListener { showMainDashboard() }
        }
        layout.addView(btnBack)

        setContentView(layout)
    }

    // --- YARDIMCI FONKSİYONLAR (GPS & API) ---
    private fun getDeviceLocation(onLocationFound: (Double, Double) -> Unit) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                if (loc != null) onLocationFound(loc.latitude, loc.longitude)
                else onLocationFound(41.0082, 28.9784) // Varsayılan İstanbul
            }
        } else {
            onLocationFound(41.0082, 28.9784)
        }
    }

    private fun fetchPrayerTimes(lat: Double, lon: Double, callback: (Boolean) -> Unit) {
        thread {
            try {
                val url = URL("https://api.aladhan.com/v1/timings?latitude=$lat&longitude=$lon&method=13")
                val conn = url.openConnection() as HttpURLConnection
                val text = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(text).getJSONObject("data").getJSONObject("timings")

                prefs.edit().apply {
                    putString("vakit_İmsak", json.getString("Fajr"))
                    putString("vakit_Öğle", json.getString("Dhuhr"))
                    putString("vakit_İkindi", json.getString("Asr"))
                    putString("vakit_Akşam", json.getString("Maghrib"))
                    putString("vakit_Yatsı", json.getString("Isha"))
                    apply()
                }
                callback(true)
            } catch (e: Exception) {
                callback(false)
            }
        }
    }

    // --- DURUM ÇUBUĞU / PANEL BİLDİRİMİ (HİLAL İKONLU) ---
    private fun startForegroundServiceNotification() {
        val intent = Intent(this, PrayerNotificationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
