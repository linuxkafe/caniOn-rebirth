package org.caniorebirth.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MapFragment : Fragment() {

    private var mapView: MapView? = null
    private var marker: Marker? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_map, container, false)
        val ctx = requireContext()
        Configuration.getInstance().load(ctx, androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx))
        Configuration.getInstance().userAgentValue = ctx.packageName

        val mv = view.findViewById<MapView>(R.id.mapView)
        mv.setTileSource(TileSourceFactory.MAPNIK)
        mv.setMultiTouchControls(true)
        mv.controller.setZoom(5.0)
        mv.controller.setCenter(GeoPoint(0.0, 0.0))
        mapView = mv

        view.findViewById<Button>(R.id.btnOpenOrganicMaps).setOnClickListener {
            openInOrganicMaps()
        }
        return view
    }

    fun updatePosition(lat: Double, lon: Double) {
        val mv = mapView ?: return
        val gp = GeoPoint(lat, lon)
        mv.controller.animateTo(gp)
        mv.controller.setZoom(17.0)
        marker?.let { mv.overlays.remove(it) }
        val m = Marker(mv).apply {
            position = gp
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Vehicle"
        }
        marker = m
        mv.overlays.add(m)
        mv.invalidate()
    }

    private fun openInOrganicMaps() {
        val mv = mapView ?: return
        val center = mv.mapCenter
        val lat = center.latitude
        val lon = center.longitude
        val uri = Uri.parse("om://map?ll=$lat,$lon&z=17")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), "Organic Maps not installed. Install from omaps.app/get", Toast.LENGTH_LONG).show()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://omaps.app/get?api")))
        }
    }

    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { super.onPause(); mapView?.onPause() }
}
