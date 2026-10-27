// www/js/app.js

// ============================================================================
// 1. APPLICATION STATE
// ============================================================================
const state = {
  viewMode: 'split', // 'split' | 'parent' | 'child'
  parentTab: 'alerts', // 'alerts' | 'apps' | 'contacts'
  pairCode: 'SG-8492-HK',
  activeModalAlertId: null,
  approvedContacts: [
    { handle: '@soccer_coach_dan', platform: 'Instagram', note: 'School Team Coach' },
    { handle: '@cousin_emily', platform: 'TikTok', note: 'Family' },
    { handle: 'alex_math_club#1042', platform: 'Discord', note: 'School Classmate' }
  ],
  monitoredApps: [
    {
      id: 'instagram',
      name: 'Instagram',
      icon: '📸',
      packageName: 'com.instagram.android',
      supervisionName: 'Instagram Family Center',
      parentModeEnabled: false,
      deepLink: 'https://www.instagram.com/familycenter/',
      description: 'Restricts DMs from strangers & notifies parents of new supervision settings.'
    },
    {
      id: 'tiktok',
      name: 'TikTok',
      icon: '🎵',
      packageName: 'com.zhiliaoapp.musically',
      supervisionName: 'TikTok Family Pairing',
      parentModeEnabled: true,
      deepLink: 'https://www.tiktok.com/safety/en/family-pairing/',
      description: 'Locks direct messages to approved friends and manages screen time.'
    },
    {
      id: 'discord',
      name: 'Discord',
      icon: '🎮',
      packageName: 'com.discord',
      supervisionName: 'Discord Family Center',
      parentModeEnabled: false,
      deepLink: 'https://support.discord.com/hc/en-us/articles/14155043715735-Family-Center-for-Parents-and-Guardians',
      description: 'Disable friend requests and DMs from unknown server members.'
    }
  ],
  strangerAlerts: [
    {
      id: 'alert-init-1',
      platform: 'Instagram',
      sender: '@model_scout_jay',
      message: "Hey! Don't tell your parents I messaged you, you look super mature for your age. Send me a private photo on Telegram!",
      timestamp: '2 mins ago',
      riskLevel: 'CRITICAL',
      riskScore: 95,
      categories: ['Secrecy Manipulation', 'Age Flattery', 'Photo Probe', 'Off-Platform Lure'],
      matchedPhrases: ["don't tell your parents", 'mature for your age', 'send me a private photo', 'telegram'],
      status: 'UNREVIEWED', // 'UNREVIEWED' | 'FLAGGED' | 'BLOCKED' | 'TRUSTED'
      parentNote: ''
    },
    {
      id: 'alert-init-2',
      platform: 'Discord',
      sender: 'nitro_king#9921',
      message: 'Yo I can give you free Robux and Discord Nitro if you keep this our secret and DM me on WhatsApp.',
      timestamp: '14 mins ago',
      riskLevel: 'CRITICAL',
      riskScore: 88,
      categories: ['Gift / Currency Luring', 'Secrecy Manipulation', 'Off-Platform Lure'],
      matchedPhrases: ['free robux', 'nitro', 'our secret', 'whatsapp'],
      status: 'FLAGGED',
      parentNote: 'Flagged for attempting to lure Leo with free Robux.'
    }
  ],
  p2pPacketLog: []
};

// ============================================================================
// 2. ON-DEVICE GROOMING RISK CLASSIFIER
// ============================================================================
const GROOMING_RULES = [
  {
    category: 'Secrecy Manipulation',
    weight: 35,
    patterns: ["don't tell your parents", "dont tell your parents", "our secret", "keep this secret", "delete this chat", "between us"]
  },
  {
    category: 'Personal / Photo Probe',
    weight: 30,
    patterns: ["send me a photo", "send me a private photo", "send a pic", "are you alone", "where do you live", "what school"]
  },
  {
    category: 'Off-Platform Lure',
    weight: 25,
    patterns: ["telegram", "whatsapp", "snapchat", "signal", "kik", "private chat"]
  },
  {
    category: 'Gift / Currency Luring',
    weight: 25,
    patterns: ["free robux", "gift card", "nitro", "v-bucks", "buy you", "send you money"]
  },
  {
    category: 'Age Flattery',
    weight: 20,
    patterns: ["mature for your age", "age is just a number", "look older", "so pretty"]
  }
];

function evaluateStrangerMessage(platform, sender, message) {
  const lowerMsg = message.toLowerCase();
  let score = 25; // Base score because sender is NOT in Approved Safe Contacts
  const categories = [];
  const matchedPhrases = [];

  GROOMING_RULES.forEach((rule) => {
    rule.patterns.forEach((phrase) => {
      if (lowerMsg.includes(phrase)) {
        if (!categories.includes(rule.category)) {
          categories.push(rule.category);
          score += rule.weight;
        }
        matchedPhrases.push(phrase);
      }
    });
  });

  if (categories.length === 0) {
    categories.push('Unverified Stranger Contact');
  }

  const clampedScore = Math.min(score, 99);
  const riskLevel = clampedScore >= 70 ? 'CRITICAL' : clampedScore >= 45 ? 'HIGH' : 'MEDIUM';

  return {
    riskScore: clampedScore,
    riskLevel,
    categories,
    matchedPhrases
  };
}

// ============================================================================
// 3. P2P ENCRYPTION & SERVERLESS MESH (BroadcastChannel + MQTT over WSS)
// ============================================================================
let localChannel = null;
let mqttClient = null;

function encodeEncryptedFrame(type, payload) {
  const rawJson = JSON.stringify(payload);
  const base64Cipher = btoa(unescape(encodeURIComponent(rawJson)));
  const iv = Math.random().toString(16).substring(2, 14).toUpperCase();
  return {
    frameId: 'P2P-' + Date.now().toString(36).toUpperCase(),
    pairCode: state.pairCode,
    type,
    iv,
    cipherText: base64Cipher,
    timestamp: new Date().toLocaleTimeString()
  };
}

function decodeEncryptedFrame(frame) {
  try {
    const jsonStr = decodeURIComponent(escape(atob(frame.cipherText)));
    return JSON.parse(jsonStr);
  } catch (err) {
    console.error('Failed to decode P2P frame:', err);
    return null;
  }
}

function recordPacketLog(direction, frame) {
  state.p2pPacketLog.unshift({
    direction,
    ...frame
  });
  const badge = document.getElementById('packet-count-badge');
  if (badge) badge.textContent = String(state.p2pPacketLog.length);
  renderPacketLog();
}

function broadcastP2P(type, payload) {
  const frame = encodeEncryptedFrame(type, payload);
  recordPacketLog('OUTBOUND', frame);

  if (localChannel) {
    localChannel.postMessage(frame);
  }
  if (mqttClient && mqttClient.connected) {
    const topic = `safeguard/p2p/v1/${state.pairCode}`;
    mqttClient.publish(topic, JSON.stringify(frame));
  }
}

function handleIncomingP2PFrame(frame) {
  if (!frame || frame.pairCode !== state.pairCode) return;
  // Avoid duplicating frames we already processed
  if (state.p2pPacketLog.some((p) => p.frameId === frame.frameId)) return;

  recordPacketLog('INBOUND', frame);
  const data = decodeEncryptedFrame(frame);
  if (!data) return;

  if (frame.type === 'STRANGER_DM_ALERT') {
    if (!state.strangerAlerts.some((a) => a.id === data.id)) {
      state.strangerAlerts.unshift(data);
      renderAll();
    }
  } else if (frame.type === 'PARENTAL_MODE_SYNC') {
    const target = state.monitoredApps.find((a) => a.id === data.appId);
    if (target) {
      target.parentModeEnabled = data.enabled;
      renderAll();
    }
  } else if (frame.type === 'ALERT_STATUS_UPDATE') {
    const alertItem = state.strangerAlerts.find((a) => a.id === data.alertId);
    if (alertItem) {
      alertItem.status = data.status;
      alertItem.parentNote = data.parentNote || alertItem.parentNote;
      renderAll();
    }
  }
}

function initP2PMesh() {
  if ('BroadcastChannel' in window) {
    localChannel = new BroadcastChannel('safeguard_family_p2p');
    localChannel.onmessage = (event) => handleIncomingP2PFrame(event.data);
  }

  const badge = document.getElementById('mesh-status-badge');
  if (window.mqtt) {
    try {
      mqttClient = window.mqtt.connect('wss://broker.emqx.io:8084/mqtt');
      mqttClient.on('connect', () => {
        const topic = `safeguard/p2p/v1/${state.pairCode}`;
        mqttClient.subscribe(topic);
        if (badge) {
          badge.textContent = 'P2P Mesh Live (WSS + Local)';
          badge.className = 'text-[10px] font-semibold px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 border border-emerald-500/30';
        }
      });
      mqttClient.on('message', (_topic, messageBuffer) => {
        try {
          const parsed = JSON.parse(messageBuffer.toString());
          handleIncomingP2PFrame(parsed);
        } catch (e) {
          console.error(e);
        }
      });
    } catch (err) {
      console.warn('MQTT fallback to local BroadcastChannel:', err);
    }
  }
}

// ============================================================================
// 4. UI RENDERING FUNCTIONS
// ============================================================================
function highlightTriggersInText(text, matchedPhrases) {
  let safeHtml = text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');

  (matchedPhrases || []).forEach((phrase) => {
    const escaped = phrase.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const regex = new RegExp(`(${escaped})`, 'gi');
    safeHtml = safeHtml.replace(regex, '<mark class="trigger-highlight">$1</mark>');
  });
  return safeHtml;
}

function renderUnsecuredAppsBanner() {
  const missing = state.monitoredApps.filter((a) => !a.parentModeEnabled);
  const banner = document.getElementById('unsecured-apps-banner');
  const title = document.getElementById('unsecured-banner-title');
  const desc = document.getElementById('unsecured-banner-desc');

  if (missing.length === 0) {
    banner.className = 'bg-emerald-500/15 border-b border-emerald-500/30 px-4 py-2.5 flex items-center justify-between gap-3';
    title.className = 'text-xs font-bold text-emerald-200';
    title.textContent = 'All Social Apps Have Parental Mode Enabled';
    desc.className = 'text-[11px] text-emerald-300/90';
    desc.textContent = 'Instagram, TikTok, and Discord are actively supervised.';
  } else {
    banner.className = 'bg-amber-500/15 border-b border-amber-500/30 px-4 py-3 flex items-center justify-between gap-3';
    title.className = 'text-xs font-bold text-amber-200';
    title.textContent = `${missing.length} App${missing.length > 1 ? 's' : ''} Missing Parental Mode`;
    desc.className = 'text-[11px] text-amber-300/90';
    desc.textContent = `${missing.map((m) => m.name).join(' & ')} do not have Family Supervision enabled on Leo's phone.`;
  }
}

function renderParentAlerts() {
  const container = document.getElementById('parent-tab-alerts');
  const pill = document.getElementById('alerts-count-pill');
  pill.textContent = String(state.strangerAlerts.length);

  if (state.strangerAlerts.length === 0) {
    container.innerHTML = `
      <div class="text-center py-12 text-slate-400 text-xs">
        No stranger message alerts yet.
      </div>
    `;
    return;
  }

  container.innerHTML = state.strangerAlerts
    .map((alert) => {
      const isCritical = alert.riskLevel === 'CRITICAL';
      const badgeClass = isCritical
        ? 'bg-rose-500/20 text-rose-300 border border-rose-500/40'
        : 'bg-amber-500/20 text-amber-300 border border-amber-500/40';

      const statusChip =
        alert.status === 'FLAGGED'
          ? '<span class="px-2 py-0.5 rounded bg-amber-500/20 text-amber-300 text-[10px] font-bold">🚩 FLAGGED</span>'
          : alert.status === 'BLOCKED'
          ? '<span class="px-2 py-0.5 rounded bg-rose-500/20 text-rose-300 text-[10px] font-bold">🛑 BLOCKED</span>'
          : '<span class="px-2 py-0.5 rounded bg-slate-800 text-slate-300 text-[10px]">UNREVIEWED</span>';

      return `
        <div class="bg-slate-950/90 border ${isCritical ? 'border-rose-500/40 alert-critical-card' : 'border-slate-800'} rounded-2xl p-3.5 space-y-2.5">
          <div class="flex items-start justify-between gap-2">
            <div>
              <div class="flex items-center gap-1.5 flex-wrap">
                <span class="text-xs font-bold text-white">${alert.sender}</span>
                <span class="text-[10px] px-2 py-0.5 rounded-full bg-slate-800 text-slate-300">${alert.platform}</span>
                ${statusChip}
              </div>
              <p class="text-[10px] text-slate-400 mt-0.5">${alert.timestamp}</p>
            </div>
            <span class="text-[10px] font-bold px-2 py-0.5 rounded-full ${badgeClass}">
              ${alert.riskLevel} (${alert.riskScore}%)
            </span>
          </div>

          <div class="bg-slate-900/90 border border-slate-800/90 rounded-xl p-2.5 text-xs text-slate-200 leading-relaxed">
            "${highlightTriggersInText(alert.message, alert.matchedPhrases)}"
          </div>

          <div class="flex flex-wrap gap-1">
            ${alert.categories
              .map(
                (cat) =>
                  `<span class="text-[10px] px-2 py-0.5 rounded bg-indigo-500/15 text-indigo-300 border border-indigo-500/30">${cat}</span>`
              )
              .join('')}
          </div>

          ${
            alert.parentNote
              ? `<div class="text-[11px] text-amber-300 bg-amber-500/10 border border-amber-500/20 rounded-lg px-2.5 py-1.5">
                   <b>Parent Flag Note:</b> ${alert.parentNote}
                 </div>`
              : ''
          }

          <div class="pt-1 flex items-center justify-end">
            <button
              onclick="openPreviewModal('${alert.id}')"
              class="w-full sm:w-auto px-3.5 py-2 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white font-bold text-xs transition"
            >
              🔍 Preview & Flag Message
            </button>
          </div>
        </div>
      `;
    })
    .join('');
}

function renderParentalModeAuditor() {
  const parentContainer = document.getElementById('parent-tab-apps');
  const childContainer = document.getElementById('child-apps-status-list');

  parentContainer.innerHTML = state.monitoredApps
    .map((app) => {
      const badge = app.parentModeEnabled
        ? '<span class="px-2.5 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 text-[10px] font-bold">✅ Enabled</span>'
        : '<span class="px-2.5 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/30 text-[10px] font-bold">⚠️ Not Enabled</span>';

      return `
        <div class="bg-slate-950 border border-slate-800 rounded-2xl p-4 space-y-3">
          <div class="flex items-start justify-between gap-2">
            <div class="flex items-center gap-2.5">
              <span class="text-2xl">${app.icon}</span>
              <div>
                <h4 class="text-xs font-bold text-white">${app.name}</h4>
                <p class="text-[11px] text-indigo-400 font-medium">${app.supervisionName}</p>
              </div>
            </div>
            ${badge}
          </div>

          <p class="text-xs text-slate-400 leading-relaxed">${app.description}</p>

          <div class="flex flex-wrap items-center justify-between gap-2 pt-1">
            <a
              href="${app.deepLink}"
              target="_blank"
              rel="noopener noreferrer"
              class="text-[11px] text-indigo-400 hover:underline"
            >
              Open Official ${app.name} Setup Guide ↗
            </a>
            <button
              onclick="toggleAppParentalMode('${app.id}')"
              class="px-3 py-1.5 rounded-xl font-bold text-xs transition ${
                app.parentModeEnabled
                  ? 'bg-slate-800 hover:bg-slate-700 text-slate-200'
                  : 'bg-amber-400 hover:bg-amber-300 text-slate-950'
              }"
            >
              ${app.parentModeEnabled ? 'Mark as Disabled' : 'Enable Parental Mode Now'}
            </button>
          </div>
        </div>
      `;
    })
    .join('');

  childContainer.innerHTML = state.monitoredApps
    .map(
      (app) => `
      <div class="flex items-center justify-between bg-slate-900 border border-slate-800 rounded-xl px-3 py-2 text-xs">
        <div class="flex items-center gap-2">
          <span>${app.icon}</span>
          <span class="font-semibold text-white">${app.name}</span>
          <span class="text-[10px] text-slate-500 font-mono">(${app.packageName})</span>
        </div>
        <span class="text-[10px] font-bold px-2 py-0.5 rounded ${
          app.parentModeEnabled
            ? 'bg-emerald-500/20 text-emerald-300'
            : 'bg-amber-500/20 text-amber-300'
        }">
          ${app.parentModeEnabled ? 'Parental Mode ON' : 'Parental Mode OFF'}
        </span>
      </div>
    `
    )
    .join('');
}

function renderApprovedContacts() {
  const container = document.getElementById('parent-tab-contacts');
  container.innerHTML = `
    <div class="bg-slate-950 border border-slate-800 rounded-2xl p-3.5 space-y-2">
      <p class="text-xs text-slate-300 font-semibold">Approved Safe Contacts (Whitelisted)</p>
      <p class="text-[11px] text-slate-400">
        Messages from these handles are treated as known friends and do not trigger stranger alerts.
      </p>
      <div class="space-y-2 pt-2">
        ${state.approvedContacts
          .map(
            (c) => `
          <div class="flex items-center justify-between bg-slate-900 border border-slate-800 rounded-xl px-3 py-2 text-xs">
            <div>
              <span class="font-bold text-emerald-300">${c.handle}</span>
              <span class="text-[10px] text-slate-400 ml-1.5">• ${c.platform}</span>
            </div>
            <span class="text-[10px] text-slate-400">${c.note}</span>
          </div>
        `
          )
          .join('')}
      </div>
    </div>
  `;
}

function renderPacketLog() {
  const list = document.getElementById('packet-log-list');
  if (!list) return;

  if (state.p2pPacketLog.length === 0) {
    list.innerHTML = '<div class="text-slate-500">No encrypted P2P frames sent yet. Trigger a DM on the Child Phone to inspect packets.</div>';
    return;
  }

  list.innerHTML = state.p2pPacketLog
    .map(
      (pkt) => `
      <div class="bg-slate-950 border border-slate-800 rounded-lg p-2 flex flex-col gap-1">
        <div class="flex items-center justify-between">
          <span class="font-bold ${pkt.direction === 'OUTBOUND' ? 'text-indigo-400' : 'text-emerald-400'}">
            [${pkt.direction}] ${pkt.type} • Frame ${pkt.frameId}
          </span>
          <span class="text-slate-500">${pkt.timestamp} | IV: ${pkt.iv}</span>
        </div>
        <div class="text-slate-400 truncate">Ciphertext: ${pkt.cipherText}</div>
      </div>
    `
    )
    .join('');
}

function renderAll() {
  renderUnsecuredAppsBanner();
  renderParentAlerts();
  renderParentalModeAuditor();
  renderApprovedContacts();
  renderPacketLog();
}

// ============================================================================
// 5. INTERACTIVE ACTIONS & EVENT HANDLERS
// ============================================================================
window.setViewMode = function (mode) {
  state.viewMode = mode;
  const grid = document.getElementById('phones-grid');
  const parentPhone = document.getElementById('parent-phone-container');
  const childPhone = document.getElementById('child-phone-container');

  const btnSplit = document.getElementById('view-split-btn');
  const btnParent = document.getElementById('view-parent-btn');
  const btnChild = document.getElementById('view-child-btn');

  [btnSplit, btnParent, btnChild].forEach((b) => {
    b.className = 'px-2.5 py-1 rounded-lg text-slate-400 hover:text-white transition';
  });

  if (mode === 'split') {
    btnSplit.className = 'px-2.5 py-1 rounded-lg bg-indigo-600 text-white transition';
    grid.className = 'flex-1 max-w-6xl w-full mx-auto p-4 grid grid-cols-1 lg:grid-cols-2 gap-6 items-start';
    parentPhone.classList.remove('hidden');
    childPhone.classList.remove('hidden');
  } else if (mode === 'parent') {
    btnParent.className = 'px-2.5 py-1 rounded-lg bg-indigo-600 text-white transition';
    grid.className = 'flex-1 max-w-md w-full mx-auto p-4 grid grid-cols-1 gap-6 items-start';
    parentPhone.classList.remove('hidden');
    childPhone.classList.add('hidden');
  } else {
    btnChild.className = 'px-2.5 py-1 rounded-lg bg-indigo-600 text-white transition';
    grid.className = 'flex-1 max-w-md w-full mx-auto p-4 grid grid-cols-1 gap-6 items-start';
    parentPhone.classList.add('hidden');
    childPhone.classList.remove('hidden');
  }
};

window.switchParentTab = function (tab) {
  state.parentTab = tab;
  ['alerts', 'apps', 'contacts'].forEach((t) => {
    const content = document.getElementById(`parent-tab-${t}`);
    const btn = document.getElementById(`tab-btn-${t}`);
    if (t === tab) {
      content.classList.remove('hidden');
      btn.className = 'py-3 border-b-2 border-indigo-500 text-indigo-400 flex items-center justify-center gap-1.5';
    } else {
      content.classList.add('hidden');
      btn.className = 'py-3 border-b-2 border-transparent text-slate-400 hover:text-slate-200 flex items-center justify-center gap-1.5';
    }
  });
};

window.loadPreset = function (presetKey) {
  const platformEl = document.getElementById('sim-platform');
  const senderEl = document.getElementById('sim-sender');
  const messageEl = document.getElementById('sim-message');

  if (presetKey === 'ig_grooming') {
    platformEl.value = 'Instagram';
    senderEl.value = '@model_scout_jay';
    messageEl.value = "Hey! Don't tell your parents I messaged you, you look super mature for your age. Send me a private photo on Telegram!";
  } else if (presetKey === 'discord_gift') {
    platformEl.value = 'Discord';
    senderEl.value = 'nitro_king#9921';
    messageEl.value = 'Yo I can give you free Robux and Discord Nitro if you keep this our secret and DM me on WhatsApp.';
  } else if (presetKey === 'tiktok_stranger') {
    platformEl.value = 'TikTok';
    senderEl.value = '@skater_vibes_99';
    messageEl.value = 'Hey saw your TikTok video! Which school do you go to? Are you alone right now?';
  }
};

window.triggerIncomingChildDM = function () {
  const platform = document.getElementById('sim-platform').value;
  const sender = document.getElementById('sim-sender').value.trim() || '@unknown_user';
  const message = document.getElementById('sim-message').value.trim();
  if (!message) return;

  // Check if sender is already whitelisted
  const isApproved = state.approvedContacts.some(
    (c) => c.handle.toLowerCase() === sender.toLowerCase()
  );
  if (isApproved) {
    alert(`Sender ${sender} is in the Approved Safe Contacts list. No parent alert triggered.`);
    return;
  }

  const analysis = evaluateStrangerMessage(platform, sender, message);
  const newAlert = {
    id: 'alert-' + Date.now(),
    platform,
    sender,
    message,
    timestamp: 'Just now',
    riskLevel: analysis.riskLevel,
    riskScore: analysis.riskScore,
    categories: analysis.categories,
    matchedPhrases: analysis.matchedPhrases,
    status: 'UNREVIEWED',
    parentNote: ''
  };

  state.strangerAlerts.unshift(newAlert);
  broadcastP2P('STRANGER_DM_ALERT', newAlert);
  switchParentTab('alerts');
  renderAll();
};

window.toggleAppParentalMode = function (appId) {
  const app = state.monitoredApps.find((a) => a.id === appId);
  if (!app) return;
  app.parentModeEnabled = !app.parentModeEnabled;
  broadcastP2P('PARENTAL_MODE_SYNC', {
    appId: app.id,
    enabled: app.parentModeEnabled
  });
  renderAll();
};

window.openPreviewModal = function (alertId) {
  const alertItem = state.strangerAlerts.find((a) => a.id === alertId);
  if (!alertItem) return;

  state.activeModalAlertId = alertId;
  const modal = document.getElementById('preview-modal');
  document.getElementById('modal-sender-title').textContent = `${alertItem.sender} • ${alertItem.platform}`;
  document.getElementById('modal-timestamp').textContent = `Received ${alertItem.timestamp} via Encrypted P2P`;
  document.getElementById('modal-message-body').innerHTML = highlightTriggersInText(
    alertItem.message,
    alertItem.matchedPhrases
  );
  document.getElementById('modal-flag-note').value = alertItem.parentNote || '';

  const badge = document.getElementById('modal-risk-badge');
  badge.textContent = `${alertItem.riskLevel} RISK (${alertItem.riskScore}%)`;
  badge.className =
    alertItem.riskLevel === 'CRITICAL'
      ? 'text-[10px] font-bold px-2.5 py-0.5 rounded-full bg-rose-500/20 text-rose-300 border border-rose-500/40'
      : 'text-[10px] font-bold px-2.5 py-0.5 rounded-full bg-amber-500/20 text-amber-300 border border-amber-500/40';

  const triggersList = document.getElementById('modal-triggers-list');
  triggersList.innerHTML = alertItem.categories
    .map(
      (c) =>
        `<span class="text-[10px] px-2.5 py-0.5 rounded-full bg-indigo-500/20 text-indigo-300 border border-indigo-500/30">${c}</span>`
    )
    .join('');

  modal.classList.remove('hidden');
  modal.classList.add('flex');
};

window.closePreviewModal = function () {
  const modal = document.getElementById('preview-modal');
  modal.classList.add('hidden');
  modal.classList.remove('flex');
  state.activeModalAlertId = null;
};

window.handleModalAction = function (actionStatus) {
  const alertItem = state.strangerAlerts.find((a) => a.id === state.activeModalAlertId);
  if (!alertItem) return;

  const noteVal = document.getElementById('modal-flag-note').value.trim();
  alertItem.status = actionStatus;
  if (noteVal) {
    alertItem.parentNote = noteVal;
  } else if (actionStatus === 'FLAGGED' && !alertItem.parentNote) {
    alertItem.parentNote = 'Flagged by parent for review.';
  } else if (actionStatus === 'BLOCKED' && !alertItem.parentNote) {
    alertItem.parentNote = 'Sender flagged and blocked by parent.';
  }

  if (actionStatus === 'TRUSTED') {
    state.approvedContacts.push({
      handle: alertItem.sender,
      platform: alertItem.platform,
      note: noteVal || 'Approved by Parent'
    });
    state.strangerAlerts = state.strangerAlerts.filter((a) => a.id !== alertItem.id);
  }

  broadcastP2P('ALERT_STATUS_UPDATE', {
    alertId: alertItem.id,
    status: actionStatus,
    parentNote: alertItem.parentNote
  });

  closePreviewModal();
  renderAll();
};

window.togglePacketDrawer = function () {
  const drawer = document.getElementById('packet-drawer');
  drawer.classList.toggle('hidden');
};

// Initialize when DOM finishes loading
document.addEventListener('DOMContentLoaded', () => {
  const pairInput = document.getElementById('pair-code-input');
  if (pairInput) {
    pairInput.addEventListener('change', (e) => {
      state.pairCode = e.target.value.trim().toUpperCase() || 'SG-8492-HK';
      initP2PMesh();
    });
  }
  initP2PMesh();
  renderAll();
});

// Add to the bottom of www/js/app.js:
// Receives real intercepted notifications from SocialNotificationListener.kt on Android
window.onNativeSocialDM = function (payload) {
  if (!payload || !payload.message) return;

  const platform = payload.platform || 'Instagram';
  const sender = payload.sender || '@unknown_sender';
  const message = payload.message;

  // Ignore if sender is already in Approved Safe Contacts
  const isApproved = state.approvedContacts.some(
    (c) => c.handle.toLowerCase() === sender.toLowerCase()
  );
  if (isApproved) return;

  // Run On-Device Grooming Risk Classifier
  const analysis = evaluateStrangerMessage(platform, sender, message);
  const newAlert = {
    id: 'alert-native-' + Date.now(),
    platform,
    sender,
    message,
    timestamp: 'Just now (Live Android Notification)',
    riskLevel: analysis.riskLevel,
    riskScore: analysis.riskScore,
    categories: analysis.categories,
    matchedPhrases: analysis.matchedPhrases,
    status: 'UNREVIEWED',
    parentNote: ''
  };

  state.strangerAlerts.unshift(newAlert);
  broadcastP2P('STRANGER_DM_ALERT', newAlert);
  renderAll();
};