package com.openyt.extension

external fun encodeURIComponent(value: String): String

/** Browser-only handoff. No extraction, networking, download, or local server. */
fun main() {
    val document = js("document")
    val api = js("globalThis.chrome || globalThis.browser")
    val portuguese = (js("navigator.language") as String).startsWith("pt", true)
    val button = document.getElementById("open")
    val address = document.getElementById("address")
    document.getElementById("hint").textContent = if (portuguese) "Revise o link no OpenYT antes de baixar." else "Review the link in OpenYT before downloading."
    button.textContent = if (portuguese) "Abrir no OpenYT" else "Open in OpenYT"
    api.tabs.query(js("({active:true,currentWindow:true})")) { tabs: dynamic ->
        val url = tabs[0]?.url as? String
        val valid = url != null && (url.startsWith("https://") || url.startsWith("http://")) &&
            !url.substringAfter("://").substringBefore('/').contains('@')
        address.textContent = url ?: ""
        button.disabled = !valid
        if (valid) button.addEventListener("click", { _: dynamic ->
            js("window").location.href = "openyt://download?url=" + encodeURIComponent(url!!)
        })
    }
}
