import { createRouter, createWebHistory } from "vue-router";

const routes = [
  {
    path: "/",
    name: "home",
    component: () => import("@/views/Home.vue"),
    meta: { title: "首页" },
  },
  {
    path: "/assistant",
    name: "assistant",
    component: () => import("@/views/Assistant.vue"),
    meta: { title: "编程助手 (RAG)" },
  },
  {
    path: "/agent",
    name: "agent",
    component: () => import("@/views/Agent.vue"),
    meta: { title: "编程智能体 (ReAct)" },
  },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.afterEach((to) => {
  if (to.meta?.title) {
    document.title = `CodeMate · ${to.meta.title}`;
  }
});

export default router;
