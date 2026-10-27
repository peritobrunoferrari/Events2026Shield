// CLI administrativa: gera par de chaves EC P-256 e assina comandos remotos
// para teste do protocolo (módulo remote-wipe). Uso exclusivo do administrador.
plugins { kotlin("jvm"); application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { implementation(project(":core-logic-jvm")) }
application { mainClass.set("com.shieldpericias.sentinel.tools.AdminCli") }
