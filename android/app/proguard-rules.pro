# Zyll 相册 release 包的 R8 规则。大部分库（Billing、WorkManager、Media3、AppAuth、Coil、kotlinx.serialization、Compose）
# 自带规则；这里只补它们没带的。

# 端到端加密的密钥包用 kotlinx.serialization 读写：保留生成的序列化器（库自带规则之外再兜一层）
-keepclassmembers @kotlinx.serialization.Serializable class app.zyll.** {
    static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.zyll.**$$serializer { *; }

# Bouncy Castle：只直接调用 Argon2 等算法类，不注册 Provider；有些类引用了安卓上没有的 JDK 类，忽略警告
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# uCrop（修图裁剪）：界面通过 Intent 启动，内部有少量按名字查找的视图
-keep class com.yalantis.ucrop.** { *; }
-dontwarn com.yalantis.ucrop.**

# GPUImage：滤镜通过 GLSL 字符串工作，不需要保留；它引用的相机类在 R8 下可能报警告
-dontwarn jp.co.cyberagent.android.gpuimage.**

# 崩溃日志里保留行号（上传 mapping 文件到 Play Console 后可以还原）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
