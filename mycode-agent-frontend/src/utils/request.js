import axios from "axios";

/**
 * 通用 axios 实例：走 vite 代理 /api → http://localhost:8082
 */
const request = axios.create({
  baseURL: "/api",
  // 默认超时 5 分钟，避免上传大项目或后端同步处理（如向量化）耗时较长时被提前中断
  timeout: 300000,
  headers: {
    // 普通 JSON 接口统一声明 Accept，避免 Spring 内容协商返回 406
    Accept: "application/json",
  },
});

// 兜底：部分版本的 axios 不会把 create 时的 headers 合并到 common
// 这里再显式设置一遍各动词的默认 Accept。
request.defaults.headers.common["Accept"] = "application/json";
["get", "post", "put", "delete", "patch"].forEach((m) => {
  request.defaults.headers[m] = {
    ...(request.defaults.headers[m] || {}),
    Accept: "application/json",
  };
});

request.interceptors.response.use(
  (res) => {
    const body = res.data;
    // 兼容后端包装体：{ code, data, message } / { success, data }
    if (body && typeof body === "object" && !Array.isArray(body)) {
      const hasWrapper =
        "data" in body &&
        ("code" in body ||
          "success" in body ||
          "message" in body ||
          "msg" in body);
      if (hasWrapper) {
        const code = body.code;
        const ok =
          body.success === true ||
          code === undefined ||
          code === 0 ||
          code === 200 ||
          code === "0" ||
          code === "200";
        if (!ok) {
          const msg = body.message || body.msg || `接口错误 (code=${code})`;
          return Promise.reject(new Error(msg));
        }
        return body.data;
      }
    }
    return body;
  },
  (err) => {
    console.error("[request error]", err?.response?.data || err.message);
    return Promise.reject(err);
  },
);

export default request;
