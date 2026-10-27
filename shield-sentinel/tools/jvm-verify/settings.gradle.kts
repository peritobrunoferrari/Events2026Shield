// Build auxiliar JVM: permite compilar e testar o código do Shield Sentinel em
// ambientes SEM Android SDK (CI restrito, contêineres sem acesso a dl.google.com).
//  - :core-logic-jvm  → compila e roda os testes do módulo core-logic.
//  - :android-typecheck → compila os módulos Android (exceto :app) contra o jar
//    android-all do Robolectric (framework Android 17 / API 37) apenas para
//    checagem de tipos. NÃO gera APK e NÃO substitui o build AGP.
pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "shield-sentinel-jvm-verify"
include(":core-logic-jvm")
include(":android-typecheck")
include(":admin-cli")
