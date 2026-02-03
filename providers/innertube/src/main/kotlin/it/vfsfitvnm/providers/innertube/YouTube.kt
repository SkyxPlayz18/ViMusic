package it.vfsfitvnm.providers.innertube

import java.net.Proxy
object YouTube{
    var proxy: Proxy?
        get() = Innertube.proxy
        set(value) {
            Innertube.proxy = value
        }
}
