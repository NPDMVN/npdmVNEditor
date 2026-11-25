use crate::error::{DmeditError, Result};
use crate::model::{BackupArchiveSummary, BackupEntry, BackupManifest, GameProfile};
use crate::sanitizer::sanitize_playerprefs_xml;
use sha2::{Digest, Sha256};
use std::collections::{BTreeMap, HashSet};
use std::fs::{self, File};
use std::io::{Read, Write};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

pub const MANIFEST_NAME: &str = "manifest.json";

pub fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    hex::encode(hasher.finalize())
}

fn sanitize_zip_path(path: &str) -> Result<String> {
    let normalized = path.replace('\\', "/");
    let trimmed = normalized.trim_start_matches('/');
    if trimmed.is_empty() || trimmed.contains("..") {
        return Err(DmeditError::Backup(format!(
            "Unsafe ZIP entry path rejected: '{path}'"
        )));
    }
    Ok(trimmed.to_string())
}

pub fn create_backup(
    target_path: &Path,
    profile: &GameProfile,
    package_name: &str,
    game_files: &BTreeMap<String, Vec<u8>>,
    shared_prefs: Option<(&str, &[u8])>,
    sanitize_account_data: bool,
    app_version: &str,
) -> Result<BackupManifest> {
    if let Some(parent) = target_path.parent() {
        fs::create_dir_all(parent)?;
    }
    let tmp_path = target_path.with_extension("skdb.tmp");
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    let write_res: Result<BackupManifest> = (|| {
        let file = File::create(&tmp_path)?;
        let mut zip = ZipWriter::new(file);
        let mut entries = Vec::new();

        for (rel_path, raw_bytes) in game_files {
            let safe_rel = sanitize_zip_path(rel_path)?;
            let archive_path = format!("files/{safe_rel}");
            zip.start_file(&archive_path, options)
                .map_err(|e| DmeditError::Backup(e.to_string()))?;
            zip.write_all(raw_bytes)?;

            entries.push(BackupEntry {
                archive_path,
                target_relative_path: safe_rel,
                size_bytes: raw_bytes.len() as u64,
                sha256: sha256_hex(raw_bytes),
                is_shared_prefs: false,
            });
        }

        if let Some((prefs_name, prefs_bytes)) = shared_prefs {
            let final_bytes = if sanitize_account_data {
                let xml = std::str::from_utf8(prefs_bytes)
                    .map_err(|e| DmeditError::Xml(e.to_string()))?;
                sanitize_playerprefs_xml(xml, &profile.sanitizer)?
                    .xml
                    .into_bytes()
            } else {
                prefs_bytes.to_vec()
            };
            let safe_name = sanitize_zip_path(prefs_name)?;
            let archive_path = format!("shared_prefs/{safe_name}");
            zip.start_file(&archive_path, options)
                .map_err(|e| DmeditError::Backup(e.to_string()))?;
            zip.write_all(&final_bytes)?;

            entries.push(BackupEntry {
                archive_path,
                target_relative_path: safe_name,
                size_bytes: final_bytes.len() as u64,
                sha256: sha256_hex(&final_bytes),
                is_shared_prefs: true,
            });
        }

        let now_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0);

        let manifest = BackupManifest {
            schema_version: 2,
            app_version: app_version.to_string(),
            game_profile_id: profile.id.clone(),
            package_name: package_name.to_string(),
            created_at_epoch_ms: now_ms,
            sanitized: sanitize_account_data,
            entries,
        };

        let manifest_json = serde_json::to_vec_pretty(&manifest)
            .map_err(|e| DmeditError::Backup(e.to_string()))?;
        zip.start_file(MANIFEST_NAME, options)
            .map_err(|e| DmeditError::Backup(e.to_string()))?;
        zip.write_all(&manifest_json)?;
        zip.finish().map_err(|e| DmeditError::Backup(e.to_string()))?;
        Ok(manifest)
    })();

    match write_res {
        Ok(manifest) => {
            if target_path.exists() {
                let _ = fs::remove_file(target_path);
            }
            fs::rename(&tmp_path, target_path)?;
            Ok(manifest)
        }
        Err(e) => {
            let _ = fs::remove_file(&tmp_path);
            Err(e)
        }
    }
}

pub fn inspect_backup(archive_path: &Path) -> Result<BackupArchiveSummary> {
    let meta = fs::metadata(archive_path)?;
    let file = File::open(archive_path)?;
    let mut zip = ZipArchive::new(file).map_err(|e| DmeditError::Backup(e.to_string()))?;

    let mut entry_names = Vec::new();
    let mut manifest: Option<BackupManifest> = None;

    for i in 0..zip.len() {
        let mut entry = zip
            .by_index(i)
            .map_err(|e| DmeditError::Backup(e.to_string()))?;
        if entry.is_dir() {
            continue;
        }
        let name = entry.name().to_string();
        if name == MANIFEST_NAME {
            let mut text = String::new();
            entry.read_to_string(&mut text)?;
            manifest = Some(
                serde_json::from_str(&text).map_err(|e| DmeditError::Backup(e.to_string()))?,
            );
        } else {
            entry_names.push(name);
        }
    }

    Ok(BackupArchiveSummary {
        file_name: archive_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("backup.skdb")
            .to_string(),
        file_path: archive_path.to_string_lossy().to_string(),
        size_bytes: meta.len(),
        is_legacy_skdb: manifest.is_none(),
        manifest,
        entry_names,
    })
}

pub fn verify_and_read_entry(archive_path: &Path, entry_archive_path: &str) -> Result<Vec<u8>> {
    let safe_target = sanitize_zip_path(entry_archive_path)?;
    let file = File::open(archive_path)?;
    let mut zip = ZipArchive::new(file).map_err(|e| DmeditError::Backup(e.to_string()))?;

    let expected_sha = match zip.by_name(MANIFEST_NAME) {
        Ok(mut mf) => {
            let mut txt = String::new();
            mf.read_to_string(&mut txt)?;
            let manifest: BackupManifest =
                serde_json::from_str(&txt).map_err(|e| DmeditError::Backup(e.to_string()))?;
            manifest
                .entries
                .into_iter()
                .find(|e| e.archive_path == safe_target)
                .map(|e| e.sha256)
        }
        Err(_) => None,
    };

    let mut ze = zip
        .by_name(&safe_target)
        .map_err(|_| DmeditError::NotFound(format!("Entry '{safe_target}' not found in .skdb")))?;
    let mut data = Vec::new();
    ze.read_to_end(&mut data)?;

    if let Some(expected) = expected_sha {
        let actual = sha256_hex(&data);
        if !actual.eq_ignore_ascii_case(&expected) {
            return Err(DmeditError::Verification(format!(
                "SHA-256 mismatch for '{safe_target}': archive corrupted"
            )));
        }
    }
    Ok(data)
}

pub fn verify_and_read_entries(
    archive_path: &Path,
    selected_paths: Option<&HashSet<String>>,
) -> Result<Vec<(BackupEntry, Vec<u8>)>> {
    let summary = inspect_backup(archive_path)?;
    let mut out = Vec::new();

    if let Some(manifest) = summary.manifest {
        for entry in manifest.entries {
            if let Some(sel) = selected_paths {
                if !sel.contains(&entry.archive_path) {
                    continue;
                }
            }
            let bytes = verify_and_read_entry(archive_path, &entry.archive_path)?;
            out.push((entry, bytes));
        }
    } else {
        for name in summary.entry_names {
            if let Some(sel) = selected_paths {
                if !sel.contains(&name) {
                    continue;
                }
            }
            let bytes = verify_and_read_entry(archive_path, &name)?;
            let is_prefs = name.ends_with(".xml") || name.starts_with("shared_prefs/");
            let rel_target = name
                .strip_prefix("files/")
                .or_else(|| name.strip_prefix("shared_prefs/"))
                .unwrap_or(&name)
                .to_string();
            let entry = BackupEntry {
                archive_path: name,
                target_relative_path: rel_target,
                size_bytes: bytes.len() as u64,
                sha256: sha256_hex(&bytes),
                is_shared_prefs: is_prefs,
            };
            out.push((entry, bytes));
        }
    }

    Ok(out)
}

pub fn update_entry_in_archive(
    archive_path: &Path,
    entry_archive_path: &str,
    new_bytes: &[u8],
) -> Result<Option<BackupManifest>> {
    let safe_target = sanitize_zip_path(entry_archive_path)?;
    let tmp_path = archive_path.with_extension("skdb.edit.tmp");
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    let res: Result<Option<BackupManifest>> = (|| {
        let src_file = File::open(archive_path)?;
        let mut src_zip =
            ZipArchive::new(src_file).map_err(|e| DmeditError::Backup(e.to_string()))?;

        let existing_manifest: Option<BackupManifest> = match src_zip.by_name(MANIFEST_NAME) {
            Ok(mut mf) => {
                let mut txt = String::new();
                mf.read_to_string(&mut txt)?;
                Some(serde_json::from_str(&txt).map_err(|e| DmeditError::Backup(e.to_string()))?)
            }
            Err(_) => None,
        };

        let dst_file = File::create(&tmp_path)?;
        let mut dst_zip = ZipWriter::new(dst_file);
        let mut wrote_target = false;

        for i in 0..src_zip.len() {
            let mut ze = src_zip
                .by_index(i)
                .map_err(|e| DmeditError::Backup(e.to_string()))?;
            let name = ze.name().to_string();
            if name == MANIFEST_NAME {
                continue;
            }
            dst_zip
                .start_file(&name, options)
                .map_err(|e| DmeditError::Backup(e.to_string()))?;
            if name == safe_target {
                dst_zip.write_all(new_bytes)?;
                wrote_target = true;
            } else {
                let mut buf = Vec::new();
                ze.read_to_end(&mut buf)?;
                dst_zip.write_all(&buf)?;
            }
        }

        if !wrote_target {
            dst_zip
                .start_file(&safe_target, options)
                .map_err(|e| DmeditError::Backup(e.to_string()))?;
            dst_zip.write_all(new_bytes)?;
        }

        let updated_manifest = existing_manifest.map(|mut m| {
            let new_hash = sha256_hex(new_bytes);
            for e in &mut m.entries {
                if e.archive_path == safe_target {
                    e.size_bytes = new_bytes.len() as u64;
                    e.sha256 = new_hash.clone();
                }
            }
            m
        });

        if let Some(ref mf) = updated_manifest {
            let mf_bytes =
                serde_json::to_vec_pretty(mf).map_err(|e| DmeditError::Backup(e.to_string()))?;
            dst_zip
                .start_file(MANIFEST_NAME, options)
                .map_err(|e| DmeditError::Backup(e.to_string()))?;
            dst_zip.write_all(&mf_bytes)?;
        }

        dst_zip
            .finish()
            .map_err(|e| DmeditError::Backup(e.to_string()))?;
        Ok(updated_manifest)
    })();

    match res {
        Ok(manifest) => {
            if archive_path.exists() {
                let _ = fs::remove_file(archive_path);
            }
            fs::rename(&tmp_path, archive_path)?;
            Ok(manifest)
        }
        Err(e) => {
            let _ = fs::remove_file(&tmp_path);
            Err(e)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::SanitizerConfig;

    #[test]
    fn skdb_v2_create_verify_and_inline_edit() {
        let dir = std::env::temp_dir().join(format!("dmedit_skdb_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let archive = dir.join("test.skdb");

        let profile = GameProfile {
            schema_version: 2,
            id: "chillyroom.soul_knight".into(),
            display_name: "Soul Knight".into(),
            developer: "ChillyRoom".into(),
            engine: "unity".into(),
            packages: vec!["com.ChillyRoom.DungeonShooter".into()],
            paths: Default::default(),
            codecs: BTreeMap::new(),
            file_rules: vec![],
            sanitizer: SanitizerConfig {
                player_prefs_remove_patterns: vec!["^cloudSaveId$".into()],
                json_strip_keys: vec![],
                account_suffix_regex: None,
            },
            features: Default::default(),
            is_generic: false,
        };

        let mut files = BTreeMap::new();
        files.insert("game.data".into(), br#"{"gems":1000}"#.to_vec());
        let prefs = br#"<?xml version='1.0' encoding='utf-8'?><map><string name="cloudSaveId">abc</string><int name="vol" value="1"/></map>"#;

        let manifest = create_backup(
            &archive,
            &profile,
            "com.ChillyRoom.DungeonShooter",
            &files,
            Some(("com.ChillyRoom.DungeonShooter.v2.playerprefs.xml", prefs)),
            true,
            "1.0.0",
        )
        .unwrap();
        assert_eq!(manifest.entries.len(), 2);

        let extracted = verify_and_read_entries(&archive, None).unwrap();
        let prefs_after = String::from_utf8(extracted[1].1.clone()).unwrap();
        assert!(!prefs_after.contains("cloudSaveId"));
        assert!(prefs_after.contains("vol"));

        update_entry_in_archive(&archive, "files/game.data", br#"{"gems":999999}"#).unwrap();
        let updated_game = verify_and_read_entry(&archive, "files/game.data").unwrap();
        assert_eq!(updated_game, br#"{"gems":999999}"#);
        let _ = fs::remove_dir_all(&dir);
    }
}
