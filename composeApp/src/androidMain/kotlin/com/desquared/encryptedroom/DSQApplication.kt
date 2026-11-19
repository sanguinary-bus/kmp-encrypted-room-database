package com.desquared.encryptedroom

import android.app.Application

class DSQApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        System.loadLibrary("sqlcipher")
    }

    companion object {
        internal lateinit var instance: DSQApplication
            private set
    }
}
