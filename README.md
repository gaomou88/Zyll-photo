# Zyll-photo

Zyll 相册 Android 端的加密部分：密文格式、测试向量和 Kotlin 实现。App 的其他代码不在这里。

网页版的源码在 [Zyll-photo-web](https://github.com/gaomou88/Zyll-photo-web)，那边用 TypeScript 实现了同一套格式，
两边跑的是同一份测试向量，可以对照着看。

- `spec/`：格式说明（容器、密钥包、共享相册和外链的钥匙）
- `spec/test-vectors/`：测试向量
- `kotlin/`：Kotlin 实现，纯 JVM，不需要 Android SDK

```bash
cd kotlin
./gradlew test   # JDK 17+
./gradlew run    # 重新生成测试向量
```

App 连哪些地址、网盘上存了什么，写在 [PRIVACY.md](PRIVACY.md)。许可见 [LICENSE](LICENSE)：可以看、可以编译验证，不能拿去用。

## English

The encryption part of the Zyll Photo Android app: format spec, test vectors and the Kotlin implementation.
The rest of the app is not here. The web app ([Zyll-photo-web](https://github.com/gaomou88/Zyll-photo-web))
implements the same format in TypeScript against the same vectors. Run `cd kotlin && ./gradlew test`.
Network and storage details are in PRIVACY.md. See LICENSE: you may read and verify, not reuse.
