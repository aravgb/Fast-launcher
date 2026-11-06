package com.fast.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.concurrent.Executors

data class AppItem(
    val label: String,
    val packageName: String,
    val icon: Drawable
)

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val allApps = mutableListOf<AppItem>()
    private val filteredApps = mutableListOf<AppItem>()
    private val homeApps = mutableListOf<AppItem>()
    private val dockApps = mutableListOf<AppItem>()

    private lateinit var prefs: SharedPreferences
    private lateinit var homeLayer: LinearLayout
    private lateinit var drawerLayer: LinearLayout
    private lateinit var batteryText: TextView
    private lateinit var homeGrid: GridView
    private lateinit var dockContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var drawerGrid: GridView

    private lateinit var homeAdapter: AppGridAdapter
    private lateinit var drawerAdapter: AppGridAdapter
    private lateinit var gestureDetector: GestureDetector

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadAppsAsync()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                if (level >= 0) {
                    val chargeText = if (isCharging) " ⚡ Зарядка" else ""
                    batteryText.text = "Заряд: $level%$chargeText"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)
        setupGestures()
        setupUI()
        registerReceivers()
        loadAppsAsync()
    }

    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = false

            override fun onLongPress(e: MotionEvent) {
                if (drawerLayer.visibility != View.VISIBLE) {
                    showHomeOptionsDialog()
                }
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (Math.abs(diffY) > Math.abs(diffX) && Math.abs(diffY) > dp(60)) {
                    if (diffY < 0) {
                        openDrawer() // Свайп вверх открывает меню всех приложений
                        return true
                    } else if (diffY > 0 && drawerLayer.visibility != View.VISIBLE) {
                        expandNotifications() // Свайп вниз опускает шторку уведомлений
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupUI() {
        val root = FrameLayout(this)

        // ================= 1. СЛОЙ ГЛАВНОГО ЭКРАНА =================
        homeLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(42), dp(16), dp(16))
            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                true
            }
        }

        // Блок часов и даты
        val clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(20))
            setOnClickListener {
                try {
                    startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {}
            }
        }

        val timeClock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "hh:mm"
            setTextColor(Color.WHITE)
            textSize = 54f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }

        val dateClock = TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"
            format12Hour = "EEEE, d MMMM"
            setTextColor(Color.parseColor("#EEEEEE"))
            textSize = 16f
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }

        batteryText = TextView(this).apply {
            setTextColor(Color.parseColor("#CCCCCC"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }

        clockBox.addView(timeClock)
        clockBox.addView(dateClock)
        clockBox.addView(batteryText)

        // Сетка избранных приложений на рабочем столе
        homeAdapter = AppGridAdapter(homeApps)
        homeGrid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(18)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.CENTER
            isVerticalScrollBarEnabled = false
            adapter = homeAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )

            setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                false
            }

            setOnItemClickListener { _, _, position, _ ->
                if (position in homeApps.indices) {
                    launchApp(homeApps[position].packageName)
                }
            }

            setOnItemLongClickListener { _, _, position, _ ->
                if (position in homeApps.indices) {
                    showHomeAppMenu(homeApps[position], isDock = false)
                }
                true
            }
        }

        // Подсказка над док-баром
        val swipeHint = TextView(this).apply {
            text = "⌃ Свайп вверх — все приложения"
            setTextColor(Color.parseColor("#AAFFFFFF"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            setOnClickListener { openDrawer() }
        }

        // Нижняя панель (Док-бар)
        val dockBg = GradientDrawable().apply {
            setColor(Color.parseColor("#66111111"))
            cornerRadius = dp(28).toFloat()
        }

        dockContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = dockBg
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(74)
            )
        }

        homeLayer.addView(clockBox)
        homeLayer.addView(homeGrid)
        homeLayer.addView(swipeHint)
        homeLayer.addView(dockContainer)

        // ================= 2. СЛОЙ МЕНЮ ПРИЛОЖЕНИЙ =================
        drawerLayer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD000000"))
            setPadding(dp(12), dp(36), dp(12), dp(12))
            visibility = View.GONE
        }

        val searchBg = GradientDrawable().apply {
            setColor(Color.parseColor("#99282828"))
            cornerRadius = dp(24).toFloat()
        }

        searchInput = EditText(this).apply {
            hint = "Поиск приложений..."
            setHintTextColor(Color.parseColor("#AAAAAA"))
            setTextColor(Color.WHITE)
            textSize = 15f
            background = searchBg
            setSingleLine(true)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterApps(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        drawerAdapter = AppGridAdapter(filteredApps)
        drawerGrid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(16)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.CENTER
            isVerticalScrollBarEnabled = false
            adapter = drawerAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )

            setOnItemClickListener { _, _, position, _ ->
                if (position in filteredApps.indices) {
                    val app = filteredApps[position]
                    closeDrawer()
                    launchApp(app.packageName)
                }
            }

            setOnItemLongClickListener { _, _, position, _ ->
                if (position in filteredApps.indices) {
                    showDrawerAppMenu(filteredApps[position])
                }
                true
            }
        }

        drawerLayer.addView(searchInput)
        drawerLayer.addView(drawerGrid)

        root.addView(homeLayer)
        root.addView(drawerLayer)
        setContentView(root)
    }

    private fun updateDockUI() {
        dockContainer.removeAllViews()
        val itemLayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

        for (app in dockApps) {
            val iconView = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(4), dp(6), dp(4))
                layoutParams = itemLayoutParams
                setOnClickListener { launchApp(app.packageName) }
                setOnLongClickListener {
                    showHomeAppMenu(app, isDock = true)
                    true
                }
            }
            dockContainer.addView(iconView)
        }

        // Центральная кнопка открытия меню всех приложений
        val menuBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#44FFFFFF"))
        }

        val menuBtnWrapper = FrameLayout(this).apply {
            layoutParams = itemLayoutParams
            setOnClickListener { openDrawer() }
        }

        val menuBtn = TextView(this).apply {
            text = "⋮⋮⋮"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = menuBtnBg
            layoutParams = FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER)
        }

        menuBtnWrapper.addView(menuBtn)
        dockContainer.addView(menuBtnWrapper)
    }

    private fun openDrawer() {
        drawerLayer.visibility = View.VISIBLE
        homeLayer.visibility = View.INVISIBLE
    }

    private fun closeDrawer() {
        hideKeyboard()
        if (searchInput.text.isNotEmpty()) {
            searchInput.setText("")
        }
        drawerLayer.visibility = View.GONE
        homeLayer.visibility = View.VISIBLE
    }

    private fun launchApp(pkg: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
    }

    private fun showDrawerAppMenu(app: AppItem) {
        val options = arrayOf(
            "Добавить на главный экран",
            "Закрепить в нижней панели (Док)",
            "О приложении (Удалить / Остановить)"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addPackageToList("home_apps", app.packageName, maxCount = 16)
                    1 -> addPackageToList("dock_apps", app.packageName, maxCount = 4)
                    2 -> openAppSettings(app.packageName)
                }
            }
            .show()
    }

    private fun showHomeAppMenu(app: AppItem, isDock: Boolean) {
        val removeLabel = if (isDock) "Убрать из нижней панели" else "Убрать с главного экрана"
        val options = arrayOf(
            removeLabel,
            "О приложении (Удалить / Остановить)"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> removePackageFromList(if (isDock) "dock_apps" else "home_apps", app.packageName)
                    1 -> openAppSettings(app.packageName)
                }
            }
            .show()
    }

    private fun showHomeOptionsDialog() {
        val options = arrayOf("Все приложения", "Сменить обои", "Настройки телефона")
        AlertDialog.Builder(this)
            .setTitle("Главный экран")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openDrawer()
                    1 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Выбрать обои"))
                    2 -> startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }
            }
            .show()
    }

    private fun openAppSettings(pkg: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun addPackageToList(key: String, pkg: String, maxCount: Int) {
        val list = getSavedList(key)
        if (!list.contains(pkg)) {
            if (list.size >= maxCount) {
                list.removeAt(0)
            }
            list.add(pkg)
            saveList(key, list)
            syncFavorites()
            Toast.makeText(this, "Добавлено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removePackageFromList(key: String, pkg: String) {
        val list = getSavedList(key)
        if (list.remove(pkg)) {
            saveList(key, list)
            syncFavorites()
        }
    }

    private fun getSavedList(key: String): MutableList<String> {
        val raw = prefs.getString(key, "") ?: ""
        return if (raw.isEmpty()) mutableListOf() else raw.split(",").filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveList(key: String, list: List<String>) {
        prefs.edit().putString(key, list.joinToString(",")).apply()
    }

    private fun syncFavorites() {
        val map = allApps.associateBy { it.packageName }

        // Если это первый запуск, автоматически заполним док-бар первыми 4 приложениями
        if (!prefs.contains("dock_apps") && allApps.isNotEmpty()) {
            val initialDock = allApps.take(4).map { it.packageName }
            saveList("dock_apps", initialDock)
        }

        homeApps.clear()
        for (pkg in getSavedList("home_apps")) {
            map[pkg]?.let { homeApps.add(it) }
        }

        dockApps.clear()
        for (pkg in getSavedList("dock_apps")) {
            map[pkg]?.let { dockApps.add(it) }
        }

        homeAdapter.notifyDataSetChanged()
        updateDockUI()
    }

    private fun loadAppsAsync() {
        executor.execute {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val loadedList = mutableListOf<AppItem>()
            val myPackage = packageName

            for (ri in resolveInfos) {
                val pkg = ri.activityInfo.packageName
                if (pkg == myPackage) continue
                val label = ri.loadLabel(pm).toString()
                val icon = ri.loadIcon(pm)
                loadedList.add(AppItem(label, pkg, icon))
            }

            loadedList.sortBy { it.label.lowercase() }

            runOnUiThread {
                allApps.clear()
                allApps.addAll(loadedList)
                filterApps(searchInput.text.toString())
                syncFavorites()
            }
        }
    }

    private fun filterApps(query: String) {
        val cleanQuery = query.trim().lowercase()
        filteredApps.clear()
        if (cleanQuery.isEmpty()) {
            filteredApps.addAll(allApps)
        } else {
            for (app in allApps) {
                if (app.label.lowercase().contains(cleanQuery)) {
                    filteredApps.add(app)
                }
            }
        }
        drawerAdapter.notifyDataSetChanged()
    }

    private fun expandNotifications() {
        try {
            val service = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val expand = statusBarManager.getMethod("expandNotificationsPanel")
            expand.invoke(service)
        } catch (_: Exception) {}
    }

    private fun registerReceivers() {
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, pkgFilter)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawerLayer.visibility == View.VISIBLE) {
            closeDrawer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageReceiver)
        unregisterReceiver(batteryReceiver)
        executor.shutdownNow()
    }

    private inner class AppGridAdapter(private val items: List<AppItem>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): AppItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: ViewHolder
            val view: View

            if (convertView == null) {
                val container = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(4), dp(8), dp(4), dp(8))
                }

                val iconView = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val labelView = TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                }

                container.addView(iconView)
                container.addView(labelView)
                view = container
                holder = ViewHolder(iconView, labelView)
                view.tag = holder
            } else {
                view = convertView
                holder = view.tag as ViewHolder
            }

            val item = items[position]
            holder.icon.setImageDrawable(item.icon)
            holder.label.text = item.label
            return view
        }
    }

    private class ViewHolder(val icon: ImageView, val label: TextView)

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
