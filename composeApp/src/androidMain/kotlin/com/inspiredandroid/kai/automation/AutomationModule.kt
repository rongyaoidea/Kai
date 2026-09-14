package com.inspiredandroid.kai.automation

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val automationModule = module {
    single<AutomationController> { AutomationController(androidContext(), get()) }
    single<ShellUiBackend> { ShellUiBackend(androidContext(), get()) }
    single<ShizukuController> { ShizukuController(androidContext()) }
}
