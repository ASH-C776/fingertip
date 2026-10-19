# 指间 Android ProGuard / R8 规则（release 构建已启用 minify + shrink）
#
# 原则：先保证能跑通。Compose BOM 自带 consumer-rules 处理 Compose / 序列化，
# 此处仅补齐最关键的包级 keep 与第三方库告警抑制；若 minify 后出现崩溃风险，
# 宁可先放宽 keep 也不阻塞构建。

# 保留主入口 Activity（及其成员），避免反射 / 组件注入被误删导致启动崩溃
-keep class com.fingertip.app.MainActivity { *; }

# 保留注解与签名信息（Compose / 泛型擦除 / 序列化相关，误删易崩）
-keepattributes *Annotation*,Signature

# 抑制 OkHttp / Okio 相关告警（不影响功能，仅消除编译期噪音）
-dontwarn okhttp3.**
-dontwarn okio.**
