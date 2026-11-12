const { withMainActivity } = require('expo/config-plugins');

const REFRESH_MARKER = '// PILLION_HIGH_REFRESH_RATE';

/**
 * Requests the display's highest refresh rate matching physical resolution and
 * disables Android 15+ frame rate power-saving throttling so cockpit scrolling
 * and overlay transitions stay responsive on 120Hz+ panels.
 */
module.exports = function withHighRefreshRate(config) {
  return withMainActivity(config, (mod) => {
    let contents = mod.modResults.contents;
    if (contents.includes(REFRESH_MARKER)) {
      return mod;
    }

    const imports = [
      'import android.os.Build',
      'import android.view.Display',
      'import android.view.WindowManager',
    ];

    for (const imp of imports) {
      if (!contents.includes(imp)) {
        contents = contents.replace(/^(package\s+[^\n]+\n)/m, `$1${imp}\n`);
      }
    }

    const helperCode = `    ${REFRESH_MARKER}
    enableHighRefreshRate()`;

    contents = contents.replace(
      /(super\.onCreate\([^\n]*\))/,
      `$1\n${helperCode}`
    );

    const methodBlock = `
  private fun enableHighRefreshRate() {
    if (Build.VERSION.SDK_INT >= 35) {
      try {
        window.setFrameRatePowerSavingsBalanced(false)
      } catch (_: Throwable) {}
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val currentDisplay: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display
      } else {
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay
      }
      val activeMode = currentDisplay?.mode ?: return
      val bestMode = currentDisplay.supportedModes
        .filter { it.physicalWidth == activeMode.physicalWidth && it.physicalHeight == activeMode.physicalHeight }
        .maxByOrNull { it.refreshRate }
      if (bestMode != null) {
        val attrs = window.attributes
        attrs.preferredDisplayModeId = bestMode.modeId
        window.attributes = attrs
      }
    }
  }
`;

    contents = contents.replace(/(\n\}\s*)$/, `${methodBlock}$1`);
    mod.modResults.contents = contents;
    return mod;
  });
};

