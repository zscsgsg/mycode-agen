import { defineStore } from "pinia";
import { ref, computed } from "vue";

/**
 * RAG 助手检索透明面板的状态：
 *  - 每次提问会先收到一条 retrieved_docs 事件，把片段塞进 docs
 *  - highlightId：用户从消息里点击 [文件#L行] 引用 chip 时高亮对应片段
 */
export const useRetrievedDocsStore = defineStore("retrievedDocs", () => {
  const vectorHits = ref(0);
  const bm25Hits = ref(0);
  const docs = ref([]); // [{ id, file_path, start_line, end_line, tag, branch, rrf_score, snippet, full_text, ... }]
  const expandedIds = ref(new Set()); // 展开的片段 id
  const highlightId = ref("");
  const lastQueryAt = ref(0);

  function reset() {
    vectorHits.value = 0;
    bm25Hits.value = 0;
    docs.value = [];
    expandedIds.value = new Set();
    highlightId.value = "";
  }

  /** 接收后端 retrieved_docs 事件的 payload。 */
  function setFromPayload(payload) {
    try {
      const obj = typeof payload === "string" ? JSON.parse(payload) : payload;
      vectorHits.value = obj.vector_hits || 0;
      bm25Hits.value = obj.bm25_hits || 0;
      docs.value = Array.isArray(obj.docs) ? obj.docs : [];
      expandedIds.value = new Set();
      highlightId.value = "";
      lastQueryAt.value = Date.now();
    } catch (e) {
      console.warn("[retrievedDocs] 解析失败", e);
    }
  }

  function toggle(id) {
    const set = new Set(expandedIds.value);
    if (set.has(id)) set.delete(id);
    else set.add(id);
    expandedIds.value = set;
  }

  function expand(id) {
    const set = new Set(expandedIds.value);
    set.add(id);
    expandedIds.value = set;
  }

  /** 通过文件路径 + 行号定位片段（点击引用 chip 时使用）。 */
  function findByLocation(filePath, startLine) {
    const sl = Number(startLine);
    return docs.value.find(
      (d) =>
        d.file_path === filePath &&
        (sl ? Number(d.start_line) <= sl && Number(d.end_line) >= sl : true),
    );
  }

  function highlight(id) {
    highlightId.value = id;
    expand(id);
    setTimeout(() => {
      if (highlightId.value === id) highlightId.value = "";
    }, 2500);
  }

  const total = computed(() => docs.value.length);

  return {
    vectorHits,
    bm25Hits,
    docs,
    expandedIds,
    highlightId,
    lastQueryAt,
    total,
    reset,
    setFromPayload,
    toggle,
    expand,
    findByLocation,
    highlight,
  };
});
