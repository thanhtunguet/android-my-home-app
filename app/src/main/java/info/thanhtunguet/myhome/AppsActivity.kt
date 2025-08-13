package info.thanhtunguet.myhome

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class AppsActivity : AppCompatActivity() {
    private lateinit var apps: List<ResolveInfo>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        val grid = findViewById<GridView>(R.id.gridApps)
        val pm = packageManager
        apps = queryLaunchableApps()

        grid.adapter = AppsAdapter()
        grid.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val ri = apps[position]
            val launchIntent = pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
            if (launchIntent != null) startActivity(launchIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload apps for better UX when returning from settings or installs
        apps = queryLaunchableApps()
        val grid = findViewById<GridView>(R.id.gridApps)
        (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
    }

    private fun queryLaunchableApps(): List<ResolveInfo> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(mainIntent, 0)
    }

    private inner class AppsAdapter : BaseAdapter() {
        override fun getCount(): Int = apps.size
        override fun getItem(position: Int): Any = apps[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val iconView = view.findViewById<ImageView>(R.id.appIcon)
            val labelView = view.findViewById<TextView>(R.id.appLabel)
            val ri = apps[position]
            val icon: Drawable = ri.loadIcon(packageManager)
            val label: String = ri.loadLabel(packageManager).toString()
            iconView.setImageDrawable(icon)
            labelView.text = label
            return view
        }
    }
}


