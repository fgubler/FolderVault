package ch.abwesend.foldervault.domain.util

import org.koin.mp.KoinPlatform

inline fun <reified T : Any> injectAnywhere(): Lazy<T> = KoinPlatform.getKoin().inject()
