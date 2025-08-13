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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        val grid = findViewById<GridView>(R.id.gridApps)
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        val apps: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)

        grid.adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                val iconView = view.findViewById<ImageView>(R.id.appIcon)
                val labelView = view.findViewById<TextView>(R.id.appLabel)
                val ri = apps[position]
                val icon: Drawable = ri.loadIcon(pm)
                val label: String = ri.loadLabel(pm).toString()
                iconView.setImageDrawable(icon)
                labelView.text = label
                return view
            }
        }
        grid.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val ri = apps[position]
            val launchIntent = pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
            if (launchIntent != null) startActivity(launchIntent)
        }
    }
}


