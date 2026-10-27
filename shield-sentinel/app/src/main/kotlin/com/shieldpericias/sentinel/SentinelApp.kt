package com.shieldpericias.sentinel

import android.app.Application
import android.content.Context

class SentinelApp : Application() {
    lateinit var graph: SentinelGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = SentinelGraph.get(this)
        graph.onAppStart()
    }

    companion object {
        fun graph(context: Context): SentinelGraph = SentinelGraph.get(context.applicationContext)
    }
}
