<template>
  <div class="file-upload">
    <div class="project-info">
      <div class="label">当前项目 ID</div>
      <div class="pid-row">
        <code class="pid" :title="projectStore.projectId">
          {{ shortPid }}
        </code>
        <button
          class="btn btn-ghost btn-sm"
          @click="copyPid"
          title="复制完整 ID"
        >
          {{ copied ? "已复制" : "复制" }}
        </button>
        <button
          class="btn btn-ghost btn-sm"
          @click="resetProject"
          title="新建项目"
        >
          新建
        </button>
      </div>
    </div>

    <div
      class="dropzone"
      :class="{ dragging, uploading }"
      @click="openFolderPicker"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <!-- 文件夹选择器：webkitdirectory 限定选中整个文件夹 -->
      <input
        ref="folderInput"
        type="file"
        multiple
        webkitdirectory
        directory
        mozdirectory
        hidden
        @change="onPick"
      />
      <!-- 普通多选文件选择器（退化选项） -->
      <input ref="fileInput" type="file" multiple hidden @change="onPick" />
      <div v-if="uploading" class="up-text">上传中…{{ uploadInfo }}</div>
      <div v-else>
        <div class="up-title">拖拽或点击上传整个项目文件夹</div>
        <div class="up-sub">
          保留目录结构；仅上传源码文件，自动过滤 target/.git/node_modules
          等大目录；或
          <a href="#" class="link" @click.stop.prevent="openFilePicker"
            >选择单个/多个文件</a
          >
        </div>
      </div>
    </div>

    <div class="file-list" v-if="projectStore.files.length">
      <div class="file-list-title">
        已上传（{{ projectStore.files.length }}）
        <button
          class="btn btn-ghost btn-sm"
          :disabled="clearing"
          @click="handleClear"
        >
          {{ clearing ? "清空中…" : "清空" }}
        </button>
      </div>
      <ul>
        <li v-for="(f, i) in projectStore.files" :key="i" class="file-item">
          <span class="fname" :title="f.name">{{ f.name }}</span>
          <span class="fsize">{{ formatSize(f.size) }}</span>
        </li>
      </ul>
    </div>

    <div v-if="error" class="err-msg">{{ error }}</div>
  </div>
</template>

<script setup>
import { computed, ref } from "vue";
import { useProjectStore } from "@/stores/project";
import {
  uploadFiles,
  deleteProjectVectors,
  checkUploadedFiles,
} from "@/api/assistant";

const projectStore = useProjectStore();
projectStore.ensureProjectId();

const folderInput = ref(null);
const fileInput = ref(null);
const dragging = ref(false);
const uploading = ref(false);
const copied = ref(false);
const error = ref("");
const uploadInfo = ref("");
const clearing = ref(false);

// 需要排除的目录名（直接匹配任一层）
const EXCLUDE_DIRS = [
  "target", // Maven/Gradle 编译输出
  ".git", // Git 版本控制目录
  "node_modules", // 前端依赖
  ".idea", // IntelliJ IDEA 配置
  ".mvn", // Maven wrapper 目录
  "logs",
  "build",
  "dist",
  "__pycache__", // Python 缓存
  ".pytest_cache",
  ".gradle",
];

// 允许的文件扩展名（只上传这些类型）
const ALLOWED_EXTS = [
  ".java",
  ".kt",
  ".kts", // Java/Kotlin
  ".xml",
  ".properties",
  ".yml",
  ".yaml", // 配置
  ".sql",
  ".md",
  ".txt", // 文档
  ".js",
  ".ts",
  ".html",
  ".css",
  ".vue", // 前端源码
  ".py", // Python
  ".go",
  ".rs", // 其他语言按需添加
];

/**
 * 判断文件是否应被上传
 * @param {string} relativePath - 文件相对路径（如 "src/main/java/App.java"）
 * @returns {boolean}
 */
function shouldUploadFile(relativePath) {
  if (!relativePath) return false;
  // 统一路径分隔符（兼容 Windows 拖拽/系统返回的路径）
  const normalized = String(relativePath).replace(/\\/g, "/");
  const parts = normalized.split("/");
  // 检查每一级目录是否在排除列表中
  for (const part of parts) {
    if (EXCLUDE_DIRS.includes(part)) {
      return false;
    }
  }
  // 检查扩展名
  const idx = normalized.lastIndexOf(".");
  if (idx < 0) return false;
  const ext = normalized.slice(idx).toLowerCase();
  return ALLOWED_EXTS.includes(ext);
}

const shortPid = computed(() => {
  const pid = projectStore.projectId;
  if (!pid) return "";
  return pid.length > 12 ? pid.slice(0, 8) + "…" + pid.slice(-4) : pid;
});

function openFolderPicker() {
  if (uploading.value) return;
  folderInput.value?.click();
}
function openFilePicker() {
  if (uploading.value) return;
  fileInput.value?.click();
}

function onPick(e) {
  const files = Array.from(e.target.files || []);
  if (files.length) handleUpload(files);
  e.target.value = "";
}

async function onDrop(e) {
  dragging.value = false;
  // 优先用 dataTransfer.items + webkitGetAsEntry 递归读取文件夹
  const items = e.dataTransfer?.items;
  if (
    items &&
    items.length &&
    typeof items[0].webkitGetAsEntry === "function"
  ) {
    const entries = [];
    for (const it of items) {
      const entry = it.webkitGetAsEntry?.();
      if (entry) entries.push(entry);
    }
    const collected = [];
    for (const entry of entries) {
      const sub = await entryToFiles(entry);
      collected.push(...sub);
    }
    if (collected.length) {
      await handleUpload(collected);
      return;
    }
  }
  // 兑底：不支持文件夹拖拽的环境仅收到文件列表
  const files = Array.from(e.dataTransfer?.files || []);
  if (files.length) await handleUpload(files);
}

/**
 * 递归读取 FileSystemEntry，返回 [{file, path}]。
 * path 使用 entry.fullPath（去掉开头 /）以保留拖入的目录结构。
 */
function entryToFiles(entry) {
  return new Promise((resolve) => {
    if (!entry) return resolve([]);
    // 拖拽遍历阶段提前跳过排除目录，避免读取大量无关 entry
    if (entry.isDirectory && EXCLUDE_DIRS.includes(entry.name)) {
      return resolve([]);
    }
    if (entry.isFile) {
      entry.file(
        (file) => {
          const path = (entry.fullPath || "").replace(/^\//, "") || file.name;
          resolve([{ file, path }]);
        },
        () => resolve([]),
      );
    } else if (entry.isDirectory) {
      const reader = entry.createReader();
      const acc = [];
      const readBatch = () => {
        reader.readEntries(
          async (entries) => {
            if (!entries.length) {
              resolve(acc);
              return;
            }
            for (const sub of entries) {
              const list = await entryToFiles(sub);
              acc.push(...list);
            }
            // 同一目录可能需要多次 readEntries 才能读完
            readBatch();
          },
          () => resolve(acc),
        );
      };
      readBatch();
    } else {
      resolve([]);
    }
  });
}

/**
 * 过滤排除目录与非白名单扩展名的文件，统一输出为 {file, path} 结构。
 * 返回 {kept, skipped}：便于提示用户过滤数量。
 */
function normalizeAndFilter(items) {
  const kept = [];
  let skipped = 0;
  for (const it of items) {
    const file = it?.file || it;
    if (!file || (!(file instanceof File) && !(file instanceof Blob))) {
      skipped++;
      continue;
    }
    const path =
      it?.path || file.webkitRelativePath || file.relativePath || file.name;
    if (!shouldUploadFile(path)) {
      skipped++;
      continue;
    }
    kept.push({ file, path });
  }
  return { kept, skipped };
}

async function handleUpload(rawList) {
  error.value = "";
  const { kept, skipped } = normalizeAndFilter(rawList);
  if (!kept.length) {
    error.value =
      skipped > 0
        ? `没有可上传的源码文件（已过滤 ${skipped} 个依赖/构建/二进制文件）`
        : "没有可上传的文件";
    return;
  }

  // 按文件数 + 总字节双阈值切批，避免单批过大触发后端 413
  const BATCH_MAX_COUNT = 20; // 每批最多 20 个文件
  const BATCH_MAX_BYTES = 8 * 1024 * 1024; // 每批最多 8MB
  const MAX_RETRIES = 3; // 每批失败后最多重试次数
  const RETRY_BASE_DELAY = 1000; // 重试基础延时（毫秒），实际按指数退避

  const totalFiles = kept.length;
  const totalSize = kept.reduce((s, it) => s + (it.file.size || 0), 0);
  const skipTip = skipped > 0 ? `，已过滤 ${skipped} 个` : "";

  uploading.value = true;
  let uploadedCount = 0;
  let totalSkipped = 0; // 断点续传跳过的已存在文件数
  const uploadedItems = [];

  try {
    const pid = projectStore.ensureProjectId();

    // ── 断点续传：首次全量检查哪些文件已存在 ──
    const allFileNames = kept.map((it) => it.path);
    let { existing: alreadyThere } = await checkUploadedFiles(
      pid,
      allFileNames,
    );
    // 规范化：后端可能返回带前导路径的
    alreadyThere = (alreadyThere || []).map((s) =>
      String(s).replace(/\\/g, "/"),
    );
    const alreadySet = new Set(alreadyThere);

    // 从待上传列表中剔除已存在的文件
    const remaining = kept.filter((it) => {
      const norm = String(it.path).replace(/\\/g, "/");
      return !alreadySet.has(norm);
    });
    totalSkipped = kept.length - remaining.length;

    // 已跳过的文件直接加入 store，保持 UI 一致
    if (totalSkipped > 0) {
      const skippedItems = kept.filter((it) => {
        const norm = String(it.path).replace(/\\/g, "/");
        return alreadySet.has(norm);
      });
      projectStore.addFiles(skippedItems);
      uploadedCount = totalSkipped;
    }

    if (remaining.length === 0) {
      uploadInfo.value = `（全部 ${totalFiles} 个文件已存在，跳过${skipTip}）`;
      return;
    }

    // 对剩余文件重新分批
    const batches = buildBatches(remaining, BATCH_MAX_COUNT, BATCH_MAX_BYTES);

    for (let i = 0; i < batches.length; i++) {
      let batch = batches[i];
      let retries = 0;
      let batchOk = false;

      // ── 断点续传核心：批次失败自动重试，重试前重新查询进度 ──
      while (!batchOk && retries <= MAX_RETRIES) {
        try {
          // 重试前再次检查已存在的文件（上一次可能部分成功）
          if (retries > 0) {
            const batchNames = batch.map((it) =>
              String(it.path).replace(/\\/g, "/"),
            );
            const { existing: already } = await checkUploadedFiles(
              pid,
              batchNames,
            );
            const alreadySet2 = new Set(
              (already || []).map((s) => String(s).replace(/\\/g, "/")),
            );
            const stillNeeded = batch.filter(
              (it) => !alreadySet2.has(String(it.path).replace(/\\/g, "/")),
            );
            if (stillNeeded.length === 0) {
              // 全部已存在，跳过这批
              batchOk = true;
              uploadedCount += batch.length;
              uploadedItems.push(...batch);
              totalSkipped += batch.length;
              break;
            }
            totalSkipped += batch.length - stillNeeded.length;
            batch = stillNeeded;
          }

          const retryTip = retries > 0 ? `，重试第${retries}次` : "";
          const skipTip2 = totalSkipped > 0 ? `，已跳过${totalSkipped}个` : "";
          uploadInfo.value = `（第 ${i + 1}/${batches.length} 批：${uploadedCount}/${totalFiles} 文件，共 ${formatSize(totalSize)}${skipTip}${skipTip2}${retryTip}）`;

          await uploadFiles(pid, batch);
          uploadedCount += batch.length;
          uploadedItems.push(...batch);
          batchOk = true;
        } catch (e) {
          retries++;
          if (retries > MAX_RETRIES) {
            // 超过重试次数，静默跳过该批次，不中断整体流程
            console.warn(
              `[断点续传] 批次 ${i + 1} 上传失败，已重试 ${MAX_RETRIES} 次，跳过`,
              e,
            );
            // 继续下一批，不抛异常
            break;
          }
          // 指数退避：1s → 2s → 4s
          await sleep(RETRY_BASE_DELAY * Math.pow(2, retries - 1));
        }
      }

      // 批间小延时，给浏览器/服务端喘息空间
      if (i < batches.length - 1) await sleep(60);
    }

    // 全部批次完成后再写入 store，避免中途失败的列表不一致
    projectStore.addFiles(uploadedItems);
    const skipMsg = totalSkipped > 0 ? `，跳过 ${totalSkipped} 个已存在` : "";
    uploadInfo.value = `（已完成 ${uploadedCount}/${totalFiles} 文件${skipTip}${skipMsg}）`;
  } catch (e) {
    // 只在完全无法恢复时才显示错误（如 projectId 无效等）
    error.value = `上传失败：` + (e?.response?.data?.message || e.message);
    if (uploadedItems.length) projectStore.addFiles(uploadedItems);
  } finally {
    uploading.value = false;
    // 保留完成提示稍久一些
    setTimeout(() => {
      uploadInfo.value = "";
    }, 2000);
  }
}

/**
 * 将文件列表切成多个批次：任一批达到 maxCount 或追加即将超过 maxBytes 就切切。
 * 若单个文件自身超过 maxBytes，单独成为一批（交给后端去判断是否能收）。
 */
function buildBatches(items, maxCount, maxBytes) {
  const batches = [];
  let cur = [];
  let curBytes = 0;
  for (const it of items) {
    const size = it.file.size || 0;
    const wouldExceed =
      cur.length >= maxCount || (cur.length > 0 && curBytes + size > maxBytes);
    if (wouldExceed) {
      batches.push(cur);
      cur = [];
      curBytes = 0;
    }
    cur.push(it);
    curBytes += size;
  }
  if (cur.length) batches.push(cur);
  return batches;
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

function resetProject() {
  if (
    !confirm(
      "将生成新的 projectId 并清空文件列表，同时删除后端向量索引，确定吗？",
    )
  )
    return;
  const oldPid = projectStore.projectId;
  // 先重置本地状态，避免后端调用期间 UI 还看到旧项目
  projectStore.resetProject();
  // 后端向量索引清理失败不影响本地重置
  if (oldPid) {
    deleteProjectVectors(oldPid).catch((e) => {
      console.warn("删除旧项目向量失败", e);
    });
  }
}

async function handleClear() {
  if (clearing.value) return;
  if (!confirm("将同时清空文件列表并删除后端向量索引，确定吗？")) return;
  error.value = "";
  const pid = projectStore.projectId;
  if (!pid) {
    projectStore.clearFiles();
    return;
  }
  clearing.value = true;
  try {
    await deleteProjectVectors(pid);
    projectStore.clearFiles();
  } catch (e) {
    error.value =
      "清空后端向量失败：" + (e?.response?.data?.message || e.message);
  } finally {
    clearing.value = false;
  }
}

async function copyPid() {
  try {
    await navigator.clipboard.writeText(projectStore.projectId);
    copied.value = true;
    setTimeout(() => (copied.value = false), 1200);
  } catch {
    error.value = "复制失败，请手动选择";
  }
}

function formatSize(bytes) {
  if (bytes == null) return "";
  if (bytes < 1024) return bytes + " B";
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + " KB";
  return (bytes / 1024 / 1024).toFixed(1) + " MB";
}
</script>

<style scoped>
.file-upload {
  padding: 12px;
  border-bottom: 1px solid var(--border-subtle);
}
.project-info {
  margin-bottom: 10px;
}
.label {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.pid-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.pid {
  flex: 1;
  background: rgba(255, 255, 255, 0.04);
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  font-family: "JetBrains Mono", monospace;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-muted);
}
.btn-sm {
  padding: 4px 8px;
  font-size: 12px;
}
.dropzone {
  margin-top: 8px;
  border: 1.5px dashed var(--border-default);
  border-radius: var(--radius-md);
  padding: 16px;
  text-align: center;
  color: var(--text-secondary);
  background: rgba(255, 255, 255, 0.02);
  transition: all 0.2s ease;
  cursor: pointer;
}
.dropzone:hover,
.dropzone.dragging {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent);
}
.dropzone.uploading {
  opacity: 0.7;
  cursor: wait;
}
.up-title {
  font-size: 13px;
  margin-bottom: 4px;
}
.up-sub {
  font-size: 11px;
}
.up-sub .link {
  color: var(--accent);
  text-decoration: none;
}
.up-sub .link:hover {
  text-decoration: underline;
}
.up-text {
  font-size: 13px;
}
.file-list {
  margin-top: 10px;
}
.file-list-title {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 4px;
}
.file-list ul {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 140px;
  overflow-y: auto;
}
.file-item {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  padding: 4px 6px;
  font-size: 12px;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
}
.file-item:hover {
  background: rgba(255, 255, 255, 0.04);
}
.fname {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fsize {
  color: var(--text-muted);
  flex-shrink: 0;
}
.err-msg {
  margin-top: 8px;
  color: var(--red);
  font-size: 12px;
}
</style>
