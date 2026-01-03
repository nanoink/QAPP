package com.qapp.app.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryOptimizationStatus(
    val isIgnoring: Boolean,
    val isAggressiveManufacturer: Boolean,
    val manufacturerHint: String?
)

object BatteryOptimizationHelper {

    private val _status = MutableStateFlow(
        BatteryOptimizationStatus(
            isIgnoring = false,
            isAggressiveManufacturer = DeviceInfoUtil.isAggressiveBatteryDevice(),
            manufacturerHint = buildManufacturerHint()
        )
    )
    val status: StateFlow<BatteryOptimizationStatus> = _status.asStateFlow()

    fun refresh(context: Context) {
        val ignoring = isIgnoringBatteryOptimizations(context)
        _status.value = _status.value.copy(isIgnoring = ignoring)
        Log.i(LogTags.WATCHDOG, "Battery optimization ignored=$ignoring")
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun buildRequestIntent(context: Context): Intent {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:${context.packageName}")
        return intent
    }

    private fun buildManufacturerHint(): String? {
        if (!DeviceInfoUtil.isAggressiveBatteryDevice()) return null
        return when (DeviceInfoUtil.manufacturer()) {
            "xiaomi", "redmi", "poco" ->
                "Abra Configuracoes > Bateria > Economia de bateria e permita execucao em segundo plano."
            "samsung" ->
                "Abra Configuracoes > Bateria > Limites de uso em segundo plano e permita este app."
            "huawei" ->
                "Abra Gerenciador do telefone > Bateria > Apps protegidos e habilite o app."
            "motorola" ->
                "Abra Configuracoes > Bateria > Otimizacao de bateria e permita este app."
            else -> null
        }
    }
}
