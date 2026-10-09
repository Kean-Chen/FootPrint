package com.footprint.app.map

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.NavigateArrow
import com.amap.api.maps.model.NavigateArrowOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.footprint.app.data.TrackPointEntity

@Composable
fun AMapTrackMap(
    points: List<TrackPointEntity>,
    momentPoints: List<TrackPointEntity> = emptyList(),
    hasApiKey: Boolean,
    showCurrentLocation: Boolean = false,
    locationPermissionGranted: Boolean = false,
    showLiveTrackLocation: Boolean = false,
    legendTopPadding: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    if (!hasApiKey) {
        Box(
            modifier = modifier.background(Color(0xFFE7E8E1)),
            contentAlignment = Alignment.Center,
        ) {
            Text("添加高德 Key 后显示地图", color = Color(0xFF68746D), modifier = Modifier.padding(20.dp))
        }
        return
    }

    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember(context) { MapView(context).apply { onCreate(Bundle()) } }
    var lastRenderedSignature by remember { mutableStateOf("") }
    var lastLocationEnabled by remember { mutableStateOf<Boolean?>(null) }
    var routePolyline by remember(mapView) { mutableStateOf<Polyline?>(null) }
    var routeDirectionArrow by remember(mapView) { mutableStateOf<NavigateArrow?>(null) }
    var startMarker by remember(mapView) { mutableStateOf<Marker?>(null) }
    var endMarker by remember(mapView) { mutableStateOf<Marker?>(null) }
    var currentTrackMarker by remember(mapView) { mutableStateOf<Marker?>(null) }
    var momentMarkers by remember(mapView) { mutableStateOf<List<Marker>>(emptyList()) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    val signature = remember(points, momentPoints, showLiveTrackLocation) {
        val latestPointTime = points.lastOrNull()?.recordedAt?.toString() ?: "empty"
        val momentSequenceSignature = momentPoints.joinToString(",") { it.sequence.toString() }
        "${points.size}:$latestPointTime:$showLiveTrackLocation:$momentSequenceSignature"
    }
    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (signature != lastRenderedSignature) {
                    lastRenderedSignature = signature
                    val map: AMap = view.map
                    map.uiSettings.isZoomControlsEnabled = false
                    map.uiSettings.isCompassEnabled = true
                    routePolyline?.remove()
                    routePolyline = null
                    routeDirectionArrow?.remove()
                    routeDirectionArrow = null
                    startMarker?.remove()
                    startMarker = null
                    endMarker?.remove()
                    endMarker = null
                    currentTrackMarker?.remove()
                    currentTrackMarker = null
                    momentMarkers.forEach(Marker::remove)
                    momentMarkers = emptyList()

                    val coordinates = points.map { LatLng(it.latitude, it.longitude) }
                    if (coordinates.isNotEmpty()) {
                        if (coordinates.size >= 2) {
                            routePolyline = map.addPolyline(
                                PolylineOptions()
                                    .addAll(coordinates)
                                    .width(11f)
                                    .color(android.graphics.Color.rgb(47, 107, 85))
                                    .zIndex(1f),
                            )
                            routeDirectionArrow = map.addNavigateArrow(
                                NavigateArrowOptions()
                                    .addAll(coordinates)
                                    .width(13f)
                                    .topColor(android.graphics.Color.WHITE)
                                    .sideColor(android.graphics.Color.rgb(47, 107, 85))
                                    .zIndex(2f),
                            )
                            startMarker = map.addMarker(
                                MarkerOptions()
                                    .position(coordinates.first())
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                                    .title("起点")
                                    .zIndex(5f),
                            )
                            if (showLiveTrackLocation) {
                                currentTrackMarker = map.addMarker(
                                    MarkerOptions()
                                        .position(coordinates.last())
                                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                                        .title("当前位置")
                                        .zIndex(6f),
                                )
                            } else {
                                endMarker = map.addMarker(
                                    MarkerOptions()
                                        .position(coordinates.last())
                                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                                        .title("终点")
                                        .zIndex(6f),
                                )
                            }
                        } else if (showLiveTrackLocation) {
                            currentTrackMarker = map.addMarker(
                                MarkerOptions()
                                    .position(coordinates.first())
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                                    .title("起点 / 当前位置")
                                    .zIndex(6f),
                            )
                        } else {
                            startMarker = map.addMarker(
                                MarkerOptions()
                                    .position(coordinates.first())
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                                    .title("起点 / 终点")
                                    .zIndex(5f),
                            )
                        }

                        val anchoredMoments = momentPoints.groupBy { it.sequence }
                        momentMarkers = anchoredMoments.map { (sequence, matchingMoments) ->
                            val point = matchingMoments.first()
                            val momentCount = matchingMoments.size
                            map.addMarker(
                                MarkerOptions()
                                    .position(LatLng(point.latitude, point.longitude))
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                                    .title(if (momentCount == 1) "图文记录" else "$momentCount 条图文记录")
                                    .snippet("关联轨迹点 #${sequence + 1}")
                                    .zIndex(8f),
                            )
                        }

                        if (showLiveTrackLocation) {
                            map.animateCamera(CameraUpdateFactory.newLatLng(coordinates.last()))
                        } else {
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(coordinates.last(), 16f))
                        }
                    }
                }
                val enableLocation = showCurrentLocation && locationPermissionGranted &&
                    (!showLiveTrackLocation || points.isEmpty())
                if (lastLocationEnabled != enableLocation) {
                    lastLocationEnabled = enableLocation
                    val map = view.map
                    map.uiSettings.isMyLocationButtonEnabled = enableLocation
                    if (enableLocation) {
                        map.myLocationStyle = MyLocationStyle()
                            .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
                        map.isMyLocationEnabled = true
                    } else {
                        map.isMyLocationEnabled = false
                    }
                }
            },
        )

        if (points.size >= 2 || momentPoints.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = legendTopPadding)
                    .background(Color.White.copy(alpha = 0.94f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text("箭头表示行进方向", color = Color(0xFF33443B))
                Spacer(Modifier.size(5.dp))
                LegendItem(Color(0xFF2F9B68), "起点")
                LegendItem(
                    color = if (showLiveTrackLocation) Color(0xFF2694E8) else Color(0xFFD84A43),
                    label = if (showLiveTrackLocation) "当前位置" else "终点",
                )
                if (momentPoints.isNotEmpty()) {
                    LegendItem(Color(0xFFFFA500), "图文记录位置")
                }
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color(0xFF33443B))
    }
}
