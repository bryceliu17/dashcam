package com.example.dashcam.battery

import kotlin.math.abs

object BatteryElectricalUnits {
    private const val HUAWEI_MILLIAMP_MODEL = "HWI-AL00"
    private const val MAX_PLAUSIBLE_MILLIAMP_READING = 20_000L

    fun currentNowMicroamps(manufacturer: String, model: String, reportedValue: Int): Int {
        if (!usesMilliampCurrentReporting(manufacturer, model)) return reportedValue
        if (abs(reportedValue.toLong()) > MAX_PLAUSIBLE_MILLIAMP_READING) return reportedValue
        return (reportedValue.toLong() * 1_000L)
            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun usesMilliampCurrentReporting(manufacturer: String, model: String): Boolean =
        manufacturer.equals("HUAWEI", ignoreCase = true) &&
            model.equals(HUAWEI_MILLIAMP_MODEL, ignoreCase = true)
}
