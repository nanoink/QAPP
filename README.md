# QAPP

Aplicativo Android nativo em Kotlin com Jetpack Compose.

## Requisitos
- Android Studio Iguana ou superior
- JDK 17
- SDK 34 (compile/target)

## Comandos úteis
- Build debug: `./gradlew assembleDebug`
- Testes locais: `./gradlew test`
- Testes instrumentados (emulador/dispositivo): `./gradlew connectedAndroidTest`
- Formatação/Ktlint via Spotless: `./gradlew spotlessApply` (verificação: `./gradlew spotlessCheck`)

## Estrutura inicial
- `app/src/main/java/com/qapp/app/MainActivity.kt`: ponto de entrada da Activity.
- `app/src/main/java/com/qapp/app/QAppApp.kt`: host de navegação Compose.
- `app/src/main/java/com/qapp/app/ui/home/HomeScreen.kt`: tela inicial.
- `app/src/main/java/com/qapp/app/ui/theme/*`: tema Material 3 para Compose.
