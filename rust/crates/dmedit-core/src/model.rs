use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GameProfile {
    #[serde(default = "default_schema_version")]
    pub schema_version: u32,
    pub id: String,
    pub display_name: String,
    #[serde(default)]
    pub developer: String,
    #[serde(default = "default_engine")]
    pub engine: String,
    #[serde(default)]
    pub packages: Vec<String>,
    #[serde(default)]
    pub paths: GamePaths,
    #[serde(default)]
    pub codecs: BTreeMap<String, CodecDefinition>,
    #[serde(default)]
    pub file_rules: Vec<FileRule>,
    #[serde(default)]
    pub sanitizer: SanitizerConfig,
    #[serde(default)]
    pub features: ProfileFeatures,
    #[serde(default)]
    pub is_generic: bool,
}

fn default_schema_version() -> u32 {
    2
}

fn default_engine() -> String {
    "unity".to_string()
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GamePaths {
    #[serde(default = "default_files_dir")]
    pub files_dir: String,
    #[serde(default = "default_shared_prefs")]
    pub shared_prefs: String,
}

fn default_files_dir() -> String {
    "files".to_string()
}

fn default_shared_prefs() -> String {
    "shared_prefs/{package}.v2.playerprefs.xml".to_string()
}

impl Default for GamePaths {
    fn default() -> Self {
        Self {
            files_dir: default_files_dir(),
            shared_prefs: default_shared_prefs(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum CodecType {
    #[serde(rename = "plain")]
    Plain,
    #[serde(rename = "xor")]
    Xor,
    #[serde(rename = "des_cbc_pkcs5")]
    DesCbcPkcs5,
    #[serde(rename = "aes_cbc_pkcs5")]
    AesCbcPkcs5,
    #[serde(rename = "aes_cbc_zero_bom")]
    AesCbcZeroBom,
    #[serde(rename = "base64")]
    Base64,
    #[serde(rename = "gzip")]
    Gzip,
    #[serde(rename = "chain")]
    Chain,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CodecDefinition {
    #[serde(rename = "type")]
    pub codec_type: CodecType,
    #[serde(default)]
    pub key_hex: Option<String>,
    #[serde(default)]
    pub key_utf8: Option<String>,
    #[serde(default)]
    pub iv_hex: Option<String>,
    #[serde(default)]
    pub iv_utf8: Option<String>,
    #[serde(default)]
    pub header_skip_bytes: usize,
    #[serde(default)]
    pub steps: Vec<String>,
    #[serde(default)]
    pub description: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum DataFormat {
    #[serde(rename = "json")]
    Json,
    #[serde(rename = "xml")]
    Xml,
    #[serde(rename = "text")]
    Text,
    #[serde(rename = "binary")]
    Binary,
}

impl Default for DataFormat {
    fn default() -> Self {
        DataFormat::Json
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FileRule {
    #[serde(rename = "match")]
    pub match_pattern: String,
    #[serde(default = "default_plain")]
    pub codec: String,
    #[serde(default)]
    pub format: DataFormat,
    #[serde(default)]
    pub visual_schema: Option<String>,
    #[serde(default)]
    pub label: Option<String>,
}

// Allow JSON key "match" in profile.json
impl FileRule {
    pub fn pattern(&self) -> &str {
        &self.match_pattern
    }
}

fn default_plain() -> String {
    "plain".to_string()
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SanitizerConfig {
    #[serde(default)]
    pub player_prefs_remove_patterns: Vec<String>,
    #[serde(default)]
    pub json_strip_keys: Vec<String>,
    #[serde(default)]
    pub account_suffix_regex: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileFeatures {
    #[serde(default = "default_true")]
    pub account_clear: bool,
    #[serde(default = "default_true")]
    pub ssaid_spoof: bool,
    #[serde(default = "default_true")]
    pub force_stop_before_write: bool,
}

fn default_true() -> bool {
    true
}

impl Default for ProfileFeatures {
    fn default() -> Self {
        Self {
            account_clear: true,
            ssaid_spoof: true,
            force_stop_before_write: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupManifest {
    #[serde(default = "default_schema_version")]
    pub schema_version: u32,
    pub app_version: String,
    pub game_profile_id: String,
    pub package_name: String,
    pub created_at_epoch_ms: i64,
    pub sanitized: bool,
    pub entries: Vec<BackupEntry>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupEntry {
    pub archive_path: String,
    pub target_relative_path: String,
    pub size_bytes: u64,
    pub sha256: String,
    pub is_shared_prefs: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupArchiveSummary {
    pub file_name: String,
    pub file_path: String,
    pub size_bytes: u64,
    pub manifest: Option<BackupManifest>,
    pub is_legacy_skdb: bool,
    pub entry_names: Vec<String>,
}

#[allow(non_camel_case_types)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum SsaidFormat {
    ABX_BINARY,
    PLAIN_XML,
    LEGACY_SECURE_XML,
    UNKNOWN,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SsaidEntry {
    pub id: String,
    pub name: String,
    pub value: String,
    pub package_name: String,
    pub default_value: Option<String>,
    pub default_sys_set: bool,
    pub tag: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ValidationResult {
    pub is_valid: bool,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum ChangeType {
    ADDED,
    MODIFIED,
    REMOVED,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DiffEntry {
    pub path: String,
    pub old_value: Option<String>,
    pub new_value: Option<String>,
    pub change_type: ChangeType,
}
