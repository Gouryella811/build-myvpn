#[cfg(target_os = "macos")]
use std::process::Command;

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;
#[cfg(target_os = "windows")]
use std::process::Command;

#[cfg(target_os = "windows")]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[cfg(target_os = "windows")]
#[link(name = "wininet")]
extern "system" {
    fn InternetSetOptionW(
        h_internet: *mut std::ffi::c_void,
        dw_option: u32,
        lp_buffer: *mut std::ffi::c_void,
        dw_buffer_length: u32,
    ) -> i32;
}

#[cfg(target_os = "windows")]
fn refresh_wininet() {
    const INTERNET_OPTION_REFRESH: u32 = 37;
    const INTERNET_OPTION_SETTINGS_CHANGED: u32 = 39;
    unsafe {
        InternetSetOptionW(
            std::ptr::null_mut(),
            INTERNET_OPTION_SETTINGS_CHANGED,
            std::ptr::null_mut(),
            0,
        );
        InternetSetOptionW(
            std::ptr::null_mut(),
            INTERNET_OPTION_REFRESH,
            std::ptr::null_mut(),
            0,
        );
    }
}

#[cfg(target_os = "macos")]
fn active_network_services() -> Vec<String> {
    let out = Command::new("/usr/sbin/networksetup")
        .arg("-listallnetworkservices")
        .output();
    let Ok(output) = out else {
        return Vec::new();
    };
    let text = String::from_utf8_lossy(&output.stdout);
    text.lines()
        .skip(1)
        .map(|s| s.trim())
        .filter(|s| !s.is_empty() && !s.starts_with('*'))
        .map(|s| s.to_string())
        .collect()
}

pub fn enable_system_proxy(host: &str, port: u16) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    {
        let port_s = port.to_string();
        let services = active_network_services();
        if services.is_empty() {
            return Err("No active macOS network services found".to_string());
        }
        for svc in services {
            let _ = Command::new("/usr/sbin/networksetup")
                .args(["-setwebproxy", &svc, host, &port_s])
                .status();
            let _ = Command::new("/usr/sbin/networksetup")
                .args(["-setsecurewebproxy", &svc, host, &port_s])
                .status();
            let _ = Command::new("/usr/sbin/networksetup")
                .args(["-setsocksfirewallproxy", &svc, host, &port_s])
                .status();
            let _ = Command::new("/usr/sbin/networksetup")
                .args(["-setwebproxystate", &svc, "on"])
                .status();
            let _ = Command::new("/usr/sbin/networksetup")
                .args(["-setsecurewebproxystate", &svc, "on"])
                .status();
            let _ = Command::new("/usr/sbin/networksetup")
                .args(["-setsocksfirewallproxystate", &svc, "on"])
                .status();
            let _ = Command::new("/usr/sbin/networksetup")
                .args([
                    "-setproxybypassdomains",
                    &svc,
                    "localhost",
                    "127.0.0.1",
                    "::1",
                    "10.0.0.0/8",
                    "172.16.0.0/12",
                    "192.168.0.0/16",
                    "*.local",
                ])
                .status();
        }
        return Ok(());
    }

    #[cfg(target_os = "windows")]
    {
        let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        let server = format!("{host}:{port}");
        let bypass = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>";

        for args in [
            vec!["add", key, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f"],
            vec!["add", key, "/v", "ProxyServer", "/t", "REG_SZ", "/d", &server, "/f"],
            vec!["add", key, "/v", "ProxyOverride", "/t", "REG_SZ", "/d", bypass, "/f"],
        ] {
            let st = Command::new("reg.exe")
                .args(args)
                .creation_flags(CREATE_NO_WINDOW)
                .status()
                .map_err(|e| format!("Failed running reg.exe: {e}"))?;
            if !st.success() {
                return Err("Failed updating Windows proxy registry".to_string());
            }
        }
        refresh_wininet();
        return Ok(());
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        let _ = (host, port);
        Ok(())
    }
}

pub fn disable_system_proxy() {
    #[cfg(target_os = "macos")]
    {
        for svc in active_network_services() {
            let _ = Command::new("/usr/sbin/networksetup")
                .args(["-setwebproxystate", &svc, "off"])
                .status();
            let _ = Command::new("/usr/sbin/networksetup")
                .args(["-setsecurewebproxystate", &svc, "off"])
                .status();
            let _ = Command::new("/usr/sbin/networksetup")
                .args(["-setsocksfirewallproxystate", &svc, "off"])
                .status();
        }
    }

    #[cfg(target_os = "windows")]
    {
        let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings";
        let _ = Command::new("reg.exe")
            .args(["add", key, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f"])
            .creation_flags(CREATE_NO_WINDOW)
            .status();
        refresh_wininet();
    }
}
