/**
 * SSE（POST + text/event-stream）流式处理工具
 *
 * 后端返回 Flux<String>，每个 chunk 可能是：
 *   1) 纯文本片段（直接拼接）
 *   2) 标准 SSE 事件（"event: xxx\ndata: yyy\n\n"）
 *
 * 本工具会尝试解析标准 SSE 事件，若解析失败则作为纯文本回调。
 */

/**
 * 通过 POST 发起 SSE 请求
 * @param {string} url        接口 URL（相对路径，走 /api 代理）
 * @param {object} options
 *   - params   : 查询参数对象
 *   - body     : 请求体
 *   - headers  : 额外请求头
 *   - signal   : AbortSignal 用于取消
 *   - onText   : (chunkText) => void  每接收到一段纯文本
 *   - onEvent  : ({event, data}) => void  每接收到一个命名事件（如 tool_call）
 *   - onDone   : () => void
 *   - onError  : (err) => void
 */
export async function postSSE(
  url,
  {
    params = {},
    body = null,
    headers = {},
    signal = null,
    onText = () => {},
    onEvent = () => {},
    onDone = () => {},
    onError = () => {},
  } = {},
) {
  try {
    // 构造查询参数
    const qs = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null) qs.append(k, v);
    });
    const full = qs.toString() ? `${url}?${qs.toString()}` : url;

    const response = await fetch(full, {
      method: "POST",
      headers: {
        Accept: "text/event-stream",
        ...(body && !(body instanceof FormData)
          ? { "Content-Type": "application/json" }
          : {}),
        ...headers,
      },
      body: body
        ? body instanceof FormData
          ? body
          : JSON.stringify(body)
        : null,
      signal,
    });

    if (!response.ok) {
      const text = await response.text().catch(() => "");
      throw new Error(`HTTP ${response.status}: ${text}`);
    }
    if (!response.body) {
      throw new Error("响应无 body，不支持流式读取");
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      const chunk = decoder.decode(value, { stream: true });
      buffer += chunk;

      // 尝试按标准 SSE 事件切分：事件以 \n\n 分隔
      if (buffer.includes("\n\n")) {
        const parts = buffer.split("\n\n");
        buffer = parts.pop(); // 最后一段保留（可能未结束）
        for (const part of parts) {
          if (!part.trim()) continue;
          parseSSEBlock(part, onEvent, onText);
        }
      } else {
        // 不是标准 SSE 结构，直接作为文本片段推出
        // 但要避免将未结束的 data: 行切掉。简单策略：若包含 "data:" 或 "event:" 前缀则等待 \n\n
        if (!/^\s*(data:|event:|id:|retry:)/m.test(buffer)) {
          onText(buffer);
          buffer = "";
        }
      }
    }

    // 冲洗剩余
    if (buffer.trim()) {
      if (/^\s*(data:|event:)/m.test(buffer)) {
        parseSSEBlock(buffer, onEvent, onText);
      } else {
        onText(buffer);
      }
    }

    onDone();
  } catch (err) {
    if (err?.name === "AbortError") {
      onDone();
      return;
    }
    onError(err);
  }
}

/**
 * 解析一个 SSE 块
 * 形如：
 *   event: tool_call
 *   data: {...}
 * 或仅：
 *   data: hello world
 */
function parseSSEBlock(block, onEvent, onText) {
  const lines = block.split(/\r?\n/);
  let event = null;
  const dataLines = [];
  for (const line of lines) {
    if (line.startsWith("event:")) {
      event = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).replace(/^\s/, ""));
    }
    // 忽略 id: / retry: / 注释
  }
  const data = dataLines.join("\n");
  if (event) {
    onEvent({ event, data });
  } else if (data) {
    onText(data);
  }
}
