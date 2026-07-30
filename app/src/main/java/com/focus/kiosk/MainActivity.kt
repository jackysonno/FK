package com.focus.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Session(
    val id: Long,
    val startHour: Int, val startMinute: Int,
    val endHour: Int, val endMinute: Int,
    val allowedApps: List<String>
)

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val i = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(i)
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var prefs: SharedPreferences

    private lateinit var tvStatus: TextView
    private lateinit var tvTimer: TextView
    private lateinit var tvScheduleInfo: TextView
    private lateinit var etHours: EditText
    private lateinit var btnSelectApps: Button
    private lateinit var btnStartLock: Button
    private lateinit var btnOpenApp: Button
    
    private lateinit var btnAddSchedule: Button
    private lateinit var btnViewSchedules: Button

    private var manualSelectedPackages = mutableSetOf<String>()
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, AdminReceiver::class.java)
        prefs = getSharedPreferences("FocusPrefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        tvStatus = TextView(this).apply { textSize = 16f; setPadding(0, 10, 0, 10) }
        tvScheduleInfo = TextView(this).apply { textSize = 14f; setPadding(0, 10, 0, 10); setTextColor(android.graphics.Color.parseColor("#0066cc")) }
        tvTimer = TextView(this).apply { textSize = 24f; setPadding(0, 20, 0, 20) }

        etHours = EditText(this).apply {
            hint = "Lock duration (Hours) - Manual Mode"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        btnSelectApps = Button(this).apply { text = "Select Whitelisted Apps (Manual)" }
        btnStartLock = Button(this).apply { text = "Start Manual Lock" }
        btnOpenApp = Button(this).apply { text = "Open Whitelisted App" }
        
        btnAddSchedule = Button(this).apply { text = "+ Add New Schedule" }
        btnViewSchedules = Button(this).apply { text = "Manage Schedules" }

        val divider = View(this).apply { 
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 4).apply { setMargins(0, 40, 0, 40) }
            setBackgroundColor(android.graphics.Color.LTGRAY)
        }

        layout.addView(tvStatus)
        layout.addView(tvScheduleInfo)
        layout.addView(tvTimer)
        layout.addView(btnAddSchedule)
        layout.addView(btnViewSchedules)
        layout.addView(divider)
        layout.addView(etHours)
        layout.addView(btnSelectApps)
        layout.addView(btnStartLock)
        layout.addView(btnOpenApp)

        setContentView(layout)

        manualSelectedPackages = prefs.getStringSet("manual_whitelist", emptySet())?.toMutableSet() ?: mutableSetOf()

        btnSelectApps.setOnClickListener { showManualAppSelectionDialog() }
        btnStartLock.setOnClickListener { startManualLock() }
        btnOpenApp.setOnClickListener { showWhitelistedAppsLauncher() }
        
        btnAddSchedule.setOnClickListener { startAddScheduleFlow() }
        btnViewSchedules.setOnClickListener { showSchedulesListDialog() }

        checkDeviceOwnerStatus()
        scheduleAlarmsForSessions()
    }

    override fun onResume() {
        super.onResume()
        checkCurrentTimeForSchedule()
        updateLockState()
        updateScheduleInfoText()
    }

    private fun checkDeviceOwnerStatus() {
        if (!dpm.isDeviceOwnerApp(packageName)) {
            tvStatus.text = "STATUS: NOT Device Owner!\nRun ADB command."
            btnStartLock.isEnabled = false
            btnAddSchedule.isEnabled = false
        } else {
            tvStatus.text = "STATUS: Device Owner Active ✅"
            btnStartLock.isEnabled = true
            btnAddSchedule.isEnabled = true
        }
    }

    private fun loadSessions(): List<Session> {
        val jsonStr = prefs.getString("saved_sessions", "[]") ?: "[]"
        val sessions = mutableListOf<Session>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val appsArray = obj.getJSONArray("apps")
                val appsList = mutableListOf<String>()
                for (j in 0 until appsArray.length()) {
                    appsList.add(appsArray.getString(j))
                }
                sessions.add(
                    Session(
                        id = obj.getLong("id"),
                        startHour = obj.getInt("startHour"),
                        startMinute = obj.getInt("startMinute"),
                        endHour = obj.getInt("endHour"),
                        endMinute = obj.getInt("endMinute"),
                        allowedApps = appsList
                    )
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
        return sessions
    }

    private fun saveSessions(sessions: List<Session>) {
        val jsonArray = JSONArray()
        for (s in sessions) {
            val obj = JSONObject()
            obj.put("id", s.id)
            obj.put("startHour", s.startHour)
            obj.put("startMinute", s.startMinute)
            obj.put("endHour", s.endHour)
            obj.put("endMinute", s.endMinute)
            val appsArray = JSONArray()
            s.allowedApps.forEach { appsArray.put(it) }
            obj.put("apps", appsArray)
            jsonArray.put(obj)
        }
        prefs.edit().putString("saved_sessions", jsonArray.toString()).apply()
        scheduleAlarmsForSessions()
        updateScheduleInfoText()
    }

    private fun updateScheduleInfoText() {
        val sessionsCount = loadSessions().size
        tvScheduleInfo.text = "Active Schedules: $sessionsCount"
    }

    private fun startAddScheduleFlow() {
        showTimePicker("Select Start Time") { startH, startM ->
            showTimePicker("Select End Time") { endH, endM ->
                showScheduleAppSelectionDialog(startH, startM, endH, endM)
            }
        }
    }

    private fun showTimePicker(title: String, onTimePicked: (Int, Int) -> Unit) {
        val cal = Calendar.getInstance()
        val picker = TimePickerDialog(this, { _, hourOfDay, minute ->
            onTimePicked(hourOfDay, minute)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true)
        picker.setTitle(title)
        picker.show()
    }

    private fun showScheduleAppSelectionDialog(sh: Int, sm: Int, eh: Int, em: Int) {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val appNames = packages.map { it.loadLabel(pm).toString() }.toTypedArray()
        val checkedItems = BooleanArray(packages.size)
        val selectedAppsForSchedule = mutableSetOf<String>()

        AlertDialog.Builder(this)
            .setTitle("Select Allowed Apps for this Schedule")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                val pkg = packages[which].packageName
                if (isChecked) selectedAppsForSchedule.add(pkg) else selectedAppsForSchedule.remove(pkg)
            }
            .setPositiveButton("Save Schedule") { _, _ ->
                val newSession = Session(
                    id = System.currentTimeMillis(),
                    startHour = sh, startMinute = sm,
                    endHour = eh, endMinute = em,
                    allowedApps = selectedAppsForSchedule.toList()
                )
                val currentSessions = loadSessions().toMutableList()
                currentSessions.add(newSession)
                saveSessions(currentSessions)
                Toast.makeText(this, "Schedule saved successfully!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSchedulesListDialog() {
        val sessions = loadSessions()
        if (sessions.isEmpty()) {
            Toast.makeText(this, "No schedules found.", Toast.LENGTH_SHORT).show()
            return
        }

        val items = sessions.map { 
            val sh = String.format("%02d:%02d", it.startHour, it.startMinute)
            val eh = String.format("%02d:%02d", it.endHour, it.endMinute)
            "From $sh to $eh (${it.allowedApps.size} apps)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Schedules List (Click to delete)")
            .setItems(items) { _, which ->
                val sessionToDelete = sessions[which]
                AlertDialog.Builder(this)
                    .setTitle("Delete Schedule?")
                    .setMessage("Are you sure you want to delete this schedule?")
                    .setPositiveButton("Yes, Delete") { _, _ ->
                        val updatedSessions = sessions.filter { it.id != sessionToDelete.id }
                        saveSessions(updatedSessions)
                        Toast.makeText(this, "Deleted.", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun scheduleAlarmsForSessions() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val sessions = loadSessions()
        
        sessions.forEachIndexed { index, session ->
            val intent = Intent(this, ScheduleReceiver::class.java)
            val pi = PendingIntent.getBroadcast(this, session.id.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, session.startHour)
                set(Calendar.MINUTE, session.startMinute)
                set(Calendar.SECOND, 0)
            }

            if (calendar.timeInMillis < System.currentTimeMillis()) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }

            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pi)
            } catch (e: Exception) { }
        }
    }

    private fun checkCurrentTimeForSchedule() {
        val now = Calendar.getInstance()
        val currentTotalMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val sessions = loadSessions()

        for (session in sessions) {
            val startTotal = session.startHour * 60 + session.startMinute
            val endTotal = session.endHour * 60 + session.endMinute

            if (currentTotalMinutes in startTotal until endTotal) {
                val isLocked = prefs.getBoolean("is_locked", false)
                val lockedSessionId = prefs.getLong("locked_session_id", -1)

                if (!isLocked || lockedSessionId != session.id) {
                    val millisLeft = (endTotal - currentTotalMinutes) * 60 * 1000L
                    val endTime = System.currentTimeMillis() + millisLeft

                    val whitelist = session.allowedApps.toMutableSet()
                    
                    prefs.edit()
                        .putLong("end_time", endTime)
                        .putBoolean("is_locked", true)
                        .putStringSet("current_whitelist", whitelist)
                        .putLong("locked_session_id", session.id)
                        .apply()

                    val allowedApps = whitelist.toMutableList().apply { add(packageName) }
                    
                    if (dpm.isDeviceOwnerApp(packageName)) {
                        dpm.setLockTaskPackages(adminComponent, allowedApps.toTypedArray())
                        startLockTask()
                    }
                    Toast.makeText(this, "Automatic lock activated!", Toast.LENGTH_LONG).show()
                }
                return 
            }
        }

        val lockedSessionId = prefs.getLong("locked_session_id", -1)
        if (prefs.getBoolean("is_locked", false) && lockedSessionId != -1L) {
            stopLock() 
        }
    }

    private fun showManualAppSelectionDialog() {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val appNames = packages.map { it.loadLabel(pm).toString() }.toTypedArray()
        val checkedItems = packages.map { manualSelectedPackages.contains(it.packageName) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Select Whitelisted Apps")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                val pkg = packages[which].packageName
                if (isChecked) manualSelectedPackages.add(pkg) else manualSelectedPackages.remove(pkg)
            }
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putStringSet("manual_whitelist", manualSelectedPackages).apply()
            }
            .show()
    }

    private fun startManualLock() {
        val hoursStr = etHours.text.toString()
        if (hoursStr.isEmpty() || hoursStr.toIntOrNull() == null || hoursStr.toInt() <= 0) {
            Toast.makeText(this, "Please enter valid hours!", Toast.LENGTH_SHORT).show()
            return
        }

        val hours = hoursStr.toLong()
        val endTime = System.currentTimeMillis() + (hours * 3600 * 1000)

        prefs.edit()
            .putLong("end_time", endTime)
            .putBoolean("is_locked", true)
            .putStringSet("current_whitelist", manualSelectedPackages)
            .putLong("locked_session_id", -1L) 
            .apply()

        val allowedApps = manualSelectedPackages.toMutableList().apply { add(packageName) }
        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.setLockTaskPackages(adminComponent, allowedApps.toTypedArray())
            startLockTask()
        }
        updateLockState()
    }

    private fun updateLockState() {
        val isLocked = prefs.getBoolean("is_locked", false)
        val endTime = prefs.getLong("end_time", 0)
        val now = System.currentTimeMillis()

        if (isLocked && now < endTime) {
            btnStartLock.visibility = View.GONE
            btnSelectApps.visibility = View.GONE
            btnAddSchedule.visibility = View.GONE
            btnViewSchedules.visibility = View.GONE
            etHours.visibility = View.GONE
            btnOpenApp.visibility = View.VISIBLE

            startTimer(endTime - now)
        } else if (isLocked && now >= endTime) {
            stopLock()
        } else {
            btnStartLock.visibility = View.VISIBLE
            btnSelectApps.visibility = View.VISIBLE
            btnAddSchedule.visibility = View.VISIBLE
            btnViewSchedules.visibility = View.VISIBLE
            etHours.visibility = View.VISIBLE
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
                Toast.makeText(this@MainActivity, "Time's up! Unlocked.", Toast.LENGTH_LONG).show()
                stopLock()
            }
        }.start()
    }

    private fun showWhitelistedAppsLauncher() {
        val pm = packageManager
        val currentWhitelist = prefs.getStringSet("current_whitelist", emptySet()) ?: emptySet()
        
        val allowedApps = currentWhitelist.mapNotNull { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                Pair(appInfo.loadLabel(pm).toString(), pkg)
            } catch (e: Exception) { null }
        }

        if (allowedApps.isEmpty()) {
            Toast.makeText(this, "No apps in whitelist!", Toast.LENGTH_SHORT).show()
            return
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
        try { stopLockTask() } catch (e: Exception) {}
        
        prefs.edit()
             .putBoolean("is_locked", false)
             .putLong("locked_session_id", -1L)
             .apply()
             
        updateLockState()
    }
}
