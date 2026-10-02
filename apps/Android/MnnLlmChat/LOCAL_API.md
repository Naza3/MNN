# MNN Chat API (local-only fork)

This fork uses package `io.github.naza3.mnnchat`, display name **MNN Chat API**, and version
`0.8.3-localapi.1` (831). Its Android/JNI namespace stays unchanged. It installs beside the
upstream app, does not migrate/read its private data, and does not offer upstream APK updates.
Download/import models within this fork using the existing model manager.

## 中文快速使用

- 本分支名称为 **MNN Chat API**，包名 `io.github.naza3.mnnchat`，可与官方版共存。
  不读取或迁移官方版私有数据；请在本分支内下载或导入模型。
- 打开已下载的文本模型，选择 **API 设置**，进入控制页后点 **启动本机 API**。
  原聊天页面关闭，已保存的聊天记录保留。看到“可用”后，将接口地址和手动复制的密钥填入本机调用应用。
- 默认地址是 `http://127.0.0.1:8080/v1`。必须带 `Authorization: Bearer 密钥`。
  模型名使用 `/v1/models` 返回的 ID，也可填 `mnn-local`。不开放局域网访问。
- 退出聊天或控制页不会停止已启动的前台服务；请用通知中的停止按钮，或回控制页点停止。
  原生加载、预填充及部分基准测试不能立即抢占，显示“停止中”时需要等待安全清理完成。
- 修改密钥或端口前必须停止并等待清理完成。重新生成密钥后，客户端需换新密钥，再手动启动服务。
- 首版只提供纯文本 `/v1/chat/completions`（JSON/SSE）、`/v1/models` 和队列状态。
  最多一项推理、一项排队；超额返回 429。不支持工具调用、多模态、文件读取或 `/v1/messages`。
- Android 的省电、低内存或强制停止仍可能结束进程；不会自动后台重启或重放请求。
  真机安装、原生推理及息屏运行仍需设备验收，JVM 测试通过不代表已做过手机实测。

## Start and connect

1. Open a downloaded text model in Chat, then choose **API settings**. This closes that Chat
   screen and opens the local API controls. Its saved conversation is retained.
2. Press **Start local API**. A foreground notification shows Starting, Ready, or Stopping and
   includes a **Stop** action. Merely enabling an old preference or opening Chat never starts it.
3. Press **Copy API key** only when you want to give a trusted local application access.
   Use base URL `http://127.0.0.1:8080/v1` (or the configured port), the copied Bearer key,
   and the model identifier returned by `/v1/models`. `mnn-local` is also accepted as an alias.
4. Leaving or destroying either Activity does not stop a started API service. Stop it using
   the notification or control page. When it has fully stopped, return to the model list and
   open Chat again for a fresh UI runtime.

The service binds **only `127.0.0.1`**, ports 1024–65535. All registered endpoints require
`Authorization: Bearer YOUR_KEY`. Authentication cannot be disabled; there is no LAN mode,
CORS wildcard, token-in-URL test page, browser launch with credentials, or exported service.
The key is generated using 32 bytes of `SecureRandom`, stored privately, never displayed in
notifications or logs, and copied only by explicit action. App backup/device transfer are disabled. Native configuration telemetry is suppressed throughout
API startup, execution and cleanup, and the default fork build explicitly disables Firebase access.
To rotate the key: **Stop → wait until stopped → Generate new API key → Start**. Rotation and
port changes are rejected while any API startup, inference, or cleanup is still in progress.

Example request (replace the placeholder locally; never put a real key in a shared file):

```sh
curl http://127.0.0.1:8080/v1/chat/completions \
  -H 'Authorization: Bearer YOUR_KEY' -H 'Content-Type: application/json' \
  -d '{"model":"mnn-local","messages":[{"role":"user","content":"Hello"}],"max_tokens":128,"stream":false}'
```

## Scope and limits

- `GET /v1/models`: exactly the selected model
- `GET /v1/queue/status`: active/waiting counts and readiness
- `POST /v1/chat/completions`: text messages; JSON or OpenAI-style SSE, ending with `[DONE]`
- 1 active native request + 1 waiting, FIFO. Excess requests get `429`; unavailable/stopping
  service gets `503` where a listener still exists. A stopped listener refuses connections
- Default `max_tokens=512`, accepted range 1–2048, actually applied to the native session
- Maximum request body 64 KiB; 1–64 messages, total text at most 32768 characters; generated
  response at most 1,048,576 UTF-16 code units. These are safety bounds, not model context promises
- Supported roles: system/user/assistant. Unsupported generation parameters, tools/function
  calling, media content and MNN media markup are rejected. No caller-selected files or URLs
  are fetched. Media tags are checked both per message and after concatenation
- `/v1/messages`, old media conversion, queue clearing and the HTML test page are not registered
  in this first local API implementation. This is a limited compatible API, not all of OpenAI
- Each request resets the private API session and uses only its explicit messages. UI history,
  previous caller history, live UI parameter changes, audio output, and user-custom config are
  not inherited. Token usage is read from native metrics, never inferred from character counts
- No new models, QNN/NPU support, backend capabilities, or inference quality guarantees are claimed

## Ownership, cancellation, and Android behavior

A process-wide epoch/lease permits only one Chat/API native runtime, including benchmark,
Diffusion and Sana entry points. API startup blocks new UI operations, marks the old owner
cancelled, waits for existing native work, releases it, then loads a separate API session.
Old Activity callbacks cannot release a new owner's session. A cleanup error keeps the runtime
reserved; loading a second instance is forbidden until the process is restarted.

Ktor is kept at 3.1.3. HTTP/1.1 uses the actual Netty channel `closeFuture`, including non-streaming
requests. Client disconnect cancels its queued ticket or requests cooperative stop of active JNI
inference. SSE has a bounded 64-chunk channel; a slow/failed consumer cancels inference rather
than silently dropping tokens. The independent FIFO worker retains its slot until native returns.
HTTP/2 is disabled in this first implementation.

Native load/prefill and some benchmark/diffusion operations have no safe immediate interruption
hook. **Stopping can remain visible until these operations return.** Cleanup never frees native
objects concurrently, never waits on the main thread, and never cancels its own cleanup scope
before drain completes. The service uses `specialUse` foreground classification on Android 14+
and normal foreground mode on earlier supported versions, not an unlimited `dataSync` timer.

This is not an always-on daemon. Android/OEM battery policies, low memory, force-stop, reboot,
or process death can stop it. It is `START_NOT_STICKY`: no boot start, silent restart, request
replay, or guarantee of screen-off survival. Restart explicitly in the app. Loopback is accessible
to other apps on the phone, so the key must be treated as a password. A rooted/device-admin
compromise is outside this protection model.

## Verification

App tests under `com.alibaba.mnnllm.api.openai` cover the owner gate, the production lifecycle
coordinator core with a fake native runtime, bounded FIFO/cancellation/prefill cleanup, parser
limits/auth/routes, real Netty TCP close for streaming and non-streaming calls, and Robolectric
manifest/Activity control lifecycle. Tests use ephemeral fake keys and no model downloads.
Run `:app:testStandardDebugUnitTest --tests 'com.alibaba.mnnllm.api.openai.*'` in the configured
Android build. The full CI also builds the current pinned MNN sources and the APK.

**Device acceptance remains required:** install this fork beside the official app, download a
small supported text model, test actual text/SSE and token limits, background the Activity,
turn the screen off, reconnect a client, disconnect during prefill/decode, Stop during loading,
repeat start/stop, exercise failed bind, and return to Chat. No device or native-inference result
is implied by fake-backend JVM/Robolectric tests.
