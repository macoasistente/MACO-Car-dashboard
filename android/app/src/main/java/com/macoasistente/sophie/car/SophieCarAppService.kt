package com.macoasistente.sophie.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.macoasistente.sophie.ObdService

/** Android Auto entry point. Sideloaded app, so any Android Auto host may connect. */
class SophieCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            ObdService.send(carContext, ObdService.ACTION_START)
            return DashboardScreen(carContext)
        }
    }
}
