package com.lastwave.app.diagnostics

/**
 * The distinct ways this app's process can die, and how each was detected.
 *
 * The label is what a user sees in the viewer, so it avoids claiming more than
 * is known — in particular [UNKNOWN_TERMINATION] does not assert a crash.
 */
enum class CrashType(val label: String) {
    UNCAUGHT_EXCEPTION("Uncaught exception"),
    ANR("App not responding"),
    NATIVE_CRASH("Native crash"),
    LOW_MEMORY("Low memory kill"),
    EXCESSIVE_RESOURCE_USE("Excessive resource use"),
    UNKNOWN_TERMINATION("Ended without a clean-shutdown marker"),
    LEGACY("Imported from an earlier version"),
}