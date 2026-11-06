package com.fast.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
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

    private lateinit var searchInput: EditText
    private lateinit var gridView: GridView
    private lateinit var adapter: AppGridAdapter

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadAppsAsync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupUI()
        registerPackageReceiver()
        loadAppsAsync()
    }

    private fun setupUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#66000000"))
            setPadding(dp(12), dp(36), dp(12), dp(12))
        }

        val searchBg = GradientDrawable().apply {
            setColor(Color.parseColor("#88222222"))
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

        adapter = AppGridAdapter()
        gridView = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(16)
            horizontalSpacing = dp(8)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            gravity = Gravity.CENTER
            isVerticalScrollBarEnabled = false
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )

            setOnItemClickListener { _, _, position, _ ->
                val app = filteredApps[position]
                hideKeyboard()
                val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
            }

            setOnItemLongClickListener { _, _, position, _ ->
                val app = filteredApps[position]
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", app.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                true
            }
        }

        root.addView(searchInput)
        root.addView(gridView)
        setContentView(root)
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
        adapter.notifyDataSetChanged()
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (searchInput.text.isNotEmpty()) {
            searchInput.setText("")
            hideKeyboard()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageReceiver)
        executor.shutdownNow()
    }

    private inner class AppGridAdapter : BaseAdapter() {
        override fun getCount(): Int = filteredApps.size
        override fun getItem(position: Int): AppItem = filteredApps[position]
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
                    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
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

            val item = filteredApps[position]
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
