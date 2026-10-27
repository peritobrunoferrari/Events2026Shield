// Shield Sentinel — build raiz.
// AGP 9.x já traz suporte Kotlin embutido (built-in Kotlin); o plugin
// org.jetbrains.kotlin.android NÃO deve ser aplicado nos módulos Android.
// O plugin kotlin-jvm é usado apenas no módulo :core-logic (JVM puro).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
