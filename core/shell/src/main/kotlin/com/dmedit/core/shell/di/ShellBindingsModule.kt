package com.dmedit.core.shell.di

import com.dmedit.core.shell.AndroidRootBackend
import com.dmedit.core.shell.FileSystemBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ShellBindingsModule {
    @Binds
    @Singleton
    abstract fun bindFileSystemBackend(impl: AndroidRootBackend): FileSystemBackend
}
