use crate::error::{DmeditError, Result};
use crate::model::{ChangeType, DiffEntry};
use serde_json::{Map, Value};
use std::collections::HashSet;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PathSegment {
    Field(String),
    Index(usize),
}

pub fn parse_json_path(path: &str) -> Result<Vec<PathSegment>> {
    let cleaned = path.trim().strip_prefix('$').unwrap_or(path.trim());
    let cleaned = cleaned.strip_prefix('.').unwrap_or(cleaned);
    if cleaned.is_empty() {
        return Ok(Vec::new());
    }

    let bytes = cleaned.as_bytes();
    let mut segments = Vec::new();
    let mut i = 0;

    while i < bytes.len() {
        match bytes[i] as char {
            '.' => i += 1,
            '[' => {
                let end = cleaned[i + 1..]
                    .find(']')
                    .map(|off| i + 1 + off)
                    .ok_or_else(|| DmeditError::Ast(format!("Unclosed '[' in path: {path}")))?;
                let token = cleaned[i + 1..end].trim();
                if (token.starts_with('"') && token.ends_with('"'))
                    || (token.starts_with('\'') && token.ends_with('\''))
                {
                    segments.push(PathSegment::Field(token[1..token.len() - 1].to_string()));
                } else {
                    let idx = token
                        .parse::<usize>()
                        .map_err(|e| DmeditError::Ast(format!("Invalid index '{token}': {e}")))?;
                    segments.push(PathSegment::Index(idx));
                }
                i = end + 1;
            }
            _ => {
                let start = i;
                while i < bytes.len() && bytes[i] != b'.' && bytes[i] != b'[' {
                    i += 1;
                }
                segments.push(PathSegment::Field(cleaned[start..i].to_string()));
            }
        }
    }
    Ok(segments)
}

pub fn read_path<'a>(root: &'a Value, path: &str) -> Result<Option<&'a Value>> {
    let segments = parse_json_path(path)?;
    let mut current = root;
    for seg in segments {
        current = match seg {
            PathSegment::Field(ref name) => match current.as_object().and_then(|o| o.get(name)) {
                Some(v) => v,
                None => return Ok(None),
            },
            PathSegment::Index(idx) => match current.as_array().and_then(|a| a.get(idx)) {
                Some(v) => v,
                None => return Ok(None),
            },
        };
    }
    Ok(Some(current))
}

/// Lossless JSONPath patcher (backed by `serde_json` `preserve_order`):
/// updates ONLY the targeted node while preserving every unknown sibling key & order.
pub fn patch_json_text(json_text: &str, path: &str, new_value_json: &str) -> Result<String> {
    let is_pretty = json_text.contains('\n');
    let mut root: Value =
        serde_json::from_str(json_text).map_err(|e| DmeditError::Ast(e.to_string()))?;
    let new_val: Value =
        serde_json::from_str(new_value_json).map_err(|e| DmeditError::Ast(e.to_string()))?;
    let segments = parse_json_path(path)?;
    write_recursive(&mut root, &segments, new_val)?;
    encode_matching_style(&root, is_pretty)
}

pub fn patch_map_entry(
    json_text: &str,
    map_path: &str,
    entry_key: &str,
    new_value_json: &str,
) -> Result<String> {
    let is_pretty = json_text.contains('\n');
    let mut root: Value =
        serde_json::from_str(json_text).map_err(|e| DmeditError::Ast(e.to_string()))?;
    let new_val: Value =
        serde_json::from_str(new_value_json).map_err(|e| DmeditError::Ast(e.to_string()))?;

    let mut segments = parse_json_path(map_path)?;
    segments.push(PathSegment::Field(entry_key.to_string()));
    write_recursive(&mut root, &segments, new_val)?;
    encode_matching_style(&root, is_pretty)
}

pub fn batch_set_map_values(
    json_text: &str,
    map_path: &str,
    new_value_json: &str,
) -> Result<String> {
    let is_pretty = json_text.contains('\n');
    let mut root: Value =
        serde_json::from_str(json_text).map_err(|e| DmeditError::Ast(e.to_string()))?;
    let new_val: Value =
        serde_json::from_str(new_value_json).map_err(|e| DmeditError::Ast(e.to_string()))?;

    let segments = parse_json_path(map_path)?;
    let target = navigate_mut(&mut root, &segments)?;
    if let Some(obj) = target.as_object_mut() {
        for (_, v) in obj.iter_mut() {
            *v = new_val.clone();
        }
    } else {
        return Err(DmeditError::Ast(format!(
            "Target at '{map_path}' is not a JSON object"
        )));
    }
    encode_matching_style(&root, is_pretty)
}

/// Updates `"Value"` (or custom `value_field`) inside nested object-of-arrays structures
/// such as Soul Knight's `skinLock` (`{"Knight": [{"Key": 0, "Value": 1}, ...]}`)
/// and `heroSkillUnlock` (`{"Knight": [{"Key": 0, "Value": true}, ...]}`).
/// If `group_key` is `Some("Knight")`, updates only that hero; if `None`, updates all groups.
pub fn batch_update_nested_pairs(
    json_text: &str,
    map_path: &str,
    group_key: Option<&str>,
    value_field: &str,
    new_value_json: &str,
) -> Result<String> {
    let is_pretty = json_text.contains('\n');
    let mut root: Value =
        serde_json::from_str(json_text).map_err(|e| DmeditError::Ast(e.to_string()))?;
    let new_val: Value =
        serde_json::from_str(new_value_json).map_err(|e| DmeditError::Ast(e.to_string()))?;

    let segments = parse_json_path(map_path)?;
    let target = navigate_mut(&mut root, &segments)?;
    let obj = target.as_object_mut().ok_or_else(|| {
        DmeditError::Ast(format!("Target at '{map_path}' is not a JSON object"))
    })?;

    for (k, group_val) in obj.iter_mut() {
        if let Some(filter_key) = group_key {
            if k != filter_key {
                continue;
            }
        }
        if let Some(arr) = group_val.as_array_mut() {
            for item in arr.iter_mut() {
                if let Some(entry_obj) = item.as_object_mut() {
                    if entry_obj.contains_key(value_field) {
                        entry_obj.insert(value_field.to_string(), new_val.clone());
                    }
                }
            }
        }
    }

    encode_matching_style(&root, is_pretty)
}

/// Losslessly merges a full-resource JSON preset (object or array) into `target_path`.
/// - For objects: inserts all missing keys from `preset_json` (and updates existing keys if `overwrite_existing`).
/// - For arrays: appends missing items (deduplicating by `dedupe_key` on object items, or by exact equality).
pub fn merge_json_preset(
    json_text: &str,
    target_path: &str,
    preset_json: &str,
    overwrite_existing: bool,
    dedupe_key: Option<&str>,
) -> Result<String> {
    let is_pretty = json_text.contains('\n');
    let mut root: Value =
        serde_json::from_str(json_text).map_err(|e| DmeditError::Ast(e.to_string()))?;
    let preset_val: Value =
        serde_json::from_str(preset_json).map_err(|e| DmeditError::Ast(e.to_string()))?;

    let segments = parse_json_path(target_path)?;
    let target = navigate_mut(&mut root, &segments)?;

    match preset_val {
        Value::Object(preset_map) => {
            if !target.is_object() {
                *target = Value::Object(Map::new());
            }
            let target_map = target.as_object_mut().unwrap();
            for (k, v) in preset_map {
                if overwrite_existing || !target_map.contains_key(&k) {
                    target_map.insert(k, v);
                }
            }
        }
        Value::Array(preset_arr) => {
            if !target.is_array() {
                *target = Value::Array(Vec::new());
            }
            let target_arr = target.as_array_mut().unwrap();
            for item in preset_arr {
                if let Some(dk) = dedupe_key {
                    if let Some(item_key) = item.as_object().and_then(|o| o.get(dk)) {
                        if let Some(pos) = target_arr.iter().position(|existing| {
                            existing
                                .as_object()
                                .and_then(|o| o.get(dk))
                                .map(|ek| ek == item_key)
                                .unwrap_or(false)
                        }) {
                            if overwrite_existing {
                                target_arr[pos] = item;
                            }
                            continue;
                        }
                        target_arr.push(item);
                        continue;
                    }
                }
                if !target_arr.contains(&item) {
                    target_arr.push(item);
                }
            }
        }
        other => {
            if overwrite_existing || target.is_null() {
                *target = other;
            }
        }
    }

    encode_matching_style(&root, is_pretty)
}

fn navigate_mut<'a>(root: &'a mut Value, segments: &[PathSegment]) -> Result<&'a mut Value> {
    let mut current = root;
    for seg in segments {
        current = match seg {
            PathSegment::Field(name) => {
                if !current.is_object() {
                    *current = Value::Object(Map::new());
                }
                let obj = current.as_object_mut().unwrap();
                obj.entry(name.clone()).or_insert(Value::Null)
            }
            PathSegment::Index(idx) => {
                let arr = current
                    .as_array_mut()
                    .ok_or_else(|| DmeditError::Ast("Expected JSON array".into()))?;
                arr.get_mut(*idx)
                    .ok_or_else(|| DmeditError::Ast(format!("Array index {idx} out of bounds")))?
            }
        };
    }
    Ok(current)
}

fn write_recursive(target: &mut Value, segments: &[PathSegment], new_val: Value) -> Result<()> {
    if segments.is_empty() {
        *target = new_val;
        return Ok(());
    }
    let leaf = navigate_mut(target, segments)?;
    *leaf = new_val;
    Ok(())
}

fn encode_matching_style(val: &Value, pretty: bool) -> Result<String> {
    if pretty {
        serde_json::to_string_pretty(val).map_err(|e| DmeditError::Ast(e.to_string()))
    } else {
        serde_json::to_string(val).map_err(|e| DmeditError::Ast(e.to_string()))
    }
}

pub fn diff_json_texts(original_text: &str, modified_text: &str) -> Vec<DiffEntry> {
    if let (Ok(old_val), Ok(new_val)) = (
        serde_json::from_str::<Value>(original_text),
        serde_json::from_str::<Value>(modified_text),
    ) {
        let mut out = Vec::new();
        collect_json_diff(&old_val, &new_val, "$", &mut out);
        out
    } else {
        diff_lines(original_text, modified_text)
    }
}

fn collect_json_diff(old_val: &Value, new_val: &Value, path: &str, out: &mut Vec<DiffEntry>) {
    if old_val == new_val {
        return;
    }

    if let (Some(o_map), Some(n_map)) = (old_val.as_object(), new_val.as_object()) {
        let mut seen = HashSet::new();
        for key in o_map.keys().chain(n_map.keys()) {
            if !seen.insert(key.as_str()) {
                continue;
            }
            let child_path = if key.contains('.') || key.contains(' ') {
                format!("{path}[\"{key}\"]")
            } else {
                format!("{path}.{key}")
            };
            match (o_map.get(key), n_map.get(key)) {
                (None, Some(n)) => out.push(DiffEntry {
                    path: child_path,
                    old_value: None,
                    new_value: Some(summarize(n)),
                    change_type: ChangeType::ADDED,
                }),
                (Some(o), None) => out.push(DiffEntry {
                    path: child_path,
                    old_value: Some(summarize(o)),
                    new_value: None,
                    change_type: ChangeType::REMOVED,
                }),
                (Some(o), Some(n)) => collect_json_diff(o, n, &child_path, out),
                (None, None) => {}
            }
        }
        return;
    }

    if let (Some(o_arr), Some(n_arr)) = (old_val.as_array(), new_val.as_array()) {
        let max_len = o_arr.len().max(n_arr.len());
        for i in 0..max_len {
            let child_path = format!("{path}[{i}]");
            match (o_arr.get(i), n_arr.get(i)) {
                (None, Some(n)) => out.push(DiffEntry {
                    path: child_path,
                    old_value: None,
                    new_value: Some(summarize(n)),
                    change_type: ChangeType::ADDED,
                }),
                (Some(o), None) => out.push(DiffEntry {
                    path: child_path,
                    old_value: Some(summarize(o)),
                    new_value: None,
                    change_type: ChangeType::REMOVED,
                }),
                (Some(o), Some(n)) => collect_json_diff(o, n, &child_path, out),
                (None, None) => {}
            }
        }
        return;
    }

    out.push(DiffEntry {
        path: path.to_string(),
        old_value: Some(summarize(old_val)),
        new_value: Some(summarize(new_val)),
        change_type: ChangeType::MODIFIED,
    });
}

pub fn diff_lines(original: &str, modified: &str) -> Vec<DiffEntry> {
    if original == modified {
        return Vec::new();
    }
    let old_lines: Vec<&str> = original.lines().collect();
    let new_lines: Vec<&str> = modified.lines().collect();
    let max_len = old_lines.len().max(new_lines.len());
    let mut out = Vec::new();
    for i in 0..max_len {
        let o = old_lines.get(i).copied();
        let n = new_lines.get(i).copied();
        if o != n {
            let change_type = match (o, n) {
                (None, Some(_)) => ChangeType::ADDED,
                (Some(_), None) => ChangeType::REMOVED,
                _ => ChangeType::MODIFIED,
            };
            out.push(DiffEntry {
                path: format!("Line {}", i + 1),
                old_value: o.map(|s| s.chars().take(120).collect()),
                new_value: n.map(|s| s.chars().take(120).collect()),
                change_type,
            });
            if out.len() >= 200 {
                break;
            }
        }
    }
    out
}

fn summarize(v: &Value) -> String {
    let s = v.to_string();
    if s.len() > 100 {
        format!("{}...", s.chars().take(97).collect::<String>())
    } else {
        s
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lossless_patch_preserves_order_and_unknown_fields() {
        let raw = r#"{"gems":500,"unknownFuture":{"nested":42},"materials":{"iron":10,"parts":5}}"#;
        let step1 = patch_json_text(raw, "$.gems", "99999").unwrap();
        let step2 = patch_map_entry(&step1, "$.materials", "iron", "500").unwrap();

        let parsed: Value = serde_json::from_str(&step2).unwrap();
        assert_eq!(parsed["gems"], 99999);
        assert_eq!(parsed["unknownFuture"]["nested"], 42);
        assert_eq!(parsed["materials"]["iron"], 500);
        assert_eq!(parsed["materials"]["parts"], 5);

        let diffs = diff_json_texts(raw, &step2);
        assert_eq!(diffs.len(), 2);

        let batched = batch_set_map_values(&step2, "$.materials", "999").unwrap();
        let parsed_b: Value = serde_json::from_str(&batched).unwrap();
        assert_eq!(parsed_b["materials"]["iron"], 999);
        assert_eq!(parsed_b["materials"]["parts"], 999);
    }

    #[test]
    fn nested_pair_unlock_works_for_soul_knight_skinlock_and_skills() {
        let sample = r#"{
            "skinLock": {
                "Knight": [{"Key": 0, "Value": 1}, {"Key": 1, "Value": 2000}, {"Key": 2, "Value": -2}],
                "Rogue": [{"Key": 0, "Value": 1}, {"Key": 1, "Value": 5000}]
            },
            "heroSkillUnlock": {
                "Knight": [{"Key": 0, "Value": true}, {"Key": 1, "Value": false}]
            }
        }"#;

        // Unlock only Knight skins
        let knight_only =
            batch_update_nested_pairs(sample, "$.skinLock", Some("Knight"), "Value", "1").unwrap();
        let v1: Value = serde_json::from_str(&knight_only).unwrap();
        assert_eq!(v1["skinLock"]["Knight"][1]["Value"], 1);
        assert_eq!(v1["skinLock"]["Knight"][2]["Value"], 1);
        assert_eq!(v1["skinLock"]["Rogue"][1]["Value"], 5000);

        // Unlock ALL skins
        let all_skins =
            batch_update_nested_pairs(sample, "$.skinLock", None, "Value", "1").unwrap();
        let v2: Value = serde_json::from_str(&all_skins).unwrap();
        assert_eq!(v2["skinLock"]["Rogue"][1]["Value"], 1);

        // Unlock ALL skills
        let all_skills =
            batch_update_nested_pairs(&all_skins, "$.heroSkillUnlock", None, "Value", "true")
                .unwrap();
        let v3: Value = serde_json::from_str(&all_skills).unwrap();
        assert_eq!(v3["heroSkillUnlock"]["Knight"][1]["Value"], true);
    }

    #[test]
    fn merge_json_preset_handles_objects_and_deduped_arrays() {
        let base = r#"{"materials":{"material_wood":12},"itemUnlock":["Motorcycle"],"mythicWeapons":[{"id":2,"level":1}]}"#;

        // Object merge without overwriting existing value
        let m1 = merge_json_preset(
            base,
            "$.materials",
            r#"{"material_wood":999,"material_iron":999}"#,
            false,
            None,
        )
        .unwrap();
        let v1: Value = serde_json::from_str(&m1).unwrap();
        assert_eq!(v1["materials"]["material_wood"], 12);
        assert_eq!(v1["materials"]["material_iron"], 999);

        // Object merge WITH overwrite
        let m2 = merge_json_preset(
            &m1,
            "$.materials",
            r#"{"material_wood":999,"material_gear":999}"#,
            true,
            None,
        )
        .unwrap();
        let v2: Value = serde_json::from_str(&m2).unwrap();
        assert_eq!(v2["materials"]["material_wood"], 999);
        assert_eq!(v2["materials"]["material_gear"], 999);

        // String array union
        let m3 = merge_json_preset(
            &m2,
            "$.itemUnlock",
            r#"["Motorcycle","plant_pot3","plant_pot4"]"#,
            false,
            None,
        )
        .unwrap();
        let v3: Value = serde_json::from_str(&m3).unwrap();
        assert_eq!(v3["itemUnlock"].as_array().unwrap().len(), 3);

        // Object array dedupe by "id" with overwrite
        let m4 = merge_json_preset(
            &m3,
            "$.mythicWeapons",
            r#"[{"id":2,"level":3},{"id":4,"level":3}]"#,
            true,
            Some("id"),
        )
        .unwrap();
        let v4: Value = serde_json::from_str(&m4).unwrap();
        assert_eq!(v4["mythicWeapons"].as_array().unwrap().len(), 2);
        assert_eq!(v4["mythicWeapons"][0]["level"], 3);
        assert_eq!(v4["mythicWeapons"][1]["id"], 4);
    }
}
