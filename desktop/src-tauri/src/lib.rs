mod models;
mod rules;
mod singbox;
mod ssh_deploy;
mod storage;
mod sysproxy;
mod telemetry;
mod updater;

use futures_util::StreamExt;
use models::{
    ActiveProtocol, AppSettings, DelayResult, DeployRequest, DeployResult, ServerProfile,
    VpnStatus, VpsCheckResult,
};
use serde::Deserialize;
use serde_json::Value;
use singbox::{SingBoxManager, CLASH_API_PORT};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};
use tauri::{
    image::Image,
    menu::{MenuBuilder, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Emitter, Manager, RunEvent, State, WindowEvent,
};
use tokio::sync::Mutex;

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;

struct AppState {
    profiles: Mutex<Vec<ServerProfile>>,
    settings: Mutex<AppSettings>,
    minimize_to_tray: AtomicBool,
    singbox: Mutex<SingBoxManager>,
    vpn: Mutex<VpnStatus>,
    traffic_cancel: Mutex<Option<tokio::task::AbortHandle>>,
}

impl AppState {
    fn new() -> Self {
        let profiles = storage::load_profiles();
        let mut settings = storage::load_settings();
        if settings.selected_server_id.is_none() {
            settings.selected_server_id = profiles.first().map(|p| p.id.clone());
        }
        let minimize_to_tray = AtomicBool::new(settings.minimize_to_tray);
        let vpn = VpnStatus {
            connected: false,
            connecting: false,
            server_id: settings.selected_server_id.clone(),
            server_name: String::new(),
            proxy_mode: settings.proxy_mode.clone(),
            active_protocol: ActiveProtocol::Reality,
            local_proxy_port: settings.local_proxy_port,
            up_bps: 0,
            down_bps: 0,
            session_bytes: 0,
            last_error: None,
        };
        Self {
            profiles: Mutex::new(profiles),
            settings: Mutex::new(settings),
            minimize_to_tray,
            singbox: Mutex::new(SingBoxManager::new()),
            vpn: Mutex::new(vpn),
            traffic_cancel: Mutex::new(None),
        }
    }
}

#[derive(Debug, Deserialize)]
struct TrafficTick {
    up: u64,
    down: u64,
}

fn update_tray_icon(app: &AppHandle, connected: bool) {
    if let Some(tray) = app.tray_by_id("main-tray") {
        #[cfg(target_os = "windows")]
        {
            let bytes: &[u8] = if connected {
                include_bytes!("../icons/tray-win-connected.png")
            } else {
                include_bytes!("../icons/tray-win-idle.png")
            };
            if let Ok(img) = Image::from_bytes(bytes) {
                let _ = tray.set_icon(Some(img));
            }
        }
        #[cfg(target_os = "macos")]
        {
            let tooltip = if connected {
                "Build MyVpn — Connected"
            } else {
                "Build MyVpn — Disconnected"
            };
            let _ = tray.set_tooltip(Some(tooltip));
        }
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        let _ = (tray, connected);
    }
}

#[tauri::command]
async fn get_bootstrap(state: State<'_, Arc<AppState>>) -> Result<Value, String> {
    let profiles = state.profiles.lock().await.clone();
    let settings = state.settings.lock().await.clone();
    let vpn = state.vpn.lock().await.clone();
    Ok(serde_json::json!({
        "profiles": profiles,
        "settings": settings,
        "vpn": vpn,
        "platform": std::env::consts::OS,
        "arch": std::env::consts::ARCH,
        "version": env!("CARGO_PKG_VERSION")
    }))
}

#[tauri::command]
async fn save_app_settings(
    state: State<'_, Arc<AppState>>,
    settings: AppSettings,
) -> Result<AppSettings, String> {
    storage::save_settings(&settings)?;
    state
        .minimize_to_tray
        .store(settings.minimize_to_tray, Ordering::Relaxed);
    *state.settings.lock().await = settings.clone();
    Ok(settings)
}

#[tauri::command]
async fn select_server(
    state: State<'_, Arc<AppState>>,
    server_id: String,
) -> Result<AppSettings, String> {
    let mut s = state.settings.lock().await;
    s.selected_server_id = Some(server_id);
    storage::save_settings(&s)?;
    Ok(s.clone())
}

#[tauri::command]
async fn set_server_protocol(
    state: State<'_, Arc<AppState>>,
    server_id: String,
    protocol: ActiveProtocol,
) -> Result<Vec<ServerProfile>, String> {
    let mut list = state.profiles.lock().await;
    if let Some(p) = list.iter_mut().find(|x| x.id == server_id) {
        p.active_protocol = protocol;
        storage::save_profiles(&list)?;
    }
    Ok(list.clone())
}

#[tauri::command]
async fn delete_server(
    state: State<'_, Arc<AppState>>,
    server_id: String,
) -> Result<Vec<ServerProfile>, String> {
    let mut list = state.profiles.lock().await;
    list.retain(|x| x.id != server_id);
    storage::save_profiles(&list)?;
    let mut s = state.settings.lock().await;
    if s.selected_server_id.as_deref() == Some(server_id.as_str()) {
        s.selected_server_id = list.first().map(|p| p.id.clone());
        let _ = storage::save_settings(&s);
    }
    Ok(list.clone())
}

#[tauri::command]
async fn deploy_vps(
    app: AppHandle,
    state: State<'_, Arc<AppState>>,
    request: DeployRequest,
) -> Result<DeployResult, String> {
    let action = request.action.clone();
    let existing_id = request.existing_server_id.clone();
    let res = ssh_deploy::run_ssh_deploy(&app, request).await;

    if res.status == "ok" {
        if action == "uninstall" {
            if let Some(id) = existing_id {
                let mut list = state.profiles.lock().await;
                list.retain(|x| x.id != id);
                let _ = storage::save_profiles(&list);
            }
        } else if let Some(mut profile) = res.profile.clone() {
            let mut list = state.profiles.lock().await;
            if let Some(pos) = list.iter().position(|x| x.id == profile.id || x.host == profile.host)
            {
                profile.total_used_bytes = list[pos].total_used_bytes;
                profile.region_flag = list[pos].region_flag.clone();
                profile.region_name = list[pos].region_name.clone();
                profile.country_code = list[pos].country_code.clone();
                list[pos] = profile.clone();
            } else {
                list.push(profile.clone());
            }
            let _ = storage::save_profiles(&list);
            let mut s = state.settings.lock().await;
            s.selected_server_id = Some(profile.id);
            let _ = storage::save_settings(&s);
        }
    }
    Ok(res)
}

async fn connect_internal(app: AppHandle, state: Arc<AppState>, server_id: Option<String>) -> Result<VpnStatus, String> {
    let settings = state.settings.lock().await.clone();
    let target_id = server_id
        .or(settings.selected_server_id.clone())
        .ok_or_else(|| "No VPS server selected. Please add a VPS first.".to_string())?;

    let profile = {
        let list = state.profiles.lock().await;
        list.iter()
            .find(|p| p.id == target_id)
            .cloned()
            .ok_or_else(|| "Selected server not found".to_string())?
    };

    {
        let mut v = state.vpn.lock().await;
        v.connecting = true;
        v.last_error = None;
        v.server_id = Some(profile.id.clone());
        v.server_name = profile.name.clone();
        v.proxy_mode = settings.proxy_mode.clone();
        v.active_protocol = profile.active_protocol.clone();
        let _ = app.emit("vpn-status", v.clone());
    }

    // Best-effort rule update before connecting (fail-closed fallback if offline)
    if rules::rule_paths_for_mode(&settings.routing_mode).is_none() {
        let _ = rules::update_rules_for_mode(&settings.routing_mode).await;
    }

    let start_res = {
        let mut sb = state.singbox.lock().await;
        sb.start(
            &profile,
            &settings.routing_mode,
            &settings.proxy_mode,
            settings.local_proxy_port,
        )
        .await
    };

    if let Err(err) = start_res {
        let mut v = state.vpn.lock().await;
        v.connected = false;
        v.connecting = false;
        v.last_error = Some(err.clone());
        let snapshot = v.clone();
        drop(v);
        update_tray_icon(&app, false);
        let _ = app.emit("vpn-status", snapshot);
        return Err(err);
    }

    // Save last connected timestamp
    {
        let mut list = state.profiles.lock().await;
        if let Some(p) = list.iter_mut().find(|x| x.id == profile.id) {
            p.last_connected_ms = chrono::Utc::now().timestamp_millis();
            let _ = storage::save_profiles(&list);
        }
    }

    let snapshot = {
        let mut v = state.vpn.lock().await;
        v.connected = true;
        v.connecting = false;
        v.up_bps = 0;
        v.down_bps = 0;
        v.session_bytes = 0;
        v.last_error = None;
        v.clone()
    };
    update_tray_icon(&app, true);
    let _ = app.emit("vpn-status", snapshot.clone());

    // Start Clash API live traffic stream task
    spawn_traffic_monitor(app, state, profile.id).await;

    Ok(snapshot)
}

async fn spawn_traffic_monitor(app: AppHandle, state: Arc<AppState>, server_id: String) {
    if let Some(old) = state.traffic_cancel.lock().await.take() {
        old.abort();
    }

    let state_clone = state.clone();
    let handle = tokio::spawn(async move {
        let Ok(client) = reqwest::Client::builder().no_proxy().build() else {
            return;
        };
        let url = format!("http://127.0.0.1:{CLASH_API_PORT}/traffic");
        let mut pending_bytes: u64 = 0;
        let mut tick_count: u32 = 0;

        loop {
            let Ok(resp) = client.get(&url).send().await else {
                tokio::time::sleep(std::time::Duration::from_secs(2)).await;
                if !state_clone.vpn.lock().await.connected {
                    break;
                }
                continue;
            };

            let mut stream = resp.bytes_stream();
            let mut buf = String::new();
            while let Some(chunk) = stream.next().await {
                let Ok(bytes) = chunk else { break };
                buf.push_str(&String::from_utf8_lossy(&bytes));
                while let Some(nl) = buf.find('\n') {
                    let line = buf[..nl].trim().to_string();
                    buf.drain(..=nl);
                    if let Ok(tick) = serde_json::from_str::<TrafficTick>(&line) {
                        let delta = tick.up.saturating_add(tick.down);
                        pending_bytes = pending_bytes.saturating_add(delta);
                        tick_count = tick_count.wrapping_add(1);

                        let snapshot = {
                            let mut v = state_clone.vpn.lock().await;
                            if !v.connected {
                                return;
                            }
                            v.up_bps = tick.up;
                            v.down_bps = tick.down;
                            v.session_bytes = v.session_bytes.saturating_add(delta);
                            v.clone()
                        };
                        let _ = app.emit("vpn-status", snapshot);

                        if tick_count % 10 == 0 && pending_bytes > 0 {
                            let mut list = state_clone.profiles.lock().await;
                            if let Some(p) = list.iter_mut().find(|x| x.id == server_id) {
                                p.total_used_bytes = p.total_used_bytes.saturating_add(pending_bytes);
                                let _ = storage::save_profiles(&list);
                            }
                            pending_bytes = 0;
                        }
                    }
                }
            }

            if !state_clone.vpn.lock().await.connected {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_secs(1)).await;
        }
    });

    *state.traffic_cancel.lock().await = Some(handle.abort_handle());
}

async fn disconnect_internal(app: AppHandle, state: Arc<AppState>) -> VpnStatus {
    if let Some(h) = state.traffic_cancel.lock().await.take() {
        h.abort();
    }
    {
        let mut sb = state.singbox.lock().await;
        sb.stop().await;
    }

    let (server_id_opt, session_bytes) = {
        let mut v = state.vpn.lock().await;
        let sid = v.server_id.clone();
        let sb = v.session_bytes;
        v.connected = false;
        v.connecting = false;
        v.up_bps = 0;
        v.down_bps = 0;
        v.session_bytes = 0;
        (sid, sb)
    };

    // Ensure final session delta persisted if small (<10s connection)
    if let Some(sid) = server_id_opt {
        if session_bytes > 0 {
            let mut list = state.profiles.lock().await;
            if let Some(p) = list.iter_mut().find(|x| x.id == sid) {
                if p.total_used_bytes < session_bytes {
                    p.total_used_bytes = session_bytes;
                    let _ = storage::save_profiles(&list);
                }
            }
        }
    }

    let snapshot = state.vpn.lock().await.clone();
    update_tray_icon(&app, false);
    let _ = app.emit("vpn-status", snapshot.clone());
    snapshot
}

#[tauri::command]
async fn connect_vpn(
    app: AppHandle,
    state: State<'_, Arc<AppState>>,
    server_id: Option<String>,
) -> Result<VpnStatus, String> {
    connect_internal(app, state.inner().clone(), server_id).await
}

#[tauri::command]
async fn disconnect_vpn(
    app: AppHandle,
    state: State<'_, Arc<AppState>>,
) -> Result<VpnStatus, String> {
    Ok(disconnect_internal(app, state.inner().clone()).await)
}

#[tauri::command]
async fn test_delay() -> Result<DelayResult, String> {
    Ok(telemetry::measure_delay().await)
}

#[tauri::command]
async fn check_all_servers(state: State<'_, Arc<AppState>>) -> Result<Vec<VpsCheckResult>, String> {
    let list = state.profiles.lock().await.clone();
    let mut handles = Vec::new();
    for p in list {
        handles.push(tokio::spawn(telemetry::check_vps_status(
            p.id,
            p.host,
            p.vpn_port,
            p.region_flag,
            p.region_name,
            p.country_code,
        )));
    }
    let mut results = Vec::new();
    for h in handles {
        if let Ok(r) = h.await {
            results.push(r);
        }
    }

    // Persist any newly resolved region/flag info
    let mut changed = false;
    {
        let mut list = state.profiles.lock().await;
        for r in &results {
            if let Some(p) = list.iter_mut().find(|x| x.id == r.id) {
                if p.region_name.is_empty() && !r.region.is_empty() {
                    p.region_flag = r.flag.clone();
                    p.region_name = r.region.clone();
                    p.country_code = r.country_code.clone();
                    changed = true;
                }
            }
        }
        if changed {
            let _ = storage::save_profiles(&list);
        }
    }

    Ok(results)
}

#[tauri::command]
async fn update_routing_rules(state: State<'_, Arc<AppState>>) -> Result<String, String> {
    let mode = state.settings.lock().await.routing_mode.clone();
    rules::update_rules_for_mode(&mode).await
}

#[tauri::command]
async fn get_diagnostics_logs(state: State<'_, Arc<AppState>>) -> Result<Vec<String>, String> {
    Ok(state.singbox.lock().await.get_logs())
}

#[tauri::command]
async fn get_monetization_config() -> Result<Value, String> {
    Ok(telemetry::fetch_monetization_config().await)
}

#[tauri::command]
async fn check_for_update() -> Result<updater::UpdateCheckResult, String> {
    Ok(updater::check_for_update().await)
}

#[tauri::command]
async fn download_and_install_update(
    app: AppHandle,
    state: State<'_, Arc<AppState>>,
    url: String,
    asset_name: String,
) -> Result<String, String> {
    // Cleanly disconnect VPN before launching Windows installer so files aren't locked
    #[cfg(target_os = "windows")]
    {
        if state.vpn.lock().await.connected {
            disconnect_internal(app.clone(), state.inner().clone()).await;
        }
    }
    #[cfg(not(target_os = "windows"))]
    let _ = &state;
    updater::download_and_launch_update(&app, &url, &asset_name).await
}

#[tauri::command]
fn open_external_url(url: String) -> Result<(), String> {
    if !(url.starts_with("https://") || url.starts_with("http://") || url.starts_with("mailto:")) {
        return Err("Unsupported URL scheme".to_string());
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("/usr/bin/open")
            .arg(&url)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("cmd.exe")
            .args(["/C", "start", "", &url])
            .creation_flags(0x0800_0000)
            .spawn()
            .map_err(|e| e.to_string())?;
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        let _ = std::process::Command::new("xdg-open").arg(&url).spawn();
    }
    Ok(())
}

pub fn run() {
    let app_state = Arc::new(AppState::new());
    let state_for_tray = app_state.clone();
    let state_for_close = app_state.clone();

    tauri::Builder::default()
        .manage(app_state)
        .setup(move |app| {
            let show_item = MenuItem::with_id(app, "show", "Open Build MyVpn", true, None::<&str>)?;
            let toggle_item =
                MenuItem::with_id(app, "toggle", "Connect / Disconnect", true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;

            let menu = MenuBuilder::new(app)
                .item(&show_item)
                .item(&toggle_item)
                .separator()
                .item(&quit_item)
                .build()?;

            let mut tray_builder = TrayIconBuilder::with_id("main-tray")
                .menu(&menu)
                .tooltip("Build MyVpn")
                .show_menu_on_left_click(false);

            #[cfg(target_os = "macos")]
            {
                let img = Image::from_bytes(include_bytes!("../icons/tray-template@2x.png"))?;
                tray_builder = tray_builder.icon(img).icon_as_template(true);
            }
            #[cfg(target_os = "windows")]
            {
                let img = Image::from_bytes(include_bytes!("../icons/tray-win-idle.png"))?;
                tray_builder = tray_builder.icon(img);
            }
            #[cfg(not(any(target_os = "macos", target_os = "windows")))]
            {
                if let Some(icon) = app.default_window_icon() {
                    tray_builder = tray_builder.icon(icon.clone());
                }
            }

            let state_menu = state_for_tray.clone();
            tray_builder
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(win) = app.get_webview_window("main") {
                            let _ = win.show();
                            let _ = win.unminimize();
                            let _ = win.set_focus();
                        }
                    }
                })
                .on_menu_event(move |app, event| {
                    let app_handle = app.clone();
                    let st = state_menu.clone();
                    match event.id.as_ref() {
                        "show" => {
                            if let Some(win) = app.get_webview_window("main") {
                                let _ = win.show();
                                let _ = win.unminimize();
                                let _ = win.set_focus();
                            }
                        }
                        "toggle" => {
                            tauri::async_runtime::spawn(async move {
                                let is_connected = st.vpn.lock().await.connected;
                                if is_connected {
                                    disconnect_internal(app_handle, st).await;
                                } else {
                                    let _ = connect_internal(app_handle, st, None).await;
                                }
                            });
                        }
                        "quit" => {
                            tauri::async_runtime::spawn(async move {
                                disconnect_internal(app_handle.clone(), st).await;
                                app_handle.exit(0);
                            });
                        }
                        _ => {}
                    }
                })
                .build(app)?;

            Ok(())
        })
        .on_window_event(move |window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                if state_for_close.minimize_to_tray.load(Ordering::Relaxed) {
                    api.prevent_close();
                    let _ = window.hide();
                } else {
                    sysproxy::disable_system_proxy();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            get_bootstrap,
            save_app_settings,
            select_server,
            set_server_protocol,
            delete_server,
            deploy_vps,
            connect_vpn,
            disconnect_vpn,
            test_delay,
            check_all_servers,
            update_routing_rules,
            get_diagnostics_logs,
            get_monetization_config,
            check_for_update,
            download_and_install_update,
            open_external_url
        ])
        .build(tauri::generate_context!())
        .expect("error while building Build MyVpn desktop application")
        .run(|_app, event| {
            if let RunEvent::Exit = event {
                sysproxy::disable_system_proxy();
            }
        });
}
