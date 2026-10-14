package com.pablo.yumemi

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pablo.yumemi.ui.chat.ChatViewModel
import com.pablo.yumemi.ui.settings.SettingsViewModel
import com.pablo.yumemi.ui.threads.ThreadListViewModel

object AppViewModelProvider {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val c = yumemiApplication().container
            ThreadListViewModel(c.repository, c.settings)
        }
        initializer {
            val c = yumemiApplication().container
            ChatViewModel(createSavedStateHandle(), c.repository, c.client, c.settings)
        }
        initializer {
            val c = yumemiApplication().container
            SettingsViewModel(c.client, c.settings)
        }
    }
}

fun CreationExtras.yumemiApplication(): YumemiApplication =
    this[AndroidViewModelFactory.APPLICATION_KEY] as YumemiApplication
