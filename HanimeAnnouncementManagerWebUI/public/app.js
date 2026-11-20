"use strict";

// Firebase Web config is public metadata. Authorization is enforced by database rules.
firebase.initializeApp({
  apiKey: "AIzaSyCviB_yfKwnsYRc2QE4E8d04wY25YyYbUI",
  authDomain: "han1meviewer-chuishui.firebaseapp.com",
  databaseURL: "https://han1meviewer-chuishui-default-rtdb.asia-southeast1.firebasedatabase.app",
  projectId: "han1meviewer-chuishui",
  appId: "1:996744088154:web:8bf707e1c028eecc126663"
});

const auth = firebase.auth();
const db = firebase.database();
const byId = id => document.getElementById(id);
let editingKey = null;

function status(message) { byId("status").textContent = message; }
function show(view) {
  for (const id of ["loginView", "deniedView", "adminView"]) byId(id).hidden = id !== view;
}
function report(error) { status(error?.message || String(error)); }

async function checkAdmin(user) {
  if (!user) { show("loginView"); return; }
  try {
    const token = await user.getIdTokenResult(true);
    if (token.claims.announcementAdmin !== true) {
      show("deniedView");
      status(`已登录 ${user.email}，但没有公告管理权限。`);
      return;
    }
    byId("currentUser").textContent = `管理员：${user.email}`;
    show("adminView");
    status("");
    await loadAnnouncements();
  } catch (error) { report(error); }
}

auth.setPersistence(firebase.auth.Auth.Persistence.SESSION)
  .then(() => auth.onAuthStateChanged(checkAdmin))
  .catch(report);

byId("loginForm").addEventListener("submit", async event => {
  event.preventDefault();
  const button = event.submitter || byId("loginButton");
  button.disabled = true;
  try {
    await auth.signInWithEmailAndPassword(byId("email").value.trim(), byId("password").value);
    byId("password").value = "";
    status("");
  } catch (error) { report(error); }
  finally { button.disabled = false; }
});
byId("refreshClaim").addEventListener("click", () => checkAdmin(auth.currentUser));
for (const id of ["logout", "deniedLogout"]) {
  byId(id).addEventListener("click", () => auth.signOut().catch(report));
}
byId("reload").addEventListener("click", () => loadAnnouncements().catch(report));

function textCell(row, value, className) {
  const cell = row.insertCell();
  cell.textContent = String(value ?? "");
  if (className) cell.className = className;
  return cell;
}
function action(cell, label, handler, className = "secondary") {
  const button = document.createElement("button");
  button.type = "button";
  button.className = className;
  button.textContent = label;
  button.addEventListener("click", () => Promise.resolve(handler()).catch(report));
  cell.append(button);
}
async function loadAnnouncements() {
  const snapshot = await db.ref("announcements").once("value");
  const list = byId("announcementList");
  list.replaceChildren();
  const entries = [];
  snapshot.forEach(child => {
    const data = child.val();
    if (data && typeof data === "object") entries.push({ key: child.key, data });
  });
  entries.sort((a, b) => a.data.priority - b.data.priority || b.data.timestamp - a.data.timestamp);
  for (const { key, data } of entries) {
    const row = list.insertRow();
    textCell(row, data.title);
    textCell(row, data.content.length > 180 ? `${data.content.slice(0, 180)}…` : data.content, "excerpt");
    textCell(row, data.priority);
    textCell(row, data.isActive ? "已发布" : "停用", data.isActive ? "" : "muted");
    textCell(row, new Date(data.timestamp * 1000).toLocaleString());
    const cell = row.insertCell();
    cell.className = "actionsCell";
    action(cell, "编辑", () => edit(key, data));
    action(cell, data.isActive ? "停用" : "发布", () => toggle(key, data.isActive));
    action(cell, "删除", () => remove(key, data.title), "danger");
  }
  if (!snapshot.exists()) status("暂无公告。新增公告会先保存为停用状态。");
}

function resetForm() {
  editingKey = null;
  byId("announcementForm").reset();
  byId("editTitle").textContent = "新增公告";
  byId("saveButton").textContent = "保存为停用公告";
  byId("cancelEdit").hidden = true;
}
function edit(key, data) {
  editingKey = key;
  for (const id of ["title", "content", "imageUrl", "positiveText", "negativeText", "priority"]) {
    byId(id).value = data[id] ?? "";
  }
  byId("editTitle").textContent = "编辑公告";
  byId("saveButton").textContent = "保存修改";
  byId("cancelEdit").hidden = false;
  byId("title").focus();
}
byId("cancelEdit").addEventListener("click", resetForm);

byId("announcementForm").addEventListener("submit", async event => {
  event.preventDefault();
  const imageUrl = byId("imageUrl").value.trim();
  if (imageUrl && !imageUrl.startsWith("https://")) {
    status("图片地址必须使用 HTTPS。");
    return;
  }
  const data = {
    title: byId("title").value.trim(),
    content: byId("content").value.trim(),
    imageUrl,
    positiveText: byId("positiveText").value.trim(),
    negativeText: byId("negativeText").value.trim(),
    priority: Number(byId("priority").value),
    timestamp: Math.floor(Date.now() / 1000)
  };
  if (!data.title || !data.content || !Number.isInteger(data.priority) || data.priority < 0 || data.priority > 10) {
    status("请填写标题、正文和 0–10 的整数优先级。");
    return;
  }
  const button = event.submitter || byId("saveButton");
  button.disabled = true;
  try {
    if (editingKey) await db.ref(`announcements/${editingKey}`).update(data);
    else await db.ref("announcements").push().set({ ...data, isActive: false });
    resetForm();
    await loadAnnouncements();
    status("已保存。新公告需手动发布才会在 App 中显示。");
  } catch (error) { report(error); }
  finally { button.disabled = false; }
});

async function toggle(key, currentlyActive) {
  if (!currentlyActive && !confirm("发布后，所有客户端都能看到这条公告。确定发布吗？")) return;
  await db.ref(`announcements/${key}/isActive`).set(!currentlyActive);
  await loadAnnouncements();
  status(currentlyActive ? "公告已停用。" : "公告已发布。");
}
async function remove(key, title) {
  if (!confirm(`确定删除“${title}”吗？此操作无法恢复。`)) return;
  await db.ref(`announcements/${key}`).remove();
  await loadAnnouncements();
  status("公告已删除。");
}
