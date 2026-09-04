package com.soundmesh.product

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * How much charge is left and how hot the handset is, read and not acted on.
 *
 * Section 11.2's last row asks for exactly that - long sessions cost battery and produce heat, and
 * the interface has to say so honestly. It does not ask for the app to throttle itself, warn, or
 * stop, so none of that is here.
 *
 * Battery temperature and thermal status are both kept because they answer different questions:
 * one is how many degrees the cell is at, the other is whether the system has begun throttling. A
 * charging handset can sit warm at NONE, and a cool one can reach SEVERE right after a burst.
 *
 * Anything unreadable comes back null rather than as a plausible number. A made-up reading on a
 * screen whose whole job is honesty would be the one bug that cannot be seen.
 */
data class Health(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val celsius: Double?,
    val thermalStatus: Int?
)

object DeviceHealth {
    fun read(context: Context): Health {
        // The sticky broadcast: registering with a null receiver returns the last one rather than
        // subscribing, so this costs nothing to call on the refresh tick.
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        return Health(
            batteryPercent = level(context),
            charging = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.let { it > 0 },
            celsius = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, MISSING)
                ?.takeIf { it != MISSING }?.let { it / 10.0 },
            thermalStatus = runCatching {
                context.getSystemService(PowerManager::class.java)?.currentThermalStatus
            }.getOrNull()
        )
    }

    private fun level(context: Context): Int? = runCatching {
        context.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
    }.getOrNull()

    /** The value the platform hands back when the extra was never put in the intent. */
    private const val MISSING = Int.MIN_VALUE
}
