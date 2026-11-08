# MausBot 本地版（安卓客户端）

把 [OpenMausBot](https://github.com/milind-soni/OpenMausBot) 这个 PC 多智能体聊天客户端，
搬到安卓手机上：装一个 APK，点开跟着引导点几下「允许」就能用，数据只留在本机，不依赖电脑。

> **非官方项目**。上游 OpenMausBot 为 Apache-2.0（其 `enterprise/` 目录另有许可，
> 本项目不打包、不引用任何 enterprise 代码）。本仓库只提供安卓外壳与适配补丁，
> 不含上游源码副本；OpenMausBot 本体由用户手机在 Termux 里通过官方 npm 包安装。

## v2.0：给人用的，不是给调试的

这一版重做了**首启权限引导**，借鉴 AutoGLM / DSH 等 AI 手机助手的范式：

- **产品级分步向导**：4 步清单，每步一个状态灯 + 一句人话 + 一个按钮；
  从系统设置页 / 授权框返回后 `onResume` **自动复检打勾**，全绿自动进入聊天。
- **主界面零代码零命令**：所有命令、路径、脚本名收进折叠的「懂技术？看这里」。
- **Shizuku 官方接入**：检测 / 连接 / 授权，授权后用系统级能力
  `grantRuntimePermission` **自动授予** `RUN_COMMAND` 权限（Shizuku 官网首页用法）。
- **分层降级**：Shizuku 自动授权 → Android 标准授权框 → 系统权限页，任何 ROM 都不会卡死。
- **一次性准备傻瓜化**：把装环境 + 补丁 + 启动做成一条自包含命令（gzip 内联全部补丁，
  不依赖任何 CDN/网盘），App 自动复制 + 自动打开 Termux，用户只需粘贴回车一次。

## 这是什么 / 不是什么

| | |
|---|---|
| ✅ 是 | 手机本机跑 OpenMausBot 的 **Node 服务端 + 自带 Web UI**，APK 是 WebView 壳 |
| ✅ 是 | 不依赖电脑、日常不需要联网（除非你自己接云端 API） |
| ❌ 不是 | 官方那个「扫码连电脑、手机当遥控器」的安卓端 |
| ❌ 不是 | 把 Node 运行时塞进 APK 的单文件方案（工程量大、无法真机自测） |

**形态说明**：服务端跑在手机 Termux 里，APK 负责显示界面并按需把服务拉起。
首次需要一次性准备（见下），之后点开图标即自动进聊天。

## 快速开始（用户视角）

1. 手机上装 **Termux**（GitHub / F-Droid 版）和 **Shizuku**，各按自己屏幕提示启动一次。
2. 装 **MausBot-本地版-debug.apk**（GitHub Actions 最新运行里下载，debug 包可直接安装）。
3. 点开图标 → 跟着屏幕 4 步点「允许」。
4. 首次可能出现一张蓝色卡片：点「复制并打开 Termux」→ 在 Termux 里长按粘贴、回车 → 回来自动进聊天。

> **关于那一次粘贴**：`allow-external-apps` 是 Termux 官方刻意设的门槛
> （源码里 termux.properties 被强制只读、所有执行入口都先卡这道检查），
> 无 root 时谁都绕不过，那些 AI 手机 App 也一样。App 已把它压到"自动复制 + 自动打开 +
> 粘贴一次"，**做完永久全自动**。Shizuku 为 root 模式时通常可完全免粘贴。

不懂技术直接看 [`docs/使用说明.md`](docs/使用说明.md)。

## 技术要点

### APK 壳（`app/`）

纯 Java + Android framework 原生 API，第三方依赖仅 Shizuku 相关三件
（`dev.rikka.shizuku:api`、`provider`，以及 `hiddenapibypass`），均为纯 Java、已验证可解析。

- `MainActivity`：WebView 加载 `http://127.0.0.1:8799`；引导页状态机 + 全套权限桥接
- `ShizukuKit`：封装 `pingBinder` / `checkSelfPermission` / `requestPermission` /
  `isRoot` / `grantRuntimePermission`，所有失败静默降级，绝不阻塞主流程
- `guide.html`：内置中文分步引导页（状态灯、进度、一键跳转、自动复检打勾、分层降级）
- `bootstrap.sh`（assets + termux/ 各一份）：一次性自举脚本，gzip 内联全部补丁文件
- 权限路径：Shizuku 自动授权 → `requestPermissions` 系统框 → 系统权限页兜底
- 电池优化白名单、Termux 未安装引导、系统框被 ROM 吞掉的兜底提示
- 补齐 WebView 能力：文件选择、下载（含 blob/data 导出）、音视频权限、JS 弹窗、返回键
- `network_security_config.xml` 仅放行 `127.0.0.1` / `localhost`；外链交给系统浏览器

### 核心补丁（`termux/omb-shim.cjs`）

Android 全局禁止 `link(2)` 硬链接，而 OpenMausBot 用它做数据目录租约，
导致 `serve` 启动即崩 `EACCES: permission denied, link`。
补丁通过 `NODE_OPTIONS=--require` 注入，用 `copyFile(..., COPYFILE_EXCL)`
回退并调用 `syncBuiltinESMExports()` 覆盖 ESM named import。

**已在 Node 24 + openmausbot 0.1.148 上完成端到端对照实测**：
无补丁时服务退出、报 `EACCES: link`；带补丁时 `/api/health` 正常返回；正常 Linux 无回归。
另有 8 项单元自测全通过。`patch-dist.mjs` 为文本补丁第二道保险（按 import 绑定名精确替换）。

### 出包（`.github/workflows/build-android.yml`）

`ubuntu-latest` + JDK 17 + Android SDK 34 + `./gradlew assembleDebug`，
产物经 `aapt` / `apksigner` 校验后上传 Actions Artifacts（90 天）。

## 能力保留与砍掉

| 能力 | 安卓版处置 |
|---|---|
| 多 bot 通讯录、独立人设 / 模型 / 记忆 | ✅ 保留（服务端原生能力） |
| 群聊 / rooms / bot 互相派活 | ✅ 保留 |
| 自定义模型（本地 3B/4B + 云端多实例） | ✅ 通过 `openai-compat` 接，见配置模板 |
| routines 定时任务 / webhooks | ⚠️ 保留但受 Doze 限制，锁屏几小时后不准点 |
| 语音朗读 / 语音条 | ⚠️ 需把本地 TTS 包成 `/v1/audio/speech` |
| 审批卡 Allow/Deny、MCP 绘图 | ⚠️ 上游 README 写「API connections currently support chat only」，可能退化为纯聊天 |
| 控制 Windows/Mac 桌面、本地 VM / 云电脑 | ❌ 砍 |

⚠️ **落差如实说**：小模型若不支持 tool calling，审批卡 / MCP 绘图就用不上。
另外这是 WebView 壳 + 本地服务，不是零等待瞬时启动，首次含一次性准备过程。

## 验收

沙盒内能验证的（编译、补丁端到端、引导页状态机渲染、桥方法一致性、APK 签名/Manifest）
都已真跑并打勾；**真机项目需装完 APK 后确认，不代为宣称通过**。
详见 [`docs/验收清单.md`](docs/验收清单.md)。

## 安全

- 仓库里**没有任何真实密钥**；云端 API Key 只在手机本地 `config.json`（0600）填写
- 所有本地服务只绑 `127.0.0.1`；明文 HTTP 仅限本机回环
- Shizuku 能力仅用于授予本 App 权限，不做模拟点击 / 读屏等越界操作；未授权时不影响使用
- 不修改官方仓库

## 许可

上游 OpenMausBot © milind-soni，Apache-2.0。本仓库壳与补丁同为 Apache-2.0，
见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。Shizuku API 为 Apache-2.0 / MIT，见其仓库。
