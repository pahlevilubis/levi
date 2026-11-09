package com.pahlevi.levi.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Kembalikan fungsi yang menjalankan [onGranted] setelah izin mikrofon diberikan. */
@Composable
fun rememberMicPermission(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) current() else toast(context, "Izin mikrofon ditolak, fitur suara tidak bisa dipakai")
    }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) current()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

/** Minta izin mikrofon + notifikasi (untuk rekaman di latar belakang). */
@Composable
fun rememberRecordPermissions(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(onGranted)
    val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) current()
        else toast(context, "Izin mikrofon ditolak, tidak bisa merekam")
    }
    return {
        val missing = perms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) current() else launcher.launch(missing.toTypedArray())
    }
}

/** Minta izin notifikasi (Android 13+) sebelum pekerjaan panjang di latar belakang. */
@Composable
fun rememberNotificationPermission(onDone: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val current by rememberUpdatedState(onDone)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { current() }
    return {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) current()
        else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
