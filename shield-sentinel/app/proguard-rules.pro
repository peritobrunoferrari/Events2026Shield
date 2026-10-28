# Shield Sentinel — regras R8 (release).
# Componentes declarados no manifesto são mantidos automaticamente pelo AGP.
# Mantém nomes de enums serializados em preferências/auditoria (valueOf/name).
-keepclassmembers enum com.shieldpericias.sentinel.** { *; }
# Classes de protocolo e modelos referenciados por nome em logs/auditoria.
-keep class com.shieldpericias.sentinel.core.remote.** { *; }
# Sem obfuscação de nomes de classes do próprio app: facilita auditoria do APK
# (o encolhimento de código/recursos continua ativo).
-keepnames class com.shieldpericias.sentinel.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
