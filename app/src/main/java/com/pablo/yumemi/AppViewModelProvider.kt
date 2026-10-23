package com.pablo.yumemi

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pablo.yumemi.ui.chat.ChatViewModel
import com.pablo.yumemi.ui.cron.CronResultsViewModel
import com.pablo.yumemi.ui.cron.CronViewModel
import com.pablo.yumemi.ui.feeds.FeedDetailViewModel
import com.pablo.yumemi.ui.feeds.FeedsViewModel
import com.pablo.yumemi.ui.settings.SettingsViewModel
import com.pablo.yumemi.ui.threads.ThreadListViewModel

object AppViewModelProvider {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val c = yumemiApplication().container
            ThreadListViewModel(c.repository, c.turnRunner, c.settings, c.networkMonitor)
        }
        initializer {
            val c = yumemiApplication().container
            ChatViewModel(createSavedStateHandle(), c.repository, c.turnRunner, c.settings, c.networkMonitor)
        }
        initializer {
            val c = yumemiApplication().container
            SettingsViewModel(c.client, c.settings, c.updater)
        }
        initializer {
            CronViewModel(yumemiApplication().container.cronRepository)
        }
        initializer {
            CronResultsViewModel(yumemiApplication().container.cronRepository)
        }
        initializer {
            val c = yumemiApplication().container
            FeedsViewModel(c.feedRepository, c.cronRepository)
        }
        initializer {
            val c = yumemiApplication().container
            FeedDetailViewModel(createSavedStateHandle(), c.feedRepository, c.cronRepository)
        }
    }
}

fun CreationExtras.yumemiApplication(): YumemiApplication =
    this[AndroidViewModelFactory.APPLICATION_KEY] as YumemiApplication
