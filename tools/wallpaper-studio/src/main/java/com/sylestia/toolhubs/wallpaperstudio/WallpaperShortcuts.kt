package com.sylestia.toolhubs.wallpaperstudio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

object WallpaperShortcuts {
    fun refresh(context: Context, scenarios: List<WallpaperScenario>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = scenarios
            .filter { it.enabled }
            .take(manager.maxShortcutCountPerActivity)
            .map { scenario ->
                ShortcutInfo.Builder(context, "scene_${scenario.id}")
                    .setActivity(ComponentName(context, MainActivity::class.java))
                    .setShortLabel(scenario.name.take(10))
                    .setLongLabel("${scenario.name.take(14)} wallpaper")
                    .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_scene))
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(MainActivity.ACTION_APPLY_SCENE)
                            .putExtra(WallpaperScheduler.EXTRA_SCENARIO_ID, scenario.id)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    .build()
            }
        manager.dynamicShortcuts = shortcuts
    }
}
