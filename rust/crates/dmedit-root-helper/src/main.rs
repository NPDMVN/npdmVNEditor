//! Standalone native Rust Root Helper (`dmedit-helper`) for Android / LDPlayer.
//! Replaces fragile BusyBox/Toybox shell parsing with exact JSON output & atomic POSIX writes.

use serde::Serialize;
use std::env;
use std::fs;
use std::path::Path;
use std::process::ExitCode;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct StatJson {
    name: String,
    full_path: String,
    mode_octal: String,
    uid: u32,
    gid: u32,
    size_bytes: u64,
    is_directory: bool,
    is_symlink: bool,
}

fn main() -> ExitCode {
    let args: Vec<String> = env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: dmedit-helper <ping|stat <path>|ls <dir>|atomic-write <src> <dst> <mode_octal> <uid> <gid>>");
        return ExitCode::from(2);
    }

    let result = match args[1].as_str() {
        "ping" => {
            println!("ok");
            Ok(())
        }
        "stat" => {
            if args.len() < 3 {
                Err("Missing <path>".into())
            } else {
                cmd_stat(&args[2])
            }
        }
        "ls" => {
            if args.len() < 3 {
                Err("Missing <dir>".into())
            } else {
                cmd_ls(&args[2])
            }
        }
        "atomic-write" => {
            if args.len() < 7 {
                Err("Usage: atomic-write <src> <dst> <mode_octal> <uid> <gid>".into())
            } else {
                cmd_atomic_write(&args[2], &args[3], &args[4], &args[5], &args[6])
            }
        }
        other => Err(format!("Unknown subcommand: {other}")),
    };

    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(msg) => {
            eprintln!("{msg}");
            ExitCode::FAILURE
        }
    }
}

fn stat_entry(path: &Path) -> Result<StatJson, String> {
    let sym_meta = fs::symlink_metadata(path).map_err(|e| format!("stat failed: {e}"))?;
    let is_symlink = sym_meta.file_type().is_symlink();
    let meta = if is_symlink {
        fs::metadata(path).unwrap_or(sym_meta)
    } else {
        sym_meta
    };

    #[cfg(unix)]
    let (mode_octal, uid, gid) = {
        use std::os::unix::fs::MetadataExt;
        (format!("{:o}", meta.mode() & 0o7777), meta.uid(), meta.gid())
    };
    #[cfg(not(unix))]
    let (mode_octal, uid, gid) = ("660".to_string(), 0u32, 0u32);

    Ok(StatJson {
        name: path
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or("")
            .to_string(),
        full_path: path.to_string_lossy().to_string(),
        mode_octal,
        uid,
        gid,
        size_bytes: meta.len(),
        is_directory: meta.is_dir(),
        is_symlink,
    })
}

fn cmd_stat(path: &str) -> Result<(), String> {
    let info = stat_entry(Path::new(path))?;
    let out = serde_json::to_string(&info).map_err(|e| e.to_string())?;
    println!("{out}");
    Ok(())
}

fn cmd_ls(dir: &str) -> Result<(), String> {
    let rd = fs::read_dir(dir).map_err(|e| format!("read_dir failed: {e}"))?;
    let mut items = Vec::new();
    for entry in rd.flatten() {
        if let Ok(info) = stat_entry(&entry.path()) {
            items.push(info);
        }
    }
    let out = serde_json::to_string(&items).map_err(|e| e.to_string())?;
    println!("{out}");
    Ok(())
}

fn cmd_atomic_write(
    src: &str,
    dst: &str,
    mode_octal: &str,
    uid_str: &str,
    gid_str: &str,
) -> Result<(), String> {
    let dst_path = Path::new(dst);
    if let Some(parent) = dst_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("mkdir failed: {e}"))?;
    }
    let tmp_path = format!("{dst}.dmedit.tmp");
    fs::copy(src, &tmp_path).map_err(|e| format!("copy failed: {e}"))?;
    fs::rename(&tmp_path, dst_path).map_err(|e| format!("atomic rename failed: {e}"))?;

    #[cfg(unix)]
    {
        use std::ffi::CString;
        let mode = u32::from_str_radix(mode_octal, 8).unwrap_or(0o660);
        let uid = uid_str.parse::<u32>().unwrap_or(0);
        let gid = gid_str.parse::<u32>().unwrap_or(0);
        let c_dst = CString::new(dst).map_err(|e| e.to_string())?;
        unsafe {
            libc::chmod(c_dst.as_ptr(), mode as libc::mode_t);
            if uid > 0 || gid > 0 {
                libc::chown(c_dst.as_ptr(), uid, gid);
            }
        }
    }
    #[cfg(not(unix))]
    {
        let _ = (mode_octal, uid_str, gid_str);
    }

    Ok(())
}
