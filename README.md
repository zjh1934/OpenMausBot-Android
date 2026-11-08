# MausBot 本地版（安卓客户端）

把 [OpenMausBot](https://github.com/milind-soni/OpenMausBot) 这个 PC 多智能体聊天客户端，
搬到安卓手机上：装一个 APK，点开就能用，数据只留在本机，不依赖电脑。

> **非官方项目**。上游 OpenMausBot 为 Apache-2.0（其 `enterprise/` 目录另有许可，
> 本项目不打包、不引用任何 enterprise 代码）。本仓库只提供安卓外壳与适配补丁，
> 不含上游源码副本；OpenMausBot 本体由用户手机在 Termux 里通过官方 npm 包安装。

## 这是什么 / 不是什么

| | |
|---|---|
| ✅ 是 | 手机本机跑 OpenMausBot 的 **Node 服务端 + 自带 Web UI**，APK 是一个 WebView 壳，点图标即开 |
| ✅ 是 | 不依赖电脑、不需要联网（除非你自己接云端 API） |
| ❌ 不是 | 官方那个「扫码连电脑、手机当遥控器」的安卓端 |
| ❌ 不是 | 从零重写一个功能相似的全新 App |
| ❌ 不是 | 把 Node 运行时塞进 APK（安卓上跑不动原生二进制，也不必要） |

**形态说明（重要）**：服务端跑在你手机的 Termux 里，APK 负责显示界面并在需要时
一键把服务拉起来。所以「点开即用」= 点图标 →（首次自动拉起服务，等几秒）→ 出聊天界面。
Termux 是一次性依赖，日常只需要点开 APK。

## 快速开始

1. **手机准备**：GitHub / F-Droid 版 Termux（不要用已停更的 Google Play 版）。
2. **跑一次安装**：把仓库 `termux/` 目录里的 5 个文件放到手机，在 Termux 里
   `bash install.sh`。全程自动，会装 Node 24、openmausbot、安卓补丁并自检。
   详见 [`termux/README.md`](termux/README.md)。
3. **装 APK**：从本仓库 GitHub Actions 的最新一次运行里下载
   `MausBot-本地版-debug.apk`（debug 包自带调试签名，可直接安装）。
4. **点开图标**：首次会自动拉起服务，几秒后进入聊天界面。

> 第一次如果没自动起来，按引导页「启动不了？」里的一次性设置做一遍即可，
> 主要是允许本 App 调用 Termux：
> `echo 'allow-external-apps = true' >> ~/.termux/termux.properties`，
> 然后彻底关掉 Termux 再重开。

不懂技术的话直接看 [`docs/使用说明.md`](docs/使用说明.md)。

## 技术要点

### APK 壳（`app/`）

纯 Java + Android framework 原生 API，**零第三方依赖**，
最大化 GitHub Actions 编译成功率。

- `MainActivity`：WebView 加载 `http://127.0.0.1:8799`，先探测服务是否可达
- 服务没起来时显示**内置中文引导页**（不是白屏），提供「一键启动」「重新检测」「复制启动命令」「改端口」
- 通过 Termux `RUN_COMMAND` Intent 在后台拉起 `~/.openmausbot/start-omb.sh`
- 启动即自动尝试拉起服务并轮询 health，通了自动进入界面，用户无需输入任何命令
- 补齐 WebView 在安卓上需要的能力：文件选择、下载（含 blob/data 导出）、
  麦克风/相机权限、JS 弹窗、混合内容、返回键、进度条
- `network_security_config.xml` 默认禁止明文，**仅放行 `127.0.0.1` / `localhost`**；
  外链一律交给系统浏览器，不在壳内跳转
- 不使用前台服务与常驻通知（保活由 Termux 侧 wake lock + 电池白名单负责）

### 核心补丁（`termux/omb-shim.cjs`）

Android 全局禁止 `link(2)` 硬链接，而 OpenMausBot 用它做数据目录租约，
导致 `serve` 启动即崩 `EACCES: permission denied, link`。
补丁通过 `NODE_OPTIONS=--require` 注入，用 `copyFile(..., COPYFILE_EXCL)`
回退并调用 `syncBuiltinESMExports()` 覆盖 ESM named import。

**已在 Node 24 + openmausbot 0.1.148 上完成端到端对照实测**：
无补丁时服务退出，带补丁时 `/api/health` 正常返回；正常 Linux 无回归。
另有 8 项单元自测全部通过。细节与上游升级后的处理见 [`termux/README.md`](termux/README.md)。

### 出包（`.github/workflows/build-android.yml`）

`ubuntu-latest` + JDK 17 + Android SDK 34 + `./gradlew assembleDebug`，
产物经 `aapt` / `apksigner` 校验后上传 Actions Artifacts（保留 90 天），
也可手动触发并勾选创建 Release。

## 能力保留与砍掉

| 能力 | 安卓版处置 |
|---|---|
| 多 bot 通讯录、独立人设 / 模型 / 记忆 | ✅ 保留（服务端原生能力） |
| 群聊 / rooms / bot 互相派活 | ✅ 保留 |
| 自定义模型（本地 3B/4B + 云端多实例） | ✅ 通过 `openai-compat` 接，见配置模板 |
| routines 定时任务 / webhooks | ⚠️ 保留但受 Doze 限制，锁屏几小时后不准点 |
| 语音朗读 / 语音条 | ⚠️ 需把本地 TTS 包成 `/v1/audio/speech` |
| 语音输入（桌面原生听写） | ❌ 砍（官方标注桌面专属） |
| 审批卡 Allow/Deny、MCP 绘图 | ⚠️ 依赖工具调用；上游 README 写「API connections currently support chat only」，可能退化为纯聊天 |
| 控制 Windows/Mac 宿主桌面、本地 VM / 云电脑 | ❌ 砍 |
| claude / codex / grok 桌面 CLI 引擎 | ❌ 砍（需桌面登录态，bionic 跑不了原生二进制） |
| Composio 云连接器 | ⚠️ 未接 |

⚠️ **落差须知**：小模型若不支持 tool calling，审批卡 / MCP 绘图就用不上，
App 会退化成「多 bot 聊天壳」。这一点没有默默写成"完整保留"。

## 验收清单（沙盒能测 / 只能真机测）

沙盒内能验证的都已在 CI 与自测中完成并打勾；**真机项目需你装完 APK 后确认**，
本仓库不代为宣称通过。详见 [`docs/验收清单.md`](docs/验收清单.md)。

## 安全

- 仓库里**没有任何真实密钥**；云端 API Key 只在手机本地 `config.json`（0600）填写
- 所有本地服务只绑 `127.0.0.1`，不绑 `0.0.0.0`、不映射公网
- 明文 HTTP 仅限手机本机回环，APK 侧有 network security config 约束
- 不修改官方仓库；全部改动都在本仓库

## 许可

上游 OpenMausBot © milind-soni，Apache-2.0。本仓库的壳与补丁同为 Apache-2.0，
见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。
