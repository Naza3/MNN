# MNN Chat API (local-only fork)

This fork uses package `io.github.naza3.mnnchat`, display name **MNN Chat API**, and version
`0.8.3-localapi.4` (834). Its Android/JNI namespace stays unchanged. It installs beside the
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

## API 直接回答模式（834 / localapi.4）

- 本机 API 会话现在独立设置 `jinja.context.enable_thinking=false`，不继承聊天页的思考开关。
  设置在原生模型加载完成后应用，避免模型加载期间的 `context.json` 覆盖它。
- 这是模型会话配置，不是只隐藏生成的思考文本，也不依靠提示词中的“不要思考”。
  不修改模型文件或聊天 `custom_config`；普通聊天仍使用自己的设置。
- 安装此版本后先停止旧 API、等待清理完成，再重新启动；后续请求的 reset 不撤销该会话设置。
  地址、API Key、模型名称、`max_tokens` 与普通／流式协议保持原有规则。
- 生效仍取决于模型模板支持 `enable_thinking`。不声称任意模板都能关闭思考；不能把编译通过当成真实模型验证。
  不通过删除返回文本中的思考段或伪造 `finish_reason=stop` 掩盖超限。
- 复测 Qwen3.5-2B-MNN：先执行只回复 OK 的连接测试，再用两条短消息做通用总结（无补充要求），
  保留 Telegram 的 2000 tokens／32000 字符预算，分别记录思考提示、实际正文与耗时。
  在同一 API 会话连续请求两次，然后 Stop/Start 后再次请求，确认 reset 和重新加载后的行为。
  当前没有连接真实手机，以上模型行为及耗时仍待验收。

公开的 [taobao-mnn/Qwen3.5-2B-MNN 配置](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/blob/35781816d7b6a9dcb273a6765ac9563401951c3c/config.json#L28)
在该固定版本中默认开启思考；其[实际导出模板](https://huggingface.co/taobao-mnn/Qwen3.5-2B-MNN/blob/35781816d7b6a9dcb273a6765ac9563401951c3c/llm_config.json#L24)
明确支持 `enable_thinking=false`，通过在输入模板中预先闭合思考段引导直接回答。
这项源码证据不代表已经核实用户手机上的模型来源、版本或实际生成行为。

## 保持模型加载（832 / localapi.2）

- **设置 → 通用 → 退出聊天后保持模型加载** 默认开启，升级时缺少这个新偏好也按开启处理。
  返回模型列表或重建聊天 Activity 会解绑旧 UI，保留同一份模型权重。833 起纯文本后台生成不随 UI 销毁而停止。
- 再次打开相同模型无需重复加载权重。新聊天使用空历史；打开历史记录使用明确选定的会话，
  重绑时清空旧 KV/native history，不会把另一会话带入。已保存的数据库记录不受卸载影响。
- **聊天菜单 → 卸载当前模型**，或 **设置 → 通用 → 卸载当前模型**，会先停止生成，等待
  原生调用安全返回，再释放模型。加载/预填充/部分图像推理无法立即抢占，不能强制 free。
- 关闭“保持模型加载”会卸载当前保留的聊天模型，后续离开聊天会释放权重。切换模型、改变模型
  配置、显式启动独立的基准/语音模型任务，也会先释放旧聊天模型，始终最多一个 native runtime。
- 本机 API 仍需要主动启动。**停止 API 就是主动卸载 API 模型**，必须完成真实清理；普通返回
  模型列表不会停止 API。API 与聊天不共享聊天历史，也不会同时加载两份模型。
- 保留权重会占用 RAM，并可能增加耗电。Android 低内存回收、强制结束或进程重启仍会卸载。
  833 起已加载的聊天模型使用同一个前台状态通知；空闲时不持有唤醒锁。不会开机自启或在进程结束后自动恢复模型。
- API 控制页按状态栏、导航栏、屏幕开孔和键盘的 WindowInsets 留出空间，旋转后重新计算；
  不使用固定像素值顶开状态栏。页内仍可滚动到所有按钮。

The retained-chat option defaults to true and preserves existing application data/preferences. Its
lifetime is the current process, not a guarantee against Android reclaiming it. One resident runtime
can have only one current UI attachment; older callbacks cannot release or mutate a newer attachment.
Configuration fingerprint changes require a safe reload. API Stop always drains and releases its own
runtime, regardless of this chat preference.

## 后台文本生成与统一状态图标（833 / localapi.3）

- 打开模型后，状态栏使用一个模型状态图标。模型已加载但空闲时为静态图标；纯文本思考/输出时，
  同一个通知切换为动画图标并显示不确定进度；生成结束回到静态已加载状态；卸载后移除。
  动画由 Android System UI 播放，不按 token 刷新通知。部分系统/OEM、息屏或 Doze 可能显示静态帧，
  此时以通知中的“正在生成/正在停止/模型已加载”文字和进度为准。
- 纯文本聊天由独立的前台服务任务执行。按 Home、返回列表或 Activity 重建不会取消这项任务；
  通知可返回对应会话或停止当前回复。旧通知的停止/卸载按钮不会作用于后来的任务或模型。
- 回复及部分停止结果由任务统一保存一次；重新打开页面只观察已有任务，不重复提交、不重置正在使用的 KV。
  保存失败会在聊天页明确提示，不能把未保存的屏幕内容当作持久记录。
- “保持模型加载”开启时，生成完成后保留权重和静态状态通知；关闭时，最后一个聊天页面离开后，
  等后台任务安全结束再卸载。空闲通知的“卸载当前模型”会释放权重并关闭通知。
- API 和聊天仍共用单一 native runtime，仍然需要用户主动启动 API。两种服务交接同一个通知身份；
  旧服务先退出前台状态，过期更新/移除不会覆盖新服务。两者复用原有 API 通知渠道，保留用户设置的
  通知重要程度和禁用状态，不创建替代渠道绕过禁用。API 执行推理时也使用忙碌图标。
- Android 13+ 首次使用会请求通知权限。拒绝或关闭通知渠道后，Android 可能不显示状态栏图标；
  页面会说明限制，可以回聊天页停止。不会自动修改通知设置或请求电池优化豁免。
- 本次保证的后台任务路径仅限纯文本模型和纯文本输入。图片/视觉、扩散、音频/Omni、语音聊天及其 TTS、页面内基准测试
  保持原有页面路径，不声称已支持后台生成。NPU 支持暂不处理。
- 模型加载/驻留和生成使用私有 `specialUse` 前台服务，无开机启动、静默重启或请求重放。
  生成时可持有最长 30 分钟的部分唤醒锁，结束/停止/异常后释放；空闲权重不持锁。
  Android/OEM 的低内存回收、强制结束和电池策略仍可能终止进程，不能保证一直后台运行。

### Background chat ownership

`ChatGenerationCoordinator` owns a single request with an immutable conversation ID, native attachment,
job ID, bounded immutable output snapshots and one database persistence path. UI observers have separate
revocable tokens. Reattaching the same job does not reload weights, clear history or submit another prompt.
Runtime transitions block new admission while cancellation, native drain and persistence finish. A failed
save is never published as saved. Foreground-only voice/custom listeners retire a completed background
attachment before taking control.

`ChatGenerationService` is `START_NOT_STICKY`. Host/generation intents require matching process-local
admission; saved intents cannot replay work after process death. Residency is published from confirmed
native load/release transitions. `ForegroundNotificationOwner` arbitrates notification ID 1001 between
Chat and API, demoting the previous service before promotion and rejecting stale removal/update calls.
Both reuse the existing `local_api_service` channel and preserve its user-controlled importance/block settings.
Notifications contain only model/status, never prompts, responses or API credentials. Animation-list
support is best-effort; state text and indeterminate progress provide the fallback.

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
Run `:app:testStandardDebugUnitTest` in the configured
Android build. The full CI also builds the current pinned MNN sources and the APK.

**Device acceptance remains required:** install this fork beside the official app, download a
small supported text model, test actual text/SSE and token limits, background the Activity,
turn the screen off, reconnect a client, disconnect during prefill/decode, Stop during loading,
repeat start/stop, exercise failed bind, and return to Chat. No device or native-inference result
is implied by fake-backend JVM/Robolectric tests.

Background-specific tests under `com.alibaba.mnnllm.android.chat.background` cover coordinator ownership,
Home/detach/recreate simulation, stop/drain, stale observer/action and transition tokens, exactly-once
process-local persistence, save failure, fast host admission, bounded output, and notification arbitration.
Robolectric service tests check the actual manifest, foreground promotion, idle wake-lock absence,
private immutable notifications, return intents and disabled-channel handling. Pure JVM tests do not
prove actual Android process priority, status-bar animation, or native background execution.

**833 phone acceptance:** allow and deny notification permission; load a small text model; confirm the
static indicator; generate while pressing Home and with screen off; return during and after completion;
stop during prefill/decode; recreate the Activity; verify history is not duplicated; test retention ON/OFF,
old Stop/Unload intents after a newer request, actual unload, model/config switch, and Chat/API handoff.
Check there is one status indicator, no idle wake lock, and no automatic generation after force-stop.
