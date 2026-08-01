package com.example.ocr_translation

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * The launcher icons the user can choose between.
 *
 * Android has no API for setting an app's icon; the only way is to declare several
 * `<activity-alias>` entries in the manifest, each carrying its own icon and the LAUNCHER filter,
 * and have exactly one enabled. So the set is fixed at build time — an alias cannot be created at
 * runtime — and picking one is really enabling a component and disabling its siblings.
 *
 * [MainActivity] deliberately has no LAUNCHER filter of its own. If it did, it would be a seventh
 * entry that could not be turned off without disabling the activity the aliases all point at.
 */
enum class AppIcon(
    /** Alias class name, relative to the package. Persisted, so these strings are not free to change. */
    val alias: String,
    val labelRes: Int,
    /** The icon itself, shown in the row and the picker — a colour swatch can't stand in for art. */
    val iconRes: Int
) {
    /**
     * The app's own icon, and the default. First in the list and never replaced by the generated
     * set: the alternatives exist to be chosen, not to displace what the app already had.
     */
    ORIGINAL(".LauncherOriginal", R.string.app_icon_original, R.mipmap.ic_launcher),
    BLUE(".LauncherBlue", R.string.accent_name_blue, R.mipmap.ic_launcher_blue),
    PURPLE(".LauncherPurple", R.string.accent_name_purple, R.mipmap.ic_launcher_purple),
    GREEN(".LauncherGreen", R.string.accent_name_green, R.mipmap.ic_launcher_green),
    ORANGE(".LauncherOrange", R.string.accent_name_orange, R.mipmap.ic_launcher_orange),
    PINK(".LauncherPink", R.string.accent_name_pink, R.mipmap.ic_launcher_pink),
    GRAPHITE(".LauncherGraphite", R.string.accent_name_graphite, R.mipmap.ic_launcher_graphite);

    companion object {
        private const val TAG = "AppIcon"

        /** The one the manifest ships enabled; also what an unset or unrecognised preference means. */
        val DEFAULT = ORIGINAL

        fun fromAlias(alias: String?): AppIcon =
            entries.firstOrNull { it.alias == alias } ?: DEFAULT

        /**
         * Switches the launcher icon to [icon].
         *
         * The new alias is enabled *before* the others are disabled, and never the other way
         * round: with none enabled there is no LAUNCHER component at all, and the app disappears
         * from the launcher — recoverable only by reinstalling, since there is nothing left to
         * tap. Passing through a state with two enabled is merely untidy for an instant.
         *
         * DONT_KILL_APP because changing a component's state otherwise restarts the process, and
         * this is called from a screen the user is looking at.
         *
         * The launcher still notices the swap as a package change: expect the icon to blink, any
         * home-screen shortcut pointing at the old alias to be dropped, and some launchers to
         * re-sort the app. That is inherent to the mechanism, not something to fix here.
         */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            val pkg = context.packageName
            try {
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, pkg + icon.alias),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                for (other in entries) {
                    if (other == icon) continue
                    pm.setComponentEnabledSetting(
                        ComponentName(pkg, pkg + other.alias),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't switch the launcher icon", e)
            }
        }
    }
}
