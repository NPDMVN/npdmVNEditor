use thiserror::Error;

#[derive(Debug, Error)]
pub enum DmeditError {
    #[error("Invalid configuration: {0}")]
    InvalidConfig(String),

    #[error("Crypto/Codec error: {0}")]
    Codec(String),

    #[error("JSON/AST error: {0}")]
    Ast(String),

    #[error("XML error: {0}")]
    Xml(String),

    #[error("ABX binary XML error: {0}")]
    Abx(String),

    #[error("Backup archive error: {0}")]
    Backup(String),

    #[error("Not found: {0}")]
    NotFound(String),

    #[error("Verification failed: {0}")]
    Verification(String),

    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
}

pub type Result<T> = std::result::Result<T, DmeditError>;
