package com.footprint.app.map

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
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
    showCompass: Boolean = true,
    markerScale: Float = 1f,
    recenterRequest: Int = 0,
    compassResetRequest: Int = 0,
    onCameraBearingChanged: (Float) -> Unit = {},
    onMomentPointClick: (Long) -> Unit = {},
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
    val currentOnMomentPointClick by rememberUpdatedState(onMomentPointClick)
    val currentOnCameraBearingChanged = rememberUpdatedState(onCameraBearingChanged)
    val mapView = remember(context) { MapView(context).apply { onCreate(Bundle()) } }
    val startMarkerIcon = remember(mapView, markerScale) { TrackMarkerIcons.start(context, markerScale) }
    val finishMarkerIcon = remember(mapView, markerScale) { TrackMarkerIcons.finish(context, markerScale) }
    val momentMarkerIcon = remember(mapView, markerScale) { TrackMarkerIcons.moment(context, markerScale) }
    var lastRenderedSignature by remember { mutableStateOf("") }
    var lastLocationEnabled by remember { mutableStateOf<Boolean?>(null) }
    var lastRecenterRequest by remember(mapView) { mutableStateOf(recenterRequest) }
    var lastCompassResetRequest by remember(mapView) { mutableStateOf(compassResetRequest) }
    val cameraChangeListener = remember(mapView) {
        object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition) {
                currentOnCameraBearingChanged.value(position.bearing)
            }

            override fun onCameraChangeFinish(position: CameraPosition) {
                currentOnCameraBearingChanged.value(position.bearing)
            }
        }
    }
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

    val signature = remember(points, momentPoints, showLiveTrackLocation, markerScale) {
        val latestPointTime = points.lastOrNull()?.recordedAt?.toString() ?: "empty"
        val momentSequenceSignature = momentPoints.joinToString(",") { it.sequence.toString() }
        "${points.size}:$latestPointTime:$showLiveTrackLocation:$markerScale:$momentSequenceSignature"
    }
    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                val map = view.map
                map.setOnCameraChangeListener(cameraChangeListener)
                currentOnCameraBearingChanged.value(map.cameraPosition.bearing)
                map.setOnMarkerClickListener { marker ->
                    val sequence = marker.getObject() as? Long
                    if (sequence != null) {
                        currentOnMomentPointClick(sequence)
                        true
                    } else {
                        false
                    }
                }
                if (signature != lastRenderedSignature) {
                    lastRenderedSignature = signature
                    map.uiSettings.isZoomControlsEnabled = false
                    map.uiSettings.isScrollGesturesEnabled = true
                    map.uiSettings.isZoomGesturesEnabled = true
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
                                    .width(12f)
                                    .color(android.graphics.Color.rgb(25, 75, 48))
                                    .zIndex(1f),
                            )
                            routeDirectionArrow = map.addNavigateArrow(
                                NavigateArrowOptions()
                                    .addAll(coordinates)
                                    .width(13f)
                                    .topColor(android.graphics.Color.rgb(25, 75, 48))
                                    .sideColor(android.graphics.Color.rgb(13, 48, 30))
                                    .zIndex(2f),
                            )
                            startMarker = map.addMarker(
                                MarkerOptions()
                                    .position(coordinates.first())
                                    .icon(startMarkerIcon)
                                    .anchor(0.5f, 0.98f)
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
                                        .icon(finishMarkerIcon)
                                        .anchor(0.5f, 0.98f)
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
                                    .icon(startMarkerIcon)
                                    .anchor(0.5f, 0.98f)
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
                                    .icon(momentMarkerIcon)
                                    .anchor(0.5f, 0.98f)
                                    .title(if (momentCount == 1) "图文记录" else "$momentCount 条图文记录")
                                    .snippet("关联轨迹点 #${sequence + 1}")
                                    .zIndex(8f),
                            ).apply { setObject(sequence) }
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
                map.uiSettings.isMyLocationButtonEnabled = false
                map.uiSettings.isCompassEnabled = showCompass
                if (lastLocationEnabled != enableLocation) {
                    lastLocationEnabled = enableLocation
                    val map = view.map
                    if (enableLocation) {
                        map.myLocationStyle = MyLocationStyle()
                            .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
                        map.isMyLocationEnabled = true
                    } else {
                        map.isMyLocationEnabled = false
                    }
                }
                if (recenterRequest != lastRecenterRequest) {
                    lastRecenterRequest = recenterRequest
                    if (enableLocation) {
                        map.myLocation?.let { location ->
                            map.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(location.latitude.toDouble(), location.longitude.toDouble()),
                                    16f,
                                ),
                            )
                        }
                    }
                }
                if (compassResetRequest != lastCompassResetRequest) {
                    lastCompassResetRequest = compassResetRequest
                    map.animateCamera(CameraUpdateFactory.changeBearing(0f))
                }
            },
        )

    }
}
