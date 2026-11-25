use dmedit_core::abx::{
    generate_random_ssaid, inspect_ssaid, update_ssaid_verified, SsaidUpdateMeta,
};
use dmedit_core::ast::{
    batch_set_map_values, batch_update_nested_pairs, diff_json_texts, merge_json_preset,
    patch_json_text, patch_map_entry,
};
use dmedit_core::backup::{
    create_backup, inspect_backup, update_entry_in_archive, verify_and_read_entries,
    verify_and_read_entry,
};
use dmedit_core::codec::{
    beautify_json, decrypt_with_profile, encrypt_with_profile, has_valid_key_configured,
    match_file_rule, minify_json, validate_content,
};
use dmedit_core::model::{DataFormat, GameProfile, SanitizerConfig};
use dmedit_core::sanitizer::{find_account_keys_in_playerprefs, sanitize_playerprefs_xml};
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::{BTreeMap, HashSet};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

fn throw_and_null(env: &mut JNIEnv, msg: &str) -> jstring {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
    std::ptr::null_mut()
}

fn throw_and_null_bytes(env: &mut JNIEnv, msg: &str) -> jbyteArray {
    let _ = env.throw_new("java/lang/IllegalStateException", msg);
    std::ptr::null_mut()
}

fn get_str(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|js| js.into())
        .map_err(|e| format!("JNI get_string failed: {e}"))
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    match env.new_string(s) {
        Ok(js) => js.into_raw(),
        Err(e) => throw_and_null(env, &format!("JNI new_string failed: {e}")),
    }
}

fn to_jbytearray(env: &mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(arr) => arr.into_raw(),
        Err(e) => throw_and_null_bytes(env, &format!("JNI byte_array_from_slice failed: {e}")),
    }
}

fn run_string_jni<F>(env: &mut JNIEnv, f: F) -> jstring
where
    F: FnOnce(&mut JNIEnv) -> Result<String, String>,
{
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(val)) => to_jstring(env, &val),
        Ok(Err(err)) => throw_and_null(env, &err),
        Err(_) => throw_and_null(env, "Rust core panic caught cleanly across JNI boundary"),
    }
}

fn run_bytes_jni<F>(env: &mut JNIEnv, f: F) -> jbyteArray
where
    F: FnOnce(&mut JNIEnv) -> Result<Vec<u8>, String>,
{
    match catch_unwind(AssertUnwindSafe(|| f(env))) {
        Ok(Ok(bytes)) => to_jbytearray(env, &bytes),
        Ok(Err(err)) => throw_and_null_bytes(env, &err),
        Err(_) => throw_and_null_bytes(env, "Rust core panic caught cleanly across JNI boundary"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeCoreBanner(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    run_string_jni(&mut env, |_| Ok(dmedit_core::core_banner()))
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeMatchFileRule(
    mut env: JNIEnv,
    _class: JClass,
    profile_json: JString,
    file_name: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let p_str = get_str(env, &profile_json)?;
        let fname = get_str(env, &file_name)?;
        let profile: GameProfile =
            serde_json::from_str(&p_str).map_err(|e| format!("Profile parse error: {e}"))?;
        let rule = match_file_rule(&profile, &fname);
        serde_json::to_string(&rule).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeHasValidCodecKey(
    mut env: JNIEnv,
    _class: JClass,
    profile_json: JString,
    codec_id: JString,
) -> jboolean {
    let res = catch_unwind(AssertUnwindSafe(|| -> Result<bool, String> {
        let p_str = get_str(&mut env, &profile_json)?;
        let cid = get_str(&mut env, &codec_id)?;
        let profile: GameProfile =
            serde_json::from_str(&p_str).map_err(|e| format!("Profile parse error: {e}"))?;
        Ok(has_valid_key_configured(&profile, &cid))
    }));
    match res {
        Ok(Ok(true)) => 1,
        _ => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeDecryptBytes(
    mut env: JNIEnv,
    _class: JClass,
    profile_json: JString,
    codec_id: JString,
    input: JByteArray,
) -> jbyteArray {
    run_bytes_jni(&mut env, |env| {
        let p_str = get_str(env, &profile_json)?;
        let cid = get_str(env, &codec_id)?;
        let data = env
            .convert_byte_array(&input)
            .map_err(|e| format!("JNI read bytes failed: {e}"))?;
        let profile: GameProfile =
            serde_json::from_str(&p_str).map_err(|e| format!("Profile parse error: {e}"))?;
        decrypt_with_profile(&profile, &cid, &data).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeEncryptBytes(
    mut env: JNIEnv,
    _class: JClass,
    profile_json: JString,
    codec_id: JString,
    plaintext: JByteArray,
    original_raw: JByteArray,
) -> jbyteArray {
    run_bytes_jni(&mut env, |env| {
        let p_str = get_str(env, &profile_json)?;
        let cid = get_str(env, &codec_id)?;
        let pt = env
            .convert_byte_array(&plaintext)
            .map_err(|e| format!("JNI read plaintext failed: {e}"))?;
        let orig = if original_raw.is_null() {
            None
        } else {
            Some(
                env.convert_byte_array(&original_raw)
                    .map_err(|e| format!("JNI read originalRaw failed: {e}"))?,
            )
        };
        let profile: GameProfile =
            serde_json::from_str(&p_str).map_err(|e| format!("Profile parse error: {e}"))?;
        encrypt_with_profile(&profile, &cid, &pt, orig.as_deref()).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeValidateContent(
    mut env: JNIEnv,
    _class: JClass,
    content: JString,
    format_name: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let text = get_str(env, &content)?;
        let fmt_str = get_str(env, &format_name)?;
        let fmt = match fmt_str.to_lowercase().as_str() {
            "json" => DataFormat::Json,
            "xml" => DataFormat::Xml,
            "binary" => DataFormat::Binary,
            _ => DataFormat::Text,
        };
        let res = validate_content(&text, fmt);
        serde_json::to_string(&res).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeBeautifyJson(
    mut env: JNIEnv,
    _class: JClass,
    content: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let text = get_str(env, &content)?;
        beautify_json(&text).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeMinifyJson(
    mut env: JNIEnv,
    _class: JClass,
    content: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let text = get_str(env, &content)?;
        minify_json(&text).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativePatchJsonPath(
    mut env: JNIEnv,
    _class: JClass,
    json_text: JString,
    json_path: JString,
    new_value_json: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let text = get_str(env, &json_text)?;
        let path = get_str(env, &json_path)?;
        let val = get_str(env, &new_value_json)?;
        patch_json_text(&text, &path, &val).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativePatchMapEntry(
    mut env: JNIEnv,
    _class: JClass,
    json_text: JString,
    map_path: JString,
    entry_key: JString,
    new_value_json: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let text = get_str(env, &json_text)?;
        let path = get_str(env, &map_path)?;
        let key = get_str(env, &entry_key)?;
        let val = get_str(env, &new_value_json)?;
        patch_map_entry(&text, &path, &key, &val).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeBatchSetMap(
    mut env: JNIEnv,
    _class: JClass,
    json_text: JString,
    map_path: JString,
    new_value_json: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let text = get_str(env, &json_text)?;
        let path = get_str(env, &map_path)?;
        let val = get_str(env, &new_value_json)?;
        batch_set_map_values(&text, &path, &val).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeBatchUpdateNestedPairs(
    mut env: JNIEnv,
    _class: JClass,
    json_text: JString,
    map_path: JString,
    group_key: JString,
    value_field: JString,
    new_value_json: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let text = get_str(env, &json_text)?;
        let path = get_str(env, &map_path)?;
        let gkey_opt = if group_key.is_null() {
            None
        } else {
            let s = get_str(env, &group_key)?;
            if s.is_empty() { None } else { Some(s) }
        };
        let vfield = get_str(env, &value_field)?;
        let val = get_str(env, &new_value_json)?;
        batch_update_nested_pairs(&text, &path, gkey_opt.as_deref(), &vfield, &val)
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeMergeJsonPreset(
    mut env: JNIEnv,
    _class: JClass,
    json_text: JString,
    target_path: JString,
    preset_json: JString,
    overwrite_existing: jboolean,
    dedupe_key: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let text = get_str(env, &json_text)?;
        let path = get_str(env, &target_path)?;
        let preset = get_str(env, &preset_json)?;
        let dk_opt = if dedupe_key.is_null() {
            None
        } else {
            let s = get_str(env, &dedupe_key)?;
            if s.is_empty() { None } else { Some(s) }
        };
        merge_json_preset(&text, &path, &preset, overwrite_existing != 0, dk_opt.as_deref())
            .map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeDiffTexts(
    mut env: JNIEnv,
    _class: JClass,
    original_text: JString,
    modified_text: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let orig = get_str(env, &original_text)?;
        let modi = get_str(env, &modified_text)?;
        let diffs = diff_json_texts(&orig, &modi);
        serde_json::to_string(&diffs).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeFindAccountKeys(
    mut env: JNIEnv,
    _class: JClass,
    xml_text: JString,
    sanitizer_json: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let xml = get_str(env, &xml_text)?;
        let cfg_str = get_str(env, &sanitizer_json)?;
        let cfg: SanitizerConfig = serde_json::from_str(&cfg_str).map_err(|e| e.to_string())?;
        let keys = find_account_keys_in_playerprefs(&xml, &cfg).map_err(|e| e.to_string())?;
        serde_json::to_string(&keys).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeSanitizePlayerPrefs(
    mut env: JNIEnv,
    _class: JClass,
    xml_text: JString,
    sanitizer_json: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let xml = get_str(env, &xml_text)?;
        let cfg_str = get_str(env, &sanitizer_json)?;
        let cfg: SanitizerConfig = serde_json::from_str(&cfg_str).map_err(|e| e.to_string())?;
        let res = sanitize_playerprefs_xml(&xml, &cfg).map_err(|e| e.to_string())?;
        serde_json::to_string(&res).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeGenerateRandomSsaid(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    run_string_jni(&mut env, |_| Ok(generate_random_ssaid()))
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeInspectSsaid(
    mut env: JNIEnv,
    _class: JClass,
    ssaid_bytes: JByteArray,
    package_name: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let bytes = env
            .convert_byte_array(&ssaid_bytes)
            .map_err(|e| e.to_string())?;
        let pkg = get_str(env, &package_name)?;
        let info = inspect_ssaid(&bytes, &pkg).map_err(|e| e.to_string())?;
        serde_json::to_string(&info).map_err(|e| e.to_string())
    })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SsaidUpdateJniResponse {
    meta: SsaidUpdateMeta,
    updated_bytes_hex: String,
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeUpdateSsaidVerified(
    mut env: JNIEnv,
    _class: JClass,
    original_bytes: JByteArray,
    package_name: JString,
    new_ssaid: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let bytes = env
            .convert_byte_array(&original_bytes)
            .map_err(|e| e.to_string())?;
        let pkg = get_str(env, &package_name)?;
        let ssaid = get_str(env, &new_ssaid)?;
        let (updated, meta) =
            update_ssaid_verified(&bytes, &pkg, &ssaid).map_err(|e| e.to_string())?;
        let resp = SsaidUpdateJniResponse {
            meta,
            updated_bytes_hex: bytes_to_hex(&updated),
        };
        serde_json::to_string(&resp).map_err(|e| e.to_string())
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreateBackupRequest {
    target_path: String,
    profile: GameProfile,
    package_name: String,
    game_files_staging: BTreeMap<String, String>,
    shared_prefs_file_name: Option<String>,
    shared_prefs_staging_path: Option<String>,
    sanitize_account_data: bool,
    app_version: String,
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeCreateBackupFromStaging(
    mut env: JNIEnv,
    _class: JClass,
    request_json: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let req_str = get_str(env, &request_json)?;
        let req: CreateBackupRequest =
            serde_json::from_str(&req_str).map_err(|e| format!("Invalid backup req: {e}"))?;

        let mut files_map = BTreeMap::new();
        for (rel_name, disk_path) in &req.game_files_staging {
            let bytes = std::fs::read(disk_path)
                .map_err(|e| format!("Failed reading staged file '{disk_path}': {e}"))?;
            files_map.insert(rel_name.clone(), bytes);
        }

        let prefs_bytes_owned = match &req.shared_prefs_staging_path {
            Some(p) => Some(
                std::fs::read(p).map_err(|e| format!("Failed reading staged prefs '{p}': {e}"))?,
            ),
            None => None,
        };
        let prefs_tuple = match (&req.shared_prefs_file_name, &prefs_bytes_owned) {
            (Some(name), Some(bytes)) => Some((name.as_str(), bytes.as_slice())),
            _ => None,
        };

        let manifest = create_backup(
            Path::new(&req.target_path),
            &req.profile,
            &req.package_name,
            &files_map,
            prefs_tuple,
            req.sanitize_account_data,
            &req.app_version,
        )
        .map_err(|e| e.to_string())?;
        serde_json::to_string(&manifest).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeInspectBackup(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let path = get_str(env, &archive_path)?;
        let info = inspect_backup(Path::new(&path)).map_err(|e| e.to_string())?;
        serde_json::to_string(&info).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeVerifyAndReadEntry(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    entry_archive_path: JString,
) -> jbyteArray {
    run_bytes_jni(&mut env, |env| {
        let path = get_str(env, &archive_path)?;
        let entry = get_str(env, &entry_archive_path)?;
        verify_and_read_entry(Path::new(&path), &entry).map_err(|e| e.to_string())
    })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ExtractedBackupFile {
    archive_path: String,
    target_relative_path: String,
    is_shared_prefs: bool,
    extracted_disk_path: String,
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeVerifyAndExtractToStaging(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    selected_paths_json: JString,
    staging_dir: JString,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let arch = get_str(env, &archive_path)?;
        let sel_str = get_str(env, &selected_paths_json)?;
        let stage = get_str(env, &staging_dir)?;
        let selected: Option<HashSet<String>> = if sel_str.trim().is_empty() {
            None
        } else {
            Some(serde_json::from_str(&sel_str).map_err(|e| e.to_string())?)
        };

        std::fs::create_dir_all(&stage).map_err(|e| e.to_string())?;
        let entries = verify_and_read_entries(Path::new(&arch), selected.as_ref())
            .map_err(|e| e.to_string())?;

        let mut out = Vec::with_capacity(entries.len());
        for (idx, (entry, bytes)) in entries.into_iter().enumerate() {
            let dest = Path::new(&stage).join(format!("restore_{idx}.bin"));
            std::fs::write(&dest, &bytes).map_err(|e| e.to_string())?;
            out.push(ExtractedBackupFile {
                archive_path: entry.archive_path,
                target_relative_path: entry.target_relative_path,
                is_shared_prefs: entry.is_shared_prefs,
                extracted_disk_path: dest.to_string_lossy().to_string(),
            });
        }
        serde_json::to_string(&out).map_err(|e| e.to_string())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_dmedit_core_nativebridge_RustCoreJni_nativeUpdateBackupEntry(
    mut env: JNIEnv,
    _class: JClass,
    archive_path: JString,
    entry_archive_path: JString,
    new_bytes: JByteArray,
) -> jstring {
    run_string_jni(&mut env, |env| {
        let arch = get_str(env, &archive_path)?;
        let entry = get_str(env, &entry_archive_path)?;
        let data = env
            .convert_byte_array(&new_bytes)
            .map_err(|e| e.to_string())?;
        let mf = update_entry_in_archive(Path::new(&arch), &entry, &data)
            .map_err(|e| e.to_string())?;
        serde_json::to_string(&mf).map_err(|e| e.to_string())
    })
}

fn bytes_to_hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        use std::fmt::Write;
        let _ = write!(&mut s, "{:02x}", b);
    }
    s
}
