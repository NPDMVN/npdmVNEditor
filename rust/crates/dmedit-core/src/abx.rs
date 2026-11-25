use crate::error::{DmeditError, Result};
use crate::model::{SsaidEntry, SsaidFormat};
use quick_xml::events::{BytesStart, Event as XmlEvent};
use quick_xml::{Reader as XmlReader, Writer as XmlWriter};
use rand::RngCore;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::io::Cursor;

pub const ABX_MAGIC_V0: [u8; 4] = *b"ABX\0";

const START_DOCUMENT: u8 = 0;
const END_DOCUMENT: u8 = 1;
const START_TAG: u8 = 2;
const END_TAG: u8 = 3;
const TEXT: u8 = 4;
const CDSECT: u8 = 5;
const ENTITY_REF: u8 = 6;
const IGNORABLE_WHITESPACE: u8 = 7;
const PROCESSING_INSTRUCTION: u8 = 8;
const COMMENT: u8 = 9;
const DOCDECL: u8 = 10;
const ATTRIBUTE: u8 = 15;

const TYPE_NULL: u8 = 1 << 4;
const TYPE_STRING: u8 = 2 << 4;
const TYPE_STRING_INTERNED: u8 = 3 << 4;
const TYPE_BYTES_HEX: u8 = 4 << 4;
const TYPE_BYTES_BASE64: u8 = 5 << 4;
const TYPE_INT: u8 = 6 << 4;
const TYPE_INT_HEX: u8 = 7 << 4;
const TYPE_LONG: u8 = 8 << 4;
const TYPE_LONG_HEX: u8 = 9 << 4;
const TYPE_FLOAT: u8 = 10 << 4;
const TYPE_DOUBLE: u8 = 11 << 4;
const TYPE_BOOLEAN_TRUE: u8 = 12 << 4;
const TYPE_BOOLEAN_FALSE: u8 = 13 << 4;

#[derive(Debug, Clone, PartialEq)]
pub enum AbxValue {
    Null,
    Str { value: String, interned: bool },
    BytesHex(Vec<u8>),
    BytesBase64(Vec<u8>),
    Int { value: i32, hex: bool },
    Long { value: i64, hex: bool },
    Float(f32),
    Double(f64),
    Bool(bool),
}

impl AbxValue {
    pub fn as_display_string(&self) -> String {
        match self {
            AbxValue::Null => String::new(),
            AbxValue::Str { value, .. } => value.clone(),
            AbxValue::BytesHex(b) | AbxValue::BytesBase64(b) => hex::encode(b),
            AbxValue::Int { value, hex } => {
                if *hex {
                    format!("{:x}", *value as u32)
                } else {
                    value.to_string()
                }
            }
            AbxValue::Long { value, hex } => {
                if *hex {
                    format!("{:x}", *value as u64)
                } else {
                    value.to_string()
                }
            }
            AbxValue::Float(v) => v.to_string(),
            AbxValue::Double(v) => v.to_string(),
            AbxValue::Bool(v) => v.to_string(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct AbxAttribute {
    pub name: String,
    pub value: AbxValue,
}

#[derive(Debug, Clone, PartialEq)]
pub enum AbxEvent {
    StartDocument,
    EndDocument,
    StartTag {
        name: String,
        attributes: Vec<AbxAttribute>,
    },
    EndTag(String),
    TextToken {
        token_type: u8,
        text: String,
    },
}

#[derive(Debug, Clone, PartialEq)]
pub struct AbxDocument {
    pub events: Vec<AbxEvent>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SsaidInspection {
    pub format: SsaidFormat,
    pub entry: Option<SsaidEntry>,
    pub total_entries: usize,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SsaidUpdateMeta {
    pub format: SsaidFormat,
    pub previous_value: String,
    pub new_value: String,
    pub verified_entry_count: usize,
}

pub fn is_abx(bytes: &[u8]) -> bool {
    bytes.len() >= 4 && bytes[..4] == ABX_MAGIC_V0
}

pub fn detect_ssaid_format(bytes: &[u8]) -> SsaidFormat {
    if is_abx(bytes) {
        return SsaidFormat::ABX_BINARY;
    }
    let prefix = String::from_utf8_lossy(&bytes[..bytes.len().min(64)]);
    if prefix.trim_start().starts_with('<') {
        SsaidFormat::PLAIN_XML
    } else {
        SsaidFormat::UNKNOWN
    }
}

pub fn generate_random_ssaid() -> String {
    let mut bytes = [0u8; 8];
    rand::rngs::OsRng.fill_bytes(&mut bytes);
    hex::encode(bytes)
}

pub fn parse_abx(bytes: &[u8]) -> Result<AbxDocument> {
    if !is_abx(bytes) {
        return Err(DmeditError::Abx("Invalid ABX magic header".into()));
    }
    let mut pos = 4usize;
    let mut interned: Vec<String> = Vec::new();
    let mut events: Vec<AbxEvent> = Vec::new();

    while pos < bytes.len() {
        let token = read_u8(bytes, &mut pos)?;
        let event_type = token & 0x0F;
        let value_type = token & 0xF0;

        match event_type {
            START_DOCUMENT => events.push(AbxEvent::StartDocument),
            END_DOCUMENT => {
                events.push(AbxEvent::EndDocument);
                break;
            }
            START_TAG => {
                let name = read_interned_utf(bytes, &mut pos, &mut interned)?;
                events.push(AbxEvent::StartTag {
                    name,
                    attributes: Vec::new(),
                });
            }
            END_TAG => {
                let name = read_interned_utf(bytes, &mut pos, &mut interned)?;
                events.push(AbxEvent::EndTag(name));
            }
            ATTRIBUTE => {
                let attr_name = read_interned_utf(bytes, &mut pos, &mut interned)?;
                let attr_val = read_typed_value(bytes, &mut pos, value_type, &mut interned)?;
                match events.last_mut() {
                    Some(AbxEvent::StartTag { attributes, .. }) => {
                        attributes.push(AbxAttribute {
                            name: attr_name,
                            value: attr_val,
                        });
                    }
                    _ => {
                        return Err(DmeditError::Abx(
                            "Encountered ABX ATTRIBUTE outside START_TAG".into(),
                        ));
                    }
                }
            }
            TEXT | CDSECT | ENTITY_REF | IGNORABLE_WHITESPACE | PROCESSING_INSTRUCTION | COMMENT
            | DOCDECL => {
                let text = match value_type {
                    TYPE_STRING => read_utf(bytes, &mut pos)?,
                    TYPE_STRING_INTERNED => read_interned_utf(bytes, &mut pos, &mut interned)?,
                    TYPE_NULL => String::new(),
                    _ => {
                        return Err(DmeditError::Abx(format!(
                            "Unexpected valueType 0x{value_type:02x} for text token {event_type}"
                        )));
                    }
                };
                events.push(AbxEvent::TextToken {
                    token_type: event_type,
                    text,
                });
            }
            _ => {
                return Err(DmeditError::Abx(format!(
                    "Unknown ABX event token {event_type} (raw 0x{token:02x})"
                )));
            }
        }
    }

    Ok(AbxDocument { events })
}

pub fn serialize_abx(doc: &AbxDocument) -> Result<Vec<u8>> {
    let mut out = Vec::with_capacity(1024);
    out.extend_from_slice(&ABX_MAGIC_V0);
    let mut intern_map: HashMap<String, u16> = HashMap::new();

    for event in &doc.events {
        match event {
            AbxEvent::StartDocument => out.push(TYPE_NULL | START_DOCUMENT),
            AbxEvent::EndDocument => out.push(TYPE_NULL | END_DOCUMENT),
            AbxEvent::StartTag { name, attributes } => {
                out.push(TYPE_STRING_INTERNED | START_TAG);
                write_interned_utf(&mut out, name, &mut intern_map)?;
                for attr in attributes {
                    write_attribute(&mut out, attr, &mut intern_map)?;
                }
            }
            AbxEvent::EndTag(name) => {
                out.push(TYPE_STRING_INTERNED | END_TAG);
                write_interned_utf(&mut out, name, &mut intern_map)?;
            }
            AbxEvent::TextToken { token_type, text } => {
                out.push(TYPE_STRING | *token_type);
                write_utf(&mut out, text)?;
            }
        }
    }

    Ok(out)
}

pub fn inspect_ssaid(bytes: &[u8], package_name: &str) -> Result<SsaidInspection> {
    let (format, entries) = read_ssaid_entries(bytes)?;
    let entry = entries
        .iter()
        .find(|e| e.package_name == package_name)
        .cloned();
    Ok(SsaidInspection {
        format,
        entry,
        total_entries: entries.len(),
    })
}

pub fn read_ssaid_entries(bytes: &[u8]) -> Result<(SsaidFormat, Vec<SsaidEntry>)> {
    let fmt = detect_ssaid_format(bytes);
    match fmt {
        SsaidFormat::ABX_BINARY => {
            let doc = parse_abx(bytes)?;
            let mut out = Vec::new();
            for ev in &doc.events {
                if let AbxEvent::StartTag { name, attributes } = ev {
                    if name == "setting" {
                        let get = |k: &str| {
                            attributes
                                .iter()
                                .find(|a| a.name == k)
                                .map(|a| a.value.as_display_string())
                        };
                        out.push(SsaidEntry {
                            id: get("id").unwrap_or_default(),
                            name: get("name").unwrap_or_default(),
                            value: get("value").unwrap_or_default(),
                            package_name: get("package").unwrap_or_default(),
                            default_value: get("defaultValue"),
                            default_sys_set: get("defaultSysSet").as_deref() == Some("true"),
                            tag: get("tag"),
                        });
                    }
                }
            }
            Ok((fmt, out))
        }
        SsaidFormat::PLAIN_XML => {
            let text = std::str::from_utf8(bytes)
                .map_err(|e| DmeditError::Xml(format!("Invalid UTF-8 XML: {e}")))?;
            let entries = read_plain_xml_ssaid_entries(text)?;
            Ok((fmt, entries))
        }
        _ => Err(DmeditError::Abx(
            "Unsupported settings_ssaid.xml format".into(),
        )),
    }
}

/// Updates SSAID for `package_name` (supports both Android 12-15 ABX and Android 8-11 Plain XML)
/// and performs mandatory in-memory round-trip verification before returning updated bytes.
pub fn update_ssaid_verified(
    original_bytes: &[u8],
    package_name: &str,
    new_ssaid: &str,
) -> Result<(Vec<u8>, SsaidUpdateMeta)> {
    let normalized = new_ssaid.trim().to_ascii_lowercase();
    if normalized.len() != 16 || !normalized.chars().all(|c| c.is_ascii_hexdigit()) {
        return Err(DmeditError::InvalidConfig(
            "SSAID phải gồm đúng 16 ký tự hex (0-9, a-f)".into(),
        ));
    }

    let (fmt, original_entries) = read_ssaid_entries(original_bytes)?;
    let target_orig = original_entries
        .iter()
        .find(|e| e.package_name == package_name)
        .ok_or_else(|| {
            DmeditError::NotFound(format!(
                "Không tìm thấy SSAID cho gói '{package_name}' trong settings_ssaid.xml"
            ))
        })?;

    let updated_bytes = match fmt {
        SsaidFormat::ABX_BINARY => update_abx_ssaid(original_bytes, package_name, &normalized)?,
        SsaidFormat::PLAIN_XML => update_plain_xml_ssaid(original_bytes, package_name, &normalized)?,
        _ => return Err(DmeditError::Abx("Unsupported SSAID format".into())),
    };

    // Mandatory in-memory round-trip verification!
    let (verify_fmt, verified_entries) = read_ssaid_entries(&updated_bytes)?;
    if verify_fmt != fmt {
        return Err(DmeditError::Verification(
            "Round-trip format mismatch".into(),
        ));
    }
    if verified_entries.len() != original_entries.len() {
        return Err(DmeditError::Verification(format!(
            "Round-trip entry count mismatch: expected {}, got {}",
            original_entries.len(),
            verified_entries.len()
        )));
    }
    for (orig, ver) in original_entries.iter().zip(verified_entries.iter()) {
        if orig.package_name != ver.package_name || orig.name != ver.name {
            return Err(DmeditError::Verification(
                "Round-trip package identity mismatch".into(),
            ));
        }
        if orig.package_name == package_name {
            if ver.value != normalized {
                return Err(DmeditError::Verification(
                    "Round-trip target SSAID value mismatch".into(),
                ));
            }
        } else if ver.value != orig.value {
            return Err(DmeditError::Verification(format!(
                "Round-trip altered unrelated package '{}'",
                orig.package_name
            )));
        }
    }

    let meta = SsaidUpdateMeta {
        format: fmt,
        previous_value: target_orig.value.clone(),
        new_value: normalized,
        verified_entry_count: verified_entries.len(),
    };
    Ok((updated_bytes, meta))
}

fn update_abx_ssaid(original_bytes: &[u8], package_name: &str, new_ssaid: &str) -> Result<Vec<u8>> {
    let mut doc = parse_abx(original_bytes)?;
    let mut modified = false;
    for ev in &mut doc.events {
        if let AbxEvent::StartTag { name, attributes } = ev {
            if name == "setting" {
                let is_target = attributes
                    .iter()
                    .any(|a| a.name == "package" && a.value.as_display_string() == package_name);
                if is_target {
                    for attr in attributes.iter_mut() {
                        if attr.name == "value" || attr.name == "defaultValue" {
                            let interned = matches!(attr.value, AbxValue::Str { interned: true, .. });
                            attr.value = AbxValue::Str {
                                value: new_ssaid.to_string(),
                                interned,
                            };
                        }
                    }
                    modified = true;
                }
            }
        }
    }
    if !modified {
        return Err(DmeditError::NotFound(format!(
            "Package '{package_name}' not found during ABX rewrite"
        )));
    }
    serialize_abx(&doc)
}

fn read_plain_xml_ssaid_entries(xml: &str) -> Result<Vec<SsaidEntry>> {
    let mut reader = XmlReader::from_str(xml);
    let mut buf = Vec::new();
    let mut out = Vec::new();
    loop {
        match reader.read_event_into(&mut buf) {
            Ok(XmlEvent::Empty(ref e)) | Ok(XmlEvent::Start(ref e))
                if e.name().as_ref() == b"setting" =>
            {
                let mut map = HashMap::<String, String>::new();
                for attr in e.attributes().flatten() {
                    let k = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                    let v = attr
                        .unescape_value()
                        .map(|s| s.into_owned())
                        .unwrap_or_default();
                    map.insert(k, v);
                }
                out.push(SsaidEntry {
                    id: map.get("id").cloned().unwrap_or_default(),
                    name: map.get("name").cloned().unwrap_or_default(),
                    value: map.get("value").cloned().unwrap_or_default(),
                    package_name: map.get("package").cloned().unwrap_or_default(),
                    default_value: map.get("defaultValue").cloned(),
                    default_sys_set: map.get("defaultSysSet").map(|s| s == "true").unwrap_or(false),
                    tag: map.get("tag").cloned(),
                });
            }
            Ok(XmlEvent::Eof) => break,
            Err(e) => return Err(DmeditError::Xml(e.to_string())),
            _ => {}
        }
        buf.clear();
    }
    Ok(out)
}

fn update_plain_xml_ssaid(
    original_bytes: &[u8],
    package_name: &str,
    new_ssaid: &str,
) -> Result<Vec<u8>> {
    let xml = std::str::from_utf8(original_bytes)
        .map_err(|e| DmeditError::Xml(format!("Invalid UTF-8 XML: {e}")))?;
    let mut reader = XmlReader::from_str(xml);
    let mut writer = XmlWriter::new(Cursor::new(Vec::new()));
    let mut buf = Vec::new();
    let mut modified = false;

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(XmlEvent::Empty(e)) if e.name().as_ref() == b"setting" => {
                let (rebuilt, changed) = rewrite_setting_tag(&e, package_name, new_ssaid)?;
                if changed {
                    modified = true;
                }
                writer
                    .write_event(XmlEvent::Empty(rebuilt))
                    .map_err(|e| DmeditError::Xml(e.to_string()))?;
            }
            Ok(XmlEvent::Start(e)) if e.name().as_ref() == b"setting" => {
                let (rebuilt, changed) = rewrite_setting_tag(&e, package_name, new_ssaid)?;
                if changed {
                    modified = true;
                }
                writer
                    .write_event(XmlEvent::Start(rebuilt))
                    .map_err(|e| DmeditError::Xml(e.to_string()))?;
            }
            Ok(XmlEvent::Eof) => break,
            Ok(ev) => writer
                .write_event(ev)
                .map_err(|e| DmeditError::Xml(e.to_string()))?,
            Err(e) => return Err(DmeditError::Xml(e.to_string())),
        }
        buf.clear();
    }

    if !modified {
        return Err(DmeditError::NotFound(format!(
            "Package '{package_name}' not found in Plain XML"
        )));
    }
    Ok(writer.into_inner().into_inner())
}

fn rewrite_setting_tag<'a>(
    e: &BytesStart<'a>,
    package_name: &str,
    new_ssaid: &str,
) -> Result<(BytesStart<'static>, bool)> {
    let mut attrs: Vec<(String, String)> = Vec::new();
    let mut is_target = false;
    for attr in e.attributes() {
        let attr = attr.map_err(|err| DmeditError::Xml(err.to_string()))?;
        let k = String::from_utf8_lossy(attr.key.as_ref()).to_string();
        let v = attr
            .unescape_value()
            .map_err(|err| DmeditError::Xml(err.to_string()))?
            .into_owned();
        if k == "package" && v == package_name {
            is_target = true;
        }
        attrs.push((k, v));
    }

    let mut elem = BytesStart::new("setting");
    for (k, v) in attrs {
        if is_target && (k == "value" || k == "defaultValue") {
            elem.push_attribute((k.as_str(), new_ssaid));
        } else {
            elem.push_attribute((k.as_str(), v.as_str()));
        }
    }
    Ok((elem, is_target))
}

fn read_u8(buf: &[u8], pos: &mut usize) -> Result<u8> {
    if *pos >= buf.len() {
        return Err(DmeditError::Abx("Unexpected EOF reading u8".into()));
    }
    let v = buf[*pos];
    *pos += 1;
    Ok(v)
}

fn read_u16_be(buf: &[u8], pos: &mut usize) -> Result<u16> {
    if *pos + 2 > buf.len() {
        return Err(DmeditError::Abx("Unexpected EOF reading u16".into()));
    }
    let v = u16::from_be_bytes([buf[*pos], buf[*pos + 1]]);
    *pos += 2;
    Ok(v)
}

fn read_i32_be(buf: &[u8], pos: &mut usize) -> Result<i32> {
    if *pos + 4 > buf.len() {
        return Err(DmeditError::Abx("Unexpected EOF reading i32".into()));
    }
    let v = i32::from_be_bytes(buf[*pos..*pos + 4].try_into().unwrap());
    *pos += 4;
    Ok(v)
}

fn read_i64_be(buf: &[u8], pos: &mut usize) -> Result<i64> {
    if *pos + 8 > buf.len() {
        return Err(DmeditError::Abx("Unexpected EOF reading i64".into()));
    }
    let v = i64::from_be_bytes(buf[*pos..*pos + 8].try_into().unwrap());
    *pos += 8;
    Ok(v)
}

fn read_utf(buf: &[u8], pos: &mut usize) -> Result<String> {
    let len = read_u16_be(buf, pos)? as usize;
    if *pos + len > buf.len() {
        return Err(DmeditError::Abx("Unexpected EOF reading UTF string".into()));
    }
    let s = std::str::from_utf8(&buf[*pos..*pos + len])
        .map_err(|e| DmeditError::Abx(format!("Invalid UTF-8 string: {e}")))?
        .to_string();
    *pos += len;
    Ok(s)
}

fn read_interned_utf(buf: &[u8], pos: &mut usize, pool: &mut Vec<String>) -> Result<String> {
    let idx = read_u16_be(buf, pos)?;
    if idx == 0xFFFF {
        let s = read_utf(buf, pos)?;
        pool.push(s.clone());
        Ok(s)
    } else {
        pool.get(idx as usize).cloned().ok_or_else(|| {
            DmeditError::Abx(format!(
                "Invalid ABX interned string ref {idx} (pool len={})",
                pool.len()
            ))
        })
    }
}

fn read_typed_value(
    buf: &[u8],
    pos: &mut usize,
    value_type: u8,
    pool: &mut Vec<String>,
) -> Result<AbxValue> {
    match value_type {
        TYPE_NULL => Ok(AbxValue::Null),
        TYPE_STRING => Ok(AbxValue::Str {
            value: read_utf(buf, pos)?,
            interned: false,
        }),
        TYPE_STRING_INTERNED => Ok(AbxValue::Str {
            value: read_interned_utf(buf, pos, pool)?,
            interned: true,
        }),
        TYPE_BYTES_HEX => {
            let len = read_u16_be(buf, pos)? as usize;
            if *pos + len > buf.len() {
                return Err(DmeditError::Abx("Unexpected EOF reading bytes_hex".into()));
            }
            let data = buf[*pos..*pos + len].to_vec();
            *pos += len;
            Ok(AbxValue::BytesHex(data))
        }
        TYPE_BYTES_BASE64 => {
            let len = read_u16_be(buf, pos)? as usize;
            if *pos + len > buf.len() {
                return Err(DmeditError::Abx("Unexpected EOF reading bytes_b64".into()));
            }
            let data = buf[*pos..*pos + len].to_vec();
            *pos += len;
            Ok(AbxValue::BytesBase64(data))
        }
        TYPE_INT => Ok(AbxValue::Int {
            value: read_i32_be(buf, pos)?,
            hex: false,
        }),
        TYPE_INT_HEX => Ok(AbxValue::Int {
            value: read_i32_be(buf, pos)?,
            hex: true,
        }),
        TYPE_LONG => Ok(AbxValue::Long {
            value: read_i64_be(buf, pos)?,
            hex: false,
        }),
        TYPE_LONG_HEX => Ok(AbxValue::Long {
            value: read_i64_be(buf, pos)?,
            hex: true,
        }),
        TYPE_FLOAT => Ok(AbxValue::Float(f32::from_bits(read_i32_be(buf, pos)? as u32))),
        TYPE_DOUBLE => Ok(AbxValue::Double(f64::from_bits(read_i64_be(buf, pos)? as u64))),
        TYPE_BOOLEAN_TRUE => Ok(AbxValue::Bool(true)),
        TYPE_BOOLEAN_FALSE => Ok(AbxValue::Bool(false)),
        _ => Err(DmeditError::Abx(format!(
            "Unsupported ABX value type 0x{value_type:02x}"
        ))),
    }
}

fn write_utf(out: &mut Vec<u8>, text: &str) -> Result<()> {
    let bytes = text.as_bytes();
    if bytes.len() > u16::MAX as usize {
        return Err(DmeditError::Abx("UTF string exceeds 65535 bytes".into()));
    }
    out.extend_from_slice(&(bytes.len() as u16).to_be_bytes());
    out.extend_from_slice(bytes);
    Ok(())
}

fn write_interned_utf(
    out: &mut Vec<u8>,
    text: &str,
    pool: &mut HashMap<String, u16>,
) -> Result<()> {
    if let Some(&idx) = pool.get(text) {
        out.extend_from_slice(&idx.to_be_bytes());
    } else {
        out.extend_from_slice(&0xFFFFu16.to_be_bytes());
        write_utf(out, text)?;
        if pool.len() < u16::MAX as usize {
            let next = pool.len() as u16;
            pool.insert(text.to_string(), next);
        }
    }
    Ok(())
}

fn write_attribute(
    out: &mut Vec<u8>,
    attr: &AbxAttribute,
    pool: &mut HashMap<String, u16>,
) -> Result<()> {
    match &attr.value {
        AbxValue::Null => {
            out.push(TYPE_NULL | ATTRIBUTE);
            write_interned_utf(out, &attr.name, pool)?;
        }
        AbxValue::Str { value, interned } => {
            if *interned {
                out.push(TYPE_STRING_INTERNED | ATTRIBUTE);
                write_interned_utf(out, &attr.name, pool)?;
                write_interned_utf(out, value, pool)?;
            } else {
                out.push(TYPE_STRING | ATTRIBUTE);
                write_interned_utf(out, &attr.name, pool)?;
                write_utf(out, value)?;
            }
        }
        AbxValue::BytesHex(b) => {
            out.push(TYPE_BYTES_HEX | ATTRIBUTE);
            write_interned_utf(out, &attr.name, pool)?;
            out.extend_from_slice(&(b.len() as u16).to_be_bytes());
            out.extend_from_slice(b);
        }
        AbxValue::BytesBase64(b) => {
            out.push(TYPE_BYTES_BASE64 | ATTRIBUTE);
            write_interned_utf(out, &attr.name, pool)?;
            out.extend_from_slice(&(b.len() as u16).to_be_bytes());
            out.extend_from_slice(b);
        }
        AbxValue::Int { value, hex } => {
            out.push((if *hex { TYPE_INT_HEX } else { TYPE_INT }) | ATTRIBUTE);
            write_interned_utf(out, &attr.name, pool)?;
            out.extend_from_slice(&value.to_be_bytes());
        }
        AbxValue::Long { value, hex } => {
            out.push((if *hex { TYPE_LONG_HEX } else { TYPE_LONG }) | ATTRIBUTE);
            write_interned_utf(out, &attr.name, pool)?;
            out.extend_from_slice(&value.to_be_bytes());
        }
        AbxValue::Float(v) => {
            out.push(TYPE_FLOAT | ATTRIBUTE);
            write_interned_utf(out, &attr.name, pool)?;
            out.extend_from_slice(&v.to_bits().to_be_bytes());
        }
        AbxValue::Double(v) => {
            out.push(TYPE_DOUBLE | ATTRIBUTE);
            write_interned_utf(out, &attr.name, pool)?;
            out.extend_from_slice(&v.to_bits().to_be_bytes());
        }
        AbxValue::Bool(v) => {
            out.push((if *v { TYPE_BOOLEAN_TRUE } else { TYPE_BOOLEAN_FALSE }) | ATTRIBUTE);
            write_interned_utf(out, &attr.name, pool)?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abx_ssaid_round_trip_verified() {
        let doc = AbxDocument {
            events: vec![
                AbxEvent::StartDocument,
                AbxEvent::StartTag {
                    name: "settings".into(),
                    attributes: vec![AbxAttribute {
                        name: "version".into(),
                        value: AbxValue::Int {
                            value: -1,
                            hex: false,
                        },
                    }],
                },
                AbxEvent::StartTag {
                    name: "setting".into(),
                    attributes: vec![
                        AbxAttribute {
                            name: "id".into(),
                            value: AbxValue::Str { value: "0".into(), interned: false },
                        },
                        AbxAttribute {
                            name: "name".into(),
                            value: AbxValue::Str { value: "10123".into(), interned: false },
                        },
                        AbxAttribute {
                            name: "value".into(),
                            value: AbxValue::Str { value: "1111222233334444".into(), interned: false },
                        },
                        AbxAttribute {
                            name: "package".into(),
                            value: AbxValue::Str {
                                value: "com.ChillyRoom.DungeonShooter".into(),
                                interned: true,
                            },
                        },
                        AbxAttribute {
                            name: "defaultValue".into(),
                            value: AbxValue::Str { value: "1111222233334444".into(), interned: false },
                        },
                        AbxAttribute {
                            name: "defaultSysSet".into(),
                            value: AbxValue::Bool(false),
                        },
                    ],
                },
                AbxEvent::EndTag("setting".into()),
                AbxEvent::StartTag {
                    name: "setting".into(),
                    attributes: vec![
                        AbxAttribute {
                            name: "id".into(),
                            value: AbxValue::Str { value: "1".into(), interned: false },
                        },
                        AbxAttribute {
                            name: "name".into(),
                            value: AbxValue::Str { value: "10124".into(), interned: false },
                        },
                        AbxAttribute {
                            name: "value".into(),
                            value: AbxValue::Str { value: "aaaabbbbccccdddd".into(), interned: false },
                        },
                        AbxAttribute {
                            name: "package".into(),
                            value: AbxValue::Str {
                                value: "com.android.vending".into(),
                                interned: true,
                            },
                        },
                    ],
                },
                AbxEvent::EndTag("setting".into()),
                AbxEvent::EndTag("settings".into()),
                AbxEvent::EndDocument,
            ],
        };

        let raw = serialize_abx(&doc).unwrap();
        assert!(is_abx(&raw));

        let (updated, meta) =
            update_ssaid_verified(&raw, "com.ChillyRoom.DungeonShooter", "deadbeefcafebabe")
                .unwrap();
        assert_eq!(meta.format, SsaidFormat::ABX_BINARY);
        assert_eq!(meta.previous_value, "1111222233334444");
        assert_eq!(meta.new_value, "deadbeefcafebabe");
        assert_eq!(meta.verified_entry_count, 2);

        let (_, after) = read_ssaid_entries(&updated).unwrap();
        assert_eq!(after[0].value, "deadbeefcafebabe");
        assert_eq!(after[1].value, "aaaabbbbccccdddd");
    }

    #[test]
    fn plain_xml_ssaid_round_trip_verified() {
        let xml = br#"<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<settings version="-1">
  <setting id="0" name="10088" value="0123456789abcdef" package="com.ChillyRoom.DungeonShooter" defaultValue="0123456789abcdef" defaultSysSet="false" />
  <setting id="1" name="10089" value="fedcba9876543210" package="com.other.app" defaultValue="fedcba9876543210" defaultSysSet="false" />
</settings>"#;

        let (updated, meta) =
            update_ssaid_verified(xml, "com.ChillyRoom.DungeonShooter", "9988776655443322")
                .unwrap();
        assert_eq!(meta.format, SsaidFormat::PLAIN_XML);
        assert_eq!(meta.new_value, "9988776655443322");

        let (_, after) = read_ssaid_entries(&updated).unwrap();
        assert_eq!(after[0].value, "9988776655443322");
        assert_eq!(after[1].value, "fedcba9876543210");
    }
}
