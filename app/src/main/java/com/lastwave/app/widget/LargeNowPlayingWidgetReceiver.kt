package com.lastwave.app.widget

/** Large widget. Class name kept stable so placed widgets survive the update. */
class LargeNowPlayingWidgetReceiver : BaseNowPlayingWidgetProvider() {
    override val small: Boolean = false
    override val receiverClass: Class<*> = LargeNowPlayingWidgetReceiver::class.java
}
