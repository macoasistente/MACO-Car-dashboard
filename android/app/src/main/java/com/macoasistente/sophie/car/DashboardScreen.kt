package com.macoasistente.sophie.car

import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.macoasistente.sophie.Hub
import com.macoasistente.sophie.ObdService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/** Main car screen: the whole display is Sophie's dashboard, drawn by [DashboardRenderer]. */
@OptIn(FlowPreview::class)
class DashboardScreen(ctx: CarContext) : Screen(ctx) {
    private val renderer = DashboardRenderer(ctx)
    private var lastMode = ""

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                renderer.release()
            }
        })
        lifecycleScope.launch {
            Hub.state.combine(Hub.info) { s, i -> s to i }.sample(250).collect { (s, i) ->
                renderer.update(s, i)
                // the action strip shows Connect only when there's no link
                if (s.mode != lastMode) { lastMode = s.mode; invalidate() }
            }
        }
        // keep the clock current when no data is arriving
        lifecycleScope.launch {
            while (true) {
                delay(20000)
                renderer.update(Hub.state.value, Hub.info.value)
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = Hub.state.value
        val strip = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("Codes")
                    .setOnClickListener { screenManager.push(CodesScreen(carContext)) }.build(),
            )
        if (s.mode == "idle") {
            strip.addAction(
                Action.Builder().setTitle("Connect").setBackgroundColor(CarColor.BLUE)
                    .setOnClickListener {
                        val a = ObdService.savedAddress(carContext)
                        if (a != null) ObdService.send(carContext, ObdService.ACTION_CONNECT, a)
                        else androidx.car.app.CarToast.makeText(carContext, "Pick your adapter once in the phone app", androidx.car.app.CarToast.LENGTH_LONG).show()
                    }.build(),
            )
        }
        return NavigationTemplate.Builder().setActionStrip(strip.build()).build()
    }
}
