use crate::error::{DmeditError, Result};
use crate::model::{CodecDefinition, CodecType, DataFormat, FileRule, GameProfile, ValidationResult};
use aes::{Aes128, Aes192, Aes256};
use base64::engine::general_purpose::STANDARD as BASE64_STD;
use base64::Engine;
use cbc::cipher::block_padding::{NoPadding, Pkcs7};
use cbc::cipher::{BlockDecryptMut, BlockEncryptMut, KeyIvInit};
use des::Des;
use flate2::read::GzDecoder;
use flate2::write::GzEncoder;
use flate2::Compression;
use quick_xml::events::Event as XmlEvent;
use quick_xml::Reader as XmlReader;
use regex::RegexBuilder;
use std::collections::HashSet;
use std::io::{Read, Write};

type DesCbcEnc = cbc::Encryptor<Des>;
type DesCbcDec = cbc::Decryptor<Des>;
type Aes128CbcEnc = cbc::Encryptor<Aes128>;
type Aes128CbcDec = cbc::Decryptor<Aes128>;
type Aes192CbcEnc = cbc::Encryptor<Aes192>;
type Aes192CbcDec = cbc::Decryptor<Aes192>;
type Aes256CbcEnc = cbc::Encryptor<Aes256>;
type Aes256CbcDec = cbc::Decryptor<Aes256>;

pub fn match_file_rule<'a>(profile: &'a GameProfile, file_name: &str) -> Option<&'a FileRule> {
    for rule in &profile.file_rules {
        if let Ok(re) = RegexBuilder::new(rule.pattern()).case_insensitive(true).build() {
            if re.is_match(file_name) {
                return Some(rule);
            }
        }
    }
    None
}

pub fn has_valid_key_configured(profile: &GameProfile, codec_id: &str) -> bool {
    if codec_id == "plain" {
        return true;
    }
    let Some(def) = profile.codecs.get(codec_id) else {
        return false;
    };
    match def.codec_type {
        CodecType::Plain | CodecType::Base64 | CodecType::Gzip => true,
        CodecType::Xor => resolve_key(def).map(|k| !k.is_empty()).unwrap_or(false),
        CodecType::DesCbcPkcs5 => {
            resolve_key(def).map(|k| k.len() == 8).unwrap_or(false)
                && resolve_iv(def).map(|iv| iv.len() == 8).unwrap_or(false)
        }
        CodecType::AesCbcPkcs5 | CodecType::AesCbcZeroBom => {
            resolve_key(def)
                .map(|k| matches!(k.len(), 16 | 24 | 32))
                .unwrap_or(false)
                && resolve_iv(def).map(|iv| iv.len() == 16).unwrap_or(false)
        }
        CodecType::Chain => {
            !def.steps.is_empty()
                && def
                    .steps
                    .iter()
                    .all(|step| has_valid_key_configured(profile, step))
        }
    }
}

pub fn decrypt_with_profile(profile: &GameProfile, codec_id: &str, input: &[u8]) -> Result<Vec<u8>> {
    if codec_id == "plain" {
        return Ok(input.to_vec());
    }
    let mut visited = HashSet::new();
    decrypt_recursive(profile, codec_id, input, &mut visited)
}

pub fn encrypt_with_profile(
    profile: &GameProfile,
    codec_id: &str,
    plaintext: &[u8],
    original_raw: Option<&[u8]>,
) -> Result<Vec<u8>> {
    if codec_id == "plain" {
        return Ok(plaintext.to_vec());
    }
    let mut visited = HashSet::new();
    encrypt_recursive(profile, codec_id, plaintext, original_raw, &mut visited)
}

fn decrypt_recursive(
    profile: &GameProfile,
    codec_id: &str,
    input: &[u8],
    visited: &mut HashSet<String>,
) -> Result<Vec<u8>> {
    if codec_id == "plain" {
        return Ok(input.to_vec());
    }
    if !visited.insert(codec_id.to_string()) {
        return Err(DmeditError::InvalidConfig(format!(
            "Cyclic codec chain at '{codec_id}'"
        )));
    }
    let def = profile.codecs.get(codec_id).ok_or_else(|| {
        DmeditError::InvalidConfig(format!("Codec '{codec_id}' not found in profile '{}'", profile.id))
    })?;

    let result = match def.codec_type {
        CodecType::Plain => Ok(input.to_vec()),
        CodecType::Xor => {
            let key = resolve_key(def)?;
            Ok(xor_transform(input, &key)?)
        }
        CodecType::DesCbcPkcs5 => {
            let key = resolve_key(def)?;
            let iv = resolve_iv(def)?;
            des_cbc_decrypt(input, &key, &iv)
        }
        CodecType::AesCbcPkcs5 => {
            let key = resolve_key(def)?;
            let iv = resolve_iv(def)?;
            if input.len() < def.header_skip_bytes {
                return Err(DmeditError::Codec("Input shorter than headerSkipBytes".into()));
            }
            aes_cbc_decrypt(&input[def.header_skip_bytes..], &key, &iv)
        }
        CodecType::AesCbcZeroBom => {
            let key = resolve_key(def)?;
            let iv = resolve_iv(def)?;
            if input.len() < def.header_skip_bytes {
                return Err(DmeditError::Codec("Input shorter than headerSkipBytes".into()));
            }
            aes_cbc_zero_bom_decrypt(&input[def.header_skip_bytes..], &key, &iv)
        }
        CodecType::Base64 => {
            let clean: String = std::str::from_utf8(input)
                .map_err(|e| DmeditError::Codec(format!("Invalid UTF-8 Base64: {e}")))?
                .chars()
                .filter(|c| !c.is_whitespace())
                .collect();
            BASE64_STD
                .decode(clean)
                .map_err(|e| DmeditError::Codec(format!("Base64 decode failed: {e}")))
        }
        CodecType::Gzip => {
            let mut decoder = GzDecoder::new(input);
            let mut out = Vec::new();
            decoder
                .read_to_end(&mut out)
                .map_err(|e| DmeditError::Codec(format!("GZip decompress failed: {e}")))?;
            Ok(out)
        }
        CodecType::Chain => {
            let mut acc = input.to_vec();
            for step in &def.steps {
                acc = decrypt_recursive(profile, step, &acc, visited)?;
            }
            Ok(acc)
        }
    };

    visited.remove(codec_id);
    result
}

fn encrypt_recursive(
    profile: &GameProfile,
    codec_id: &str,
    plaintext: &[u8],
    original_raw: Option<&[u8]>,
    visited: &mut HashSet<String>,
) -> Result<Vec<u8>> {
    if codec_id == "plain" {
        return Ok(plaintext.to_vec());
    }
    if !visited.insert(codec_id.to_string()) {
        return Err(DmeditError::InvalidConfig(format!(
            "Cyclic codec chain at '{codec_id}'"
        )));
    }
    let def = profile.codecs.get(codec_id).ok_or_else(|| {
        DmeditError::InvalidConfig(format!("Codec '{codec_id}' not found in profile '{}'", profile.id))
    })?;

    let result = match def.codec_type {
        CodecType::Plain => Ok(plaintext.to_vec()),
        CodecType::Xor => {
            let key = resolve_key(def)?;
            xor_transform(plaintext, &key)
        }
        CodecType::DesCbcPkcs5 => {
            let key = resolve_key(def)?;
            let iv = resolve_iv(def)?;
            des_cbc_encrypt(plaintext, &key, &iv)
        }
        CodecType::AesCbcPkcs5 => {
            let key = resolve_key(def)?;
            let iv = resolve_iv(def)?;
            let enc = aes_cbc_encrypt(plaintext, &key, &iv)?;
            if def.header_skip_bytes > 0 {
                let mut out = Vec::with_capacity(def.header_skip_bytes + enc.len());
                if let Some(orig) = original_raw {
                    if orig.len() >= def.header_skip_bytes {
                        out.extend_from_slice(&orig[..def.header_skip_bytes]);
                    } else {
                        out.resize(def.header_skip_bytes, 0);
                    }
                } else {
                    out.resize(def.header_skip_bytes, 0);
                }
                out.extend_from_slice(&enc);
                Ok(out)
            } else {
                Ok(enc)
            }
        }
        CodecType::AesCbcZeroBom => {
            let key = resolve_key(def)?;
            let iv = resolve_iv(def)?;
            aes_cbc_zero_bom_encrypt(plaintext, &key, &iv)
        }
        CodecType::Base64 => Ok(BASE64_STD.encode(plaintext).into_bytes()),
        CodecType::Gzip => {
            let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
            encoder
                .write_all(plaintext)
                .map_err(|e| DmeditError::Codec(format!("GZip write failed: {e}")))?;
            encoder
                .finish()
                .map_err(|e| DmeditError::Codec(format!("GZip finish failed: {e}")))
        }
        CodecType::Chain => {
            let mut acc = plaintext.to_vec();
            for step in def.steps.iter().rev() {
                acc = encrypt_recursive(profile, step, &acc, original_raw, visited)?;
            }
            Ok(acc)
        }
    };

    visited.remove(codec_id);
    result
}

pub fn xor_transform(input: &[u8], key: &[u8]) -> Result<Vec<u8>> {
    if key.is_empty() {
        return Err(DmeditError::InvalidConfig("XOR key must not be empty".into()));
    }
    let key_len = key.len();
    Ok(input
        .iter()
        .enumerate()
        .map(|(i, b)| b ^ key[i % key_len])
        .collect())
}

pub fn des_cbc_encrypt(plaintext: &[u8], key: &[u8], iv: &[u8]) -> Result<Vec<u8>> {
    if key.len() != 8 || iv.len() != 8 {
        return Err(DmeditError::InvalidConfig(
            "DES key and IV must be exactly 8 bytes".into(),
        ));
    }
    let mut buf = vec![0u8; plaintext.len() + 8];
    buf[..plaintext.len()].copy_from_slice(plaintext);
    let enc = DesCbcEnc::new_from_slices(key, iv)
        .map_err(|e| DmeditError::Codec(format!("DES init failed: {e}")))?;
    let ct = enc
        .encrypt_padded_mut::<Pkcs7>(&mut buf, plaintext.len())
        .map_err(|e| DmeditError::Codec(format!("DES encrypt failed: {e:?}")))?;
    Ok(ct.to_vec())
}

pub fn des_cbc_decrypt(ciphertext: &[u8], key: &[u8], iv: &[u8]) -> Result<Vec<u8>> {
    if key.len() != 8 || iv.len() != 8 {
        return Err(DmeditError::InvalidConfig(
            "DES key and IV must be exactly 8 bytes".into(),
        ));
    }
    let mut buf = ciphertext.to_vec();
    let dec = DesCbcDec::new_from_slices(key, iv)
        .map_err(|e| DmeditError::Codec(format!("DES init failed: {e}")))?;
    let pt = dec
        .decrypt_padded_mut::<Pkcs7>(&mut buf)
        .map_err(|e| DmeditError::Codec(format!("DES decrypt/unpad failed: {e:?}")))?;
    Ok(pt.to_vec())
}

pub fn aes_cbc_encrypt(plaintext: &[u8], key: &[u8], iv: &[u8]) -> Result<Vec<u8>> {
    if iv.len() != 16 {
        return Err(DmeditError::InvalidConfig("AES IV must be 16 bytes".into()));
    }
    let mut buf = vec![0u8; plaintext.len() + 16];
    buf[..plaintext.len()].copy_from_slice(plaintext);
    let ct = match key.len() {
        16 => Aes128CbcEnc::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .encrypt_padded_mut::<Pkcs7>(&mut buf, plaintext.len())
            .map_err(|e| DmeditError::Codec(format!("AES-128 encrypt failed: {e:?}")))?,
        24 => Aes192CbcEnc::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .encrypt_padded_mut::<Pkcs7>(&mut buf, plaintext.len())
            .map_err(|e| DmeditError::Codec(format!("AES-192 encrypt failed: {e:?}")))?,
        32 => Aes256CbcEnc::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .encrypt_padded_mut::<Pkcs7>(&mut buf, plaintext.len())
            .map_err(|e| DmeditError::Codec(format!("AES-256 encrypt failed: {e:?}")))?,
        other => {
            return Err(DmeditError::InvalidConfig(format!(
                "Invalid AES key size: {other} bytes"
            )))
        }
    };
    Ok(ct.to_vec())
}

pub fn aes_cbc_decrypt(ciphertext: &[u8], key: &[u8], iv: &[u8]) -> Result<Vec<u8>> {
    if iv.len() != 16 {
        return Err(DmeditError::InvalidConfig("AES IV must be 16 bytes".into()));
    }
    let mut buf = ciphertext.to_vec();
    let pt = match key.len() {
        16 => Aes128CbcDec::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .decrypt_padded_mut::<Pkcs7>(&mut buf)
            .map_err(|e| DmeditError::Codec(format!("AES-128 decrypt failed: {e:?}")))?,
        24 => Aes192CbcDec::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .decrypt_padded_mut::<Pkcs7>(&mut buf)
            .map_err(|e| DmeditError::Codec(format!("AES-192 decrypt failed: {e:?}")))?,
        32 => Aes256CbcDec::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .decrypt_padded_mut::<Pkcs7>(&mut buf)
            .map_err(|e| DmeditError::Codec(format!("AES-256 decrypt failed: {e:?}")))?,
        other => {
            return Err(DmeditError::InvalidConfig(format!(
                "Invalid AES key size: {other} bytes"
            )))
        }
    };
    Ok(pt.to_vec())
}

const UTF8_BOM: [u8; 3] = [0xEF, 0xBB, 0xBF];

/// Soul Knight 6.x–8.x `.data.new` native format (Abo.CryptUtil):
/// AES-CBC with NoPadding / zero-byte padding and StreamWriter UTF-8 BOM (`0xEF 0xBB 0xBF`).
pub fn aes_cbc_zero_bom_encrypt(plaintext: &[u8], key: &[u8], iv: &[u8]) -> Result<Vec<u8>> {
    if iv.len() != 16 {
        return Err(DmeditError::InvalidConfig("AES IV must be 16 bytes".into()));
    }
    let has_bom = plaintext.starts_with(&UTF8_BOM);
    let raw_len = if has_bom {
        plaintext.len()
    } else {
        UTF8_BOM.len() + plaintext.len()
    };
    let padded_len = if raw_len == 0 {
        16
    } else {
        raw_len.div_ceil(16) * 16
    };
    let mut buf = vec![0u8; padded_len];
    if has_bom {
        buf[..plaintext.len()].copy_from_slice(plaintext);
    } else {
        buf[..UTF8_BOM.len()].copy_from_slice(&UTF8_BOM);
        buf[UTF8_BOM.len()..UTF8_BOM.len() + plaintext.len()].copy_from_slice(plaintext);
    }
    let ct = match key.len() {
        16 => Aes128CbcEnc::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .encrypt_padded_mut::<NoPadding>(&mut buf, padded_len)
            .map_err(|e| DmeditError::Codec(format!("AES-128-Zero encrypt failed: {e:?}")))?,
        24 => Aes192CbcEnc::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .encrypt_padded_mut::<NoPadding>(&mut buf, padded_len)
            .map_err(|e| DmeditError::Codec(format!("AES-192-Zero encrypt failed: {e:?}")))?,
        32 => Aes256CbcEnc::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .encrypt_padded_mut::<NoPadding>(&mut buf, padded_len)
            .map_err(|e| DmeditError::Codec(format!("AES-256-Zero encrypt failed: {e:?}")))?,
        other => {
            return Err(DmeditError::InvalidConfig(format!(
                "Invalid AES key size: {other} bytes"
            )))
        }
    };
    Ok(ct.to_vec())
}

pub fn aes_cbc_zero_bom_decrypt(ciphertext: &[u8], key: &[u8], iv: &[u8]) -> Result<Vec<u8>> {
    if iv.len() != 16 {
        return Err(DmeditError::InvalidConfig("AES IV must be 16 bytes".into()));
    }
    if ciphertext.len() < 16 || ciphertext.len() % 16 != 0 {
        return Err(DmeditError::Codec(format!(
            "AES-Zero ciphertext length {} is not a positive multiple of 16",
            ciphertext.len()
        )));
    }
    let mut buf = ciphertext.to_vec();
    let pt = match key.len() {
        16 => Aes128CbcDec::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .decrypt_padded_mut::<NoPadding>(&mut buf)
            .map_err(|e| DmeditError::Codec(format!("AES-128-Zero decrypt failed: {e:?}")))?,
        24 => Aes192CbcDec::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .decrypt_padded_mut::<NoPadding>(&mut buf)
            .map_err(|e| DmeditError::Codec(format!("AES-192-Zero decrypt failed: {e:?}")))?,
        32 => Aes256CbcDec::new_from_slices(key, iv)
            .map_err(|e| DmeditError::Codec(e.to_string()))?
            .decrypt_padded_mut::<NoPadding>(&mut buf)
            .map_err(|e| DmeditError::Codec(format!("AES-256-Zero decrypt failed: {e:?}")))?,
        other => {
            return Err(DmeditError::InvalidConfig(format!(
                "Invalid AES key size: {other} bytes"
            )))
        }
    };
    let mut end = pt.len();
    while end > 0 && pt[end - 1] == 0 {
        end -= 1;
    }
    let start = if pt[..end].starts_with(&UTF8_BOM) {
        UTF8_BOM.len()
    } else {
        0
    };
    Ok(pt[start..end].to_vec())
}

fn resolve_key(def: &CodecDefinition) -> Result<Vec<u8>> {
    if let Some(ref h) = def.key_hex {
        let trimmed = h.trim();
        if !trimmed.is_empty() && !trimmed.starts_with('<') {
            return hex::decode(trimmed.replace(' ', ""))
                .map_err(|e| DmeditError::InvalidConfig(format!("Invalid keyHex: {e}")));
        }
    }
    if let Some(ref u) = def.key_utf8 {
        if !u.is_empty() {
            return Ok(u.as_bytes().to_vec());
        }
    }
    Err(DmeditError::InvalidConfig("Codec key not configured".into()))
}

fn resolve_iv(def: &CodecDefinition) -> Result<Vec<u8>> {
    if let Some(ref h) = def.iv_hex {
        let trimmed = h.trim();
        if !trimmed.is_empty() && !trimmed.starts_with('<') {
            return hex::decode(trimmed.replace(' ', ""))
                .map_err(|e| DmeditError::InvalidConfig(format!("Invalid ivHex: {e}")));
        }
    }
    if let Some(ref u) = def.iv_utf8 {
        if !u.is_empty() {
            return Ok(u.as_bytes().to_vec());
        }
    }
    Err(DmeditError::InvalidConfig("Codec IV not configured".into()))
}

pub fn validate_content(content: &str, format: DataFormat) -> ValidationResult {
    match format {
        DataFormat::Json => match serde_json::from_str::<serde_json::Value>(content) {
            Ok(_) => ValidationResult {
                is_valid: true,
                error_message: None,
            },
            Err(e) => ValidationResult {
                is_valid: false,
                error_message: Some(format!("Lỗi cú pháp JSON: {e}")),
            },
        },
        DataFormat::Xml => validate_xml(content),
        DataFormat::Text | DataFormat::Binary => ValidationResult {
            is_valid: true,
            error_message: None,
        },
    }
}

fn validate_xml(content: &str) -> ValidationResult {
    let mut reader = XmlReader::from_str(content);
    let mut buf = Vec::new();
    let mut saw_root = false;
    loop {
        match reader.read_event_into(&mut buf) {
            Ok(XmlEvent::Start(_)) | Ok(XmlEvent::Empty(_)) => saw_root = true,
            Ok(XmlEvent::Eof) => break,
            Err(e) => {
                return ValidationResult {
                    is_valid: false,
                    error_message: Some(format!("Lỗi cú pháp XML: {e}")),
                };
            }
            _ => {}
        }
        buf.clear();
    }
    if saw_root {
        ValidationResult {
            is_valid: true,
            error_message: None,
        }
    } else {
        ValidationResult {
            is_valid: false,
            error_message: Some("Tệp XML trống hoặc thiếu thẻ gốc".into()),
        }
    }
}

pub fn beautify_json(content: &str) -> Result<String> {
    let val: serde_json::Value =
        serde_json::from_str(content).map_err(|e| DmeditError::Ast(e.to_string()))?;
    serde_json::to_string_pretty(&val).map_err(|e| DmeditError::Ast(e.to_string()))
}

pub fn minify_json(content: &str) -> Result<String> {
    let val: serde_json::Value =
        serde_json::from_str(content).map_err(|e| DmeditError::Ast(e.to_string()))?;
    serde_json::to_string(&val).map_err(|e| DmeditError::Ast(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    #[test]
    fn xor_des_aes_chain_round_trip() {
        let mut codecs = BTreeMap::new();
        codecs.insert(
            "xor1".into(),
            CodecDefinition {
                codec_type: CodecType::Xor,
                key_hex: Some("1f2a3c77".into()),
                key_utf8: None,
                iv_hex: None,
                iv_utf8: None,
                header_skip_bytes: 0,
                steps: vec![],
                description: String::new(),
            },
        );
        codecs.insert(
            "des1".into(),
            CodecDefinition {
                codec_type: CodecType::DesCbcPkcs5,
                key_hex: None,
                key_utf8: Some("12345678".into()),
                iv_hex: None,
                iv_utf8: Some("87654321".into()),
                header_skip_bytes: 0,
                steps: vec![],
                description: String::new(),
            },
        );
        codecs.insert(
            "aes1".into(),
            CodecDefinition {
                codec_type: CodecType::AesCbcPkcs5,
                key_hex: Some("00112233445566778899aabbccddeeff".into()),
                iv_hex: Some("ffeeddccbbaa99887766554433221100".into()),
                key_utf8: None,
                iv_utf8: None,
                header_skip_bytes: 4,
                steps: vec![],
                description: String::new(),
            },
        );
        codecs.insert(
            "gz".into(),
            CodecDefinition {
                codec_type: CodecType::Gzip,
                key_hex: None,
                key_utf8: None,
                iv_hex: None,
                iv_utf8: None,
                header_skip_bytes: 0,
                steps: vec![],
                description: String::new(),
            },
        );
        codecs.insert(
            "b64".into(),
            CodecDefinition {
                codec_type: CodecType::Base64,
                key_hex: None,
                key_utf8: None,
                iv_hex: None,
                iv_utf8: None,
                header_skip_bytes: 0,
                steps: vec![],
                description: String::new(),
            },
        );
        codecs.insert(
            "chain1".into(),
            CodecDefinition {
                codec_type: CodecType::Chain,
                key_hex: None,
                key_utf8: None,
                iv_hex: None,
                iv_utf8: None,
                header_skip_bytes: 0,
                steps: vec!["b64".into(), "des1".into(), "gz".into()],
                description: String::new(),
            },
        );

        let profile = GameProfile {
            schema_version: 2,
            id: "test".into(),
            display_name: "Test".into(),
            developer: String::new(),
            engine: "unity".into(),
            packages: vec![],
            paths: Default::default(),
            codecs,
            file_rules: vec![],
            sanitizer: Default::default(),
            features: Default::default(),
            is_generic: false,
        };

        let sample = br#"{"gems":99999,"hero":"Knight"}"#;

        for id in ["xor1", "des1", "chain1"] {
            let enc = encrypt_with_profile(&profile, id, sample, None).unwrap();
            assert_ne!(enc.as_slice(), sample);
            let dec = decrypt_with_profile(&profile, id, &enc).unwrap();
            assert_eq!(dec.as_slice(), sample);
        }

        let header = b"RIJ\x010000";
        let enc_aes = encrypt_with_profile(&profile, "aes1", sample, Some(header)).unwrap();
        assert_eq!(&enc_aes[..4], b"RIJ\x01");
        let dec_aes = decrypt_with_profile(&profile, "aes1", &enc_aes).unwrap();
        assert_eq!(dec_aes.as_slice(), sample);
    }

    #[test]
    fn soul_knight_profile_asset_matches_skdeditor_codecs() {
        let profile_path = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../../../app/src/main/assets/profiles/soul_knight/profile.json");
        let raw_json = std::fs::read_to_string(&profile_path).expect("soul_knight/profile.json missing");
        let profile: GameProfile = serde_json::from_str(&raw_json).expect("invalid soul_knight/profile.json");

        assert_eq!(match_file_rule(&profile, "game.data").unwrap().codec, "sk_xor");
        assert_eq!(match_file_rule(&profile, "item_data.data").unwrap().codec, "sk_des_default");
        assert_eq!(match_file_rule(&profile, "item_data_12345_.data").unwrap().codec, "sk_des_default");
        let item_new_rule = match_file_rule(&profile, "item_data_12345_.data.new").unwrap();
        assert_eq!(item_new_rule.codec, "sk_aes_v2");
        assert_eq!(item_new_rule.visual_schema.as_deref(), Some("schemas/item_data.schema.json"));
        assert_eq!(match_file_rule(&profile, "statistic_LOCAL_.data").unwrap().codec, "sk_des_statistic");
        assert_eq!(match_file_rule(&profile, "statistic_12345_.data.new").unwrap().codec, "sk_aes_v2");
        assert_eq!(match_file_rule(&profile, "season_data.data").unwrap().codec, "sk_des_default");
        assert_eq!(match_file_rule(&profile, "battles.data").unwrap().codec, "plain");

        assert!(has_valid_key_configured(&profile, "sk_xor"));
        assert!(has_valid_key_configured(&profile, "sk_des_default"));
        assert!(has_valid_key_configured(&profile, "sk_des_statistic"));
        assert!(has_valid_key_configured(&profile, "sk_aes_v2"));

        // Verify XOR key decodes real Soul Knight game.data header bytes -> `{"ski`
        let real_prefix = [0x08u8, 0x4E, 0x10, 0x11, 0x14];
        let dec_prefix = decrypt_with_profile(&profile, "sk_xor", &real_prefix).unwrap();
        assert_eq!(&dec_prefix, br#"{"ski"#);

        // Verify round-trip for XOR, DES-iambo, DES-crst1, and AES-256-CBC-Zero-BOM (.data.new)
        let sample = br#"{"c_gems":99999,"materials":{"ironstone":500}}"#;
        for codec_id in ["sk_xor", "sk_des_default", "sk_des_statistic", "sk_aes_v2"] {
            let enc = encrypt_with_profile(&profile, codec_id, sample, None).unwrap();
            if codec_id == "sk_aes_v2" {
                assert_eq!(enc.len() % 16, 0);
            }
            let dec = decrypt_with_profile(&profile, codec_id, &enc).unwrap();
            assert_eq!(dec.as_slice(), sample);
        }
    }
}
