package com.lexiread

import android.app.Application
import com.lexiread.presentation.di.AppContainer

class LexiReadApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.initialize()
    }
}
