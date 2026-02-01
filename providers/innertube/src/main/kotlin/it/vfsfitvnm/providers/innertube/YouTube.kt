package it.vfsfitvnm.providers.innertube

import java.net.Proxy

object YouTube {
    var visitorData: String = "CgtXczFGNjhHaGsyOCi9i7W0BjIKCgJJRBIEGgAgOw%3D%3D"
    
    var proxy: Proxy? = null
        set(value) {
            field = value
            Innertube.proxy = value
        }
}
