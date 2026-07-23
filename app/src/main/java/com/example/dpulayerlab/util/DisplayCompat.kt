package com.example.dpulayerlab.util

import android.app.Activity
import android.os.Build
import android.view.Display

@Suppress("DEPRECATION")
fun Activity.currentDisplayCompat(): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else windowManager.defaultDisplay
