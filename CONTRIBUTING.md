# 贡献指南

欢迎为 **SrunX** 提交 Issue 或 Pull Request。

## 开发环境

- **JDK**：17 及以上（CI 使用 Temurin JDK 21）
- **Android SDK**：`compileSdk 37.1`（`platforms;android-37.1`）与 `build-tools;37.0.0`

## 本地测试与构建

```bash
# 运行 JVM 单元测试
./gradlew :app:testDebugUnitTest

# 编译 Debug 与 R8 压缩 Release APK
./gradlew :app:assembleDebug :app:assembleRelease
```

## 项目约束

- **极简与零冗余依赖**：保持纯原生 `HttpURLConnection`（通过 `Network.openConnection` 绑定 Wi-Fi）与 Android Keystore 加密，不引入第三方网络库或额外统计组件。
- **最小权限原则**：不引入定位、存储、悬浮窗等非必要权限。
- **双进程隔离**：界面（主进程）与后台保活（`:bg` 进程）通过原生 `Messenger` 通信，新增状态或配置字段需同步维护 `IpcCodec` 与 `BgIpcProtocol`。
- **测试覆盖**：修改加密、协议解析或跨进程序列化逻辑时，请补充或更新 `app/src/test/` 下对应的单元测试。

## 隐私与脱敏要求

- 严禁在代码、注释、测试用例、Commit 记录或 Issue 截图中包含真实学号、密码、MAC 地址、个人内网 IP 或特定学校硬编码配置。
- 单元测试中涉及的 IP、挑战码与账号请统一使用虚构数据（如 RFC 5737 文档网段 `192.0.2.0/24`、`198.51.100.0/24`、`203.0.113.0/24`）。

## 提交前检查

1. 确认 `./gradlew :app:testDebugUnitTest` 全部通过。
2. 确认未包含 `local.properties`、`*.keystore`、`*.jks` 或构建产物。
3. 使用简洁明了的中文或英文 Commit 描述（例如 `fix: ...`、`feat: ...`、`docs: ...`）。
