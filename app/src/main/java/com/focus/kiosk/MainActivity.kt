package com.focus.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var etHours: EditText
    private lateinit var btnSelectApps: Button
    private lateinit var btnStartLock: Button
    private lateinit var btnUnlock: Button
    private lateinit var btnOpenApp: Button

    private var selectedPackages = mutableSetOf<String>()
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)
        prefs = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)

        // ساخت رابط کاربری به‌صورت برنامه‌نویسی برای سادگی
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        tvStatus = TextView(this).apply { textSize = 18f; setPadding(0, 10, 0, 10) }
        tvTimer = TextView(this).apply { textSize = 24f; setPadding(0, 20, 0, 20) }
        
        etHours = EditText(this).apply {
            hint = "Lock duration (Hours)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        btnSelectApps = Button(this).apply { text = "Select Whitelisted Apps" }
        btnStartLock = Button(this).apply { text = "Start Focus Lock" }
        btnUnlock = Button(this).apply { text = "Unlock (Timer Finished)" }
        btnOpenApp = Button(this).apply { text = "Open Whitelisted App" }

        layout.addView(tvStatus)
        layout.addView(tvTimer)
        layout.addView(etHours)
        layout.addView(btnSelectApps)
        layout.addView(btnStartLock)
        layout.addView(btnOpenApp)
        layout.addView(btnUnlock)

        setContentView(layout)

        // بارگیری برنامه‌های انتخاب شده قبلی
        selectedPackages = prefs.getStringSet("whitelist", mutableSetOf()) ?: mutableSetOf()

        btnSelectApps.setOnClickListener { showAppSelectionDialog() }
        btnStartLock.setOnClickListener { startLock() }
        btnUnlock.setOnClickListener { stopLock() }
        btnOpenApp.setOnClickListener { showWhitelistedAppsLauncher() }

        checkDeviceOwnerStatus()
        updateLockState()
    }

    private fun checkDeviceOwnerStatus() {
        if (!dpm.isDeviceOwnerApp(packageName)) {
            tvStatus.text = "STATUS: NOT Device Owner!\nRun ADB command first."
            btnStartLock.isEnabled = false
        } else {
            tvStatus.text = "STATUS: Device Owner Active ✅"
            btnStartLock.isEnabled = true
        }
    }

    private fun showAppSelectionDialog() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(it.packageName) != null }
            
        val appNames = packages.map { it.loadLabel(pm).toString() }.toTypedArray()
        val checkedItems = packages.map { selectedPackages.contains(it.packageName) }.toByteArray().map { it != 0.toByte() }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Select Whitelisted Apps")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                val pkg = packages[which].packageName
                if (isChecked) selectedPackages.add(pkg) else selectedPackages.remove(pkg)
            }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet("whitelist", selectedPackages).apply()
                Toast.makeText(this, "${selectedPackages.size} apps allowed", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startLock() {
        val hoursStr = etHours.text.toString()
        if (hoursStr.isEmpty() || hoursStr.toInt() <= 0) {
            Toast.makeText(this, "Please enter valid hours!", Toast.LENGTH_SHORT).show()
            return
        }

        val hours = hoursStr.toLong()
        val endTime = System.currentTimeMillis() + (hours * 3600 * 1000)
        
        prefs.edit().putLong("end_time", endTime).putBoolean("is_locked", true).apply()

        // ست کردن لیست سفید در سیستم اندورید (برنامه خودم + برنامه‌های انتخابی)
        val allowedApps = selectedPackages.toMutableList().apply { add(packageName) }
        dpm.setLockTaskPackages(adminComponent, allowedApps.toTypedArray())

        startLockTask()
        updateLockState()
    }

    private fun updateLockState() {
        val isLocked = prefs.getBoolean("is_locked", false)
        val endTime = prefs.getLong("end_time", 0)
        val now = System.currentTimeMillis()

        if (isLocked && now < endTime) {
            btnStartLock.visibility = View.GONE
            btnSelectApps.visibility = View.GONE
            etHours.visibility = View.GONE
            btnUnlock.isEnabled = false
            btnOpenApp.visibility = View.VISIBLE

            startTimer(endTime - now)
        } else if (isLocked && now >= endTime) {
            tvTimer.text = "Time's up! You can unlock now."
            btnUnlock.isEnabled = true
            btnOpenApp.visibility = View.VISIBLE
        } else {
            btnStartLock.visibility = View.VISIBLE
            btnSelectApps.visibility = View.VISIBLE
            etHours.visibility = View.VISIBLE
            btnUnlock.visibility = View.GONE
            btnOpenApp.visibility = View.GONE
            tvTimer.text = ""
        }
    }

    private fun startTimer(millisLeft: Long) {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(millisLeft, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val hours = millisUntilFinished / (1000 * 3600)
                val minutes = (millisUntilFinished % (1000 * 3600)) / (1000 * 60)
                val seconds = (millisUntilFinished % (1000 * 60)) / 1000
                tvTimer.text = String.format("Remaining: %02d:%02d:%02d", hours, minutes, seconds)
            }

            override fun onFinish() {
                updateLockState()
            }
        }.start()
    }

    private fun showWhitelistedAppsLauncher() {
        val pm = packageManager
        val allowedApps = selectedPackages.mapNotNull { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                Pair(appInfo.loadLabel(pm).toString(), pkg)
            } catch (e: Exception) { null }
        }

        val names = allowedApps.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Open Allowed App")
            .setItems(names) { _, which ->
                val intent = pm.getLaunchIntentForPackage(allowedApps[which].second)
                if (intent != null) startActivity(intent)
            }
            .show()
    }

    private fun stopLock() {
        try {
            stopLockTask()
            prefs.edit().putBoolean("is_locked", false).apply()
            updateLockState()
            Toast.makeText(this, "Unlocked successfully!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error unlocking: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateLockState()
    }
}
