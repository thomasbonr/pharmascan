package com.tom.pharmascan

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client HTTP partagé par toute l'application.
 *
 * OkHttp recommande un client unique pour bénéficier du pool de connexions
 * et du cache DNS. Les timeouts sont ceux du cas le plus exigeant (API
 * Médicaments FR : 8 s de lecture).
 */
val SharedHttpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()
