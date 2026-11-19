package com.desquared.encryptedroom

import androidx.compose.ui.window.ComposeUIViewController
import com.desquared.encryptedroom.db.SQLCipherTester
import com.desquared.encryptedroom.db.databaseFilePath
import com.desquared.encryptedroom.db.getDatabase
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val database = getDatabase()

    SQLCipherTester.verifyEncryption(databaseFilePath())

    return ComposeUIViewController {
        App(database)
    }
}
