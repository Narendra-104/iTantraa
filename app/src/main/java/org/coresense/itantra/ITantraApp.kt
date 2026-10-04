package org.coresense.itantra

import android.app.Application
import org.coresense.itantra.storage.AppDatabase

class ITantraApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
    }

    companion object {
        lateinit var instance: ITantraApp
            private set
    }
}
