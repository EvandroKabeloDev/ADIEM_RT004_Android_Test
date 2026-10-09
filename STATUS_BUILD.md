# Status desta entrega

- Projeto Android nativo criado em Kotlin.
- Manifesto e recursos XML validados.
- Launcher Gradle e workflow GitHub Actions incluídos.
- Não foi possível compilar o APK neste ambiente: não há Android SDK/Gradle instalados e a rede do ambiente não consegue resolver `services.gradle.org`. Faça o build no Android Studio ou use o workflow GitHub Actions; o APK de teste será `app/build/outputs/apk/debug/app-debug.apk`.
- Nenhum teste físico foi executado contra o LapWiz; a primeira instalação deve ser tratada como experimento, verificando logs BLE e MQTT.
