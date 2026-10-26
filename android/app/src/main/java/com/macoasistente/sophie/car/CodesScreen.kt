package com.macoasistente.sophie.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.macoasistente.sophie.Hub
import com.macoasistente.sophie.Obd

/** Trouble codes in plain language. Read-only in the car; clearing stays on the phone. */
class CodesScreen(ctx: CarContext) : Screen(ctx) {
    override fun onGetTemplate(): Template {
        val s = Hub.state.value
        val info = Hub.info.value
        val list = ItemList.Builder()
        val all = s.codes.stored.map { it to "Stored" } + s.codes.pending.map { it to "Pending" }
        list.setNoItemsMessage(
            when {
                s.codes.scanned -> "No trouble codes. ${info.name} is happy."
                s.mode == "live" -> "Reading codes…"
                else -> "Connect to ${info.name} to read codes"
            },
        )
        for ((code, kind) in all.take(6)) {
            val d = info.codes.firstOrNull { it.code == code }
            val row = Row.Builder().setTitle("$code · ${d?.title ?: Obd.family(code)}")
            row.addText(if (kind == "Pending") "Pending, no warning light yet" else "Stored")
            d?.drive?.takeIf { it.isNotBlank() }?.let { row.addText(it) }
            list.addItem(row.build())
        }
        val light = when (s.codes.milOn) { true -> "Check-engine light ON"; false -> "Check-engine light off"; else -> "Trouble codes" }
        return ListTemplate.Builder()
            .setTitle(light)
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }
}
