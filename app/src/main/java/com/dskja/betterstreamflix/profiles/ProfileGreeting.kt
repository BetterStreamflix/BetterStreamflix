package com.dskja.betterstreamflix.profiles

import android.content.Context
import com.dskja.betterstreamflix.R
import java.util.Calendar

object ProfileGreeting {

    fun line(context: Context, profile: UserProfile?): String {
        val name = profile?.publicName()?.trim().orEmpty()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val res = when (hour) {
            in 5..11 -> R.string.profile_greeting_morning
            in 12..17 -> R.string.profile_greeting_afternoon
            in 18..21 -> R.string.profile_greeting_evening
            else -> R.string.profile_greeting_night
        }
        return if (name.isEmpty()) {
            context.getString(res, context.getString(R.string.profile_default))
        } else {
            context.getString(res, name)
        }
    }

    fun homeEyebrow(context: Context, profile: UserProfile?): String = line(context, profile)
}
