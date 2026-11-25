use crate::error::{DmeditError, Result};
use crate::model::SanitizerConfig;
use quick_xml::events::{BytesStart, Event as XmlEvent};
use quick_xml::{Reader as XmlReader, Writer as XmlWriter};
use regex::{Regex, RegexBuilder};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashSet;
use std::io::Cursor;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SanitizedXmlResult {
    pub xml: String,
    pub removed_keys: Vec<String>,
}

fn compile_patterns(patterns: &[String]) -> Result<Vec<Regex>> {
    patterns
        .iter()
        .map(|p| {
            RegexBuilder::new(p)
                .case_insensitive(true)
                .build()
                .map_err(|e| DmeditError::InvalidConfig(format!("Invalid regex '{p}': {e}")))
        })
        .collect()
}

fn extract_name_attr(e: &BytesStart<'_>) -> Option<String> {
    for attr in e.attributes().flatten() {
        if attr.key.as_ref() == b"name" {
            return attr.unescape_value().ok().map(|v| v.into_owned());
        }
    }
    None
}

pub fn find_account_keys_in_playerprefs(xml: &str, config: &SanitizerConfig) -> Result<Vec<String>> {
    if config.player_prefs_remove_patterns.is_empty() {
        return Ok(Vec::new());
    }
    let regexes = compile_patterns(&config.player_prefs_remove_patterns)?;
    let mut reader = XmlReader::from_str(xml);
    let mut buf = Vec::new();
    let mut found = Vec::new();
    let mut seen = HashSet::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(XmlEvent::Start(ref e)) | Ok(XmlEvent::Empty(ref e)) => {
                if let Some(name) = extract_name_attr(e) {
                    if regexes.iter().any(|r| r.is_match(&name)) && seen.insert(name.clone()) {
                        found.push(name);
                    }
                }
            }
            Ok(XmlEvent::Eof) => break,
            Err(e) => return Err(DmeditError::Xml(e.to_string())),
            _ => {}
        }
        buf.clear();
    }
    Ok(found)
}

pub fn sanitize_playerprefs_xml(xml: &str, config: &SanitizerConfig) -> Result<SanitizedXmlResult> {
    if config.player_prefs_remove_patterns.is_empty() {
        return Ok(SanitizedXmlResult {
            xml: xml.to_string(),
            removed_keys: Vec::new(),
        });
    }
    let regexes = compile_patterns(&config.player_prefs_remove_patterns)?;
    let mut reader = XmlReader::from_str(xml);
    let mut writer = XmlWriter::new(Cursor::new(Vec::new()));
    let mut buf = Vec::new();
    let mut removed_keys = Vec::new();
    let mut skip_depth = 0usize;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(XmlEvent::Start(e)) => {
                if skip_depth > 0 {
                    skip_depth += 1;
                } else if let Some(name) = extract_name_attr(&e) {
                    if regexes.iter().any(|r| r.is_match(&name)) {
                        removed_keys.push(name);
                        skip_depth = 1;
                    } else {
                        writer
                            .write_event(XmlEvent::Start(e))
                            .map_err(|err| DmeditError::Xml(err.to_string()))?;
                    }
                } else {
                    writer
                        .write_event(XmlEvent::Start(e))
                        .map_err(|err| DmeditError::Xml(err.to_string()))?;
                }
            }
            Ok(XmlEvent::Empty(e)) => {
                if skip_depth > 0 {
                    // skip nested empty elements
                } else if let Some(name) = extract_name_attr(&e) {
                    if regexes.iter().any(|r| r.is_match(&name)) {
                        removed_keys.push(name);
                    } else {
                        writer
                            .write_event(XmlEvent::Empty(e))
                            .map_err(|err| DmeditError::Xml(err.to_string()))?;
                    }
                } else {
                    writer
                        .write_event(XmlEvent::Empty(e))
                        .map_err(|err| DmeditError::Xml(err.to_string()))?;
                }
            }
            Ok(XmlEvent::End(e)) => {
                if skip_depth > 0 {
                    skip_depth -= 1;
                } else {
                    writer
                        .write_event(XmlEvent::End(e))
                        .map_err(|err| DmeditError::Xml(err.to_string()))?;
                }
            }
            Ok(XmlEvent::Eof) => break,
            Ok(ev) => {
                if skip_depth == 0 {
                    writer
                        .write_event(ev)
                        .map_err(|err| DmeditError::Xml(err.to_string()))?;
                }
            }
            Err(e) => return Err(DmeditError::Xml(e.to_string())),
        }
        buf.clear();
    }

    let cleaned = String::from_utf8(writer.into_inner().into_inner())
        .map_err(|e| DmeditError::Xml(e.to_string()))?;
    Ok(SanitizedXmlResult {
        xml: cleaned,
        removed_keys,
    })
}

pub fn sanitize_json_text(json_text: &str, config: &SanitizerConfig) -> String {
    if config.json_strip_keys.is_empty() {
        return json_text.to_string();
    }
    let Ok(mut val) = serde_json::from_str::<Value>(json_text) else {
        return json_text.to_string();
    };
    if let Some(obj) = val.as_object_mut() {
        for key in &config.json_strip_keys {
            obj.shift_remove(key);
        }
        serde_json::to_string(&val).unwrap_or_else(|_| json_text.to_string())
    } else {
        json_text.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn removes_account_nodes_preserving_game_prefs() {
        let xml = r#"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="cloudSaveId">secret_123</string>
    <string name="account2Name">playerOne</string>
    <int name="musicVolume" value="80" />
</map>"#;

        let cfg = SanitizerConfig {
            player_prefs_remove_patterns: vec!["^cloudSaveId$".into(), "^account2.*$".into()],
            json_strip_keys: vec!["fixGP_Test".into()],
            account_suffix_regex: None,
        };

        let found = find_account_keys_in_playerprefs(xml, &cfg).unwrap();
        assert_eq!(found.len(), 2);

        let cleaned = sanitize_playerprefs_xml(xml, &cfg).unwrap();
        assert_eq!(cleaned.removed_keys.len(), 2);
        assert!(!cleaned.xml.contains("cloudSaveId"));
        assert!(!cleaned.xml.contains("account2Name"));
        assert!(cleaned.xml.contains("musicVolume"));
    }
}
