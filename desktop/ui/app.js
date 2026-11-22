(function () {
  const invoke = window.__TAURI__?.core?.invoke;
  const listen = window.__TAURI__?.event?.listen;

  let state = {
    profiles: [],
    settings: {
      routing_mode: "auto_cn",
      proxy_mode: "system_proxy",
      language: "auto",
      theme_mode: "auto",
      auto_start: false,
      minimize_to_tray: true,
      local_proxy_port: 20808,
      selected_server_id: null
    },
    vpn: {
      connected: false,
      connecting: false,
      server_id: null,
      server_name: "",
      proxy_mode: "system_proxy",
      active_protocol: "reality",
      local_proxy_port: 20808,
      up_bps: 0,
      down_bps: 0,
      session_bytes: 0,
      last_error: null
    },
    statusMap: {},
    monetizationUrl: "https://build-myvpn.pages.dev/#sponsor",
    pendingDeployReq: null,
    updateInfo: null,
    updatingNow: false
  };

  let dict = window.BMV_I18N.getDict("en");
  const $ = (id) => document.getElementById(id);

  function fmtBytes(bytes) {
    if (!bytes || bytes < 1024) return `${Math.max(0, bytes || 0)} B`;
    const units = ["KB", "MB", "GB", "TB"];
    let val = bytes / 1024;
    let idx = 0;
    while (val >= 1024 && idx < units.length - 1) {
      val /= 1024;
      idx++;
    }
    return `${val.toFixed(val >= 100 ? 0 : 1)} ${units[idx]}`;
  }

  function fmtSpeed(bps) {
    return `${fmtBytes(bps)}/s`;
  }

  function applyTheme(mode) {
    const m = mode || "auto";
    const hour = new Date().getHours();
    const resolved = m === "auto" ? (hour >= 6 && hour < 19 ? "light" : "dark") : m;
    document.documentElement.setAttribute("data-theme", resolved);
    $("quickThemeBtn").textContent = m === "auto" ? "◐" : resolved === "dark" ? "☾" : "☀";
  }

  function applyLanguage(langCode) {
    const resolved = !langCode || langCode === "auto" ? window.BMV_I18N.detect() : langCode;
    dict = window.BMV_I18N.getDict(resolved);
    const meta = window.BMV_LANGS.find((l) => l.code === resolved);
    document.documentElement.dir = meta && meta.rtl ? "rtl" : "ltr";
    document.documentElement.lang = resolved;

    document.querySelectorAll("[data-i18n]").forEach((el) => {
      const key = el.getAttribute("data-i18n");
      if (dict[key]) el.textContent = dict[key];
    });
    renderConnect();
    renderServers();
  }

  function getSelectedServer() {
    const id = state.settings.selected_server_id;
    return state.profiles.find((p) => p.id === id) || state.profiles[0] || null;
  }

  function switchView(name) {
    document.querySelectorAll(".nav-item").forEach((b) => {
      b.classList.toggle("active", b.dataset.view === name);
    });
    document.querySelectorAll(".view").forEach((v) => {
      v.classList.toggle("active", v.id === `view-${name}`);
    });
    if (name === "settings") refreshLogs();
  }

  function renderConnect() {
    const srv = getSelectedServer();
    const v = state.vpn;

    if (srv) {
      $("activeFlag").textContent = srv.region_flag || "🌐";
      $("activeName").textContent = srv.name || srv.host;
      const st = state.statusMap[srv.id];
      const onlineTag = st ? (st.online ? `● ${dict.online}` : `○ ${dict.offline}`) : "";
      $("activeSub").textContent = [srv.region_name || srv.host, `${srv.host}:${srv.vpn_port}`, onlineTag]
        .filter(Boolean)
        .join(" · ");
      $("activeProtoBadge").textContent =
        srv.active_protocol === "hysteria2" ? "Hysteria2" : "VLESS · REALITY";
    } else {
      $("activeFlag").textContent = "🌐";
      $("activeName").textContent = dict.no_server;
      $("activeSub").textContent = dict.no_server_sub;
      $("activeProtoBadge").textContent = "VPS";
    }

    const btn = $("powerBtn");
    btn.classList.remove("connected", "connecting");
    $("sideDot").classList.remove("online", "connecting");

    if (v.connecting) {
      btn.classList.add("connecting");
      $("sideDot").classList.add("connecting");
      $("powerLabel").textContent = dict.status_connecting;
      $("sideStatus").textContent = dict.status_connecting;
      $("powerHint").textContent = srv ? `${srv.host}:${srv.vpn_port}` : "";
    } else if (v.connected) {
      btn.classList.add("connected");
      $("sideDot").classList.add("online");
      $("powerLabel").textContent = dict.status_connected;
      $("sideStatus").textContent = dict.status_connected;
      $("powerHint").textContent = dict.tap_to_disconnect;
    } else {
      $("powerLabel").textContent = dict.status_disconnected;
      $("sideStatus").textContent = dict.status_disconnected;
      $("powerHint").textContent = dict.tap_to_connect;
    }

    $("speedDown").textContent = fmtSpeed(v.down_bps);
    $("speedUp").textContent = fmtSpeed(v.up_bps);
    $("sessionTraffic").textContent = fmtBytes(v.session_bytes);
    const delayBtn = $("delayTestBtn");
    if (delayBtn) delayBtn.disabled = !v.connected;
    if (!v.connected) {
      $("delayVal").textContent = "-- ms";
      $("delayVal").style.color = "";
    }

    const eb = $("errorBanner");
    if (v.last_error) {
      eb.textContent = v.last_error;
      eb.classList.add("show");
    } else {
      eb.classList.remove("show");
    }

    $("proxyModeSelect").value = state.settings.proxy_mode || "system_proxy";
    $("routingModeSelect").value = state.settings.routing_mode || "auto_cn";
  }

  function renderServers() {
    const list = $("serverList");
    list.innerHTML = "";

    if (!state.profiles.length) {
      list.innerHTML = `
        <div class="empty-state">
          <h3>${dict.empty_title}</h3>
          <p style="font-size:13px;max-width:420px;margin:0 auto 16px;">${dict.empty_desc}</p>
          <button class="btn btn-primary" id="emptyAddBtn">${dict.btn_add_vps}</button>
        </div>`;
      $("emptyAddBtn")?.addEventListener("click", openDeployModal);
      return;
    }

    const selectedId = getSelectedServer()?.id;
    for (const p of state.profiles) {
      const isSelected = p.id === selectedId;
      const st = state.statusMap[p.id];
      const dotClass = st ? (st.online ? "dot online" : "dot") : "dot";
      const bbrBadge =
        p.bbr_status === "enabled" || p.bbr_status === "already_enabled"
          ? `<span class="badge badge-green">BBR ✓</span>`
          : "";
      const protoToggle = p.hysteria2?.enabled
        ? `<button class="btn btn-sm" data-proto="${p.id}">${
            p.active_protocol === "hysteria2" ? "Switch REALITY" : "Switch Hy2"
          }</button>`
        : "";

      const card = document.createElement("div");
      card.className = `server-card${isSelected ? " selected" : ""}`;
      card.innerHTML = `
        <div class="server-left">
          <span class="${dotClass}"></span>
          <span style="font-size:20px;">${p.region_flag || "🌐"}</span>
          <div class="server-info">
            <div class="server-name">
              <span>${escapeHtml(p.name || p.host)}</span>
              <span class="badge badge-blue">${p.active_protocol === "hysteria2" ? "Hysteria2" : "REALITY"}</span>
              ${bbrBadge}
            </div>
            <div class="server-sub">
              ${escapeHtml(p.region_name ? `${p.region_name} · ` : "")}${escapeHtml(p.host)}:${p.vpn_port}
              · ${dict.used_traffic}: ${fmtBytes(p.total_used_bytes)}
              ${st?.tcp_ms ? ` · ${st.tcp_ms}ms` : ""}
            </div>
          </div>
        </div>
        <div class="btn-row">
          ${protoToggle}
          <button class="btn btn-sm ${isSelected ? "btn-primary" : ""}" data-select="${p.id}">
            ${isSelected ? dict.btn_selected : dict.btn_select}
          </button>
          <button class="btn btn-sm btn-danger" data-del="${p.id}">${dict.btn_delete}</button>
        </div>
      `;
      list.appendChild(card);
    }

    list.querySelectorAll("[data-select]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = btn.getAttribute("data-select");
        state.settings = await invoke("select_server", { serverId: id });
        renderConnect();
        renderServers();
        if (state.vpn.connected) {
          await invoke("connect_vpn", { serverId: id }).catch(() => {});
        }
      });
    });

    list.querySelectorAll("[data-proto]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = btn.getAttribute("data-proto");
        const srv = state.profiles.find((x) => x.id === id);
        if (!srv) return;
        const nextProto = srv.active_protocol === "hysteria2" ? "reality" : "hysteria2";
        state.profiles = await invoke("set_server_protocol", { serverId: id, protocol: nextProto });
        renderConnect();
        renderServers();
        if (state.vpn.connected && state.vpn.server_id === id) {
          await invoke("connect_vpn", { serverId: id }).catch(() => {});
        }
      });
    });

    list.querySelectorAll("[data-del]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = btn.getAttribute("data-del");
        if (state.vpn.connected && state.vpn.server_id === id) {
          state.vpn = await invoke("disconnect_vpn");
        }
        state.profiles = await invoke("delete_server", { serverId: id });
        const boot = await invoke("get_bootstrap");
        state.settings = boot.settings;
        renderConnect();
        renderServers();
      });
    });
  }

  function escapeHtml(s) {
    return String(s || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  async function saveSettings(patch) {
    state.settings = { ...state.settings, ...patch };
    state.settings = await invoke("save_app_settings", { settings: state.settings });
  }

  async function checkServers() {
    if (!invoke || !state.profiles.length) return;
    try {
      const results = await invoke("check_all_servers");
      for (const r of results) {
        state.statusMap[r.id] = r;
        const srv = state.profiles.find((p) => p.id === r.id);
        if (srv && r.region && !srv.region_name) {
          srv.region_flag = r.flag;
          srv.region_name = r.region;
          srv.country_code = r.country_code;
        }
      }
      renderConnect();
      renderServers();
    } catch (_) {}
  }

  async function refreshLogs() {
    if (!invoke) return;
    try {
      const lines = await invoke("get_diagnostics_logs");
      $("logsBox").textContent = lines.length ? lines.join("\n") : "No logs yet.";
      $("logsBox").scrollTop = $("logsBox").scrollHeight;
    } catch (_) {}
  }

  function openDeployModal() {
    $("inPass").value = "";
    $("inKey").value = "";
    $("hy2RiskChk").checked = false;
    $("deployProgressWrap").classList.remove("show");
    $("startDeployBtn").disabled = false;
    $("deployModal").classList.add("open");
  }

  function closeDeployModal() {
    $("inPass").value = "";
    $("inKey").value = "";
    $("deployModal").classList.remove("open");
  }

  async function executeDeploy(req) {
    $("startDeployBtn").disabled = true;
    $("deployProgressWrap").classList.add("show");
    $("deployProgressBar").style.width = "8%";
    $("deployProgressMsg").textContent = "Connecting to VPS...";

    try {
      const res = await invoke("deploy_vps", { request: req });
      if (res.status === "needs_host_key_confirm") {
        state.pendingDeployReq = { ...req, trusted_fingerprint: res.fingerprint };
        $("tofuFingerprint").textContent = res.fingerprint || "Unknown";
        $("tofuModal").classList.add("open");
        $("startDeployBtn").disabled = false;
        return;
      }
      if (res.status === "ok") {
        $("deployProgressBar").style.width = "100%";
        $("deployProgressMsg").textContent = res.message || "Deployed!";
        const boot = await invoke("get_bootstrap");
        state.profiles = boot.profiles;
        state.settings = boot.settings;
        setTimeout(() => {
          closeDeployModal();
          renderConnect();
          renderServers();
          checkServers();
          switchView("connect");
        }, 700);
      } else {
        $("deployProgressMsg").textContent = `Error: ${res.message || res.code}`;
        $("startDeployBtn").disabled = false;
      }
    } catch (err) {
      $("deployProgressMsg").textContent = `Error: ${err}`;
      $("startDeployBtn").disabled = false;
    }
  }

  async function init() {
    // Populate 17-language dropdown
    const langSel = $("langSelect");
    const autoOpt = document.createElement("option");
    autoOpt.value = "auto";
    autoOpt.textContent = "Auto (System)";
    langSel.appendChild(autoOpt);
    for (const l of window.BMV_LANGS) {
      const opt = document.createElement("option");
      opt.value = l.code;
      opt.textContent = l.label;
      langSel.appendChild(opt);
    }

    // Bind navigation
    document.querySelectorAll(".nav-item").forEach((btn) => {
      btn.addEventListener("click", () => switchView(btn.dataset.view));
    });

    $("activeNodeBar").addEventListener("click", () => {
      if (state.profiles.length) switchView("servers");
      else {
        switchView("servers");
        openDeployModal();
      }
    });

    let delayTesting = false;
    async function runDelayTest() {
      if (!state.vpn.connected || delayTesting) return;
      delayTesting = true;
      $("delayTestBtn").disabled = true;
      $("delaySpin").classList.add("spinning");
      $("delayVal").textContent = "...";
      $("delayVal").style.color = "var(--accent)";
      try {
        const r = await invoke("test_delay");
        if (r.ok) {
          $("delayVal").textContent = `${r.ms} ms`;
          $("delayVal").style.color = "var(--green)";
        } else {
          $("delayVal").textContent = "Timeout";
          $("delayVal").style.color = "var(--red)";
        }
      } catch (_) {
        $("delayVal").textContent = "Error";
        $("delayVal").style.color = "var(--red)";
      } finally {
        delayTesting = false;
        $("delaySpin").classList.remove("spinning");
        $("delayTestBtn").disabled = !state.vpn.connected;
      }
    }

    // Connect / Disconnect button
    $("powerBtn").addEventListener("click", async () => {
      if (state.vpn.connecting) return;
      if (state.vpn.connected) {
        state.vpn = await invoke("disconnect_vpn");
        $("delayVal").textContent = "-- ms";
        $("delayVal").style.color = "";
        renderConnect();
      } else {
        const srv = getSelectedServer();
        if (!srv) {
          switchView("servers");
          openDeployModal();
          return;
        }
        try {
          state.vpn = await invoke("connect_vpn", { serverId: srv.id });
          if (state.vpn.connected) {
            setTimeout(() => runDelayTest(), 800);
          }
        } catch (err) {
          state.vpn.last_error = String(err);
        }
        renderConnect();
      }
    });

    // Explicit Latency Test button + card tap
    $("delayTestBtn").addEventListener("click", (e) => {
      e.stopPropagation();
      runDelayTest();
    });
    $("delayCard").addEventListener("click", runDelayTest);

    // Proxy & Routing mode selectors
    $("proxyModeSelect").addEventListener("change", async (e) => {
      await saveSettings({ proxy_mode: e.target.value });
      if (state.vpn.connected) {
        await invoke("connect_vpn", { serverId: state.vpn.server_id }).catch((err) => {
          state.vpn.last_error = String(err);
        });
        renderConnect();
      }
    });

    $("routingModeSelect").addEventListener("change", async (e) => {
      await saveSettings({ routing_mode: e.target.value });
      if (state.vpn.connected) {
        await invoke("connect_vpn", { serverId: state.vpn.server_id }).catch(() => {});
      }
    });

    // Servers toolbar
    $("refreshServersBtn").addEventListener("click", checkServers);
    $("openAddModalBtn").addEventListener("click", openDeployModal);
    $("cancelDeployBtn").addEventListener("click", closeDeployModal);

    $("inAuthType").addEventListener("change", (e) => {
      const isKey = e.target.value === "key";
      $("passField").style.display = isKey ? "none" : "flex";
      $("keyField").style.display = isKey ? "flex" : "none";
    });

    $("inProto").addEventListener("change", (e) => {
      $("hy2WarnBox").classList.toggle("show", e.target.value === "dual");
    });

    $("startDeployBtn").addEventListener("click", () => {
      const host = $("inHost").value.trim();
      if (!host) {
        $("deployProgressWrap").classList.add("show");
        $("deployProgressMsg").textContent = "Please enter VPS IP or domain.";
        return;
      }
      const proto = $("inProto").value;
      if (proto === "dual" && !$("hy2RiskChk").checked) {
        $("deployProgressWrap").classList.add("show");
        $("deployProgressMsg").textContent = "Please confirm the Hysteria2 risk checkbox first.";
        return;
      }
      const isKey = $("inAuthType").value === "key";
      executeDeploy({
        name: $("inName").value.trim(),
        host,
        ssh_port: parseInt($("inPort").value, 10) || 22,
        username: $("inUser").value.trim() || "root",
        password: isKey ? "" : $("inPass").value,
        private_key_pem: isKey ? $("inKey").value : "",
        passphrase: "",
        protocol_mode: proto,
        trusted_fingerprint: null,
        action: "deploy",
        existing_server_id: null
      });
    });

    $("tofuCancelBtn").addEventListener("click", () => {
      state.pendingDeployReq = null;
      $("tofuModal").classList.remove("open");
    });
    $("tofuTrustBtn").addEventListener("click", () => {
      const req = state.pendingDeployReq;
      state.pendingDeployReq = null;
      $("tofuModal").classList.remove("open");
      if (req) executeDeploy(req);
    });

    // Settings controls
    langSel.addEventListener("change", async (e) => {
      await saveSettings({ language: e.target.value });
      applyLanguage(e.target.value);
    });
    $("themeSelect").addEventListener("change", async (e) => {
      await saveSettings({ theme_mode: e.target.value });
      applyTheme(e.target.value);
    });
    $("quickThemeBtn").addEventListener("click", async () => {
      const order = ["auto", "light", "dark"];
      const next = order[(order.indexOf(state.settings.theme_mode || "auto") + 1) % order.length];
      $("themeSelect").value = next;
      await saveSettings({ theme_mode: next });
      applyTheme(next);
    });
    $("localPortInput").addEventListener("change", async (e) => {
      const port = Math.min(65535, Math.max(1024, parseInt(e.target.value, 10) || 20808));
      e.target.value = port;
      await saveSettings({ local_proxy_port: port });
    });
    $("minimizeTrayChk").addEventListener("change", async (e) => {
      await saveSettings({ minimize_to_tray: e.target.checked });
    });
    $("updateRulesBtn").addEventListener("click", async () => {
      $("updateRulesBtn").disabled = true;
      $("rulesStatusText").textContent = "Updating .srs rules...";
      try {
        const msg = await invoke("update_routing_rules");
        $("rulesStatusText").textContent = msg;
      } catch (err) {
        $("rulesStatusText").textContent = `Error: ${err}`;
      }
      $("updateRulesBtn").disabled = false;
    });
    $("refreshLogsBtn").addEventListener("click", refreshLogs);

    async function runUpdateCheck(manual) {
      if (!invoke || state.updatingNow) return;
      const btn = $("checkUpdateBtn");
      const statusEl = $("updateStatusText");
      if (manual) {
        btn.disabled = true;
        statusEl.textContent = dict.update_checking || "Checking for updates...";
      }
      try {
        const res = await invoke("check_for_update");
        state.updateInfo = res;
        if (res.error) {
          if (manual) statusEl.textContent = res.error;
        } else if (res.has_update) {
          $("updateBannerTag").textContent = `v${res.latest_version}`;
          $("updateBannerText").textContent = `${dict.update_avail || "New version available"}: v${res.latest_version}`;
          $("updateBanner").classList.add("show");
          $("installUpdateBtn").style.display = "inline-flex";
          statusEl.textContent = `${dict.update_avail || "New version available"}: v${res.latest_version} (current v${res.current_version})`;
        } else {
          $("updateBanner").classList.remove("show");
          $("installUpdateBtn").style.display = "none";
          if (manual) {
            statusEl.textContent = `${dict.update_latest || "Latest version"} (v${res.current_version})`;
          }
        }
      } catch (err) {
        if (manual) statusEl.textContent = String(err);
      } finally {
        btn.disabled = false;
      }
    }

    async function startDownloadUpdate() {
      const info = state.updateInfo;
      if (!info || !info.has_update || state.updatingNow) return;
      if (!info.download_url) {
        await invoke("open_external_url", { url: info.release_url || "https://github.com/Gouryella811/build-myvpn/releases/latest" });
        return;
      }
      state.updatingNow = true;
      $("bannerUpdateBtn").disabled = true;
      $("installUpdateBtn").disabled = true;
      $("checkUpdateBtn").disabled = true;
      switchView("settings");
      $("updateProgressWrap").classList.add("show");
      $("updateProgressBar").style.width = "5%";
      $("updateProgressMsg").textContent = dict.update_downloading || "Downloading update...";
      try {
        await invoke("download_and_install_update", {
          url: info.download_url,
          assetName: info.asset_name || ""
        });
        $("updateProgressBar").style.width = "100%";
        $("updateProgressMsg").textContent = dict.update_opening || "Installer opened";
        $("updateBanner").classList.remove("show");
      } catch (err) {
        $("updateProgressMsg").textContent = `${err}`;
        // Fallback: let user download via browser if direct download failed
        await invoke("open_external_url", { url: info.download_url }).catch(() => {});
      } finally {
        state.updatingNow = false;
        $("bannerUpdateBtn").disabled = false;
        $("installUpdateBtn").disabled = false;
        $("checkUpdateBtn").disabled = false;
      }
    }

    $("checkUpdateBtn").addEventListener("click", () => runUpdateCheck(true));
    $("installUpdateBtn").addEventListener("click", startDownloadUpdate);
    $("bannerUpdateBtn").addEventListener("click", startDownloadUpdate);
    $("bannerLaterBtn").addEventListener("click", () => $("updateBanner").classList.remove("show"));

    // External links
    $("openWebBtn").addEventListener("click", () =>
      invoke("open_external_url", { url: "https://build-myvpn.pages.dev" })
    );
    $("openGhBtn").addEventListener("click", () =>
      invoke("open_external_url", { url: "https://github.com/Gouryella811/build-myvpn" })
    );
    $("openPrivacyBtn").addEventListener("click", () =>
      invoke("open_external_url", { url: "https://build-myvpn.pages.dev/privacy.html" })
    );
    $("sponsorBar").addEventListener("click", () =>
      invoke("open_external_url", { url: state.monetizationUrl })
    );

    // Live Tauri events
    if (listen) {
      await listen("vpn-status", (event) => {
        state.vpn = event.payload;
        renderConnect();
      });
      await listen("deploy-progress", (event) => {
        const p = event.payload;
        $("deployProgressWrap").classList.add("show");
        $("deployProgressBar").style.width = `${p.pct || 20}%`;
        $("deployProgressMsg").textContent = p.message || p.step;
      });
      await listen("update-download-progress", (event) => {
        const p = event.payload;
        $("updateProgressWrap").classList.add("show");
        $("updateProgressBar").style.width = `${p.pct || 0}%`;
        $("updateProgressMsg").textContent =
          p.status === "launching"
            ? dict.update_opening || "Launching installer..."
            : `${dict.update_downloading || "Downloading"} ${p.pct || 0}% (${fmtBytes(p.downloaded_bytes)})`;
      });
    }

    // Bootstrap state from Rust backend
    if (invoke) {
      const boot = await invoke("get_bootstrap");
      state.profiles = boot.profiles || [];
      state.settings = boot.settings || state.settings;
      state.vpn = boot.vpn || state.vpn;
      $("appVer").textContent = `v${boot.version || "1.1.0"} · ${boot.platform || ""}`;
      $("aboutMeta").textContent = `v${boot.version || "1.1.0"} (${boot.platform}/${boot.arch}) · GPLv3+`;

      langSel.value = state.settings.language || "auto";
      $("themeSelect").value = state.settings.theme_mode || "auto";
      $("localPortInput").value = state.settings.local_proxy_port || 20808;
      $("minimizeTrayChk").checked = state.settings.minimize_to_tray !== false;

      applyTheme(state.settings.theme_mode);
      applyLanguage(state.settings.language);
      renderConnect();
      renderServers();
      checkServers();

      // Silent auto-check for app updates 2 seconds after launch
      setTimeout(() => runUpdateCheck(false), 2000);

      // Load remote sponsor config
      invoke("get_monetization_config")
        .then((cfg) => {
          const b = cfg?.connect_banner;
          if (b && b.enabled) {
            $("sponsorBadge").textContent = b.badge || "AD";
            $("sponsorTitle").textContent = b.title || "Build MyVpn";
            $("sponsorSub").textContent = b.subtitle || "";
            state.monetizationUrl = b.url || "https://build-myvpn.pages.dev/#sponsor";
          } else {
            $("sponsorBar").style.display = "none";
          }
        })
        .catch(() => {});
    } else {
      applyTheme("auto");
      applyLanguage("auto");
    }

    // Auto-refresh time theme every 5 minutes
    setInterval(() => {
      if ((state.settings.theme_mode || "auto") === "auto") {
        applyTheme("auto");
      }
    }, 300000);
  }

  document.addEventListener("DOMContentLoaded", init);
})();
