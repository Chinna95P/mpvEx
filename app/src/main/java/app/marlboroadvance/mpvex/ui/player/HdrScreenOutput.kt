package app.marlboroadvance.mpvex.ui.player

import `is`.xyz.mpv.MPVLib

private fun hdrScreenOutputSettings(
  enabled: Boolean,
  pipelineReady: Boolean,
): List<Pair<String, String>> {
  val hdrEnabled = enabled && pipelineReady

  return listOf(
    "target-colorspace-hint-mode" to "target",
    "target-colorspace-hint-strict" to "yes",
    "target-colorspace-hint" to if (hdrEnabled) "yes" else "no",
    "target-trc" to "auto",
    "target-prim" to "auto",
    "target-peak" to if (hdrEnabled) "1000" else "auto",
    "hdr-reference-white" to "203",
    "hdr-compute-peak" to "auto",
    "gamut-mapping-mode" to if (hdrEnabled) "clip" else "auto",
    "inverse-tone-mapping" to "no",
    "tone-mapping" to if (hdrEnabled) "clip" else "auto",
  )
}

fun applyHdrScreenOutputOptions(
  enabled: Boolean,
  pipelineReady: Boolean,
) {
  hdrScreenOutputSettings(enabled, pipelineReady).forEach { (property, value) ->
    MPVLib.setOptionString(property, value)
  }
}

fun applyHdrScreenOutputProperties(
  enabled: Boolean,
  pipelineReady: Boolean,
) {
  hdrScreenOutputSettings(enabled, pipelineReady).forEach { (property, value) ->
    MPVLib.setPropertyString(property, value)
  }
}
