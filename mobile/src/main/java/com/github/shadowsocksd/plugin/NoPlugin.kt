package com.github.shadowsocksd.plugin

import com.github.shadowsocksd.App.Companion.app

object NoPlugin : Plugin() {
    override val id: String get() = ""
    override val label: CharSequence get() = app.getText(com.github.shadowsocksd.R.string.plugin_disabled)
}
