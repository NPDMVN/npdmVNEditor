pub mod abx;
pub mod ast;
pub mod backup;
pub mod codec;
pub mod error;
pub mod model;
pub mod sanitizer;

pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

pub fn core_banner() -> String {
    format!(
        "dmedit-core v{} (Rust pure-crypto / ABX / AST / SKDB v2)",
        CORE_VERSION
    )
}
