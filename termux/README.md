# Termux 侧说明（手机本机服务）

这四份文件是"手机里真正跑起来的那一半"。APK 只是一个界面壳，
聊天界面、bot、记忆、定时任务都由这里启动的 `openmausbot serve` 提供。

| 文件 | 作用 |
|---|---|
| `install.sh` | **只跑一次**。全量升级 Termux、装 Node 24 与 openmausbot、写入补丁、开启允许外部调用、生成配置模板、自检启动。 |
| `start-omb.sh` | 每次启动服务用。注入 link(2) 兼容层后拉起 serve，可重复执行。App 的「一键启动」调用的就是它。 |
| `omb-shim.cjs` | **核心补丁**。绕过 Android 禁止硬链接的限制，见下方。 |
| `patch-dist.mjs` | 可选的第二道保险，仅在 shim 失效时才需要手动跑。 |
| `config.template.json` | 本地小模型 / 云端 API 的配置模板。 |

## 为什么必须打补丁

Android 在 app 私有存储里全局禁止 `link(2)` 硬链接（返回 `EACCES`，errno 13）。
而 OpenMausBot 用 `linkSync(candidate, lease)` 靠 `EEXIST` 判并发来做
「数据目录租约」，启动第一步就会撞上，报：

```
EACCES: permission denied, link
OpenMausBot could not start.
```

换 `--data-dir` 无效，这是平台限制。

`omb-shim.cjs` 通过 `NODE_OPTIONS="--require ..."` 在进程最早期注入，做两件事：

1. 把 `fs.linkSync` / `fs.promises.link` 换成"先试原生 link，遇到
   EACCES/EPERM/ENOSYS 就用 `copyFile(..., COPYFILE_EXCL)` 回退"的实现；
2. 调用 Node 官方 `syncBuiltinESMExports()`，让打包产物里
   `import { linkSync } from "node:fs"` 这种 ESM named import 也拿到替换后的函数。

**为什么回退用 copyFile 而不是 rename**：上游的模式是
「写 candidate → 发布到目标 → `finally` 里删 candidate」。
`rename` 会移走 candidate，导致后续 `unlink(candidate)` 报 ENOENT；
`copyFile` + `COPYFILE_EXCL` 在目标已存在时同样抛 `EEXIST`（并发判定不变），
且保留 candidate，与 `link(2)` 对这套用法完全等价。

### 实测结论（2026-11-08，Node 24 + openmausbot 0.1.148）

| 场景 | 结果 |
|---|---|
| 模拟 Android 禁止 link，**不带**补丁 | 服务退出，`EACCES: permission denied, link` |
| 模拟 Android 禁止 link，**带** omb-shim | `/api/health` 正常返回 `{"app":"openmausbot",...}` |
| 正常 Linux + omb-shim | 正常启动，无回归（原生硬链接照常工作） |

另有 8 项单元自测覆盖 CJS、ESM named/default import、同步/异步、
EEXIST 并发判定、源文件保留，全部通过。

### 上游每天发版怎么办

补丁**不改上游源码**，靠运行时注入，因此对上游发版天然免疫：
`npm install -g openmausbot` 升级后，重新跑一次 `install.sh` 即可
（它只会覆盖补丁文件，不动你已有的 `config.json` 和数据）。

只有当你发现服务仍然报 `EACCES ... link` 时，才需要手动跑第二道保险：

```bash
DIST="$(npm root -g)/openmausbot/dist-server"
node ~/.openmausbot/patch-dist.mjs "$DIST" --check   # 先看会改哪些
node ~/.openmausbot/patch-dist.mjs "$DIST"           # 实际应用
bash ~/.openmausbot/start-omb.sh --once              # 重启服务
```

该脚本按每个文件真实的 ESM import 绑定名精确替换（打包产物里
`linkSync` 有 `linkSync2`~`linkSync6` 等别名），不会误伤
`readlinkSync` / `unlinkSync`，且幂等、会留 `.omb-orig` 备份。

## 本地模型怎么接

PocketPal AI 这类 App 是**客户端**，不对外提供 API。稳妥做法是用同一个
GGUF 文件在 Termux 里起一个 OpenAI 兼容端点：

```bash
pkg install -y llama-cpp
llama-server -m /路径/模型.gguf --host 127.0.0.1 --port 8080
```

然后 `~/.openmausbot/config.json` 里把 `local-4b` 的 `model` 填成
`curl http://127.0.0.1:8080/v1/models` 查到的 id。

> 注意：PocketPal 与 llama-server **二选一加载同一个模型**，别同时载两份吃内存。
> 已实测手机上的 8080 / 11434 / 1234 / 4123 端口当时都没有服务，
> 所以本地模型这一步必须显式起服务，不会自动有。

改完 `config.json` 要**重启服务**（在 App 里点「重新检测」前先杀掉旧进程，
或重启 Termux）。文件权限保持 `0600`。

## 本地 TTS / 绘图

- **语音朗读**：官方语音引擎支持 Chatterbox (local)，通过 OpenAI 兼容的
  `POST /v1/audio/speech` 连本机服务，无需 API key。把你的本地 TTS
  包一层这个接口，再在 App 的 Settings 里填 `http://127.0.0.1:<端口>/v1`
  和 model id 即可。语音输入（听写）是桌面专属，安卓版砍掉。
- **聊天里出图**：Settings → Plugins → MCP servers → Add server，
  把你本地绘图模型包一个极小的 MCP 服务，填
  `http://127.0.0.1:<端口>/mcp`，保存后 Test 握手 → 启用，无需重启。

> 都需要你先确认这些本地服务的实际名字、端口和接口形态。
> 8080/11434/1234/4123 实测当时全空，不会有现成服务可用。

⚠️ **能力落差要如实说明**：上游 README 写明
「API connections currently support chat only」。走 `openai-compat`
接进来的模型当前以纯聊天为主，工具调用可能不可用，
因此审批卡 / MCP 绘图可能用不上。请以 App 内实际表现为准，
不要假设桌面端能力 100% 保留。

## 后台保活（HyperOS / 安卓 16）

- 锁住 Termux 的最近任务卡片；省电策略设「无限制」；允许自启动。
- 启动脚本已自动 `termux-wake-lock`，长时间使用建议插电。
- **Doze 限制**：灭屏静止后 wake lock 会被忽略、定时任务被推迟到维护窗口。
  所以桌面版 `routines` 定时任务在锁屏几小时后**不能指望准点**。
- Shizuku 可用于更稳的 ADB 级启动与电池白名单，但不是必需项。

## 安全边界

- 所有服务只绑 `127.0.0.1`，不绑 `0.0.0.0`、不映射公网端口。
- APK 的 network security config 默认禁明文，只对 `127.0.0.1` / `localhost` 放行。
- 云端 API Key 只写在手机本地 `config.json`（0600），仓库里只有占位符。
