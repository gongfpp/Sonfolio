const app = document.querySelector("#app");

const icons = {
  back: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M15 5 8 12l7 7"/><path d="M8 12h11"/></svg>`,
  more: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="5" r="1.2" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none"/><circle cx="12" cy="19" r="1.2" fill="currentColor" stroke="none"/></svg>`,
  home: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m4 10 8-6 8 6v9a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z"/><path d="M9 20v-6h6v6"/></svg>`,
  search: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.3"/><path d="m16 16 4.3 4.3"/></svg>`,
  settings: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 8.2a3.8 3.8 0 1 0 0 7.6 3.8 3.8 0 0 0 0-7.6Z"/><path d="m19.3 13.3 1.4 1.1-1.5 2.6-1.7-.6a7.7 7.7 0 0 1-1.8 1.1l-.3 1.8h-3l-.3-1.8a7.7 7.7 0 0 1-1.8-1.1l-1.7.6-1.5-2.6 1.4-1.1a7.3 7.3 0 0 1 0-2.2L5.1 10l1.5-2.6 1.7.6a7.7 7.7 0 0 1 1.8-1.1l.3-1.8h3l.3 1.8a7.7 7.7 0 0 1 1.8 1.1l1.7-.6 1.5 2.6-1.4 1.1a7.3 7.3 0 0 1 0 2.2Z"/></svg>`,
  chevron: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 5 7 7-7 7"/></svg>`,
  down: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>`,
  searchSmall: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="10.8" cy="10.8" r="6.3"/><path d="m16 16 4.3 4.3"/></svg>`,
  close: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m7 7 10 10M17 7 7 17"/></svg>`,
  file: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3.8h8l4 4v12.4H6z"/><path d="M14 3.8v4h4M9 12h6M9 15h6"/></svg>`,
  list: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 6h12M8 12h12M8 18h12"/><path d="M4 6h.01M4 12h.01M4 18h.01"/></svg>`,
  bulb: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 18h6M10 21h4M8.8 14.5A6.1 6.1 0 1 1 15.2 14.5c-.8.6-1.2 1.2-1.2 2H10c0-.8-.4-1.4-1.2-2Z"/></svg>`,
  leaf: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 4C11.5 4.3 6.1 7.4 6.1 13.1c0 2.7 2 4.5 4.6 4.5C16.4 17.6 19.6 12.5 20 4Z"/><path d="M4 20c3.1-4.7 6.6-7.6 11.3-9.7"/></svg>`,
  check: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="m8 12 2.6 2.6L16.5 9"/></svg>`,
  question: `<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M9.8 9.5a2.4 2.4 0 0 1 4.6 1c0 1.6-2.4 1.8-2.4 3.4M12 17h.01"/></svg>`,
  star: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 3 2.8 5.8 6.2.9-4.5 4.5 1.1 6.3-5.6-3-5.6 3 1.1-6.3L3 9.7l6.2-.9z"/></svg>`,
  warning: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3.5 21 20H3z"/><path d="M12 9v5M12 17.3h.01"/></svg>`,
  refresh: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11a8 8 0 0 0-14.8-3.8L3 10"/><path d="M3 5v5h5M4 13a8 8 0 0 0 14.8 3.8L21 14"/><path d="M21 19v-5h-5"/></svg>`,
  shield: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3 20 6v5.5c0 4.9-3.4 8.1-8 9.5-4.6-1.4-8-4.6-8-9.5V6z"/><path d="m8.5 12 2.2 2.2 4.8-5"/></svg>`,
  play: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 9 6-9 6z"/></svg>`,
  pause: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 6h3v12H8zM13 6h3v12h-3z"/></svg>`,
};

const state = {
  screen: "today",
  returnScreen: "today",
  search: "电梯 断电",
  filter: "全部",
  transcriptOpen: false,
  gameTranscriptOpen: false,
  playing: false,
  toggles: { chargeOnly: false, neverUpload: true },
};

const conversations = {
  release: {
    title: "与同事讨论系统投产",
    time: "09:32–09:44 · 12分钟",
    small: "确认十点投产窗口，先备份数据库并复核回滚方案",
    summary: "确认今晚十点开始投产，先完成数据库备份，再按回滚方案逐项复核。双方确认由我负责上线前检查。",
    fields: [["讨论主题", "投产安排与回滚准备"], ["已确认", "十点开始；先备份数据库"], ["后续关注", "上线前再检查一次回滚方案"]],
  },
  lunch: {
    title: "午饭多人聊天",
    time: "12:11–12:39 · 28分钟",
    small: "聊到最近的工作节奏和周末安排",
    summary: "午饭时聊了最近的工作节奏和周末安排，整体是轻松的日常交流，没有需要跟进的明确事项。",
    fields: [["交流主题", "工作节奏与周末安排"], ["主要内容", "分享最近的工作状态"], ["后续关注", "暂无明确后续事项"]],
  },
  game: {
    title: "游戏机制讨论",
    time: "14:40–15:18 · 38分钟",
    small: "构思电梯断电时的声音提示和玩家反馈",
  },
  unknown: {
    title: "与未知人物对话",
    time: "18:20–18:27 · 7分钟",
    small: "围绕晚餐和回家时间的简短交流",
    summary: "围绕晚餐和回家时间进行了简短交流，内容以确认今晚安排为主。",
    fields: [["交流主题", "晚餐与回家时间"], ["已确认", "今晚的回家安排"], ["后续关注", "暂无明确后续事项"]],
  },
};

function statusBar() {
  return `<div class="status-bar"><span class="status-time">9:41</span><span class="status-icons"><svg viewBox="0 0 24 24"><path d="M3 9a13 13 0 0 1 18 0M6 12a9 9 0 0 1 12 0M9 15a5 5 0 0 1 6 0"/><path d="M12 19h.01"/></svg><svg viewBox="0 0 24 24"><path d="M4 18h3v2H4zM9 14h3v6H9zM14 10h3v10h-3zM19 6h1v14h-1z" fill="currentColor" stroke="none"/></svg><svg class="battery" viewBox="0 0 24 24"><rect x="3" y="6.5" width="16" height="11" rx="2"/><path d="M21 10v4"/></svg></span></div>`;
}

function shell(content, nav = null) {
  return `<div class="app-viewport">${statusBar()}<div class="screen-content">${content}</div>${nav ? bottomNav(nav) : ""}<div class="toast" id="toast" role="status" aria-live="polite"></div></div>`;
}

function bottomNav(active) {
  const items = [
    ["today", icons.home, "今天"],
    ["search", icons.search, "搜索"],
    ["settings", icons.settings, "设置"],
  ];
  return `<nav class="bottom-nav" aria-label="主导航">${items.map(([id, icon, label]) => `<button class="nav-item ${active === id ? "active" : ""}" data-route="${id}">${icon}<span>${label}</span></button>`).join("")}</nav>`;
}

function homeHeader(title, subtitle) {
  return `<h1 class="screen-title">${title}</h1><p class="screen-subtitle">${subtitle}</p>`;
}

function waveform() {
  const points = "0,15 3,13 6,16 9,10 12,17 15,8 18,13 21,6 24,17 27,12 30,16 33,8 36,14 39,6 42,16 45,11 48,18 51,9 54,14 57,7 60,17 63,12 66,16 69,10 72,15 75,5 78,14 81,8 84,17 87,12 90,15 93,8 96,16 99,11 102,17 105,10 108,15 111,7 114,15 117,11 120,17 123,9 126,15 129,8 132,16 135,12 138,17 141,10 144,15 147,7 150,16 153,11 156,15 159,9 162,17 165,12 168,16 171,8 174,15 177,10 180,17";
  return `<svg class="waveform" viewBox="0 0 180 25" preserveAspectRatio="none" aria-hidden="true"><polyline points="${points}"/></svg>`;
}

function recordingCard() {
  return `<section class="recording-card" aria-label="录音状态"><div class="recording-state"><span class="live-dot" aria-hidden="true"></span><div><strong>正在记录</strong><span>06:42:17</span></div><svg class="recording-wave" viewBox="0 0 52 25" aria-hidden="true"><path d="M1 13h4V8h3v11h3V5h3v15h3V10h3v6h4V3h3v19h3V9h3v11h3V6h3v13h3V11h3v6h4"/></svg></div><button class="mark-button" data-action="mark"><span class="star">★</span>标记刚才</button></section>`;
}

function timelineCard(id, time, title, duration, summary) {
  return `<article class="timeline-card"><span class="timeline-dot" aria-hidden="true"></span><button class="timeline-button" data-conversation="${id}"><div class="timeline-meta"><span>${time}</span><strong>${title}</strong><span class="duration">${duration}</span></div><p class="timeline-summary">${summary}</p></button></article>`;
}

function todayScreen() {
  const c = conversations;
  const content = `<div class="screen-inner">${homeHeader("今天 · 9月8日", "4场对话 · 已连续记录 6小时 42分")}${recordingCard()}<div class="section-heading"><h2>今天的对话</h2></div><section class="timeline">${timelineCard("release", "09:32", "与同事讨论系统投产", "12分钟", c.release.small)}${timelineCard("lunch", "12:11", "午饭多人聊天", "28分钟", c.lunch.small)}${timelineCard("game", "14:40", "记录一个游戏想法", "3分钟", c.game.small)}${timelineCard("unknown", "18:20", "与未知人物对话", "7分钟", c.unknown.small)}</section><button class="daily-entry" data-route="daily"><span class="summary-icon">${icons.list}</span><span><strong>今日总结</strong><span>已整理 4 场对话 · 查看一日回顾</span></span><span class="chevron">${icons.chevron}</span></button></div>`;
  return shell(content, "today");
}

function detailTop(title, meta) {
  return `<div class="topbar"><button class="icon-button back-button" data-back aria-label="返回">${icons.back}</button><h1 class="screen-title">${title}</h1><div class="topbar-actions"><button class="icon-button" aria-label="更多">${icons.more}</button></div></div><div class="meta detail-meta">${meta}</div>`;
}

function transcriptBlock(rows = `<div class="transcript-row"><time>09:32</time><span>我们今晚十点可以开始投产。</span></div><div class="transcript-row"><time>09:35</time><span>先把数据库备份好。</span></div><div class="transcript-row marked"><time>09:38</time><span>然后按回滚方案逐项复核。</span></div><div class="transcript-row"><time>09:41</time><span>没问题，我来负责上线前的检查。</span></div>`) {
  return `<div class="divider"></div><button class="transcript-toggle" aria-expanded="${state.transcriptOpen}" data-action="toggle-transcript"><span class="transcript-label">${icons.file}<span>原始转写</span></span>${icons.down}</button><div class="transcript-body ${state.transcriptOpen ? "open" : ""}">${rows}</div>`;
}

function player() {
  return `<div class="player"><button class="play-button" data-action="play" aria-label="${state.playing ? "暂停" : "播放"}">${state.playing ? icons.pause : icons.play}</button><div class="player-main">${waveform()}<div class="player-time"><span>${state.playing ? "00:07" : "00:00"}</span><span>12:00</span></div></div><span class="speed">1.0x</span></div>`;
}

function conversationDetail(id) {
  const conversation = conversations[id];
  const transcript = id === "release"
    ? `<div class="transcript-row"><time>09:32</time><span>我们今晚十点可以开始投产。</span></div><div class="transcript-row"><time>09:35</time><span>先把数据库备份好。</span></div><div class="transcript-row marked"><time>09:38</time><span>然后按回滚方案逐项复核。</span></div><div class="transcript-row"><time>09:41</time><span>没问题，我来负责上线前的检查。</span></div>`
    : id === "lunch"
      ? `<div class="transcript-row"><time>12:11</time><span>最近工作节奏还好吗？</span></div><div class="transcript-row"><time>12:18</time><span>这周比较忙，周末想安排一点轻松的活动。</span></div><div class="transcript-row marked"><time>12:31</time><span>那周末再看看天气，找时间一起吃饭。</span></div>`
      : `<div class="transcript-row"><time>18:20</time><span>晚饭已经准备好了吗？</span></div><div class="transcript-row"><time>18:23</time><span>还没有，回去路上再决定吃什么。</span></div><div class="transcript-row marked"><time>18:26</time><span>好，那到家再联系。</span></div>`;
  const fields = conversation.fields.map(([label, value]) => `<div class="summary-field"><strong>${label}</strong><span>${value}</span></div>`).join("");
  const transcriptBlockHtml = transcriptBlock(transcript);
  const content = `<div class="screen-inner detail-content">${detailTop(conversation.title, conversation.time)}<section class="summary-card"><div class="summary-card-header">${icons.file}<span>本段小结</span></div><p class="summary-copy">${conversation.summary}</p><div class="summary-fields">${fields}</div><span class="local-chip">${icons.leaf}本地生成</span></section>${transcriptBlockHtml}${player()}</div>`;
  return shell(content);
}

function gameDetail() {
  const gameRows = `<div class="transcript-row"><time>14:40</time><span>如果电梯突然断电，玩家应该先听见什么？</span></div><div class="transcript-row"><time>14:51</time><span>可以先用继电器断开的声音建立预警。</span></div><div class="transcript-row marked"><time>15:06</time><span>声音提示先于画面提示，作为第一版实验方案。</span></div>`;
  const expandedTranscript = state.gameTranscriptOpen ? transcriptBlock(gameRows) : "";
  const expandLabel = state.gameTranscriptOpen ? "已展开原始转写" : "展开全部转写";
  const content = `<div class="screen-inner detail-content">${detailTop("游戏机制讨论", "14:40–15:18 · 38分钟")}<div class="long-summary-header"><h2>详细总结</h2><span class="info-badge">信息量较大</span></div><section class="structured-grid"><article class="structured-card"><div class="structured-heading">${icons.list}<span>讨论主题</span></div><p>电梯断电时如何让玩家先感知危险</p></article><article class="structured-card"><div class="structured-heading">${icons.bulb}<span>关键观点</span></div><ul><li>先用继电器断开的声音建立预警</li><li>黑暗中保留短暂的方向提示</li></ul></article><article class="structured-card decision"><div class="structured-heading">${icons.check}<span>共识与决定</span></div><p>声音提示先于画面提示，作为第一版实验方案</p></article><article class="structured-card unresolved"><div class="structured-heading">${icons.question}<span>未决问题</span></div><p>不同电梯材质是否需要不同音色</p></article></section><button class="expand-row" data-action="show-transcript"><span>${expandLabel}</span>${icons.chevron}</button>${expandedTranscript}<p class="extracted-note">已从 38 分钟语音中提炼</p></div>`;
  return shell(content);
}

function dailyScreen() {
  const content = `<div class="screen-inner detail-content">${detailTop("今日回顾", "9月8日")}<section class="journal-card"><div class="journal-heading">${icons.file}<span>今天发生了什么</span></div><p class="journal-copy">今天上午主要在处理系统投产相关工作。九点半和同事确认了晚上十点的投产安排，先做数据库备份，再复核回滚方案。中午聊了一些日常话题。下午记录了一个关于游戏电梯断电机制的想法，重点是用声音让玩家先感知危险。晚上和家里简单聊了晚餐与回家时间。</p></section><section class="aux-card starred"><div class="aux-title">${icons.star}<span>值得记住</span></div><p>电梯断电机制的第一版方向已确定</p></section><section class="aux-card warning"><div class="aux-title">${icons.warning}<span>可能需要处理</span></div><p>上线前再检查一次回滚方案</p></section><p class="footer-note">基于 4 场对话整理 · 原始录音仍按你的保留策略保存</p><button class="refresh-button" data-action="refresh">${icons.refresh}<span>重新生成</span></button></div>`;
  return shell(content);
}

function highlight(text) {
  return text.replace(/(电梯|断电)/g, "<mark>$1</mark>");
}

function searchScreen() {
  const content = `<div class="screen-inner">${homeHeader("搜索记忆", "")}${searchField()}<div class="filter-row">${["全部", "今天", "本周", "仅标记"].map((filter) => `<button class="filter-chip ${state.filter === filter ? "active" : ""}" data-filter="${filter}">${filter}</button>`).join("")}</div><p class="result-count">找到 2 条相关内容</p><button class="result-card" data-conversation="game"><div class="result-top"><span>14:40</span><strong>记录一个游戏想法</strong><span>今天</span></div><p class="result-excerpt">如果${highlight("电梯突然断电")}，可以让玩家先听见继电器断开的声音……</p></button><button class="result-card" data-conversation="game"><div class="result-top"><span>上周三</span><strong>游戏机制讨论</strong><span>38分钟</span></div><p class="result-excerpt">当${highlight("电梯断电")}时，角色会被困在中间楼层，需要手动恢复电力……</p></button></div>`;
  return shell(content, "search");
}

function searchField() {
  return `<label class="search-field">${icons.searchSmall}<input id="search-input" value="${state.search}" aria-label="搜索记忆" placeholder="搜索记忆" /><button class="clear-search" data-action="clear-search" aria-label="清空搜索">${icons.close}</button></label>`;
}

function settingsScreen() {
  const content = `<div class="screen-inner">${homeHeader("录音与存储", "")}${settingsStatus()}<section class="storage-card"><div class="storage-stats"><div><small>已使用</small><strong>18.6</strong><em>GB</em></div><div><small>预计还可记录</small><strong>47</strong><em>天</em></div></div><div class="storage-bar" aria-label="存储使用情况"><span></span></div></section><section class="retention-list"><button class="retention-row" data-retention="完整录音 · 30天"><span>完整录音</span><strong>30天 ${icons.chevron}</strong></button><button class="retention-row" data-retention="有效人声音频 · 永久保留"><span>有效人声音频</span><strong>永久保留 ${icons.chevron}</strong></button><button class="retention-row" data-retention="标记片段 · 永久保留"><span>标记片段</span><strong>永久保留 ${icons.chevron}</strong></button></section><section class="toggle-list"><div class="toggle-row"><strong>仅在充电时执行语音识别</strong><button class="toggle ${state.toggles.chargeOnly ? "on" : ""}" data-toggle="chargeOnly" aria-pressed="${state.toggles.chargeOnly}" aria-label="切换仅在充电时执行语音识别"></button></div><div class="toggle-row"><strong>原始音频永不上传</strong><button class="toggle ${state.toggles.neverUpload ? "on" : ""}" data-toggle="neverUpload" aria-pressed="${state.toggles.neverUpload}" aria-label="切换原始音频永不上传"></button></div></section><p class="privacy-note">${icons.shield}<span>所有核心处理默认在本机完成</span></p></div>`;
  return shell(content, "settings");
}

function settingsStatus() {
  return `<div class="settings-list"><button class="settings-row" data-action="service-status"><strong>录音服务</strong><span>正常 ${icons.chevron}</span></button></div>`;
}

function render() {
  const screens = {
    today: todayScreen,
    search: searchScreen,
    settings: settingsScreen,
    release: () => conversationDetail("release"),
    lunch: () => conversationDetail("lunch"),
    unknown: () => conversationDetail("unknown"),
    game: gameDetail,
    daily: dailyScreen,
  };
  app.innerHTML = screens[state.screen]();
  bindEvents();
}

function toast(message) {
  const element = document.querySelector("#toast");
  if (!element) return;
  element.textContent = message;
  element.classList.add("show");
  window.clearTimeout(toast.timer);
  toast.timer = window.setTimeout(() => element.classList.remove("show"), 1800);
}

function navigate(screen) {
  if (["release", "lunch", "unknown", "game", "daily"].includes(screen)) state.returnScreen = state.screen;
  state.screen = screen;
  state.transcriptOpen = false;
  state.gameTranscriptOpen = false;
  state.playing = false;
  render();
}

function bindEvents() {
  document.querySelectorAll("[data-route]").forEach((element) => {
    element.addEventListener("click", () => navigate(element.dataset.route));
  });
  document.querySelectorAll("[data-conversation]").forEach((element) => {
    element.addEventListener("click", () => navigate(element.dataset.conversation));
  });
  document.querySelectorAll("[data-back]").forEach((element) => {
    element.addEventListener("click", () => navigate(state.returnScreen || "today"));
  });
  document.querySelectorAll("[data-action]").forEach((element) => {
    element.addEventListener("click", () => {
      const action = element.dataset.action;
      if (action === "mark") toast("已标记刚才的内容");
      if (action === "toggle-transcript") { state.transcriptOpen = !state.transcriptOpen; render(); }
      if (action === "show-transcript") { state.gameTranscriptOpen = true; state.transcriptOpen = true; render(); }
      if (action === "play") { state.playing = !state.playing; render(); }
      if (action === "refresh") toast("今日回顾已重新生成");
      if (action === "service-status") toast("录音服务运行正常");
      if (action === "clear-search") { state.search = ""; const input = document.querySelector("#search-input"); if (input) input.value = ""; }
    });
  });
  document.querySelectorAll("[data-retention]").forEach((element) => {
    element.addEventListener("click", () => toast(`当前保留策略：${element.dataset.retention}`));
  });
  document.querySelectorAll("[data-filter]").forEach((element) => {
    element.addEventListener("click", () => { state.filter = element.dataset.filter; render(); });
  });
  document.querySelectorAll("[data-toggle]").forEach((element) => {
    element.addEventListener("click", () => { const key = element.dataset.toggle; state.toggles[key] = !state.toggles[key]; render(); });
  });
  const input = document.querySelector("#search-input");
  if (input) input.addEventListener("input", (event) => { state.search = event.target.value; });
}

render();
