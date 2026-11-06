package com.fast.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
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
    val icon: Drawable,
    val isFolder: Boolean = false,
    val folderName: String = ""
)

class MainActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val allApps = mutableListOf<AppItem>()
    private val filteredApps = mutableListOf<AppItem>()
    private val homeItems = mutableListOf<AppItem>()
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
                        openDrawer()
                        return true
                    } else if (diffY > 0 && drawerLayer.visibility != View.VISIBLE) {
                        expandNotifications()
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
            setOnLongClickListener {
                showHomeOptionsDialog()
                true
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

        // Сетка ярлыков и папок на рабочем столе
        homeAdapter = AppGridAdapter(homeItems)
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
                if (position in homeItems.indices) {
                    val item = homeItems[position]
                    if (item.isFolder) {
                        openFolderDialog(item.folderName)
                    } else {
                        launchApp(item.packageName)
                    }
                }
            }

            setOnItemLongClickListener { _, _, position, _ ->
                if (position in homeItems.indices) {
                    val item = homeItems[position]
                    if (item.isFolder) {
                        showFolderManageMenu(item.folderName)
                    } else {
                        showHomeAppMenu(item, isDock = false)
                    }
                }
                true
            }
        }

        val swipeHint = TextView(this).apply {
            text = "⌃ Свайп вверх — все приложения  |  Удержание — меню"
            setTextColor(Color.parseColor("#AAFFFFFF"))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            setOnClickListener { openDrawer() }
            setOnLongClickListener {
                showHomeOptionsDialog()
                true
            }
        }

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

        // Верхняя панель: Поиск + Кнопка настроек/скрытых приложений
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
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
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterApps(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        val settingsBtnBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#99282828"))
        }

        val settingsBtn = TextView(this).apply {
            text = "⚙"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            background = settingsBtnBg
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                leftMargin = dp(8)
            }
            setOnClickListener {
                hideKeyboard()
                showHomeOptionsDialog()
            }
        }

        topBar.addView(searchInput)
        topBar.addView(settingsBtn)

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

        drawerLayer.addView(topBar)
        drawerLayer.addView(drawerGrid)

        root.addView(homeLayer)
        root.addView(drawerLayer)
        setContentView(root)
    }

    // ================= РАБОТА С ПАПКАМИ =================

    private fun buildFolderIcon(folderApps: List<AppItem>): Drawable {
        val size = dp(54)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99222222")
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }

        val pad = dp(2).toFloat()
        val rect = RectF(pad, pad, size - pad, size - pad)
        val radius = dp(14).toFloat()
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        val innerPad = dp(8)
        val gap = dp(4)
        val miniSize = (size - innerPad * 2 - gap) / 2

        for (i in 0 until minOf(4, folderApps.size)) {
            val col = i % 2
            val row = i / 2
            val left = innerPad + col * (miniSize + gap)
            val top = innerPad + row * (miniSize + gap)
            val iconCopy = folderApps[i].icon.constantState?.newDrawable()?.mutate() ?: folderApps[i].icon
            iconCopy.setBounds(left, top, left + miniSize, top + miniSize)
            iconCopy.draw(canvas)
        }

        return BitmapDrawable(resources, bitmap)
    }

    private fun showCreateFolderDialog(initialAppPkg: String? = null) {
        val input = EditText(this).apply {
            hint = "Например: Игры или Работа"
            setSingleLine(true)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        AlertDialog.Builder(this)
            .setTitle("Новая папка")
            .setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val rawName = input.text.toString().trim().replace(",", " ").replace(":", " ")
                if (rawName.isNotEmpty()) {
                    val folderToken = "folder:$rawName"
                    val homeList = getSavedList("home_apps")
                    if (!homeList.contains(folderToken)) {
                        if (homeList.size >= 16) homeList.removeAt(0)
                        homeList.add(folderToken)
                        saveList("home_apps", homeList)
                    }
                    if (initialAppPkg != null) {
                        addAppToFolder(rawName, initialAppPkg)
                    } else {
                        syncFavorites()
                        showSelectAppsForFolderDialog(rawName)
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showChooseFolderDialog(pkg: String) {
        val folders = getSavedList("home_apps")
            .filter { it.startsWith("folder:") }
            .map { it.removePrefix("folder:") }

        val options = folders.toMutableList()
        options.add("+ Создать новую папку")

        AlertDialog.Builder(this)
            .setTitle("Выберите папку")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == folders.size) {
                    showCreateFolderDialog(initialAppPkg = pkg)
                } else {
                    addAppToFolder(folders[which], pkg)
                }
            }
            .show()
    }

    private fun addAppToFolder(folderName: String, pkg: String) {
        val key = "folder_items_$folderName"
        val list = getSavedList(key)
        if (!list.contains(pkg)) {
            list.add(pkg)
            saveList(key, list)
            syncFavorites()
            Toast.makeText(this, "Добавлено в папку «$folderName»", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFolderDialog(folderName: String) {
        val key = "folder_items_$folderName"
        val map = allApps.associateBy { it.packageName }
        val appsInFolder = getSavedList(key).mapNotNull { map[it] }.toMutableList()

        if (appsInFolder.isEmpty()) {
            showSelectAppsForFolderDialog(folderName)
            return
        }

        var dialog: AlertDialog? = null

        val folderGrid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(16)
            horizontalSpacing = dp(8)
            setPadding(dp(12), dp(16), dp(12), dp(16))
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.CENTER
            adapter = AppGridAdapter(appsInFolder)

            setOnItemClickListener { _, _, pos, _ ->
                if (pos in appsInFolder.indices) {
                    dialog?.dismiss()
                    launchApp(appsInFolder[pos].packageName)
                }
            }

            setOnItemLongClickListener { _, _, pos, _ ->
                if (pos in appsInFolder.indices) {
                    val target = appsInFolder[pos]
                    val currentList = getSavedList(key)
                    currentList.remove(target.packageName)
                    saveList(key, currentList)
                    syncFavorites()
                    dialog?.dismiss()
                    Toast.makeText(this@MainActivity, "Убрано из папки", Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(folderName)
            .setView(folderGrid)
            .setNeutralButton("+ Приложения") { _, _ ->
                showSelectAppsForFolderDialog(folderName)
            }
            .setPositiveButton("Закрыть", null)
            .create()

        dialog.show()
    }

    private fun showSelectAppsForFolderDialog(folderName: String) {
        if (allApps.isEmpty()) return
        val key = "folder_items_$folderName"
        val currentSet = getSavedList(key).toMutableSet()

        val labels = allApps.map { it.label }.toTypedArray()
        val checked = BooleanArray(allApps.size) { i ->
            currentSet.contains(allApps[i].packageName)
        }

        AlertDialog.Builder(this)
            .setTitle("Приложения в «$folderName»")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = allApps[which].packageName
                if (isChecked) currentSet.add(pkg) else currentSet.remove(pkg)
            }
            .setPositiveButton("Сохранить") { _, _ ->
                saveList(key, currentSet.toList())
                syncFavorites()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showFolderManageMenu(folderName: String) {
        val options = arrayOf(
            "Выбрать приложения для папки",
            "Переименовать папку",
            "Удалить папку с экрана"
        )
        AlertDialog.Builder(this)
            .setTitle("Папка «$folderName»")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSelectAppsForFolderDialog(folderName)
                    1 -> showRenameFolderDialog(folderName)
                    2 -> {
                        removePackageFromList("home_apps", "folder:$folderName")
                        prefs.edit().remove("folder_items_$folderName").apply()
                        syncFavorites()
                    }
                }
            }
            .show()
    }

    private fun showRenameFolderDialog(oldName: String) {
        val input = EditText(this).apply {
            setText(oldName)
            setSingleLine(true)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        AlertDialog.Builder(this)
            .setTitle("Переименовать папку")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = input.text.toString().trim().replace(",", " ").replace(":", " ")
                if (newName.isNotEmpty() && newName != oldName) {
                    val homeList = getSavedList("home_apps").map {
                        if (it == "folder:$oldName") "folder:$newName" else it
                    }
                    val folderItems = getSavedList("folder_items_$oldName")
                    saveList("folder_items_$newName", folderItems)
                    prefs.edit().remove("folder_items_$oldName").apply()
                    saveList("home_apps", homeList)
                    syncFavorites()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ================= СКРЫТИЕ ПРИЛОЖЕНИЙ =================

    private fun hideApp(app: AppItem) {
        val hidden = getSavedList("hidden_apps")
        if (!hidden.contains(app.packageName)) {
            hidden.add(app.packageName)
            saveList("hidden_apps", hidden)
            filterApps(searchInput.text.toString())
            Toast.makeText(this, "${app.label} скрыто из меню", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHiddenAppsDialog() {
        if (allApps.isEmpty()) return
        val hiddenSet = getSavedList("hidden_apps").toMutableSet()

        val labels = allApps.map { it.label }.toTypedArray()
        val checked = BooleanArray(allApps.size) { i ->
            hiddenSet.contains(allApps[i].packageName)
        }

        AlertDialog.Builder(this)
            .setTitle("Скрытые приложения (отметьте ненужные)")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = allApps[which].packageName
                if (isChecked) hiddenSet.add(pkg) else hiddenSet.remove(pkg)
            }
            .setPositiveButton("Сохранить") { _, _ ->
                saveList("hidden_apps", hiddenSet.toList())
                filterApps(searchInput.text.toString())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ================= НАВИГАЦИЯ И МЕНЮ =================

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
            "Добавить в папку...",
            "Закрепить в нижней панели (Док)",
            "Скрыть приложение из меню",
            "О приложении (Удалить / Остановить)"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> addPackageToList("home_apps", app.packageName, maxCount = 16)
                    1 -> showChooseFolderDialog(app.packageName)
                    2 -> addPackageToList("dock_apps", app.packageName, maxCount = 4)
                    3 -> hideApp(app)
                    4 -> openAppSettings(app.packageName)
                }
            }
            .show()
    }

    private fun showHomeAppMenu(app: AppItem, isDock: Boolean) {
        val removeLabel = if (isDock) "Убрать из нижней панели" else "Убрать с главного экрана"
        val options = arrayOf(
            removeLabel,
            "Переместить в папку...",
            "О приложении (Удалить / Остановить)"
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> removePackageFromList(if (isDock) "dock_apps" else "home_apps", app.packageName)
                    1 -> {
                        removePackageFromList(if (isDock) "dock_apps" else "home_apps", app.packageName)
                        showChooseFolderDialog(app.packageName)
                    }
                    2 -> openAppSettings(app.packageName)
                }
            }
            .show()
    }

    private fun showHomeOptionsDialog() {
        val hiddenCount = getSavedList("hidden_apps").size
        val options = arrayOf(
            "Создать папку на главном экране",
            "Скрытые приложения ($hiddenCount)",
            "Все приложения",
            "Сменить обои",
            "Настройки телефона"
        )
        AlertDialog.Builder(this)
            .setTitle("Настройки лаунчера")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showCreateFolderDialog()
                    1 -> showHiddenAppsDialog()
                    2 -> openDrawer()
                    3 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Выбрать обои"))
                    4 -> startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
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

        if (!prefs.contains("dock_apps") && allApps.isNotEmpty()) {
            val initialDock = allApps.take(4).map { it.packageName }
            saveList("dock_apps", initialDock)
        }

        homeItems.clear()
        for (entry in getSavedList("home_apps")) {
            if (entry.startsWith("folder:")) {
                val fName = entry.removePrefix("folder:")
                val fApps = getSavedList("folder_items_$fName").mapNotNull { map[it] }
                val fIcon = buildFolderIcon(fApps)
                homeItems.add(
                    AppItem(
                        label = fName,
                        packageName = entry,
                        icon = fIcon,
                        isFolder = true,
                        folderName = fName
                    )
                )
            } else {
                map[entry]?.let { homeItems.add(it) }
            }
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
        val hiddenSet = getSavedList("hidden_apps").toSet()
        filteredApps.clear()

        for (app in allApps) {
            if (hiddenSet.contains(app.packageName)) continue
            if (cleanQuery.isEmpty() || app.label.lowercase().contains(cleanQuery)) {
                filteredApps.add(app)
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
